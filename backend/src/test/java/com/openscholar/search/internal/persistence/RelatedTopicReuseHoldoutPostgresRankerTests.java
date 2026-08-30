package com.openscholar.search.internal.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Modifier;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import com.openscholar.TestcontainersConfiguration;
import com.openscholar.paper.DocumentType;
import com.openscholar.paper.PaperIdentifierType;
import com.openscholar.provider.ProviderId;
import com.openscholar.provider.ProviderSearchQuery;
import com.openscholar.provider.ProviderSearchResult;
import com.openscholar.provider.ResearchProvider;
import com.openscholar.search.internal.LocalCatalogSearchEvaluationAdapter;
import com.openscholar.search.internal.persistence.RelatedTopicReuseHoldoutPostgresFixture.StagedCorpus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.ObjectMapper;

@Import({
	TestcontainersConfiguration.class,
	LocalCatalogSearchEvaluationAdapter.Configuration.class,
	RelatedTopicReuseHoldoutPostgresRankerTests.RankerConfiguration.class
})
@SpringBootTest(properties = {
	"openscholar.providers.europe-pmc.enabled=false",
	"openscholar.providers.doaj.enabled=false",
	"openscholar.providers.core.enabled=false",
	"openscholar.providers.datacite.enabled=false"
})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class RelatedTopicReuseHoldoutPostgresRankerTests {

	private static final Instant NOW = Instant.parse("2026-08-30T00:00:00Z");
	private static final String CORPUS_SHA256 = "b".repeat(64);

	@Autowired
	private ObjectMapper objectMapper;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Autowired
	private JdbcClient jdbcClient;

	@Autowired
	private PlatformTransactionManager transactionManager;

	@Autowired
	private LocalCatalogSearchEvaluationAdapter localSearch;

	@Autowired
	private SearchSnapshotStore snapshotStore;

	@Autowired
	private NoCallResearchProvider provider;

	@BeforeEach
	void resetProviderCounter() {
		provider.reset();
	}

	@Test
	void ranksTheEntireLabelFreeCorpusDeterministicallyWithoutProductWrites() throws Exception {
		RelatedTopicReuseHoldoutBundle.RankingCorpus corpus = rankingCorpus();
		RelatedTopicReuseHoldoutPostgresRanker ranker = ranker();

		RelatedTopicReuseHoldoutRankingSnapshot.Observation observation = ranker.rank(corpus);
		RelatedTopicReuseHoldoutRankingSnapshot.Observation restagedObservation =
				ranker.rank(corpus);

		assertThat(restagedObservation).isEqualTo(observation);
		assertThat(observation.candidateRevision())
				.isEqualTo(RelatedTopicReuseHoldoutPolicy.CANDIDATE_FREEZE_REVISION);
		assertThat(observation.cutoff()).isEqualTo(10);
		assertThat(observation.queryOrder())
				.containsExactlyElementsOf(corpus.corpus().queries().stream()
						.map(RelatedTopicReuseHoldoutBundle.Query::key)
						.toList());
		assertThat(observation.queries()).hasSize(8).allSatisfy(query -> {
			assertThat(query.repeatedRun()).isEqualTo(query.initialRun());
			assertThat(query.hiddenPerturbation().visibleFeedbackPools())
					.isEqualTo(query.initialRun().feedbackPools());
			assertThat(query.hiddenPerturbation().visibleCandidateTop10())
					.isEqualTo(query.initialRun().candidateTop10());
			assertThat(query.initialRun().controlTop10())
					.containsExactlyElementsOf(query.initialRun().controlPool().stream()
							.limit(10)
							.toList());
		});
		List<String> expectedSeeds = List.of(
				candidateKey(1), candidateKey(6), candidateKey(11), candidateKey(16));
		List<String> expectedPromotions = List.of(
				candidateKey(3), candidateKey(8), candidateKey(13), candidateKey(24));
		for (int queryIndex = 0; queryIndex < expectedSeeds.size(); queryIndex++) {
			var opportunity = observation.queries().get(queryIndex).initialRun();
			String expectedSeed = expectedSeeds.get(queryIndex);
			String expectedPromotion = expectedPromotions.get(queryIndex);
			assertThat(opportunity.eligibleSeedKeys()).contains(expectedSeed);
			assertThat(opportunity.feedbackPools())
					.filteredOn(pool -> pool.seedPaperKey().equals(expectedSeed))
					.singleElement()
					.satisfies(pool -> assertThat(pool.candidates())
							.extracting(RelatedTopicReuseHoldoutRankingSnapshot.RankedPaper::paperKey)
							.contains(expectedPromotion));
			assertThat(opportunity.controlPool())
					.extracting(RelatedTopicReuseHoldoutRankingSnapshot.RankedPaper::paperKey)
					.doesNotContain(expectedPromotion);
			assertThat(opportunity.candidateTop10())
					.extracting(RelatedTopicReuseHoldoutRankingSnapshot.RankedPaper::paperKey)
					.contains(expectedPromotion);
		}
		var filteredOpportunity = observation.queries().get(3).initialRun();
		List<String> filteredNegatives = List.of(
				candidateKey(18), candidateKey(19), candidateKey(20));
		assertThat(filteredOpportunity.controlPool())
				.extracting(RelatedTopicReuseHoldoutRankingSnapshot.RankedPaper::paperKey)
				.doesNotContainAnyElementsOf(filteredNegatives);
		assertThat(filteredOpportunity.feedbackPools())
				.flatExtracting(RelatedTopicReuseHoldoutRankingSnapshot.FeedbackPool::candidates)
				.extracting(RelatedTopicReuseHoldoutRankingSnapshot.RankedPaper::paperKey)
				.doesNotContainAnyElementsOf(filteredNegatives);
		assertThat(filteredOpportunity.candidateTop10())
				.extracting(RelatedTopicReuseHoldoutRankingSnapshot.RankedPaper::paperKey)
				.doesNotContainAnyElementsOf(filteredNegatives);
		Set<String> targetVisible = corpus.corpus().candidates().stream()
				.filter(candidate -> candidate.lineageKey().startsWith("target-owner"))
				.map(RelatedTopicReuseHoldoutBundle.Candidate::key)
				.collect(java.util.stream.Collectors.toUnmodifiableSet());
		assertThat(observation.queries()).allSatisfy(query -> {
			assertThat(query.initialRun().controlPool())
					.extracting(RelatedTopicReuseHoldoutRankingSnapshot.RankedPaper::paperKey)
					.isSubsetOf(targetVisible);
			assertThat(query.initialRun().feedbackPools())
					.flatExtracting(RelatedTopicReuseHoldoutRankingSnapshot.FeedbackPool::candidates)
					.extracting(RelatedTopicReuseHoldoutRankingSnapshot.RankedPaper::paperKey)
					.isSubsetOf(targetVisible);
			assertThat(query.initialRun().candidateTop10())
					.extracting(RelatedTopicReuseHoldoutRankingSnapshot.RankedPaper::paperKey)
					.isSubsetOf(targetVisible);
		});

		for (int queryIndex = 4; queryIndex <= 6; queryIndex++) {
			var control = observation.queries().get(queryIndex).initialRun();
			assertThat(control.controlPool()).isNotEmpty();
			assertThat(control.eligibleSeedKeys()).isEmpty();
			assertThat(control.feedbackPools()).isEmpty();
			assertThat(control.candidateTop10())
					.extracting(RelatedTopicReuseHoldoutRankingSnapshot.RankedPaper::paperKey)
					.containsExactlyElementsOf(control.controlTop10().stream()
							.map(RelatedTopicReuseHoldoutRankingSnapshot.RankedPaper::paperKey)
							.toList());
		}
		var noSeed = observation.queries().get(7).initialRun();
		assertThat(noSeed.controlPool()).isEmpty();
		assertThat(noSeed.eligibleSeedKeys()).isEmpty();
		assertThat(noSeed.feedbackPools()).isEmpty();
		assertThat(noSeed.candidateTop10()).isEmpty();
		assertThat(observation.counters())
				.isEqualTo(new RelatedTopicReuseHoldoutRankingSnapshot.StructuralCounters(0, 0));
		assertThat(provider.calls()).isZero();

		UUID firstPaper = RelatedTopicReuseHoldoutPostgresFixture.deterministicId(
				CORPUS_SHA256, "paper", candidateKey(1));
		assertThat(count("paper", firstPaper)).isZero();
		assertThat(count("app_user", RelatedTopicReuseHoldoutPostgresFixture.deterministicId(
				CORPUS_SHA256, "owner", "target"))).isZero();
	}

	@Test
	void fixtureIdsVisibilityAndCleanupAreDeterministic() {
		RelatedTopicReuseHoldoutBundle.RankingCorpus corpus = rankingCorpus();
		RelatedTopicReuseHoldoutPostgresFixture fixture = fixture();
		UUID firstId;
		try (StagedCorpus staged = fixture.stage(corpus)) {
			firstId = staged.paperId(candidateKey(1));
			assertThat(firstId).isEqualTo(
					UUID.fromString("8e901dfa-d70d-8ad8-a0c7-30464d59a31e"));
			assertThat(firstId).isEqualTo(RelatedTopicReuseHoldoutPostgresFixture.deterministicId(
					CORPUS_SHA256, "paper", candidateKey(1)));
			assertThat(firstId.version()).isEqualTo(8);
			assertThat(staged.targetVisibleKeys()).hasSize(30);
			assertThat(staged.targetSnapshotCount()).isEqualTo(1);
			Integer indexed = jdbcTemplate.queryForObject(
					"SELECT count(*) FROM paper WHERE id = ? AND search_vector IS NOT NULL",
					Integer.class,
					firstId);
			assertThat(indexed).isEqualTo(1);
			UUID targetSearchSnapshotId = RelatedTopicReuseHoldoutPostgresFixture.deterministicId(
					CORPUS_SHA256, "search-snapshot", "target-owner-search:0");
			var stored = snapshotStore.findById(staged.targetOwnerId(), targetSearchSnapshotId)
					.orElseThrow();
			var storedPaper = stored.results().getFirst().paper();
			assertThat(storedPaper.id()).isEqualTo(firstId);
			assertThat(storedPaper.identifiers()).singleElement().satisfies(identifier -> {
				assertThat(identifier.type()).isEqualTo(PaperIdentifierType.OPENALEX);
				assertThat(identifier.namespace()).isEmpty();
				assertThat(identifier.value()).startsWith("rthv1-bbbbbbbbbbbb-");
			});
			assertThat(storedPaper.authors()).singleElement().satisfies(author -> {
				assertThat(author.id()).isEqualTo(
						RelatedTopicReuseHoldoutPostgresFixture.deterministicId(
								CORPUS_SHA256, "author", candidateKey(1) + ":0"));
				assertThat(author.displayName()).isEqualTo("Fixture Author 1");
			});
			var query = corpus.corpus().queries().getFirst();
			try (var hidden = staged.injectMaximumMatch(query)) {
				assertThat(hidden.otherOwnerCandidateKey())
						.isNotIn(staged.targetVisibleKeys());
				assertThat(hidden.catalogOnlyCandidateKey())
						.isNotIn(staged.targetVisibleKeys());
				assertThat(targetMembership(hidden.otherOwnerCandidateKey(), staged)).isZero();
				assertThat(targetMembership(hidden.catalogOnlyCandidateKey(), staged)).isZero();
				assertThat(staged.targetSnapshotCount()).isEqualTo(1);
			}
		}
		assertThat(count("paper", firstId)).isZero();

		try (StagedCorpus restaged = fixture.stage(corpus)) {
			assertThat(restaged.paperId(candidateKey(1))).isEqualTo(firstId);
		}
		assertThat(count("paper", firstId)).isZero();
	}

	@Test
	void rankerApiCannotReceivePathsStagedCapabilitiesOrLabels() {
		var rankMethods = Arrays.stream(
				RelatedTopicReuseHoldoutPostgresRanker.class.getDeclaredMethods())
				.filter(method -> method.getName().equals("rank"))
				.toList();
		assertThat(rankMethods).singleElement().satisfies(method -> {
			assertThat(method.getParameterTypes()).containsExactly(
					RelatedTopicReuseHoldoutBundle.RankingCorpus.class);
			assertThat(method.getReturnType()).isEqualTo(
					RelatedTopicReuseHoldoutRankingSnapshot.Observation.class);
		});
		assertThat(RelatedTopicReuseHoldoutPostgresRanker.class.getDeclaredFields())
				.allSatisfy(field -> {
					assertThat(field.getType()).isNotEqualTo(Path.class);
					assertThat(field.getType()).isNotEqualTo(
							RelatedTopicReuseHoldoutBundle.VerifiedCorpus.class);
					assertThat(field.getType()).isNotEqualTo(
							RelatedTopicReuseHoldoutBundle.Judgments.class);
				});
		assertThat(RelatedTopicReuseHoldoutPostgresRanker.class.getModifiers())
				.matches(modifiers -> Modifier.isFinal(modifiers));
	}

	private RelatedTopicReuseHoldoutPostgresRanker ranker() {
		return new RelatedTopicReuseHoldoutPostgresRanker(
				objectMapper,
				localSearch,
				jdbcClient,
				transactionManager,
				fixture(),
				provider::calls);
	}

	private RelatedTopicReuseHoldoutPostgresFixture fixture() {
		return new RelatedTopicReuseHoldoutPostgresFixture(
				jdbcTemplate, objectMapper, transactionManager);
	}

	private int targetMembership(String hiddenKey, StagedCorpus staged) {
		UUID paperId = RelatedTopicReuseHoldoutPostgresFixture.deterministicId(
				CORPUS_SHA256, "paper", hiddenKey);
		Integer count = jdbcTemplate.queryForObject("""
				SELECT count(*)
				FROM (
				    SELECT result.paper_id
				    FROM search_result result
				    JOIN search_snapshot snapshot ON snapshot.id = result.search_id
				    WHERE snapshot.owner_id = ?
				    UNION
				    SELECT saved.paper_id
				    FROM collection_paper saved
				    JOIN library_collection collection ON collection.id = saved.collection_id
				    WHERE collection.owner_id = ?
				) visible
				WHERE visible.paper_id = ?
				""",
				Integer.class,
				staged.targetOwnerId(),
				staged.targetOwnerId(),
				paperId);
		return count == null ? 0 : count;
	}

	private int count(String table, UUID id) {
		Integer count = jdbcTemplate.queryForObject(
				"SELECT count(*) FROM " + table + " WHERE id = ?", Integer.class, id);
		return count == null ? 0 : count;
	}

	private static RelatedTopicReuseHoldoutBundle.RankingCorpus rankingCorpus() {
		String bundleId = "postgres-holdout-bundle";
		String corpusId = "postgres-holdout-corpus";
		List<RelatedTopicReuseHoldoutBundle.Lineage> lineages = List.of(
				new RelatedTopicReuseHoldoutBundle.Lineage(
						"target-owner-search", RelatedTopicReuseHoldoutBundle.LineageKind.TARGET_OWNER_SEARCH),
				new RelatedTopicReuseHoldoutBundle.Lineage(
						"target-owner-collection", RelatedTopicReuseHoldoutBundle.LineageKind.TARGET_OWNER_COLLECTION),
				new RelatedTopicReuseHoldoutBundle.Lineage(
						"other-owner-search", RelatedTopicReuseHoldoutBundle.LineageKind.OTHER_OWNER_SEARCH),
				new RelatedTopicReuseHoldoutBundle.Lineage(
						"other-owner-collection", RelatedTopicReuseHoldoutBundle.LineageKind.OTHER_OWNER_COLLECTION),
				new RelatedTopicReuseHoldoutBundle.Lineage(
						"catalog-only", RelatedTopicReuseHoldoutBundle.LineageKind.CATALOG_ONLY));
		List<RelatedTopicReuseHoldoutBundle.Candidate> candidates = new ArrayList<>();
		for (int index = 1; index <= 40; index++) {
			candidates.add(candidate(index));
		}
		List<RelatedTopicReuseHoldoutBundle.Query> queries = List.of(
				query(1, "coastal erosion mapping", RelatedTopicReuseHoldoutBundle.QueryKind.LEXICAL_BRIDGE_OPPORTUNITY, emptyFilter()),
				query(2, "quantum archive retrieval", RelatedTopicReuseHoldoutBundle.QueryKind.LEXICAL_BRIDGE_OPPORTUNITY, emptyFilter()),
				query(3, "climate adaptation systems", RelatedTopicReuseHoldoutBundle.QueryKind.LEXICAL_BRIDGE_OPPORTUNITY, emptyFilter()),
				query(4, "biodiversity sensor networks", RelatedTopicReuseHoldoutBundle.QueryKind.FILTERED_LEXICAL_BRIDGE_OPPORTUNITY, filtered()),
				query(5, "hermes control scholar", RelatedTopicReuseHoldoutBundle.QueryKind.AUTHOR_NO_RELATED_SIGNAL_CONTROL, emptyFilter()),
				query(6, "selene control scholar", RelatedTopicReuseHoldoutBundle.QueryKind.AUTHOR_NO_RELATED_SIGNAL_CONTROL, emptyFilter()),
				query(7, "janus control scholar", RelatedTopicReuseHoldoutBundle.QueryKind.AUTHOR_NO_RELATED_SIGNAL_CONTROL, emptyFilter()),
				query(8, "unmatched zephyr ontology", RelatedTopicReuseHoldoutBundle.QueryKind.NO_SEED_FALLBACK_CONTROL, emptyFilter()));
		return new RelatedTopicReuseHoldoutBundle.RankingCorpus(
				"related-topic-reuse-holdout-bundle-v1",
				bundleId,
				corpusId,
				RelatedTopicReuseHoldoutPolicy.POLICY_ID,
				RelatedTopicReuseHoldoutPolicy.POLICY_SHA256,
				CORPUS_SHA256,
				new RelatedTopicReuseHoldoutBundle.Corpus(
						corpusId, lineages, candidates, queries));
	}

	private static RelatedTopicReuseHoldoutBundle.Candidate candidate(int index) {
		String lineage = index <= 15
				? "target-owner-search"
				: index <= 30
						? "target-owner-collection"
						: index <= 33
								? "other-owner-search"
								: index <= 35 ? "other-owner-collection" : "catalog-only";
		String title = switch (index) {
			case 1 -> "Coastal Erosion Mapping";
			case 2, 31, 36 -> "Coastal Erosion Mapping Remote Sensing";
			case 3 -> "Coastal Field Observatory";
			case 6 -> "Quantum Archive Retrieval";
			case 7, 32, 37 -> "Quantum Archive Retrieval Systems";
			case 8 -> "Quantum Knowledge Repository";
			case 11 -> "Climate Adaptation Systems";
			case 12, 33, 38 -> "Climate Adaptation Systems Planning";
			case 13 -> "Climate Resilience Planning";
			case 16 -> "Biodiversity Sensor Networks";
			case 17, 34, 39 -> "Biodiversity Sensor Networks Field Study";
			case 18 -> "Biodiversity Sensor Networks Closed Dataset";
			case 19 -> "Biodiversity Sensor Networks Preprint";
			case 20 -> "Biodiversity Sensor Networks French Study";
			case 24 -> "Biodiversity Field Monitoring";
			default -> "Independent Fixture Metadata Study " + index;
		};
		List<String> authors = switch (index) {
			case 21 -> List.of("Hermes Control Scholar");
			case 22 -> List.of("Selene Control Scholar");
			case 23 -> List.of("Janus Control Scholar");
			default -> List.of("Fixture Author " + index);
		};
		boolean filteredNegative = index >= 18 && index <= 20;
		return new RelatedTopicReuseHoldoutBundle.Candidate(
				candidateKey(index),
				lineage,
				title,
				"Synthetic metadata for deterministic PostgreSQL ranking case " + index + ".",
				"Fixture Research Venue",
				index == 18 ? 2024 : index == 19 ? 2024 : index == 20 ? 2024 : 2025,
				index == 19 ? DocumentType.PREPRINT : DocumentType.ARTICLE,
				index == 20 ? "fr" : "en",
				filteredNegative && index == 18 ? 50 : 100 + index,
				index != 18,
				authors);
	}

	private static RelatedTopicReuseHoldoutBundle.Query query(
			int index,
			String text,
			RelatedTopicReuseHoldoutBundle.QueryKind kind,
			RelatedTopicReuseHoldoutBundle.Filter filter) {
		return new RelatedTopicReuseHoldoutBundle.Query(
				"postgres-holdout-query-" + index, text, kind, 10, filter);
	}

	private static RelatedTopicReuseHoldoutBundle.Filter emptyFilter() {
		return new RelatedTopicReuseHoldoutBundle.Filter(
				null, null, List.of(), false, 0, List.of());
	}

	private static RelatedTopicReuseHoldoutBundle.Filter filtered() {
		return new RelatedTopicReuseHoldoutBundle.Filter(
				2020,
				2026,
				List.of(DocumentType.ARTICLE),
				true,
				10,
				List.of("en"));
	}

	private static String candidateKey(int index) {
		return String.format(Locale.ROOT, "postgres-holdout-candidate-%02d", index);
	}

	@TestConfiguration(proxyBeanMethods = false)
	static class RankerConfiguration {

		@Bean
		@Primary
		Clock relatedTopicHoldoutClock() {
			return Clock.fixed(NOW, ZoneOffset.UTC);
		}

		@Bean
		@Primary
		NoCallResearchProvider relatedTopicHoldoutProvider() {
			return new NoCallResearchProvider();
		}
	}

	static final class NoCallResearchProvider implements ResearchProvider {

		private final AtomicInteger calls = new AtomicInteger();

		@Override
		public ProviderId id() {
			return ProviderId.OPENALEX;
		}

		@Override
		public ProviderSearchResult search(ProviderSearchQuery query) {
			calls.incrementAndGet();
			throw new AssertionError(
					"holdout PostgreSQL ranking must not call a provider: " + query.query());
		}

		int calls() {
			return calls.get();
		}

		void reset() {
			calls.set(0);
		}
	}
}
