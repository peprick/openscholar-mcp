package com.openscholar.paper.internal.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

import com.openscholar.TestcontainersConfiguration;
import com.openscholar.embedding.EmbeddingGenerator;
import com.openscholar.jobs.EmbeddingBackfillCommand;
import com.openscholar.jobs.EmbeddingBackfillDisposition;
import com.openscholar.jobs.EmbeddingBackfillResult;
import com.openscholar.jobs.EmbeddingBackfillUseCase;
import com.openscholar.paper.EmbeddingContentKind;
import com.openscholar.paper.EmbeddingDistanceMetric;
import com.openscholar.paper.PaperCatalog;
import com.openscholar.paper.PaperEmbeddingMatch;
import com.openscholar.paper.PaperEmbeddingStore;
import com.openscholar.paper.PaperView;
import com.openscholar.paper.RelatedPaperMatch;
import com.openscholar.paper.RelatedPaperUseCase;
import com.openscholar.paper.RelatedPapersView;
import com.openscholar.paper.internal.persistence.RelatedPaperEvaluationCorpus.SeededCorpus;
import com.openscholar.paper.internal.persistence.RelatedPaperEvaluationFixture.EvaluationQuery;
import com.openscholar.paper.internal.persistence.RelatedPaperEvaluationFixture.Split;
import com.openscholar.paper.internal.persistence.RelatedPaperEvaluationMetrics.QueryMeasurement;
import com.openscholar.paper.internal.persistence.RelatedPaperEvaluationMetrics.Summary;
import com.openscholar.paper.internal.persistence.RelatedPaperHybridPolicy.Acceptance;
import com.openscholar.paper.internal.persistence.RelatedPaperHybridScorer.HybridCandidateFeatures;
import com.openscholar.paper.internal.persistence.RelatedPaperHybridScorer.HybridRankedPaper;
import com.openscholar.paper.internal.persistence.RelatedPaperHybridScorer.VectorRankedPaper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@EnabledIfEnvironmentVariable(named = "RUN_OLLAMA_HOLDOUT_EVALUATION", matches = "true")
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class MetadataHybridHoldoutEvaluationTests {

	private static final String DEVELOPMENT_FIXTURE_PATH =
			"search/relevance/related-metadata-baseline-v1.json";
	private static final String HOLDOUT_FIXTURE_PATH =
			"search/relevance/related-metadata-holdout-v1.json";
	private static final String POLICY_PATH =
			"search/relevance/related-hybrid-policy-v1.json";
	private static final String EXPECTED_DEVELOPMENT_FIXTURE_ID =
			"related-metadata-baseline-v1";
	private static final String EXPECTED_HOLDOUT_FIXTURE_ID =
			"related-metadata-holdout-v1";
	private static final String EXPECTED_POLICY_ID = "related-hybrid-policy-v1";
	private static final String EXPECTED_VECTOR_DIGEST =
			"ac6da0dfba84a81fdbfbaf330198c33cd77c4cdfc53e8bc50eb581914a15621d";
	private static final String EXPECTED_VECTOR_PROFILE_KEY =
			"paper-semantic-v1-" + EXPECTED_VECTOR_DIGEST + "-ollama-0-31-1";
	private static final String EXPECTED_VECTOR_MODEL_REVISION =
			"sha256:" + EXPECTED_VECTOR_DIGEST + ";ollama:0.31.1";
	private static final String EXPECTED_LEXICAL_TRANSFORM =
			"TS_RANK_CD_NORMALIZATION_32_IDENTITY";
	private static final String EXPECTED_SEMANTIC_TRANSFORM =
			"CLAMPED_COSINE_PLUS_ONE_OVER_TWO";
	private static final String EXPECTED_CANDIDATE_RULE =
			"ALL_EXACT_VECTOR_CANDIDATES";
	private static final String EXPECTED_MIXED_WEIGHT_TIE_BREAK = "FIXTURE_PAPER_KEY";
	private static final int DEVELOPMENT_PAPER_COUNT = 18;
	private static final int DEVELOPMENT_QUERY_COUNT = 5;
	private static final int HOLDOUT_PAPER_COUNT = 26;
	private static final int HOLDOUT_QUERY_COUNT = 7;
	private static final int CANDIDATE_COUNT = HOLDOUT_PAPER_COUNT - 1;
	private static final double FROZEN_SEMANTIC_WEIGHT = 0.50d;
	private static final double COMPARISON_EPSILON = 1.0e-9d;
	private static final Instant RETRIEVED_AT = Instant.parse("2026-08-20T10:00:00Z");
	private static final List<String> EXPECTED_HOLDOUT_QUERY_KEYS = List.of(
			"reef-recovery-soundscapes",
			"photonic-bosonic-correction",
			"french-urban-heat",
			"sodium-metal-interphases",
			"japanese-ukiyoe-pigments",
			"temperate-exoplanet-clouds",
			"software-build-provenance");

	@Autowired
	private ObjectMapper objectMapper;

	@Autowired
	private PaperCatalog paperCatalog;

	@Autowired
	private RelatedPaperUseCase relatedPapers;

	@Autowired
	private PaperEmbeddingStore embeddingStore;

	@Autowired
	private EmbeddingBackfillUseCase embeddingBackfill;

	@Autowired
	private ObjectProvider<EmbeddingGenerator> embeddingGenerators;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Test
	@Transactional(propagation = Propagation.NOT_SUPPORTED)
	@DirtiesContext(methodMode = DirtiesContext.MethodMode.AFTER_METHOD)
	void independentlyAuthoredHoldoutEvaluatesOnlyTheFrozenHybridPolicy() throws Exception {
		RelatedPaperEvaluationFixture development = RelatedPaperEvaluationFixture.load(
				objectMapper, DEVELOPMENT_FIXTURE_PATH);
		RelatedPaperEvaluationFixture holdout = RelatedPaperEvaluationFixture.load(
				objectMapper, HOLDOUT_FIXTURE_PATH);
		RelatedPaperHybridPolicy policy = loadPolicy();
		assertFrozenInputs(development, holdout, policy);

		SeededCorpus corpus = RelatedPaperEvaluationCorpus.seed(
				paperCatalog, holdout, RETRIEVED_AT);
		assertThat(corpus.papersByKey()).hasSize(HOLDOUT_PAPER_COUNT);
		assertIsolatedHoldoutDatabaseBeforeBackfill();

		EmbeddingGenerator generator = assertPinnedOllamaGenerator();
		var profile = generator.profile();
		EmbeddingBackfillResult backfill = embeddingBackfill.run(new EmbeddingBackfillCommand(
				profile.profileKey(), null, HOLDOUT_PAPER_COUNT, 2));
		assertCompleteHoldoutBackfill(backfill, profile.profileKey());
		assertIsolatedHoldoutDatabaseAfterBackfill(profile.profileKey());

		System.out.printf(
				Locale.ROOT,
				"related-hybrid-holdout-v1 policy=%s development-fixture=%s "
						+ "holdout-fixture=%s profile=%s provider=%s model=%s revision=%s "
						+ "content-kind=%s input-policy-version=%d dimensions=%d distance=%s "
						+ "papers=%d candidates-per-query=%d semantic-weight=%.2f%n",
				policy.policyId(),
				development.fixtureId(),
				holdout.fixtureId(),
				profile.profileKey(),
				profile.provider(),
				profile.model(),
				profile.modelRevision(),
				profile.contentKind(),
				profile.inputPolicyVersion(),
				profile.dimensions(),
				profile.distanceMetric(),
				HOLDOUT_PAPER_COUNT,
				CANDIDATE_COUNT,
				policy.semanticWeight());

		List<QueryMeasurement<HybridRankedPaper>> lexicalMeasurements = new ArrayList<>();
		List<QueryMeasurement<HybridRankedPaper>> vectorMeasurements = new ArrayList<>();
		List<QueryMeasurement<HybridRankedPaper>> hybridMeasurements = new ArrayList<>();
		for (EvaluationQuery query : holdout.queries()) {
			PaperView source = RelatedPaperEvaluationAssertions.assertValidQuery(
					query, corpus.papersByKey(), "hybrid holdout");
			QueryEvaluation evaluation = evaluateQuery(
					query, source, corpus, profile.profileKey(), policy.semanticWeight());
			lexicalMeasurements.add(evaluation.lexical());
			vectorMeasurements.add(evaluation.vector());
			hybridMeasurements.add(evaluation.hybrid());
		}

		Summary lexicalSummary = RelatedPaperEvaluationMetrics.summarize(lexicalMeasurements);
		Summary vectorSummary = RelatedPaperEvaluationMetrics.summarize(vectorMeasurements);
		Summary hybridSummary = RelatedPaperEvaluationMetrics.summarize(hybridMeasurements);
		printPairedResults(
				lexicalMeasurements,
				vectorMeasurements,
				hybridMeasurements,
				lexicalSummary,
				vectorSummary,
				hybridSummary);
		assertAcceptanceCriteria(
				policy.acceptance(),
				lexicalMeasurements,
				hybridMeasurements,
				lexicalSummary,
				hybridSummary);
		printAcceptanceSummary(
				policy,
				lexicalMeasurements,
				hybridMeasurements,
				lexicalSummary,
				hybridSummary);
	}

	private QueryEvaluation evaluateQuery(
			EvaluationQuery query,
			PaperView source,
			SeededCorpus corpus,
			String profileKey,
			double semanticWeight) {
		assertThat(CANDIDATE_COUNT).isEqualTo(25);

		RelatedPapersView firstLexical = relatedPapers.findRelated(source.id(), CANDIDATE_COUNT);
		RelatedPapersView repeatedLexical = relatedPapers.findRelated(
				source.id(), CANDIDATE_COUNT);
		RelatedPaperEvaluationAssertions.assertStableExplainableResults(
				firstLexical,
				repeatedLexical,
				source.id(),
				RelatedPaperMatch.POSTGRES_FULL_TEXT_REASON);
		LexicalFeatures firstLexicalFeatures = lexicalFeatures(firstLexical, corpus, query);
		LexicalFeatures repeatedLexicalFeatures = lexicalFeatures(
				repeatedLexical, corpus, query);
		assertThat(repeatedLexicalFeatures).isEqualTo(firstLexicalFeatures);

		List<PaperEmbeddingMatch> firstVector = embeddingStore.findNearestExact(
				source.id(), profileKey, CANDIDATE_COUNT);
		List<PaperEmbeddingMatch> repeatedVector = embeddingStore.findNearestExact(
				source.id(), profileKey, CANDIDATE_COUNT);
		RelatedPaperEvaluationAssertions.assertStableExactVectorResults(
				firstVector, repeatedVector, source.id(), CANDIDATE_COUNT);
		List<VectorRankedPaper> firstVectorFeatures = vectorFeatures(firstVector, corpus, query);
		List<VectorRankedPaper> repeatedVectorFeatures = vectorFeatures(
				repeatedVector, corpus, query);
		assertThat(repeatedVectorFeatures).containsExactlyElementsOf(firstVectorFeatures);

		List<HybridCandidateFeatures> candidates = RelatedPaperHybridScorer.combine(
				firstVectorFeatures,
				firstLexicalFeatures.scores(),
				firstLexicalFeatures.ranks(),
				CANDIDATE_COUNT);
		List<HybridCandidateFeatures> repeatedCandidates = RelatedPaperHybridScorer.combine(
				repeatedVectorFeatures,
				repeatedLexicalFeatures.scores(),
				repeatedLexicalFeatures.ranks(),
				CANDIDATE_COUNT);
		assertStableCompleteCandidateFeatures(
				query, firstLexicalFeatures, candidates, repeatedCandidates);

		List<HybridRankedPaper> lexical = RelatedPaperHybridScorer.rankHybridCandidates(
				candidates, 0.0d, query.cutoff());
		List<HybridRankedPaper> vector = RelatedPaperHybridScorer.rankHybridCandidates(
				candidates, 1.0d, query.cutoff());
		List<HybridRankedPaper> hybrid = RelatedPaperHybridScorer.rankHybridCandidates(
				candidates, semanticWeight, query.cutoff());
		RelatedPaperEvaluationAssertions.assertHybridRanking(
				lexical, query.sourceKey(), query.cutoff(), 0.0d);
		RelatedPaperEvaluationAssertions.assertHybridRanking(
				vector, query.sourceKey(), query.cutoff(), 1.0d);
		RelatedPaperEvaluationAssertions.assertHybridRanking(
				hybrid, query.sourceKey(), query.cutoff(), semanticWeight);

		assertThat(lexical).extracting(HybridRankedPaper::paperKey)
				.containsExactlyElementsOf(firstLexicalFeatures.rankedKeys().stream()
						.limit(query.cutoff())
						.toList());
		assertThat(vector).extracting(HybridRankedPaper::paperKey)
				.containsExactlyElementsOf(firstVectorFeatures.stream()
						.limit(query.cutoff())
						.map(VectorRankedPaper::paperKey)
						.toList());

		System.out.printf(
				Locale.ROOT,
				"related-hybrid-holdout-v1 query=%s pool=%d lexical-matches=%d "
						+ "lexical-transform=%s semantic-transform=%s tie-break=%s%n",
				query.key(),
				candidates.size(),
				firstLexicalFeatures.rankedKeys().size(),
				EXPECTED_LEXICAL_TRANSFORM,
				EXPECTED_SEMANTIC_TRANSFORM,
				EXPECTED_MIXED_WEIGHT_TIE_BREAK);
		return new QueryEvaluation(
				RelatedPaperEvaluationMetrics.measure(
						query, lexical, HybridRankedPaper::paperKey),
				RelatedPaperEvaluationMetrics.measure(
						query, vector, HybridRankedPaper::paperKey),
				RelatedPaperEvaluationMetrics.measure(
						query, hybrid, HybridRankedPaper::paperKey));
	}

	private LexicalFeatures lexicalFeatures(
			RelatedPapersView view, SeededCorpus corpus, EvaluationQuery query) {
		assertThat(view.results()).hasSizeLessThanOrEqualTo(CANDIDATE_COUNT);
		Map<String, Double> scores = new LinkedHashMap<>();
		Map<String, Integer> ranks = new LinkedHashMap<>();
		List<String> rankedKeys = new ArrayList<>();
		for (RelatedPaperMatch match : view.results()) {
			String paperKey = corpus.keyFor(match.paper().id(), query.key());
			assertThat(scores.put(paperKey, match.score())).isNull();
			assertThat(ranks.put(paperKey, match.rank())).isNull();
			assertThat(Double.isFinite(match.score())).isTrue();
			assertThat(match.score()).isStrictlyBetween(0.0d, 1.0d);
			rankedKeys.add(paperKey);
		}
		assertThat(rankedKeys).doesNotContain(query.sourceKey()).doesNotHaveDuplicates();
		return new LexicalFeatures(scores, ranks, rankedKeys);
	}

	private static List<VectorRankedPaper> vectorFeatures(
			List<PaperEmbeddingMatch> matches, SeededCorpus corpus, EvaluationQuery query) {
		return matches.stream()
				.map(match -> new VectorRankedPaper(
						corpus.keyFor(match.paperId(), query.key()),
						match.cosineSimilarity()))
				.toList();
	}

	private static void assertStableCompleteCandidateFeatures(
			EvaluationQuery query,
			LexicalFeatures lexical,
			List<HybridCandidateFeatures> candidates,
			List<HybridCandidateFeatures> repeatedCandidates) {
		assertThat(candidates).hasSize(CANDIDATE_COUNT);
		assertThat(repeatedCandidates).containsExactlyElementsOf(candidates);
		assertThat(candidates).extracting(HybridCandidateFeatures::paperKey)
				.doesNotContain(query.sourceKey())
				.doesNotHaveDuplicates()
				.containsAll(lexical.scores().keySet());
		assertThat(candidates).extracting(HybridCandidateFeatures::semanticRank)
				.containsExactlyElementsOf(
						java.util.stream.IntStream.rangeClosed(1, CANDIDATE_COUNT)
								.boxed()
								.toList());
		assertThat(candidates).allSatisfy(candidate -> {
			assertThat(Double.isFinite(candidate.lexicalScore())).isTrue();
			assertThat(Double.isFinite(candidate.semanticScore())).isTrue();
			assertThat(Double.isFinite(candidate.cosineSimilarity())).isTrue();
			assertThat(candidate.lexicalScore()).isBetween(0.0d, 1.0d);
			assertThat(candidate.semanticScore()).isBetween(0.0d, 1.0d);
			assertThat(candidate.cosineSimilarity()).isBetween(-1.000001d, 1.000001d);
			assertThat(candidate.lexicalRank()).isBetween(1, CANDIDATE_COUNT + 1);
			assertThat(candidate.semanticRank()).isBetween(1, CANDIDATE_COUNT);
		});
	}

	private RelatedPaperHybridPolicy loadPolicy() throws Exception {
		return RelatedPaperHybridPolicy.load(objectMapper, POLICY_PATH);
	}

	static void assertFrozenInputs(
			RelatedPaperEvaluationFixture development,
			RelatedPaperEvaluationFixture holdout,
			RelatedPaperHybridPolicy policy) {
		assertThat(development.fixtureId()).isEqualTo(EXPECTED_DEVELOPMENT_FIXTURE_ID);
		assertThat(development.split()).isEqualTo(Split.DEVELOPMENT);
		assertThat(development.version()).isEqualTo(1);
		assertThat(development.rankingMethod())
				.isEqualTo(RelatedPaperMatch.POSTGRES_FULL_TEXT_REASON);
		assertThat(development.papers()).hasSize(DEVELOPMENT_PAPER_COUNT);
		assertThat(development.queries()).hasSize(DEVELOPMENT_QUERY_COUNT);
		RelatedPaperEvaluationAssertions.assertWellFormed(development);

		assertThat(holdout.fixtureId()).isEqualTo(EXPECTED_HOLDOUT_FIXTURE_ID);
		assertThat(holdout.split()).isEqualTo(Split.HOLDOUT);
		assertThat(holdout.version()).isEqualTo(1);
		assertThat(holdout.rankingMethod())
				.isEqualTo(RelatedPaperMatch.POSTGRES_FULL_TEXT_REASON);
		assertThat(holdout.papers()).hasSize(HOLDOUT_PAPER_COUNT);
		assertThat(holdout.queries()).hasSize(HOLDOUT_QUERY_COUNT);
		assertThat(holdout.queries()).extracting(EvaluationQuery::key)
				.containsExactlyElementsOf(EXPECTED_HOLDOUT_QUERY_KEYS);
		RelatedPaperEvaluationAssertions.assertWellFormed(holdout);
		assertSubstantiveHoldoutCoverage(holdout);
		RelatedPaperEvaluationAssertions.assertDevelopmentAndHoldoutAreDisjoint(
				development, holdout);

		assertThat(policy).isNotNull();
		assertThat(policy.version()).isEqualTo(1);
		assertThat(policy.policyId()).isEqualTo(EXPECTED_POLICY_ID);
		assertThat(policy.developmentFixtureId()).isEqualTo(development.fixtureId());
		assertThat(policy.semanticWeight()).isEqualTo(FROZEN_SEMANTIC_WEIGHT);
		assertThat(policy.lexicalTransform()).isEqualTo(EXPECTED_LEXICAL_TRANSFORM);
		assertThat(policy.semanticTransform()).isEqualTo(EXPECTED_SEMANTIC_TRANSFORM);
		assertThat(policy.candidateRule()).isEqualTo(EXPECTED_CANDIDATE_RULE);
		assertThat(policy.mixedWeightTieBreak())
				.isEqualTo(EXPECTED_MIXED_WEIGHT_TIE_BREAK);
		assertFrozenAcceptancePolicy(policy.acceptance());
	}

	private static void assertSubstantiveHoldoutCoverage(
			RelatedPaperEvaluationFixture holdout) {
		Set<String> paperKeys = holdout.papers().stream()
				.map(RelatedPaperEvaluationFixture.FixturePaper::key)
				.collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
		Set<String> sourceKeys = holdout.queries().stream()
				.map(EvaluationQuery::sourceKey)
				.collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
		Set<String> judgedKeys = holdout.queries().stream()
				.flatMap(query -> query.judgments().keySet().stream())
				.collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
		Set<String> coveredKeys = new LinkedHashSet<>(sourceKeys);
		coveredKeys.addAll(judgedKeys);
		assertThat(sourceKeys).hasSize(HOLDOUT_QUERY_COUNT)
				.doesNotContainAnyElementsOf(judgedKeys);
		assertThat(coveredKeys).containsExactlyInAnyOrderElementsOf(paperKeys);
		assertThat(holdout.queries()).allSatisfy(query -> {
			assertThat(query.cutoff()).isEqualTo(5);
			long relevantCount = query.judgments().values().stream()
					.filter(grade -> grade > 0)
					.count();
			assertThat(relevantCount).isPositive().isLessThanOrEqualTo(query.cutoff());
			assertThat(query.judgments().values()).contains(0);
		});
		assertThat(holdout.papers()).extracting(
				RelatedPaperEvaluationFixture.FixturePaper::language)
				.contains("de", "en", "fr", "ja");
		assertThat(holdout.papers()).anySatisfy(paper -> assertThat(paper.abstractText()).isNull());
		assertThat(holdout.papers()).anySatisfy(paper -> {
			assertThat(paper.year()).isEqualTo(2026);
			assertThat(paper.citationCount()).isZero();
		});
	}

	private static void assertFrozenAcceptancePolicy(Acceptance acceptance) {
		assertThat(acceptance).isNotNull();
		assertThat(acceptance.minimumQueryRecall()).isEqualTo(0.50d);
		assertThat(acceptance.minimumQueryNdcg()).isEqualTo(0.60d);
		assertThat(acceptance.preserveLexicalQueryRecall()).isTrue();
		assertThat(acceptance.minimumMacroNdcgGainOverLexical()).isEqualTo(0.03d);
		assertThat(acceptance.preserveLexicalMacroRecall()).isTrue();
		assertThat(acceptance.preserveLexicalMacroPrecisionAtOne()).isTrue();
		assertThat(acceptance.preserveLexicalMeanReciprocalRank()).isTrue();
		assertThat(acceptance.minimumStrictNdcgImprovementQueryCount()).isEqualTo(2);
		assertThat(acceptance.maximumNdcgRegressionQueryCount()).isEqualTo(1);
		assertThat(acceptance.maximumPerQueryNdcgRegression()).isEqualTo(0.10d);
	}

	private EmbeddingGenerator assertPinnedOllamaGenerator() {
		List<EmbeddingGenerator> configuredGenerators = embeddingGenerators.orderedStream().toList();
		assertThat(configuredGenerators).hasSize(1);
		EmbeddingGenerator generator = configuredGenerators.getFirst();
		var profile = generator.profile();
		assertThat(profile.profileKey()).isEqualTo(EXPECTED_VECTOR_PROFILE_KEY);
		assertThat(profile.provider()).isEqualTo("ollama");
		assertThat(profile.model()).isEqualTo("qwen3-embedding:0.6b");
		assertThat(profile.modelRevision()).isEqualTo(EXPECTED_VECTOR_MODEL_REVISION);
		assertThat(profile.contentKind()).isEqualTo(EmbeddingContentKind.TITLE_ABSTRACT);
		assertThat(profile.inputPolicyVersion()).isEqualTo(1);
		assertThat(profile.dimensions()).isEqualTo(1024);
		assertThat(profile.distanceMetric()).isEqualTo(EmbeddingDistanceMetric.COSINE);
		return generator;
	}

	private static void assertCompleteHoldoutBackfill(
			EmbeddingBackfillResult backfill, String profileKey) {
		assertThat(backfill.profileKey()).isEqualTo(profileKey);
		assertThat(backfill.disposition()).isEqualTo(EmbeddingBackfillDisposition.COMPLETED);
		assertThat(backfill.scannedCount()).isEqualTo(HOLDOUT_PAPER_COUNT);
		assertThat(backfill.storedCount()).isEqualTo(HOLDOUT_PAPER_COUNT);
		assertThat(backfill.unchangedCount()).isZero();
		assertThat(backfill.deletedCount()).isZero();
		assertThat(backfill.failureCount()).isZero();
		assertThat(backfill.failures()).isEmpty();
		assertThat(backfill.nextCursor()).isNull();
	}

	private void assertIsolatedHoldoutDatabaseBeforeBackfill() {
		assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM paper", Long.class))
				.as("holdout database contains only the independently authored papers")
				.isEqualTo((long) HOLDOUT_PAPER_COUNT);
		assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM embedding_profile", Long.class))
				.as("holdout database starts without an embedding profile")
				.isZero();
		assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM paper_embedding", Long.class))
				.as("holdout database starts without paper embeddings")
				.isZero();
	}

	private void assertIsolatedHoldoutDatabaseAfterBackfill(String profileKey) {
		assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM paper", Long.class))
				.isEqualTo((long) HOLDOUT_PAPER_COUNT);
		assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM embedding_profile", Long.class))
				.isOne();
		assertThat(jdbcTemplate.queryForObject(
				"SELECT count(*) FROM embedding_profile WHERE profile_key = ?",
				Long.class,
				profileKey))
				.isOne();
		assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM paper_embedding", Long.class))
				.isEqualTo((long) HOLDOUT_PAPER_COUNT);
		assertThat(jdbcTemplate.queryForObject(
				"SELECT count(*) FROM paper_embedding WHERE profile_key = ?",
				Long.class,
				profileKey))
				.isEqualTo((long) HOLDOUT_PAPER_COUNT);
	}

	private static void printPairedResults(
			List<QueryMeasurement<HybridRankedPaper>> lexical,
			List<QueryMeasurement<HybridRankedPaper>> vector,
			List<QueryMeasurement<HybridRankedPaper>> hybrid,
			Summary lexicalSummary,
			Summary vectorSummary,
			Summary hybridSummary) {
		assertThat(vector).hasSameSizeAs(lexical);
		assertThat(hybrid).hasSameSizeAs(lexical);
		for (int index = 0; index < lexical.size(); index++) {
			QueryMeasurement<HybridRankedPaper> lexicalQuery = lexical.get(index);
			QueryMeasurement<HybridRankedPaper> vectorQuery = vector.get(index);
			QueryMeasurement<HybridRankedPaper> hybridQuery = hybrid.get(index);
			assertThat(vectorQuery.queryKey()).isEqualTo(lexicalQuery.queryKey());
			assertThat(hybridQuery.queryKey()).isEqualTo(lexicalQuery.queryKey());
			System.out.printf(
					Locale.ROOT,
					"related-hybrid-holdout-v1 query=%s cutoff=%d "
							+ "lexical=[recall=%.3f ndcg=%.3f p1=%.3f rr=%.3f ranked=%s] "
							+ "vector=[recall=%.3f ndcg=%.3f p1=%.3f rr=%.3f ranked=%s] "
							+ "hybrid=[recall=%.3f ndcg=%.3f p1=%.3f rr=%.3f ranked=%s] "
							+ "hybrid-minus-lexical=[recall=%+.3f ndcg=%+.3f p1=%+.3f rr=%+.3f]%n",
					lexicalQuery.queryKey(),
					lexicalQuery.cutoff(),
					lexicalQuery.recall(),
					lexicalQuery.ndcg(),
					lexicalQuery.precisionAtOne(),
					lexicalQuery.reciprocalRank(),
					rankedKeys(lexicalQuery),
					vectorQuery.recall(),
					vectorQuery.ndcg(),
					vectorQuery.precisionAtOne(),
					vectorQuery.reciprocalRank(),
					rankedKeys(vectorQuery),
					hybridQuery.recall(),
					hybridQuery.ndcg(),
					hybridQuery.precisionAtOne(),
					hybridQuery.reciprocalRank(),
					rankedKeys(hybridQuery),
					hybridQuery.recall() - lexicalQuery.recall(),
					hybridQuery.ndcg() - lexicalQuery.ndcg(),
					hybridQuery.precisionAtOne() - lexicalQuery.precisionAtOne(),
					hybridQuery.reciprocalRank() - lexicalQuery.reciprocalRank());
		}
		System.out.printf(
				Locale.ROOT,
				"related-hybrid-holdout-v1 macro "
						+ "lexical=[recall=%.3f ndcg=%.3f p1=%.3f mrr=%.3f] "
						+ "vector=[recall=%.3f ndcg=%.3f p1=%.3f mrr=%.3f] "
						+ "hybrid=[recall=%.3f ndcg=%.3f p1=%.3f mrr=%.3f] "
						+ "hybrid-minus-lexical=[recall=%+.3f ndcg=%+.3f p1=%+.3f mrr=%+.3f]%n",
				lexicalSummary.macroRecall(),
				lexicalSummary.macroNdcg(),
				lexicalSummary.macroPrecisionAtOne(),
				lexicalSummary.meanReciprocalRank(),
				vectorSummary.macroRecall(),
				vectorSummary.macroNdcg(),
				vectorSummary.macroPrecisionAtOne(),
				vectorSummary.meanReciprocalRank(),
				hybridSummary.macroRecall(),
				hybridSummary.macroNdcg(),
				hybridSummary.macroPrecisionAtOne(),
				hybridSummary.meanReciprocalRank(),
				hybridSummary.macroRecall() - lexicalSummary.macroRecall(),
				hybridSummary.macroNdcg() - lexicalSummary.macroNdcg(),
				hybridSummary.macroPrecisionAtOne() - lexicalSummary.macroPrecisionAtOne(),
				hybridSummary.meanReciprocalRank() - lexicalSummary.meanReciprocalRank());
	}

	private static List<String> rankedKeys(QueryMeasurement<HybridRankedPaper> measurement) {
		return measurement.ranked().stream().map(HybridRankedPaper::paperKey).toList();
	}

	static void assertAcceptanceCriteria(
			Acceptance acceptance,
			List<QueryMeasurement<HybridRankedPaper>> lexicalMeasurements,
			List<QueryMeasurement<HybridRankedPaper>> hybridMeasurements,
			Summary lexicalSummary,
			Summary hybridSummary) {
		Map<String, QueryMeasurement<HybridRankedPaper>> lexicalByQuery = lexicalMeasurements.stream()
				.collect(java.util.stream.Collectors.toMap(
						QueryMeasurement::queryKey,
						Function.identity(),
						(first, duplicate) -> {
							throw new IllegalStateException("Duplicate lexical query measurement");
						},
						LinkedHashMap::new));
		for (QueryMeasurement<HybridRankedPaper> hybrid : hybridMeasurements) {
			QueryMeasurement<HybridRankedPaper> lexical = lexicalByQuery.get(hybrid.queryKey());
			assertThat(lexical).as("lexical control for %s", hybrid.queryKey()).isNotNull();
			assertThat(hybrid.recall())
					.as("frozen hybrid Recall@%d for %s", hybrid.cutoff(), hybrid.queryKey())
					.isGreaterThanOrEqualTo(
							acceptance.minimumQueryRecall() - COMPARISON_EPSILON);
			assertThat(hybrid.ndcg())
					.as("frozen hybrid nDCG@%d for %s", hybrid.cutoff(), hybrid.queryKey())
					.isGreaterThanOrEqualTo(
							acceptance.minimumQueryNdcg() - COMPARISON_EPSILON);
			if (acceptance.preserveLexicalQueryRecall()) {
				assertThat(hybrid.recall())
						.as("frozen hybrid preserves lexical Recall@%d for %s",
								hybrid.cutoff(), hybrid.queryKey())
						.isGreaterThanOrEqualTo(lexical.recall() - COMPARISON_EPSILON);
			}
			assertThat(hybrid.ndcg() - lexical.ndcg())
					.as("per-query nDCG delta for %s", hybrid.queryKey())
					.isGreaterThanOrEqualTo(
							-acceptance.maximumPerQueryNdcgRegression() - COMPARISON_EPSILON);
		}

		if (acceptance.preserveLexicalMacroRecall()) {
			assertThat(hybridSummary.macroRecall())
					.as("frozen hybrid preserves lexical macro Recall@K")
					.isGreaterThanOrEqualTo(
							lexicalSummary.macroRecall() - COMPARISON_EPSILON);
		}
		assertThat(hybridSummary.macroNdcg() - lexicalSummary.macroNdcg())
				.as("frozen hybrid macro nDCG@K gain over lexical")
				.isGreaterThanOrEqualTo(
						acceptance.minimumMacroNdcgGainOverLexical() - COMPARISON_EPSILON);
		if (acceptance.preserveLexicalMacroPrecisionAtOne()) {
			assertThat(hybridSummary.macroPrecisionAtOne())
					.as("frozen hybrid preserves lexical macro Precision@1")
					.isGreaterThanOrEqualTo(
							lexicalSummary.macroPrecisionAtOne() - COMPARISON_EPSILON);
		}
		if (acceptance.preserveLexicalMeanReciprocalRank()) {
			assertThat(hybridSummary.meanReciprocalRank())
					.as("frozen hybrid preserves lexical mean reciprocal rank")
					.isGreaterThanOrEqualTo(
							lexicalSummary.meanReciprocalRank() - COMPARISON_EPSILON);
		}

		long strictNdcgImprovements = hybridMeasurements.stream()
				.filter(hybrid -> hybrid.ndcg()
						- lexicalByQuery.get(hybrid.queryKey()).ndcg() > COMPARISON_EPSILON)
				.count();
		long ndcgRegressions = hybridMeasurements.stream()
				.filter(hybrid -> hybrid.ndcg()
						- lexicalByQuery.get(hybrid.queryKey()).ndcg() < -COMPARISON_EPSILON)
				.count();
		assertThat(strictNdcgImprovements)
				.as("queries with a strict frozen-hybrid nDCG improvement")
				.isGreaterThanOrEqualTo(acceptance.minimumStrictNdcgImprovementQueryCount());
		assertThat(ndcgRegressions)
				.as("queries with a frozen-hybrid nDCG regression")
				.isLessThanOrEqualTo(acceptance.maximumNdcgRegressionQueryCount());
	}

	private static void printAcceptanceSummary(
			RelatedPaperHybridPolicy policy,
			List<QueryMeasurement<HybridRankedPaper>> lexicalMeasurements,
			List<QueryMeasurement<HybridRankedPaper>> hybridMeasurements,
			Summary lexicalSummary,
			Summary hybridSummary) {
		Map<String, Double> lexicalNdcgByQuery = lexicalMeasurements.stream()
				.collect(java.util.stream.Collectors.toMap(
						QueryMeasurement::queryKey,
						QueryMeasurement::ndcg));
		long strictNdcgImprovements = hybridMeasurements.stream()
				.filter(hybrid -> hybrid.ndcg()
						- lexicalNdcgByQuery.get(hybrid.queryKey()) > COMPARISON_EPSILON)
				.count();
		long ndcgRegressions = hybridMeasurements.stream()
				.filter(hybrid -> hybrid.ndcg()
						- lexicalNdcgByQuery.get(hybrid.queryKey()) < -COMPARISON_EPSILON)
				.count();
		Acceptance acceptance = policy.acceptance();
		System.out.printf(
				Locale.ROOT,
				"related-hybrid-holdout-v1 acceptance=PASS policy=%s "
						+ "macro-ndcg-gain=%.3f required-gain=%.3f "
						+ "strict-ndcg-improvements=%d required-improvements=%d "
						+ "ndcg-regressions=%d allowed-regressions=%d%n",
				policy.policyId(),
				hybridSummary.macroNdcg() - lexicalSummary.macroNdcg(),
				acceptance.minimumMacroNdcgGainOverLexical(),
				strictNdcgImprovements,
				acceptance.minimumStrictNdcgImprovementQueryCount(),
				ndcgRegressions,
				acceptance.maximumNdcgRegressionQueryCount());
	}

	private record LexicalFeatures(
			Map<String, Double> scores,
			Map<String, Integer> ranks,
			List<String> rankedKeys) {

		private LexicalFeatures {
			scores = Map.copyOf(scores);
			ranks = Map.copyOf(ranks);
			rankedKeys = List.copyOf(rankedKeys);
		}
	}

	private record QueryEvaluation(
			QueryMeasurement<HybridRankedPaper> lexical,
			QueryMeasurement<HybridRankedPaper> vector,
			QueryMeasurement<HybridRankedPaper> hybrid) {
	}
}
