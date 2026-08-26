package com.openscholar.search.internal.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.openscholar.TestcontainersConfiguration;
import com.openscholar.paper.DocumentType;
import com.openscholar.provider.ProviderException;
import com.openscholar.provider.ProviderId;
import com.openscholar.provider.ProviderPaperRecord;
import com.openscholar.provider.ProviderSearchQuery;
import com.openscholar.provider.ProviderSearchResult;
import com.openscholar.provider.ResearchProvider;
import com.openscholar.search.internal.persistence.ProviderQualityComparativeEvaluator.ComparativeCapture;
import com.openscholar.search.internal.persistence.ProviderQualityComparativeEvaluator.ProviderCallEvidence;
import com.openscholar.search.internal.persistence.ProviderQualityComparativeEvaluator.QueryCapture;
import com.openscholar.search.internal.persistence.ProviderQualityComparativeEvaluator.ReconciliationTrace;
import com.openscholar.search.internal.persistence.ProviderQualityComparativeEvaluator.ScenarioCapture;
import com.openscholar.search.internal.persistence.ProviderQualityComparativeEvaluator.ScenarioId;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.json.JsonMapper;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
class ProviderQualityComparativeEvaluatorIntegrationTests {

	private static final Instant RETRIEVED_AT = Instant.parse("2026-08-26T12:00:00Z");
	private static final String FAILURE_QUERY_KEY = "cancer-ml-diagnosis";
	private static final String UNTRUSTED_ERROR_QUERY_KEY = "crispr-off-target";
	private static final String MALFORMED_QUERY_KEY = "antimicrobial-surveillance";
	private static final String UNTRUSTED_ERROR_CODE = "EUROPE_PMC_CREDENTIAL_TOKEN";
	private static final String PRIVATE_UPSTREAM_DETAIL = "credential=must-not-be-captured";
	private static final Set<ScenarioId> ALL_SCENARIOS = Set.of(
			ScenarioId.OPENALEX_ONLY,
			ScenarioId.EUROPE_PMC_ONLY,
			ScenarioId.FUSED);

	@Autowired
	private SearchSnapshotStore snapshotStore;

	@Autowired
	private PlatformTransactionManager transactionManager;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Test
	void capturesEveryFrozenQueryOncePerProviderThroughThreeRollbackOnlyScenarios()
			throws Exception {
		ProviderQualityLiveQuerySet querySet = loadFrozenQuerySet();
		ProviderPair firstProviders = ProviderPair.successful(querySet);
		ComparativeCapture first = evaluator().capture(querySet, firstProviders.beans());

		assertSuccessfulCapture(querySet, first, firstProviders);
		assertDatabaseRowsRolledBack();

		ProviderPair secondProviders = ProviderPair.successful(querySet);
		ComparativeCapture second = evaluator().capture(querySet, secondProviders.beans());

		assertSuccessfulCapture(querySet, second, secondProviders);
		assertThat(second)
				.as("raw review keys, ranked results, and cluster keys are stable across captures")
				.isEqualTo(first);
		assertDatabaseRowsRolledBack();
	}

	@Test
	void recordsBoundedFailureDiagnosticsAndSkipsOnlyUnavailableScenarios()
			throws Exception {
		ProviderQualityLiveQuerySet querySet = loadFrozenQuerySet();
		ProviderPair providers = ProviderPair.withEuropePmcFailures(
				querySet, FAILURE_QUERY_KEY, UNTRUSTED_ERROR_QUERY_KEY, MALFORMED_QUERY_KEY);

		ComparativeCapture capture = evaluator().capture(querySet, providers.beans());

		providers.assertEachFrozenQueryCalledExactlyOnce(querySet);
		assertThat(capture.qualityReviewEligible()).isFalse();
		QueryCapture failedRequest = capture.queries().stream()
				.filter(query -> query.key().equals(FAILURE_QUERY_KEY))
				.findFirst()
				.orElseThrow();
		assertUnavailableEuropePmcScenario(failedRequest);
		Map<ProviderId, ProviderCallEvidence> requestEvidence = evidence(failedRequest);
		assertThat(requestEvidence.get(ProviderId.EUROPE_PMC)).isEqualTo(new ProviderCallEvidence(
				ProviderId.EUROPE_PMC,
				"FAILED",
				0L,
				0,
				0L,
				null,
				"EUROPE_PMC_RATE_LIMITED",
				true));

		QueryCapture untrustedDiagnostic = capture.queries().stream()
				.filter(query -> query.key().equals(UNTRUSTED_ERROR_QUERY_KEY))
				.findFirst()
				.orElseThrow();
		assertUnavailableEuropePmcScenario(untrustedDiagnostic);
		Map<ProviderId, ProviderCallEvidence> untrustedEvidence = evidence(untrustedDiagnostic);
		assertThat(untrustedEvidence.get(ProviderId.EUROPE_PMC).errorCode())
				.isEqualTo("EUROPE_PMC_UNEXPECTED_ERROR");

		QueryCapture malformedMetadata = capture.queries().stream()
				.filter(query -> query.key().equals(MALFORMED_QUERY_KEY))
				.findFirst()
				.orElseThrow();
		assertUnavailableEuropePmcScenario(malformedMetadata);
		Map<ProviderId, ProviderCallEvidence> malformedEvidence = evidence(malformedMetadata);
		assertThat(malformedEvidence.get(ProviderId.EUROPE_PMC)).isEqualTo(new ProviderCallEvidence(
				ProviderId.EUROPE_PMC,
				"FAILED",
				0L,
				0,
				0L,
				null,
				"EUROPE_PMC_INVALID_METADATA",
				false));
		assertThat(capture.toString())
				.doesNotContain(PRIVATE_UPSTREAM_DETAIL)
				.doesNotContain(UNTRUSTED_ERROR_CODE);

		assertThat(capture.queries())
				.filteredOn(query -> !Set.of(
						FAILURE_QUERY_KEY,
						UNTRUSTED_ERROR_QUERY_KEY,
						MALFORMED_QUERY_KEY).contains(query.key()))
				.hasSize(5)
				.allSatisfy(query -> {
					assertThat(query.complete()).isTrue();
					assertThat(query.scenarios()).containsOnlyKeys(ALL_SCENARIOS);
				});
		assertDatabaseRowsRolledBack();
	}

	private static void assertUnavailableEuropePmcScenario(QueryCapture failed) {
		assertThat(failed.complete()).isFalse();
		assertThat(failed.rawCandidates())
				.extracting(ProviderQualityRawCandidate::provider)
				.containsOnly(ProviderId.OPENALEX);
		assertThat(failed.scenarios()).containsOnlyKeys(ScenarioId.OPENALEX_ONLY);
	}

	private static Map<ProviderId, ProviderCallEvidence> evidence(QueryCapture query) {
		Map<ProviderId, ProviderCallEvidence> evidence = query.providerCalls().stream()
				.collect(Collectors.toMap(ProviderCallEvidence::provider, Function.identity()));
		assertThat(evidence.get(ProviderId.OPENALEX).status()).isEqualTo("SUCCESS");
		return evidence;
	}

	private ProviderQualityComparativeEvaluator evaluator() {
		return new ProviderQualityComparativeEvaluator(
				snapshotStore, transactionManager, () -> 0L);
	}

	private static ProviderQualityLiveQuerySet loadFrozenQuerySet() throws Exception {
		ProviderQualityLiveQuerySet querySet = ProviderQualityLiveQuerySet.load(
				JsonMapper.builder().build(), ProviderQualityLiveQuerySet.RESOURCE_PATH);
		assertThat(querySet.queries()).hasSize(8);
		return querySet;
	}

	private void assertSuccessfulCapture(
			ProviderQualityLiveQuerySet querySet,
			ComparativeCapture capture,
			ProviderPair providers) {
		providers.assertEachFrozenQueryCalledExactlyOnce(querySet);
		assertThat(capture.querySetId()).isEqualTo(querySet.querySetId());
		assertThat(capture.sourcePolicy()).isEqualTo(querySet.sourcePolicy());
		assertThat(capture.pageSize()).isEqualTo(querySet.pageSize());
		assertThat(capture.qualityReviewEligible()).isTrue();
		assertThat(capture.queries()).hasSize(8).allSatisfy(query -> {
			assertThat(query.complete()).isTrue();
			assertThat(query.providerCalls())
					.extracting(ProviderCallEvidence::provider)
					.containsExactly(ProviderId.EUROPE_PMC, ProviderId.OPENALEX);
			assertThat(query.providerCalls())
					.extracting(ProviderCallEvidence::status)
					.containsOnly("SUCCESS");
			assertThat(query.rawCandidates()).hasSize(4);
			assertThat(query.scenarios()).containsOnlyKeys(ALL_SCENARIOS);
			assertThat(new ArrayList<>(query.scenarios().keySet())).containsExactly(
					ScenarioId.OPENALEX_ONLY,
					ScenarioId.EUROPE_PMC_ONLY,
					ScenarioId.FUSED);
			assertScenarioTrace(query, ScenarioId.OPENALEX_ONLY, Set.of(ProviderId.OPENALEX), 2);
			assertScenarioTrace(query, ScenarioId.EUROPE_PMC_ONLY, Set.of(ProviderId.EUROPE_PMC), 2);
			assertScenarioTrace(
					query,
					ScenarioId.FUSED,
					Set.of(ProviderId.OPENALEX, ProviderId.EUROPE_PMC),
					4);

			ScenarioCapture fused = query.scenarios().get(ScenarioId.FUSED);
			assertThat(fused.rankedResults()).allSatisfy(result ->
					assertThat(result.presentFields())
							.extracting(Enum::name)
							.containsExactly(
									"ABSTRACT",
									"CITATION_COUNT",
									"DOCUMENT_TYPE",
									"DOI",
									"LANGUAGE",
									"PUBLICATION_YEAR",
									"SOURCE_URL",
									"TITLE",
									"VENUE"));
			Map<String, List<ReconciliationTrace>> byCluster = fused.reconciliation().stream()
					.collect(Collectors.groupingBy(
							ReconciliationTrace::clusterKey,
							LinkedHashMap::new,
							Collectors.toList()));
			assertThat(byCluster.values())
					.extracting(List::size)
					.containsExactlyInAnyOrder(2, 1, 1);
			assertThat(byCluster.keySet()).allMatch(key -> key.matches("[0-9a-f]{64}"));
			assertThat(byCluster.values())
					.filteredOn(cluster -> cluster.size() == 2)
					.singleElement()
					.satisfies(shared -> assertThat(shared)
							.extracting(ReconciliationTrace::provider)
							.containsExactlyInAnyOrder(ProviderId.OPENALEX, ProviderId.EUROPE_PMC));
		});
	}

	private static void assertScenarioTrace(
			QueryCapture query,
			ScenarioId scenarioId,
			Set<ProviderId> expectedProviders,
			int expectedRawCandidates) {
		ScenarioCapture scenario = query.scenarios().get(scenarioId);
		assertThat(scenario.scenario()).isEqualTo(scenarioId);
		assertThat(scenario.reconciliation()).hasSize(expectedRawCandidates)
				.allSatisfy(trace -> {
					assertThat(trace.provider()).isIn(expectedProviders);
					assertThat(trace.providerRank()).isBetween(1, 2);
					assertThat(trace.includedInFirstPage()).isTrue();
				});
		Set<String> expectedReviewKeys = query.rawCandidates().stream()
				.filter(candidate -> expectedProviders.contains(candidate.provider()))
				.map(ProviderQualityRawCandidate::reviewKey)
				.collect(Collectors.toSet());
		assertThat(scenario.reconciliation())
				.extracting(ReconciliationTrace::reviewKey)
				.containsExactlyInAnyOrderElementsOf(expectedReviewKeys);
		assertThat(scenario.rankedResults()).hasSize(expectedProviders.size() + 1);
	}

	private void assertDatabaseRowsRolledBack() {
		assertThat(Map.of(
				"paper", count("select count(*) from paper"),
				"provider_record", count("select count(*) from provider_record"),
				"search_snapshot", count("select count(*) from search_snapshot"),
				"search_result", count("select count(*) from search_result")))
				.as("all production persistence performed by evaluation scenarios is rollback-only")
				.containsOnly(
						Map.entry("paper", 0L),
						Map.entry("provider_record", 0L),
						Map.entry("search_snapshot", 0L),
						Map.entry("search_result", 0L));
	}

	private long count(String sql) {
		return jdbcTemplate.queryForObject(sql, Long.class);
	}

	private record ProviderPair(
			DeterministicProvider openAlex,
			DeterministicProvider europePmc) {

		private static ProviderPair successful(ProviderQualityLiveQuerySet querySet) {
			return new ProviderPair(
					new DeterministicProvider(querySet, ProviderId.OPENALEX, null, null, null),
					new DeterministicProvider(querySet, ProviderId.EUROPE_PMC, null, null, null));
		}

		private static ProviderPair withEuropePmcFailures(
				ProviderQualityLiveQuerySet querySet,
				String failureQueryKey,
				String untrustedErrorQueryKey,
				String malformedQueryKey) {
			return new ProviderPair(
					new DeterministicProvider(querySet, ProviderId.OPENALEX, null, null, null),
					new DeterministicProvider(
							querySet,
							ProviderId.EUROPE_PMC,
							failureQueryKey,
							untrustedErrorQueryKey,
							malformedQueryKey));
		}

		private List<ResearchProvider> beans() {
			return List.of(openAlex, europePmc);
		}

		private void assertEachFrozenQueryCalledExactlyOnce(
				ProviderQualityLiveQuerySet querySet) {
			openAlex.assertEachFrozenQueryCalledExactlyOnce(querySet);
			europePmc.assertEachFrozenQueryCalledExactlyOnce(querySet);
		}
	}

	private static final class DeterministicProvider implements ResearchProvider {

		private final ProviderId provider;
		private final String failureQueryKey;
		private final String untrustedErrorQueryKey;
		private final String malformedQueryKey;
		private final Map<String, ProviderQualityLiveQuerySet.QueryCommand> commandsByText;
		private final Map<String, Integer> indexByKey;
		private final Map<String, List<ProviderSearchQuery>> calls = new LinkedHashMap<>();

		private DeterministicProvider(
				ProviderQualityLiveQuerySet querySet,
				ProviderId provider,
				String failureQueryKey,
				String untrustedErrorQueryKey,
				String malformedQueryKey) {
			this.provider = provider;
			this.failureQueryKey = failureQueryKey;
			this.untrustedErrorQueryKey = untrustedErrorQueryKey;
			this.malformedQueryKey = malformedQueryKey;
			this.commandsByText = querySet.commands().stream().collect(Collectors.toMap(
					command -> command.command().query(),
					Function.identity()));
			this.indexByKey = new LinkedHashMap<>();
			for (int index = 0; index < querySet.commands().size(); index++) {
				indexByKey.put(querySet.commands().get(index).key(), index + 1);
			}
		}

		@Override
		public ProviderId id() {
			return provider;
		}

		@Override
		public ProviderSearchResult search(ProviderSearchQuery query) {
			ProviderQualityLiveQuerySet.QueryCommand expected = commandsByText.get(query.query());
			if (expected == null || !expected.command().equals(query)) {
				throw new AssertionError("Evaluator changed or invented a frozen query command");
			}
			calls.computeIfAbsent(expected.key(), ignored -> new ArrayList<>()).add(query);
			if (provider == ProviderId.EUROPE_PMC && expected.key().equals(failureQueryKey)) {
				throw new ProviderException(
						provider,
						"EUROPE_PMC_RATE_LIMITED",
						PRIVATE_UPSTREAM_DETAIL,
						true,
						Duration.ofSeconds(30),
						null);
			}
			if (provider == ProviderId.EUROPE_PMC
					&& expected.key().equals(untrustedErrorQueryKey)) {
				throw new ProviderException(
						provider,
						UNTRUSTED_ERROR_CODE,
						PRIVATE_UPSTREAM_DETAIL,
						false,
						null,
						null);
			}
			int index = indexByKey.get(expected.key());
			DocumentType documentType = provider == ProviderId.EUROPE_PMC
					&& expected.key().equals(malformedQueryKey)
							? DocumentType.THESIS
							: DocumentType.ARTICLE;
			return new ProviderSearchResult(
					provider,
					List.of(
							record(expected.key(), index, "shared", documentType),
							record(expected.key(), index, "only", documentType)),
					100L + index,
					"next-" + expected.key(),
					RETRIEVED_AT.plusSeconds(index + provider.ordinal()));
		}

		private ProviderPaperRecord record(
				String queryKey, int index, String kind, DocumentType documentType) {
			boolean shared = kind.equals("shared");
			String providerSlug = provider.name().toLowerCase(Locale.ROOT);
			String providerRecordId = provider == ProviderId.OPENALEX
					? "W9" + String.format(Locale.ROOT, "%06d", index) + (shared ? "1" : "2")
					: "MED:" + String.format(Locale.ROOT, "%06d", index) + (shared ? "1" : "2");
			String doi = "10.5555/openscholar." + index + '.'
					+ (shared ? "shared" : providerSlug);
			return new ProviderPaperRecord(
					provider,
					providerRecordId,
					doi,
					null,
					"Live evaluation " + queryKey + ' ' + kind + ' ' + providerSlug,
					"Deterministic metadata-only abstract for " + queryKey,
					LocalDate.of(2026, 8, Math.min(index, 28)),
					2026,
					documentType,
					"en",
					"Evaluation Journal",
					10 + index,
					List.of(),
					true,
					URI.create("https://fixtures.openscholar.test/landing/"
							+ providerSlug + '/' + queryKey + '/' + kind),
					null,
					shared ? 1.0d : 0.5d,
					RETRIEVED_AT.plusSeconds(index),
					Map.of("fixture", "comparative-evaluator"),
					List.of(),
					URI.create("https://fixtures.openscholar.test/source/"
							+ providerSlug + '/' + queryKey + '/' + kind));
		}

		private void assertEachFrozenQueryCalledExactlyOnce(
				ProviderQualityLiveQuerySet querySet) {
			assertThat(calls).hasSize(querySet.commands().size());
			for (ProviderQualityLiveQuerySet.QueryCommand command : querySet.commands()) {
				assertThat(calls.get(command.key()))
						.as("%s calls for %s", provider, command.key())
						.containsExactly(command.command());
			}
		}
	}
}
