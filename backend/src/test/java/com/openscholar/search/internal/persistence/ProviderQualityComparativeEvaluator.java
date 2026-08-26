package com.openscholar.search.internal.persistence;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.LongSupplier;
import java.util.stream.Collectors;

import com.openscholar.provider.ProviderException;
import com.openscholar.provider.ProviderId;
import com.openscholar.provider.ProviderPaperRecord;
import com.openscholar.provider.ProviderSearchBatchResult;
import com.openscholar.provider.ProviderSearchQuery;
import com.openscholar.provider.ProviderSearchResult;
import com.openscholar.provider.ResearchProvider;
import com.openscholar.search.CacheDisposition;
import com.openscholar.search.SearchCommand;
import com.openscholar.search.SearchFingerprintVersion;
import com.openscholar.search.SearchMode;
import com.openscholar.search.SearchResultView;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Test-side evaluator used by the manual comparative capture and by the
 * deterministic integration contract. For each query, both provider calls
 * happen once before that query's database transactions. The immutable result
 * objects are then replayed through rollback-only production persistence
 * scenarios.
 */
final class ProviderQualityComparativeEvaluator {

	private static final Set<ProviderId> EXPECTED_PROVIDERS =
			Set.of(ProviderId.OPENALEX, ProviderId.EUROPE_PMC);
	private static final List<ScenarioId> SCENARIO_ORDER = List.of(
			ScenarioId.OPENALEX_ONLY,
			ScenarioId.EUROPE_PMC_ONLY,
			ScenarioId.FUSED);
	private static final UUID EVALUATION_OWNER =
			UUID.fromString("00000000-0000-0000-0000-000000000001");
	private static final String PIPELINE_VERSION = "provider-fanout-v1";
	private static final int MAXIMUM_RAW_CANDIDATES_PER_QUERY = 40;
	private static final Map<ProviderId, Set<String>> DIAGNOSTIC_ERROR_CODES = Map.of(
			ProviderId.OPENALEX, Set.of(
					"OPENALEX_RATE_LIMITED",
					"OPENALEX_UPSTREAM_ERROR",
					"OPENALEX_RESPONSE_ERROR"),
			ProviderId.EUROPE_PMC, Set.of(
					"EUROPE_PMC_RATE_LIMITED",
					"EUROPE_PMC_UPSTREAM_ERROR",
					"EUROPE_PMC_RESPONSE_ERROR"));

	private final SearchSnapshotStore snapshotStore;
	private final PlatformTransactionManager transactionManager;
	private final LongSupplier nanoTime;

	ProviderQualityComparativeEvaluator(
			SearchSnapshotStore snapshotStore,
			PlatformTransactionManager transactionManager) {
		this(snapshotStore, transactionManager, System::nanoTime);
	}

	ProviderQualityComparativeEvaluator(
			SearchSnapshotStore snapshotStore,
			PlatformTransactionManager transactionManager,
			LongSupplier nanoTime) {
		this.snapshotStore = Objects.requireNonNull(snapshotStore, "snapshotStore");
		this.transactionManager = Objects.requireNonNull(transactionManager, "transactionManager");
		this.nanoTime = Objects.requireNonNull(nanoTime, "nanoTime");
	}

	ComparativeCapture capture(
			ProviderQualityLiveQuerySet querySet,
			List<ResearchProvider> providerBeans) {
		Objects.requireNonNull(querySet, "querySet");
		Map<ProviderId, ResearchProvider> providers = selectProviders(providerBeans);
		List<QueryCapture> queries = new ArrayList<>();
		for (ProviderQualityLiveQuerySet.QueryCommand query : querySet.commands()) {
			queries.add(captureQuery(querySet.querySetId(), query, providers));
		}
		boolean complete = queries.stream().allMatch(QueryCapture::complete);
		return new ComparativeCapture(
				1,
				querySet.querySetId(),
				querySet.sourcePolicy(),
				querySet.pageSize(),
				complete,
				List.copyOf(queries));
	}

	private QueryCapture captureQuery(
			String querySetId,
			ProviderQualityLiveQuerySet.QueryCommand query,
			Map<ProviderId, ResearchProvider> providers) {
		Map<ProviderId, ProviderCall> calls = new EnumMap<>(ProviderId.class);
		for (ProviderId provider : EXPECTED_PROVIDERS.stream()
				.sorted(Comparator.comparing(Enum::name))
				.toList()) {
			calls.put(provider, invoke(
					providers.get(provider), querySetId, query.key(), query.command()));
		}

		List<ProviderQualityRawCandidate> rawCandidates = new ArrayList<>();
		for (ProviderCall call : calls.values()) {
			rawCandidates.addAll(call.rawCandidates());
		}
		if (rawCandidates.size() > MAXIMUM_RAW_CANDIDATES_PER_QUERY) {
			throw new IllegalStateException("Comparative capture exceeded 40 raw candidates for " + query.key());
		}
		assertUniqueReviewKeys(rawCandidates, query.key());

		Map<ScenarioId, ScenarioCapture> scenarios = new LinkedHashMap<>();
		for (ScenarioId scenario : SCENARIO_ORDER) {
			List<ProviderSearchResult> inputs = scenario.inputs(calls);
			if (!inputs.isEmpty() && (scenario != ScenarioId.FUSED || inputs.size() == 2)) {
				scenarios.put(scenario, replay(
						querySetId, query, scenario, inputs, rawCandidates));
			}
		}

		List<ProviderCallEvidence> providerCalls = calls.entrySet().stream()
				.sorted(Map.Entry.comparingByKey(Comparator.comparing(Enum::name)))
				.map(entry -> entry.getValue().evidence())
				.toList();
		boolean complete = calls.size() == EXPECTED_PROVIDERS.size()
				&& calls.values().stream().allMatch(call -> call.result() != null)
				&& scenarios.keySet().equals(Set.copyOf(SCENARIO_ORDER));
		return new QueryCapture(
				query.key(),
				query.command().query(),
				complete,
				providerCalls,
				List.copyOf(rawCandidates),
				scenarios);
	}

	private ProviderCall invoke(
			ResearchProvider provider,
			String querySetId,
			String queryKey,
			ProviderSearchQuery query) {
		long startedAt = nanoTime.getAsLong();
		ProviderSearchResult result;
		try {
			result = Objects.requireNonNull(
					provider.search(query), "Research providers must not return null");
		}
		catch (ProviderException exception) {
			return ProviderCall.failure(
					provider.id(), diagnosticErrorCode(provider.id(), exception),
					exception.retryable(), elapsedMilliseconds(startedAt));
		}
		catch (RuntimeException exception) {
			return ProviderCall.failure(
					provider.id(), provider.id().name() + "_UNEXPECTED_ERROR", true,
					elapsedMilliseconds(startedAt));
		}

		try {
			validateResult(provider.id(), query.pageSize(), result);
			List<ProviderQualityRawCandidate> rawCandidates = new ArrayList<>();
			int providerRank = 1;
			for (ProviderPaperRecord record : result.records()) {
				rawCandidates.add(ProviderQualityRawCandidate.from(
						querySetId, queryKey, providerRank++, record));
			}
			return ProviderCall.success(
					result, rawCandidates, elapsedMilliseconds(startedAt));
		}
		catch (RuntimeException exception) {
			return ProviderCall.failure(
					provider.id(), provider.id().name() + "_INVALID_METADATA", false,
					elapsedMilliseconds(startedAt));
		}
	}

	private static String diagnosticErrorCode(
			ProviderId invokedProvider, ProviderException exception) {
		String errorCode = exception.errorCode();
		if (exception.provider() == invokedProvider
				&& DIAGNOSTIC_ERROR_CODES.getOrDefault(invokedProvider, Set.of()).contains(errorCode)) {
			return errorCode;
		}
		return invokedProvider.name() + "_UNEXPECTED_ERROR";
	}

	private long elapsedMilliseconds(long startedAt) {
		return Math.max(0L, Duration.ofNanos(nanoTime.getAsLong() - startedAt).toMillis());
	}

	private ScenarioCapture replay(
			String querySetId,
			ProviderQualityLiveQuerySet.QueryCommand query,
			ScenarioId scenario,
			List<ProviderSearchResult> inputs,
			List<ProviderQualityRawCandidate> rawCandidates) {
		TransactionTemplate transaction = new TransactionTemplate(transactionManager);
		transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
		return Objects.requireNonNull(transaction.execute(status -> {
			SearchCommand command = searchCommand(query.command());
			Instant retrievedAt = inputs.stream()
					.map(ProviderSearchResult::retrievedAt)
					.max(Comparator.naturalOrder())
					.orElseThrow();
			ProviderSearchBatchResult batch = new ProviderSearchBatchResult(
					inputs, List.of(), null, retrievedAt);
			SearchSnapshotStore.StoreTrace stored = snapshotStore.storeWithTrace(
					EVALUATION_OWNER,
					command,
					command.query().toLowerCase(Locale.ROOT),
					sha256(querySetId + '\n' + query.key() + '\n' + scenario.name()),
					SearchFingerprintVersion.CURRENT,
					PIPELINE_VERSION,
					batch,
					retrievedAt.plus(Duration.ofHours(1)),
					CacheDisposition.MISS_FETCHED);
			status.setRollbackOnly();
			return scenarioCapture(querySetId, query.key(), scenario, stored, rawCandidates);
		}));
	}

	private static ScenarioCapture scenarioCapture(
			String querySetId,
			String queryKey,
			ScenarioId scenario,
			SearchSnapshotStore.StoreTrace stored,
			List<ProviderQualityRawCandidate> rawCandidates) {
		Map<RawIdentity, String> reviewKeys = rawCandidates.stream()
				.collect(Collectors.toUnmodifiableMap(
						candidate -> new RawIdentity(candidate.provider(), candidate.providerRecordId()),
						ProviderQualityRawCandidate::reviewKey));
		Map<UUID, List<String>> reviewKeysByCanonical = new LinkedHashMap<>();
		for (SearchSnapshotStore.RawContributionTrace trace : stored.rawContributions()) {
			String reviewKey = requireReviewKey(reviewKeys, trace.provider(), trace.providerRecordId());
			reviewKeysByCanonical
					.computeIfAbsent(trace.canonicalPaperId(), ignored -> new ArrayList<>())
					.add(reviewKey);
		}
		Map<UUID, String> clusters = new LinkedHashMap<>();
		reviewKeysByCanonical.forEach((canonicalId, keys) -> clusters.put(
				canonicalId, stableClusterKey(querySetId, queryKey, scenario, keys)));

		List<ReconciliationTrace> reconciliations = stored.rawContributions().stream()
				.map(trace -> new ReconciliationTrace(
						requireReviewKey(reviewKeys, trace.provider(), trace.providerRecordId()),
						trace.provider(),
						trace.providerRecordId(),
						trace.providerRank(),
						Objects.requireNonNull(clusters.get(trace.canonicalPaperId())),
						trace.includedInFirstPage()))
				.toList();
		assertTraceCompleteness(scenario, rawCandidates, reconciliations);

		List<RankedScenarioResult> ranked = stored.view().results().stream()
				.map(result -> rankedResult(result, clusters, reviewKeys))
				.toList();
		return new ScenarioCapture(scenario, ranked, reconciliations);
	}

	private static RankedScenarioResult rankedResult(
			SearchResultView result,
			Map<UUID, String> clusters,
			Map<RawIdentity, String> reviewKeys) {
		return new RankedScenarioResult(
				result.rank(),
				result.score(),
				requireReviewKey(reviewKeys, result.provider(), result.providerRecordId()),
				Objects.requireNonNull(clusters.get(result.paper().id())),
				result.provider(),
				result.providerRecordId());
	}

	private static String requireReviewKey(
			Map<RawIdentity, String> reviewKeys,
			ProviderId provider,
			String providerRecordId) {
		String reviewKey = reviewKeys.get(new RawIdentity(provider, providerRecordId));
		if (reviewKey == null) {
			throw new IllegalStateException(
					"Scenario trace referenced an uncaptured provider record: "
							+ provider + '/' + providerRecordId);
		}
		return reviewKey;
	}

	private static void assertTraceCompleteness(
			ScenarioId scenario,
			List<ProviderQualityRawCandidate> rawCandidates,
			List<ReconciliationTrace> reconciliations) {
		Set<ProviderId> expectedProviders = scenario.providers();
		Set<String> expected = rawCandidates.stream()
				.filter(candidate -> expectedProviders.contains(candidate.provider()))
				.map(ProviderQualityRawCandidate::reviewKey)
				.collect(Collectors.toUnmodifiableSet());
		List<String> actual = reconciliations.stream()
				.map(ReconciliationTrace::reviewKey)
				.toList();
		if (actual.size() != expected.size() || !Set.copyOf(actual).equals(expected)) {
			throw new IllegalStateException("Scenario trace did not preserve every raw candidate for " + scenario);
		}
	}

	private static String stableClusterKey(
			String querySetId,
			String queryKey,
			ScenarioId scenario,
			List<String> reviewKeys) {
		List<String> ordered = reviewKeys.stream().distinct().sorted().toList();
		return sha256("openscholar-provider-quality-cluster-v1\n"
				+ querySetId + '\n' + queryKey + '\n' + scenario.name() + '\n'
				+ String.join("\n", ordered));
	}

	private static SearchCommand searchCommand(ProviderSearchQuery query) {
		return new SearchCommand(
				query.query(),
				query.yearFrom(),
				query.yearTo(),
				query.documentTypes(),
				query.openAccessOnly(),
				query.minimumCitations(),
				query.languages(),
				query.pageSize(),
				"*",
				false,
				SearchMode.ONLINE);
	}

	private static void validateResult(
			ProviderId expectedProvider, int pageSize, ProviderSearchResult result) {
		if (result.provider() != expectedProvider
				|| result.records().stream().anyMatch(record -> record.provider() != expectedProvider)) {
			throw new IllegalStateException("Provider result identity does not match the invoked provider");
		}
		if (result.records().size() > pageSize) {
			throw new IllegalStateException("Provider returned more records than the bounded page size");
		}
		Set<String> providerRecordIds = result.records().stream()
				.map(ProviderPaperRecord::providerRecordId)
				.collect(Collectors.toSet());
		if (providerRecordIds.size() != result.records().size()) {
			throw new IllegalStateException("Provider returned duplicate provider record identifiers");
		}
	}

	private static Map<ProviderId, ResearchProvider> selectProviders(
			List<ResearchProvider> providerBeans) {
		Objects.requireNonNull(providerBeans, "providerBeans");
		Map<ProviderId, ResearchProvider> providers = new EnumMap<>(ProviderId.class);
		for (ResearchProvider provider : providerBeans) {
			ResearchProvider value = Objects.requireNonNull(provider, "providerBeans must not contain null");
			if (providers.putIfAbsent(value.id(), value) != null) {
				throw new IllegalStateException("Duplicate provider bean for " + value.id());
			}
		}
		if (!providers.keySet().equals(EXPECTED_PROVIDERS)) {
			throw new IllegalStateException(
					"Comparative evaluation requires exactly OPENALEX and EUROPE_PMC provider beans");
		}
		return Map.copyOf(providers);
	}

	private static void assertUniqueReviewKeys(
			List<ProviderQualityRawCandidate> rawCandidates, String queryKey) {
		Set<String> reviewKeys = rawCandidates.stream()
				.map(ProviderQualityRawCandidate::reviewKey)
				.collect(Collectors.toSet());
		if (reviewKeys.size() != rawCandidates.size()) {
			throw new IllegalStateException("Comparative capture generated duplicate review keys for " + queryKey);
		}
	}

	private static String sha256(String value) {
		try {
			return java.util.HexFormat.of().formatHex(
					MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
		}
		catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException("SHA-256 is unavailable", exception);
		}
	}

	enum ScenarioId {
		OPENALEX_ONLY(Set.of(ProviderId.OPENALEX)),
		EUROPE_PMC_ONLY(Set.of(ProviderId.EUROPE_PMC)),
		FUSED(Set.of(ProviderId.OPENALEX, ProviderId.EUROPE_PMC));

		private final Set<ProviderId> providers;

		ScenarioId(Set<ProviderId> providers) {
			this.providers = Set.copyOf(providers);
		}

		Set<ProviderId> providers() {
			return providers;
		}

		List<ProviderSearchResult> inputs(Map<ProviderId, ProviderCall> calls) {
			return providers.stream()
					.sorted(Comparator.comparing(Enum::name))
					.map(calls::get)
					.filter(Objects::nonNull)
					.map(ProviderCall::result)
					.filter(Objects::nonNull)
					.toList();
		}
	}

	record ComparativeCapture(
			int schemaVersion,
			String querySetId,
			String sourcePolicy,
			int pageSize,
			boolean qualityReviewEligible,
			List<QueryCapture> queries) {

		ComparativeCapture {
			queries = List.copyOf(queries);
		}
	}

	record QueryCapture(
			String key,
			String query,
			boolean complete,
			List<ProviderCallEvidence> providerCalls,
			List<ProviderQualityRawCandidate> rawCandidates,
			Map<ScenarioId, ScenarioCapture> scenarios) {

		QueryCapture {
			providerCalls = List.copyOf(providerCalls);
			rawCandidates = List.copyOf(rawCandidates);
			scenarios = Collections.unmodifiableMap(new LinkedHashMap<>(scenarios));
		}
	}

	record ProviderCallEvidence(
			ProviderId provider,
			String status,
			long durationMilliseconds,
			int returnedRecords,
			long totalMatches,
			Instant retrievedAt,
			String errorCode,
			boolean retryable) {
	}

	record ScenarioCapture(
			ScenarioId scenario,
			List<RankedScenarioResult> rankedResults,
			List<ReconciliationTrace> reconciliation) {

		ScenarioCapture {
			rankedResults = List.copyOf(rankedResults);
			reconciliation = List.copyOf(reconciliation);
		}
	}

	record RankedScenarioResult(
			int rank,
			Double score,
			String primaryReviewKey,
			String clusterKey,
			ProviderId primaryProvider,
			String primaryProviderRecordId) {
	}

	record ReconciliationTrace(
			String reviewKey,
			ProviderId provider,
			String providerRecordId,
			int providerRank,
			String clusterKey,
			boolean includedInFirstPage) {
	}

	private record ProviderCall(
			ProviderId provider,
			ProviderSearchResult result,
			List<ProviderQualityRawCandidate> rawCandidates,
			String errorCode,
			boolean retryable,
			long durationMilliseconds) {

		private ProviderCall {
			rawCandidates = List.copyOf(rawCandidates);
		}

		private static ProviderCall success(
				ProviderSearchResult result,
				List<ProviderQualityRawCandidate> rawCandidates,
				long durationMilliseconds) {
			return new ProviderCall(
					result.provider(), result, rawCandidates, null, false, durationMilliseconds);
		}

		private static ProviderCall failure(
				ProviderId provider, String errorCode, boolean retryable, long durationMilliseconds) {
			return new ProviderCall(
					provider, null, List.of(), errorCode, retryable, durationMilliseconds);
		}

		private ProviderCallEvidence evidence() {
			return result == null
					? new ProviderCallEvidence(
							provider, "FAILED", durationMilliseconds, 0, 0L, null, errorCode, retryable)
					: new ProviderCallEvidence(
							provider,
							"SUCCESS",
							durationMilliseconds,
							result.records().size(),
							result.totalMatches(),
							result.retrievedAt(),
							null,
							false);
		}
	}

	private record RawIdentity(ProviderId provider, String providerRecordId) {
	}
}
