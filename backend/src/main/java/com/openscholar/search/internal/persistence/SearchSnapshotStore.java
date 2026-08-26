package com.openscholar.search.internal.persistence;

import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import com.openscholar.paper.CanonicalPaperCandidate;
import com.openscholar.paper.DocumentType;
import com.openscholar.paper.PaperAuthorCandidate;
import com.openscholar.paper.PaperCatalog;
import com.openscholar.paper.PaperView;
import com.openscholar.paper.ProviderRecordCandidate;
import com.openscholar.provider.ProviderAuthor;
import com.openscholar.provider.ProviderException;
import com.openscholar.provider.ProviderId;
import com.openscholar.provider.ProviderPaperRecord;
import com.openscholar.provider.ProviderSearchBatchResult;
import com.openscholar.provider.ProviderSearchResult;
import com.openscholar.search.CacheDisposition;
import com.openscholar.search.ProviderContributionView;
import com.openscholar.search.ProviderCoverageView;
import com.openscholar.search.RankingReason;
import com.openscholar.search.SearchCommand;
import com.openscholar.search.SearchExecutionSource;
import com.openscholar.search.SearchMode;
import com.openscholar.search.SearchResultOrigin;
import com.openscholar.search.SearchResultView;
import com.openscholar.search.SearchView;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class SearchSnapshotStore {

	private static final String COMPLETED = "COMPLETED";

	private final SearchSnapshotRepository snapshotRepository;
	private final SearchResultRepository resultRepository;
	private final PaperCatalog paperCatalog;

	SearchSnapshotStore(
			SearchSnapshotRepository snapshotRepository,
			SearchResultRepository resultRepository,
			PaperCatalog paperCatalog) {
		this.snapshotRepository = snapshotRepository;
		this.resultRepository = resultRepository;
		this.paperCatalog = paperCatalog;
	}

	@Transactional(readOnly = true)
	public Optional<LatestSnapshot> findLatestProvider(UUID ownerId, String fingerprint) {
		return snapshotRepository
				.findFirstByOwnerIdAndFingerprintAndResultOriginAndStatusOrderBySearchedAtDesc(
						ownerId, fingerprint, SearchResultOrigin.PROVIDER.name(), COMPLETED)
				.map(snapshot -> new LatestSnapshot(snapshot.freshUntil(), toView(snapshot, CacheDisposition.EXACT_HIT)));
	}

	@Transactional(readOnly = true)
	public Optional<SearchView> findById(UUID ownerId, UUID searchId) {
		return snapshotRepository.findByIdAndOwnerIdAndStatus(searchId, ownerId, COMPLETED)
				.map(snapshot -> toView(snapshot, CacheDisposition.EXACT_HIT));
	}

	@Transactional(readOnly = true)
	public Optional<SearchContinuation> findContinuation(UUID ownerId, UUID searchId) {
		return snapshotRepository.findByIdAndOwnerIdAndStatus(searchId, ownerId, COMPLETED)
				.map(snapshot -> new SearchContinuation(
						snapshot.nextCursor(),
						hasNextCursor(snapshot.nextCursor()) ? storedCommand(snapshot) : null));
	}

	@Transactional(readOnly = true)
	public Optional<StoredSearch> findStoredSearch(UUID searchId) {
		return snapshotRepository.findByIdAndStatus(searchId, COMPLETED)
				.map(snapshot -> new StoredSearch(
						snapshot.ownerId(), storedCommand(snapshot), snapshot.resultOrigin()));
	}

	@Transactional
	public SearchView store(
			UUID ownerId,
			SearchCommand command,
			String normalizedQuery,
			String fingerprint,
			int fingerprintVersion,
			String pipelineVersion,
			ProviderSearchBatchResult providerResult,
			Instant freshUntil,
			CacheDisposition disposition) {
		return storeInternal(
				ownerId,
				command,
				normalizedQuery,
				fingerprint,
				fingerprintVersion,
				pipelineVersion,
				providerResult,
				freshUntil,
				disposition,
				false).view();
	}

	@Transactional
	StoreTrace storeWithTrace(
			UUID ownerId,
			SearchCommand command,
			String normalizedQuery,
			String fingerprint,
			int fingerprintVersion,
			String pipelineVersion,
			ProviderSearchBatchResult providerResult,
			Instant freshUntil,
			CacheDisposition disposition) {
		return storeInternal(
				ownerId,
				command,
				normalizedQuery,
				fingerprint,
				fingerprintVersion,
				pipelineVersion,
				providerResult,
				freshUntil,
				disposition,
				true);
	}

	private StoreTrace storeInternal(
			UUID ownerId,
			SearchCommand command,
			String normalizedQuery,
			String fingerprint,
			int fingerprintVersion,
			String pipelineVersion,
			ProviderSearchBatchResult providerResult,
			Instant freshUntil,
			CacheDisposition disposition,
			boolean includeTrace) {
		List<ProviderSearchResult> successfulProviders = providerResult.results().stream()
				.sorted(Comparator.comparing(result -> result.provider().name()))
				.toList();
		boolean multiProvider = successfulProviders.size() + providerResult.failures().size() > 1;
		LinkedHashMap<UUID, CandidateAccumulator> accumulated = new LinkedHashMap<>();
		List<PendingTraceEntry> rawContributions = includeTrace ? new ArrayList<>() : null;
		int sequence = 0;
		for (ProviderSearchResult result : successfulProviders) {
			int providerRank = 1;
			for (ProviderPaperRecord record : result.records()) {
				PaperView paper = paperCatalog.upsert(
						toCanonicalCandidate(record, result.retrievedAt()),
						toProviderRecord(record, result.retrievedAt()),
						result.retrievedAt());
				CandidateAccumulator candidate = accumulated.get(paper.id());
				if (candidate == null) {
					candidate = new CandidateAccumulator(paper.id(), sequence);
					accumulated.put(paper.id(), candidate);
				}
				int rawProviderRank = providerRank++;
				if (includeTrace) {
					rawContributions.add(new PendingTraceEntry(
							record.provider(),
							record.providerRecordId(),
							rawProviderRank,
							paper.id()));
				}
				candidate.add(new ProviderContribution(record, result.retrievedAt(), rawProviderRank));
				sequence++;
			}
		}
		Map<UUID, PaperView> finalPapers = paperCatalog.findAllByIds(accumulated.keySet());
		List<PersistedCandidate> candidates = accumulated.values().stream()
				.map(candidate -> candidate.finish(requirePaper(finalPapers, candidate.paperId()), multiProvider))
				.sorted(candidateOrder(multiProvider))
				.limit(command.pageSize())
				.toList();
		Set<UUID> firstPagePaperIds = candidates.stream()
				.map(candidate -> candidate.paper().id())
				.collect(Collectors.toUnmodifiableSet());

		List<Map<String, Object>> coverage = coverage(providerResult);
		List<String> warnings = providerResult.failures().stream()
				.sorted(Comparator.comparing(failure -> failure.provider().name()))
				.map(ProviderException::errorCode)
				.distinct()
				.toList();
		SearchSnapshotEntity snapshot = SearchSnapshotEntity.completed(
				ownerId,
				command.query(),
				normalizedQuery,
				fingerprint,
				fingerprintVersion,
				pipelineVersion,
				filters(command),
				command.mode(),
				SearchResultOrigin.PROVIDER,
				providerResult.retrievedAt(),
				freshUntil,
				coverage,
				warnings,
				totalMatches(successfulProviders),
				candidates.size(),
				providerResult.nextCursor());
		snapshotRepository.saveAndFlush(snapshot);

		List<SearchResultEntity> results = new ArrayList<>();
		int rank = 1;
		for (PersistedCandidate candidate : candidates) {
			ProviderContribution primary = candidate.primary();
			ProviderPaperRecord record = primary.record();
			List<Map<String, Object>> reasons = rankingReasons(candidate, multiProvider);
			List<Map<String, Object>> contributions = candidate.contributions().stream()
					.map(SearchSnapshotStore::contributionMap)
					.toList();
			results.add(SearchResultEntity.create(
					snapshot.id(),
					candidate.paper(),
					rank++,
					candidate.score(),
					candidate.reportedOpenAccess(),
					toString(record.landingPageUrl()),
					toString(record.pdfUrl()),
					reasons,
					contributions,
					record.provider().name(),
					record.providerRecordId(),
					primary.retrievedAt()));
		}
		resultRepository.saveAllAndFlush(results);
		List<RawContributionTrace> traceEntries = includeTrace
				? rawContributions.stream()
						.map(entry -> new RawContributionTrace(
								entry.provider(),
								entry.providerRecordId(),
								entry.providerRank(),
								entry.canonicalPaperId(),
								firstPagePaperIds.contains(entry.canonicalPaperId())))
						.toList()
				: List.of();
		return new StoreTrace(toView(snapshot, results, disposition), traceEntries);
	}

	@Transactional
	public SearchView storeLocal(
			UUID ownerId,
			SearchCommand command,
			String normalizedQuery,
			String fingerprint,
			int fingerprintVersion,
			String pipelineVersion,
			Instant searchedAt,
			String nextCursor,
			List<String> warnings,
			List<SearchResultView> localResults) {
		SearchSnapshotEntity snapshot = SearchSnapshotEntity.completed(
				ownerId,
				command.query(),
				normalizedQuery,
				fingerprint,
				fingerprintVersion,
				pipelineVersion,
				filters(command),
				command.mode(),
				SearchResultOrigin.LOCAL_CATALOG,
				searchedAt,
				searchedAt,
				List.of(),
				warnings,
				0L,
				localResults.size(),
				nextCursor);
		snapshotRepository.saveAndFlush(snapshot);

		List<SearchResultEntity> persisted = localResults.stream()
				.map(result -> SearchResultEntity.create(
						snapshot.id(),
						result.paper(),
						result.rank(),
						result.score(),
						result.reportedOpenAccess(),
						toString(result.landingPageUrl()),
						toString(result.pdfUrl()),
						result.rankingReasons().stream()
								.map(reason -> rankingReasonMap(reason.feature(), reason.value()))
								.toList(),
						result.providerContributions().stream()
								.map(SearchSnapshotStore::contributionMap)
								.toList(),
						result.provider().name(),
						result.providerRecordId(),
						result.retrievedAt()))
				.toList();
		resultRepository.saveAllAndFlush(persisted);
		return toView(snapshot, persisted, CacheDisposition.LOCAL_RESULT);
	}

	private SearchView toView(SearchSnapshotEntity snapshot, CacheDisposition disposition) {
		List<SearchResultEntity> results = resultRepository.findAllBySearchIdOrderByResultRank(snapshot.id());
		return toView(snapshot, results, disposition);
	}

	private SearchView toView(
			SearchSnapshotEntity snapshot,
			List<SearchResultEntity> results,
			CacheDisposition disposition) {
		List<SearchResultView> resultViews = results.stream()
				.sorted(Comparator.comparingInt(SearchResultEntity::resultRank))
				.map(this::toResultView)
				.toList();
		return new SearchView(
				snapshot.id(),
				snapshot.originalQuery(),
				snapshot.fingerprint(),
				disposition,
				snapshot.requestedMode(),
				executionSource(snapshot.resultOrigin(), disposition),
				snapshot.searchedAt(),
				snapshot.freshUntil(),
				snapshot.nextCursor(),
				coverageViews(snapshot.providerCoverage()),
				snapshot.warnings(),
				resultViews);
	}

	private SearchResultView toResultView(SearchResultEntity result) {
		List<RankingReason> reasons = result.rankingReasons().stream()
				.map(reason -> new RankingReason(
						String.valueOf(reason.get("feature")),
						numberAsDouble(reason.get("value"))))
				.toList();
		List<ProviderContributionView> contributions = result.providerContributions().stream()
				.map(SearchSnapshotStore::contributionView)
				.toList();
		return new SearchResultView(
				result.resultRank(),
				result.paperView(),
				result.reportedOpenAccess(),
				parseHttpUri(result.landingPageUrl()),
				parseHttpUri(result.pdfUrl()),
				result.totalScore(),
				reasons,
				ProviderId.valueOf(result.provider()),
				result.providerRecordId(),
				result.retrievedAt(),
				contributions);
	}

	private static CanonicalPaperCandidate toCanonicalCandidate(
			ProviderPaperRecord record, Instant retrievedAt) {
		List<PaperAuthorCandidate> authors = record.authors().stream()
				.filter(author -> author.displayName() != null && !author.displayName().isBlank())
				.map(SearchSnapshotStore::toAuthorCandidate)
				.toList();
		return new CanonicalPaperCandidate(
				record.title(),
				record.abstractText(),
				record.publicationDate(),
				record.publicationYear(),
				record.documentType(),
				record.language(),
				record.venueName(),
				record.citationCount(),
				retrievedAt,
				record.identifiers(),
				authors,
				record.publisher(),
				record.institution(),
				record.volume(),
				record.issue(),
				record.pages(),
				record.articleNumber(),
				record.edition(),
				record.isbn(),
				record.issn(),
				record.degree());
	}

	private static PaperAuthorCandidate toAuthorCandidate(ProviderAuthor author) {
		return new PaperAuthorCandidate(
				author.providerAuthorId(),
				author.displayName(),
				author.orcid(),
				author.position(),
				author.corresponding());
	}

	private static ProviderRecordCandidate toProviderRecord(
			ProviderPaperRecord record, Instant retrievedAt) {
		return new ProviderRecordCandidate(
				record.provider().name(),
				record.providerRecordId(),
				record.providerUpdatedAt(),
				retrievedAt,
				record.sourceUrl(),
				record.reportedOpenAccess(),
				record.landingPageUrl(),
				record.pdfUrl(),
				record.metadataFragment());
	}

	private static PaperView requirePaper(Map<UUID, PaperView> papers, UUID paperId) {
		PaperView paper = papers.get(paperId);
		if (paper == null) {
			throw new IllegalStateException("A provider result paper disappeared before snapshot creation");
		}
		return paper;
	}

	private static Comparator<PersistedCandidate> candidateOrder(boolean multiProvider) {
		if (!multiProvider) {
			return Comparator.comparingInt(PersistedCandidate::firstSeen);
		}
		return Comparator.comparingDouble((PersistedCandidate candidate) -> candidate.score().doubleValue())
				.reversed()
				.thenComparingInt(candidate -> candidate.primary().providerRank())
				.thenComparing(candidate -> candidate.primary().record().provider().name())
				.thenComparing(candidate -> candidate.primary().record().providerRecordId())
				.thenComparing(candidate -> candidate.paper().id());
	}

	private static List<Map<String, Object>> coverage(ProviderSearchBatchResult batch) {
		List<Map<String, Object>> coverage = new ArrayList<>();
		batch.results().forEach(result -> coverage.add(Map.of(
				"provider", result.provider().name(),
				"status", "SUCCESS",
				"returnedCount", result.records().size(),
				"totalMatches", result.totalMatches())));
		batch.failures().forEach(failure -> coverage.add(Map.of(
				"provider", failure.provider().name(),
				"status", "FAILED",
				"returnedCount", 0,
				"totalMatches", 0L)));
		return coverage.stream()
				.sorted(Comparator.comparing(item -> String.valueOf(item.get("provider"))))
				.toList();
	}

	private static long totalMatches(List<ProviderSearchResult> results) {
		long total = 0;
		for (ProviderSearchResult result : results) {
			long value = Math.max(0, result.totalMatches());
			total = Long.MAX_VALUE - total < value ? Long.MAX_VALUE : total + value;
		}
		return total;
	}

	private static List<Map<String, Object>> rankingReasons(
			PersistedCandidate candidate, boolean multiProvider) {
		if (multiProvider) {
			return List.of(Map.of(
					"feature", "PROVIDER_RECIPROCAL_RANK_FUSION",
					"value", candidate.score()));
		}
		Double relevance = candidate.primary().record().relevanceScore();
		return relevance == null
				? List.of()
				: List.of(Map.of(
						"feature", candidate.primary().record().provider().name() + "_RELEVANCE",
						"value", relevance));
	}

	private static Map<String, Object> contributionMap(ProviderContribution contribution) {
		return Map.of(
				"provider", contribution.record().provider().name(),
				"providerRecordId", contribution.record().providerRecordId(),
				"retrievedAt", contribution.retrievedAt().toString());
	}

	private static Map<String, Object> contributionMap(ProviderContributionView contribution) {
		return Map.of(
				"provider", contribution.provider().name(),
				"providerRecordId", contribution.providerRecordId(),
				"retrievedAt", contribution.retrievedAt().toString());
	}

	private static Map<String, Object> rankingReasonMap(String feature, Double value) {
		Map<String, Object> reason = new LinkedHashMap<>();
		reason.put("feature", feature);
		reason.put("value", value);
		return reason;
	}

	private static ProviderContributionView contributionView(Map<String, Object> contribution) {
		return new ProviderContributionView(
				ProviderId.valueOf(String.valueOf(contribution.get("provider"))),
				String.valueOf(contribution.get("providerRecordId")),
				Instant.parse(String.valueOf(contribution.get("retrievedAt"))));
	}

	private static Map<String, Object> filters(SearchCommand command) {
		Map<String, Object> filters = new LinkedHashMap<>();
		if (command.yearFrom() != null) {
			filters.put("yearFrom", command.yearFrom());
		}
		if (command.yearTo() != null) {
			filters.put("yearTo", command.yearTo());
		}
		filters.put("documentTypes", command.documentTypes().stream().map(Enum::name).sorted().toList());
		filters.put("openAccessOnly", command.openAccessOnly());
		filters.put("minimumCitations", command.minimumCitations());
		filters.put("languages", command.languages().stream().sorted().toList());
		filters.put("pageSize", command.pageSize());
		filters.put("cursor", command.cursor());
		filters.put("mode", command.mode().name());
		return filters;
	}

	private static SearchCommand storedCommand(SearchSnapshotEntity snapshot) {
		Map<String, Object> filters = snapshot.filters();
		return new SearchCommand(
				snapshot.originalQuery(),
				nullableInteger(filters, "yearFrom"),
				nullableInteger(filters, "yearTo"),
				documentTypes(filters.get("documentTypes")),
				requiredBoolean(filters, "openAccessOnly"),
				requiredInteger(filters, "minimumCitations"),
				strings(filters.get("languages"), "languages"),
				requiredInteger(filters, "pageSize"),
				requiredString(filters, "cursor"),
				false,
				snapshot.requestedMode());
	}

	private static Integer nullableInteger(Map<String, Object> values, String key) {
		Object value = values.get(key);
		if (value == null) {
			return null;
		}
		if (value instanceof Number number) {
			return number.intValue();
		}
		throw invalidStoredFilter(key);
	}

	private static int requiredInteger(Map<String, Object> values, String key) {
		Integer value = nullableInteger(values, key);
		if (value == null) {
			throw invalidStoredFilter(key);
		}
		return value;
	}

	private static boolean requiredBoolean(Map<String, Object> values, String key) {
		Object value = values.get(key);
		if (value instanceof Boolean booleanValue) {
			return booleanValue;
		}
		throw invalidStoredFilter(key);
	}

	private static String requiredString(Map<String, Object> values, String key) {
		Object value = values.get(key);
		if (value instanceof String stringValue) {
			return stringValue;
		}
		throw invalidStoredFilter(key);
	}

	private static Set<DocumentType> documentTypes(Object value) {
		return strings(value, "documentTypes").stream()
				.map(DocumentType::valueOf)
				.collect(Collectors.toUnmodifiableSet());
	}

	private static Set<String> strings(Object value, String key) {
		if (!(value instanceof Collection<?> collection)) {
			throw invalidStoredFilter(key);
		}
		return collection.stream()
				.map(item -> {
					if (item instanceof String stringValue) {
						return stringValue;
					}
					throw invalidStoredFilter(key);
				})
				.collect(Collectors.toUnmodifiableSet());
	}

	private static IllegalStateException invalidStoredFilter(String key) {
		return new IllegalStateException("Stored search filter is invalid: " + key);
	}

	private static boolean hasNextCursor(String cursor) {
		return cursor != null && !cursor.isBlank();
	}

	private static List<ProviderCoverageView> coverageViews(List<Map<String, Object>> coverage) {
		return coverage.stream()
				.map(item -> new ProviderCoverageView(
						ProviderId.valueOf(String.valueOf(item.get("provider"))),
						String.valueOf(item.get("status")),
						numberAsInt(item.get("returnedCount")),
						numberAsLong(item.get("totalMatches"))))
				.toList();
	}

	private static SearchExecutionSource executionSource(
			SearchResultOrigin origin, CacheDisposition disposition) {
		if (origin == SearchResultOrigin.LOCAL_CATALOG) {
			return SearchExecutionSource.LOCAL_CATALOG;
		}
		return switch (disposition) {
			case EXACT_HIT -> SearchExecutionSource.EXACT_CACHE;
			case STALE_FALLBACK -> SearchExecutionSource.STALE_CACHE;
			default -> SearchExecutionSource.PROVIDER_FETCH;
		};
	}

	private static URI parseHttpUri(String value) {
		if (value == null || value.isBlank()) {
			return null;
		}
		try {
			URI uri = URI.create(value);
			return "http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme())
					? uri
					: null;
		}
		catch (IllegalArgumentException ignored) {
			return null;
		}
	}

	private static String toString(URI uri) {
		return uri == null ? null : uri.toString();
	}

	private static Double numberAsDouble(Object value) {
		return value instanceof Number number ? number.doubleValue() : null;
	}

	private static int numberAsInt(Object value) {
		return value instanceof Number number ? number.intValue() : 0;
	}

	private static long numberAsLong(Object value) {
		return value instanceof Number number ? number.longValue() : 0L;
	}

	public record LatestSnapshot(Instant freshUntil, SearchView view) {

		public boolean isFreshAt(Instant now) {
			return now.isBefore(freshUntil);
		}
	}

	public record SearchContinuation(String nextCursor, SearchCommand currentCommand) {

		public boolean hasNextPage() {
			return hasNextCursor(nextCursor);
		}

		public SearchCommand nextCommand() {
			if (!hasNextPage() || currentCommand == null) {
				throw new IllegalStateException("Search continuation is exhausted");
			}
			return new SearchCommand(
					currentCommand.query(),
					currentCommand.yearFrom(),
					currentCommand.yearTo(),
					currentCommand.documentTypes(),
					currentCommand.openAccessOnly(),
					currentCommand.minimumCitations(),
					currentCommand.languages(),
					currentCommand.pageSize(),
					nextCursor,
					false,
					currentCommand.mode());
		}
	}

	public record StoredSearch(
			UUID ownerId, SearchCommand command, SearchResultOrigin resultOrigin) {
	}

	record StoreTrace(SearchView view, List<RawContributionTrace> rawContributions) {

		StoreTrace {
			rawContributions = List.copyOf(rawContributions);
		}
	}

	record RawContributionTrace(
			ProviderId provider,
			String providerRecordId,
			int providerRank,
			UUID canonicalPaperId,
			boolean includedInFirstPage) {
	}

	private static final class CandidateAccumulator {

		private final UUID paperId;
		private final int firstSeen;
		private final Map<ProviderId, ProviderContribution> contributions = new EnumMap<>(ProviderId.class);

		private CandidateAccumulator(UUID paperId, int firstSeen) {
			this.paperId = paperId;
			this.firstSeen = firstSeen;
		}

		private UUID paperId() {
			return paperId;
		}

		private void add(ProviderContribution contribution) {
			contributions.merge(
					contribution.record().provider(),
					contribution,
					(left, right) -> CONTRIBUTION_ORDER.compare(left, right) <= 0 ? left : right);
		}

		private PersistedCandidate finish(PaperView paper, boolean multiProvider) {
			List<ProviderContribution> ordered = contributions.values().stream()
					.sorted(Comparator.comparing(contribution -> contribution.record().provider().name()))
					.toList();
			ProviderContribution primary = ordered.stream().min(CONTRIBUTION_ORDER).orElseThrow();
			Double score;
			if (multiProvider) {
				score = ordered.stream().mapToDouble(value -> 1.0 / (60.0 + value.providerRank())).sum();
			}
			else {
				score = primary.record().relevanceScore();
			}
			boolean reportedOpenAccess = ordered.stream()
					.anyMatch(value -> value.record().reportedOpenAccess());
			return new PersistedCandidate(
					paper, ordered, primary, score, reportedOpenAccess, firstSeen);
		}
	}

	private static final Comparator<ProviderContribution> CONTRIBUTION_ORDER = Comparator
			.comparingInt(ProviderContribution::providerRank)
			.thenComparing(contribution -> contribution.record().provider().name())
			.thenComparing(contribution -> contribution.record().providerRecordId());

	private record ProviderContribution(
			ProviderPaperRecord record, Instant retrievedAt, int providerRank) {
	}

	private record PendingTraceEntry(
			ProviderId provider,
			String providerRecordId,
			int providerRank,
			UUID canonicalPaperId) {
	}

	private record PersistedCandidate(
			PaperView paper,
			List<ProviderContribution> contributions,
			ProviderContribution primary,
			Double score,
			boolean reportedOpenAccess,
			int firstSeen) {
	}
}
