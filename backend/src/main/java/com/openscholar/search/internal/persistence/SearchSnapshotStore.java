package com.openscholar.search.internal.persistence;

import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.openscholar.paper.CanonicalPaperCandidate;
import com.openscholar.paper.PaperAuthorCandidate;
import com.openscholar.paper.PaperCatalog;
import com.openscholar.paper.PaperIdentifier;
import com.openscholar.paper.PaperIdentifierType;
import com.openscholar.paper.PaperView;
import com.openscholar.paper.ProviderRecordCandidate;
import com.openscholar.provider.ProviderAuthor;
import com.openscholar.provider.ProviderId;
import com.openscholar.provider.ProviderPaperRecord;
import com.openscholar.provider.ProviderSearchResult;
import com.openscholar.search.CacheDisposition;
import com.openscholar.search.ProviderCoverageView;
import com.openscholar.search.RankingReason;
import com.openscholar.search.SearchCommand;
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
	public Optional<LatestSnapshot> findLatest(String fingerprint) {
		return snapshotRepository
				.findFirstByFingerprintAndStatusOrderBySearchedAtDesc(fingerprint, COMPLETED)
				.map(snapshot -> new LatestSnapshot(snapshot.freshUntil(), toView(snapshot, CacheDisposition.EXACT_HIT)));
	}

	@Transactional(readOnly = true)
	public Optional<SearchView> findById(UUID searchId) {
		return snapshotRepository.findByIdAndStatus(searchId, COMPLETED)
				.map(snapshot -> toView(snapshot, CacheDisposition.EXACT_HIT));
	}

	@Transactional
	public SearchView store(
			SearchCommand command,
			String normalizedQuery,
			String fingerprint,
			int fingerprintVersion,
			String pipelineVersion,
			ProviderSearchResult providerResult,
			Instant freshUntil,
			CacheDisposition disposition) {
		LinkedHashMap<UUID, PersistedCandidate> candidates = new LinkedHashMap<>();
		for (ProviderPaperRecord record : providerResult.records()) {
			PaperView paper = paperCatalog.upsert(
					toCanonicalCandidate(record, providerResult.retrievedAt()),
					toProviderRecord(record, providerResult.retrievedAt()),
					providerResult.retrievedAt());
			candidates.putIfAbsent(paper.id(), new PersistedCandidate(paper, record));
		}

		List<Map<String, Object>> coverage = List.of(Map.of(
				"provider", providerResult.provider().name(),
				"status", "SUCCESS",
				"returnedCount", providerResult.records().size(),
				"totalMatches", providerResult.totalMatches()));
		SearchSnapshotEntity snapshot = SearchSnapshotEntity.completed(
				command.query(),
				normalizedQuery,
				fingerprint,
				fingerprintVersion,
				pipelineVersion,
				filters(command),
				providerResult.retrievedAt(),
				freshUntil,
				coverage,
				List.of(),
				providerResult.totalMatches(),
				candidates.size(),
				providerResult.nextCursor());
		snapshotRepository.saveAndFlush(snapshot);

		List<SearchResultEntity> results = new ArrayList<>();
		int rank = 1;
		for (PersistedCandidate candidate : candidates.values()) {
			ProviderPaperRecord record = candidate.providerRecord();
			List<Map<String, Object>> reasons = record.relevanceScore() == null
					? List.of()
					: List.of(Map.of("feature", "OPENALEX_RELEVANCE", "value", record.relevanceScore()));
			List<Map<String, Object>> contributions = List.of(Map.of(
					"provider", record.provider().name(),
					"providerRecordId", record.providerRecordId(),
					"retrievedAt", providerResult.retrievedAt().toString()));
			results.add(SearchResultEntity.create(
					snapshot.id(),
					candidate.paper(),
					rank++,
					record.relevanceScore(),
					record.reportedOpenAccess(),
					toString(record.landingPageUrl()),
					toString(record.pdfUrl()),
					reasons,
					contributions,
					record.provider().name(),
					record.providerRecordId(),
					providerResult.retrievedAt()));
		}
		resultRepository.saveAllAndFlush(results);
		return toView(snapshot, results, disposition);
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
				result.retrievedAt());
	}

	private static CanonicalPaperCandidate toCanonicalCandidate(
			ProviderPaperRecord record, Instant retrievedAt) {
		List<PaperIdentifier> identifiers = new ArrayList<>();
		if (record.doi() != null && !record.doi().isBlank()) {
			identifiers.add(new PaperIdentifier(PaperIdentifierType.DOI, "", record.doi()));
		}
		if (record.arxivId() != null && !record.arxivId().isBlank()) {
			identifiers.add(new PaperIdentifier(PaperIdentifierType.ARXIV, "", record.arxivId()));
		}
		identifiers.add(new PaperIdentifier(
				PaperIdentifierType.OPENALEX, "", record.providerRecordId()));
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
				identifiers,
				authors);
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
		URI sourceUrl = record.provider() == ProviderId.OPENALEX
				? parseHttpUri("https://openalex.org/works/" + record.providerRecordId())
				: null;
		return new ProviderRecordCandidate(
				record.provider().name(),
				record.providerRecordId(),
				record.providerUpdatedAt(),
				retrievedAt,
				sourceUrl,
				record.reportedOpenAccess(),
				record.landingPageUrl(),
				record.pdfUrl(),
				record.metadataFragment());
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
		return filters;
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

	private record PersistedCandidate(PaperView paper, ProviderPaperRecord providerRecord) {
	}
}
