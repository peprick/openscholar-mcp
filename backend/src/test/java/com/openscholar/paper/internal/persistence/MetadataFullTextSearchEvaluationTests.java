package com.openscholar.paper.internal.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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
import com.openscholar.paper.internal.persistence.RelatedPaperHybridScorer.HybridCandidateFeatures;
import com.openscholar.paper.internal.persistence.RelatedPaperHybridScorer.HybridRankedPaper;
import com.openscholar.paper.internal.persistence.RelatedPaperHybridEvaluationSummary.HybridWeightMeasurement;
import com.openscholar.paper.internal.persistence.RelatedPaperHybridScorer.VectorRankedPaper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@Transactional
class MetadataFullTextSearchEvaluationTests {

	private static final String FIXTURE_PATH =
			"search/relevance/related-metadata-baseline-v1.json";
	private static final String EXPECTED_FIXTURE_ID = "related-metadata-baseline-v1";
	private static final String EXPECTED_VECTOR_DIGEST =
			"ac6da0dfba84a81fdbfbaf330198c33cd77c4cdfc53e8bc50eb581914a15621d";
	private static final String EXPECTED_VECTOR_PROFILE_KEY =
			"paper-semantic-v1-" + EXPECTED_VECTOR_DIGEST + "-ollama-0-31-1";
	private static final String EXPECTED_VECTOR_MODEL_REVISION =
			"sha256:" + EXPECTED_VECTOR_DIGEST + ";ollama:0.31.1";
	private static final Instant RETRIEVED_AT = Instant.parse("2026-08-19T10:00:00Z");
	private static final double MINIMUM_QUERY_RECALL = 0.50d;
	private static final double MINIMUM_QUERY_NDCG = 0.60d;
	private static final double MINIMUM_MACRO_RECALL = 0.90d;
	private static final double MINIMUM_MACRO_NDCG = 0.80d;
	private static final double MINIMUM_MACRO_PRECISION_AT_ONE = 0.60d;
	private static final double MINIMUM_MACRO_MRR = 0.80d;
	private static final double MINIMUM_VECTOR_QUERY_RECALL = 0.90d;
	private static final double MINIMUM_VECTOR_QUERY_NDCG = 0.80d;
	private static final double MINIMUM_VECTOR_MACRO_RECALL = 0.95d;
	private static final double MINIMUM_VECTOR_MACRO_NDCG = 0.90d;
	private static final double MINIMUM_VECTOR_MACRO_PRECISION_AT_ONE = 0.80d;
	private static final double MINIMUM_VECTOR_MACRO_MRR = 0.90d;
	private static final List<Double> HYBRID_SEMANTIC_WEIGHTS =
			List.of(0.0d, 0.25d, 0.50d, 0.75d, 1.0d);

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

	@Test
	void metadataFixtureEstablishesAStableExplainableLexicalBaseline() throws Exception {
		RelatedPaperEvaluationFixture fixture = loadFixture();
		assertDevelopmentFixture(fixture);
		assertThat(fixture.rankingMethod())
				.isEqualTo(RelatedPaperMatch.POSTGRES_FULL_TEXT_REASON);

		SeededCorpus corpus = RelatedPaperEvaluationCorpus.seed(paperCatalog, fixture, RETRIEVED_AT);
		List<QueryMeasurement<String>> measurements = new ArrayList<>();

		for (EvaluationQuery query : fixture.queries()) {
			PaperView source = RelatedPaperEvaluationAssertions.assertValidQuery(
					query, corpus.papersByKey(), "evaluation");

			RelatedPapersView first = relatedPapers.findRelated(source.id(), query.cutoff());
			RelatedPapersView repeated = relatedPapers.findRelated(source.id(), query.cutoff());

			RelatedPaperEvaluationAssertions.assertStableExplainableResults(
					first, repeated, source.id(), fixture.rankingMethod());
			List<String> rankedKeys = first.results().stream()
					.map(match -> corpus.keyFor(match.paper().id(), query.key()))
					.toList();
			QueryMeasurement<String> measurement = RelatedPaperEvaluationMetrics.measure(
					query, rankedKeys, Function.identity());
			measurements.add(measurement);

			assertThat(measurement.recall())
					.as("Recall@%d for %s with ranking %s", query.cutoff(), query.key(), rankedKeys)
					.isGreaterThanOrEqualTo(MINIMUM_QUERY_RECALL);
			assertThat(measurement.ndcg())
					.as("nDCG@%d for %s with ranking %s", query.cutoff(), query.key(), rankedKeys)
					.isGreaterThanOrEqualTo(MINIMUM_QUERY_NDCG);
		}

		Summary summary = RelatedPaperEvaluationMetrics.summarize(measurements);
		double macroRecall = summary.macroRecall();
		double macroNdcg = summary.macroNdcg();
		double macroPrecisionAtOne = summary.macroPrecisionAtOne();
		double meanReciprocalRank = summary.meanReciprocalRank();
		assertThat(macroRecall).as("macro Recall@K for %s", measurements)
				.isGreaterThanOrEqualTo(MINIMUM_MACRO_RECALL);
		assertThat(macroNdcg).as("macro nDCG@K for %s", measurements)
				.isGreaterThanOrEqualTo(MINIMUM_MACRO_NDCG);
		assertThat(macroPrecisionAtOne).as("macro Precision@1 for %s", measurements)
				.isGreaterThanOrEqualTo(MINIMUM_MACRO_PRECISION_AT_ONE);
		assertThat(meanReciprocalRank).as("MRR for %s", measurements)
				.isGreaterThanOrEqualTo(MINIMUM_MACRO_MRR);

		measurements.forEach(measurement -> System.out.printf(
				Locale.ROOT,
				"related-metadata-baseline-v1 query=%s recall@%d=%.3f ndcg@%d=%.3f "
						+ "precision@1=%.3f reciprocal-rank=%.3f ranked=%s%n",
				measurement.queryKey(),
				measurement.cutoff(),
				measurement.recall(),
				measurement.cutoff(),
				measurement.ndcg(),
				measurement.precisionAtOne(),
				measurement.reciprocalRank(),
				measurement.ranked()));
		System.out.printf(
				Locale.ROOT,
				"related-metadata-baseline-v1 macro-recall=%.3f macro-ndcg=%.3f "
						+ "macro-precision@1=%.3f mrr=%.3f%n",
				macroRecall,
				macroNdcg,
				macroPrecisionAtOne,
				meanReciprocalRank);
	}

	@Test
	@EnabledIfEnvironmentVariable(named = "RUN_OLLAMA_VECTOR_EVALUATION", matches = "true")
	@Transactional(propagation = Propagation.NOT_SUPPORTED)
	@DirtiesContext(methodMode = DirtiesContext.MethodMode.AFTER_METHOD)
	void metadataFixtureMeasuresExactVectorAndExploratoryHybridRetrievalWithPinnedOllama()
			throws Exception {
		RelatedPaperEvaluationFixture fixture = loadFixture();
		assertDevelopmentFixture(fixture);
		assertThat(fixture.papers()).hasSize(18);
		assertThat(HYBRID_SEMANTIC_WEIGHTS)
				.containsExactly(0.0d, 0.25d, 0.50d, 0.75d, 1.0d)
				.doesNotHaveDuplicates()
				.allSatisfy(weight -> assertThat(weight).isBetween(0.0d, 1.0d));

		SeededCorpus corpus = RelatedPaperEvaluationCorpus.seed(paperCatalog, fixture, RETRIEVED_AT);

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
		EmbeddingBackfillResult backfill = embeddingBackfill.run(new EmbeddingBackfillCommand(
				profile.profileKey(), null, fixture.papers().size(), 2));
		assertThat(backfill.profileKey()).isEqualTo(profile.profileKey());
		assertThat(backfill.disposition()).isEqualTo(EmbeddingBackfillDisposition.COMPLETED);
		assertThat(backfill.scannedCount()).isEqualTo(18);
		assertThat(backfill.storedCount()).isEqualTo(18);
		assertThat(backfill.unchangedCount()).isZero();
		assertThat(backfill.deletedCount()).isZero();
		assertThat(backfill.failures()).isEmpty();
		assertThat(backfill.nextCursor()).isNull();

		System.out.printf(
				Locale.ROOT,
				"related-vector-baseline-v1 profile=%s provider=%s model=%s revision=%s "
						+ "content-kind=%s input-policy-version=%d dimensions=%d distance=%s%n",
				profile.profileKey(),
				profile.provider(),
				profile.model(),
				profile.modelRevision(),
				profile.contentKind(),
				profile.inputPolicyVersion(),
				profile.dimensions(),
				profile.distanceMetric());

		int candidateCount = fixture.papers().size() - 1;
		List<QueryMeasurement<VectorRankedPaper>> measurements = new ArrayList<>();
		Map<Double, List<QueryMeasurement<HybridRankedPaper>>> hybridMeasurements =
				new LinkedHashMap<>();
		HYBRID_SEMANTIC_WEIGHTS.forEach(
				weight -> hybridMeasurements.put(weight, new ArrayList<>()));
		for (EvaluationQuery query : fixture.queries()) {
			PaperView source = RelatedPaperEvaluationAssertions.assertValidQuery(
					query, corpus.papersByKey(), "vector evaluation");

			List<PaperEmbeddingMatch> first = embeddingStore.findNearestExact(
					source.id(), profile.profileKey(), candidateCount);
			List<PaperEmbeddingMatch> repeated = embeddingStore.findNearestExact(
					source.id(), profile.profileKey(), candidateCount);
			RelatedPaperEvaluationAssertions.assertStableExactVectorResults(
					first, repeated, source.id(), candidateCount);

			List<VectorRankedPaper> allVectorCandidates = first.stream()
					.map(match -> new VectorRankedPaper(
							corpus.keyFor(match.paperId(), query.key()),
							match.cosineSimilarity()))
					.toList();
			List<VectorRankedPaper> ranked = allVectorCandidates.stream()
					.limit(query.cutoff())
					.toList();
			QueryMeasurement<VectorRankedPaper> measurement =
					RelatedPaperEvaluationMetrics.measure(query, ranked, VectorRankedPaper::paperKey);
			measurements.add(measurement);

			assertThat(measurement.recall())
					.as("vector Recall@%d for %s with ranking %s", query.cutoff(), query.key(), ranked)
					.isGreaterThanOrEqualTo(MINIMUM_VECTOR_QUERY_RECALL);
			assertThat(measurement.ndcg())
					.as("vector nDCG@%d for %s with ranking %s", query.cutoff(), query.key(), ranked)
					.isGreaterThanOrEqualTo(MINIMUM_VECTOR_QUERY_NDCG);

			measureExploratoryHybrid(
					query,
					source,
					corpus,
					allVectorCandidates,
					candidateCount,
					hybridMeasurements);
		}

		Summary summary = RelatedPaperEvaluationMetrics.summarize(measurements);
		double macroRecall = summary.macroRecall();
		double macroNdcg = summary.macroNdcg();
		double macroPrecisionAtOne = summary.macroPrecisionAtOne();
		double meanReciprocalRank = summary.meanReciprocalRank();
		assertThat(macroRecall).as("vector macro Recall@K for %s", measurements)
				.isGreaterThanOrEqualTo(MINIMUM_VECTOR_MACRO_RECALL);
		assertThat(macroNdcg).as("vector macro nDCG@K for %s", measurements)
				.isGreaterThanOrEqualTo(MINIMUM_VECTOR_MACRO_NDCG);
		assertThat(macroPrecisionAtOne).as("vector macro Precision@1 for %s", measurements)
				.isGreaterThanOrEqualTo(MINIMUM_VECTOR_MACRO_PRECISION_AT_ONE);
		assertThat(meanReciprocalRank).as("vector MRR for %s", measurements)
				.isGreaterThanOrEqualTo(MINIMUM_VECTOR_MACRO_MRR);

		measurements.forEach(measurement -> System.out.printf(
				Locale.ROOT,
				"related-vector-baseline-v1 query=%s recall@%d=%.3f ndcg@%d=%.3f "
						+ "precision@1=%.3f reciprocal-rank=%.3f ranked=%s%n",
				measurement.queryKey(),
				measurement.cutoff(),
				measurement.recall(),
				measurement.cutoff(),
				measurement.ndcg(),
				measurement.precisionAtOne(),
				measurement.reciprocalRank(),
				measurement.ranked()));
		System.out.printf(
				Locale.ROOT,
				"related-vector-baseline-v1 macro-recall=%.3f macro-ndcg=%.3f "
						+ "macro-precision@1=%.3f mrr=%.3f%n",
				macroRecall,
				macroNdcg,
				macroPrecisionAtOne,
				meanReciprocalRank);

		List<HybridWeightMeasurement> hybridWeights = HYBRID_SEMANTIC_WEIGHTS.stream()
				.map(weight -> RelatedPaperHybridEvaluationSummary.summarize(
						weight, hybridMeasurements.get(weight)))
				.toList();
		HybridWeightMeasurement semanticEndpoint = hybridWeights.getLast();
		assertThat(semanticEndpoint.macroRecall()).isEqualTo(macroRecall);
		assertThat(semanticEndpoint.macroNdcg()).isEqualTo(macroNdcg);
		assertThat(semanticEndpoint.macroPrecisionAtOne()).isEqualTo(macroPrecisionAtOne);
		assertThat(semanticEndpoint.meanReciprocalRank()).isEqualTo(meanReciprocalRank);

		hybridWeights.forEach(weight -> {
			weight.queries().forEach(query -> System.out.printf(
					Locale.ROOT,
					"related-hybrid-exploration-v1 semantic-weight=%.2f query=%s "
							+ "recall@%d=%.3f ndcg@%d=%.3f precision@1=%.3f "
							+ "reciprocal-rank=%.3f ranked=%s%n",
					weight.semanticWeight(),
					query.queryKey(),
					query.cutoff(),
					query.recall(),
					query.cutoff(),
					query.ndcg(),
					query.precisionAtOne(),
					query.reciprocalRank(),
					query.ranked()));
			System.out.printf(
					Locale.ROOT,
					"related-hybrid-exploration-v1 semantic-weight=%.2f macro-recall=%.3f "
							+ "macro-ndcg=%.3f macro-precision@1=%.3f mrr=%.3f%n",
					weight.semanticWeight(),
					weight.macroRecall(),
					weight.macroNdcg(),
					weight.macroPrecisionAtOne(),
					weight.meanReciprocalRank());
		});
	}

	private RelatedPaperEvaluationFixture loadFixture() throws Exception {
		return RelatedPaperEvaluationFixture.load(objectMapper, FIXTURE_PATH);
	}

	private static void assertDevelopmentFixture(RelatedPaperEvaluationFixture fixture) {
		assertThat(fixture.fixtureId()).isEqualTo(EXPECTED_FIXTURE_ID);
		assertThat(fixture.split()).isEqualTo(Split.DEVELOPMENT);
		assertThat(fixture.version()).isEqualTo(1);
		RelatedPaperEvaluationAssertions.assertWellFormed(fixture);
	}

	private void measureExploratoryHybrid(
			EvaluationQuery query,
			PaperView source,
			SeededCorpus corpus,
			List<VectorRankedPaper> vectorCandidates,
			int candidateCount,
			Map<Double, List<QueryMeasurement<HybridRankedPaper>>> measurementsByWeight) {
		RelatedPapersView lexical = relatedPapers.findRelated(source.id(), candidateCount);
		RelatedPapersView repeatedLexical = relatedPapers.findRelated(source.id(), candidateCount);
		RelatedPaperEvaluationAssertions.assertStableExplainableResults(
				lexical, repeatedLexical, source.id(), RelatedPaperMatch.POSTGRES_FULL_TEXT_REASON);
		assertThat(lexical.results()).hasSizeLessThanOrEqualTo(candidateCount);
		Map<String, Double> lexicalScores = new LinkedHashMap<>();
		Map<String, Integer> lexicalRanks = new LinkedHashMap<>();
		for (RelatedPaperMatch match : lexical.results()) {
			String paperKey = corpus.keyFor(match.paper().id(), query.key());
			assertThat(lexicalScores.put(paperKey, match.score())).isNull();
			assertThat(lexicalRanks.put(paperKey, match.rank())).isNull();
			assertThat(match.score()).isBetween(0.0d, 1.0d);
		}

		List<HybridCandidateFeatures> candidates = RelatedPaperHybridScorer.combine(
				vectorCandidates, lexicalScores, lexicalRanks, candidateCount);
		assertThat(candidates).hasSize(candidateCount).allSatisfy(candidate -> {
			assertThat(candidate.lexicalScore()).isBetween(0.0d, 1.0d);
			assertThat(candidate.semanticScore()).isBetween(0.0d, 1.0d);
			assertThat(candidate.cosineSimilarity()).isBetween(-1.000001d, 1.000001d);
		});
		assertThat(candidates).extracting(HybridCandidateFeatures::paperKey)
				.doesNotContain(query.sourceKey())
				.doesNotHaveDuplicates()
				.containsAll(lexicalScores.keySet());
		System.out.printf(
				Locale.ROOT,
				"related-hybrid-exploration-v1 query=%s pool=%d lexical-matches=%d "
						+ "lexical-formula=ts_rank_cd-normalization-32 semantic-formula=(cosine+1)/2 "
						+ "candidates=%s%n",
				query.key(),
				candidates.size(),
				lexical.results().size(),
				candidates);

		List<String> vectorEndpoint = vectorCandidates.stream()
				.limit(query.cutoff())
				.map(VectorRankedPaper::paperKey)
				.toList();
		List<String> lexicalEndpoint = lexical.results().stream()
				.limit(query.cutoff())
				.map(match -> corpus.keyFor(match.paper().id(), query.key()))
				.toList();

		for (double semanticWeight : HYBRID_SEMANTIC_WEIGHTS) {
			List<HybridRankedPaper> ranked = RelatedPaperHybridScorer.rankHybridCandidates(
					candidates, semanticWeight, query.cutoff());
			RelatedPaperEvaluationAssertions.assertHybridRanking(
					ranked, query.sourceKey(), query.cutoff(), semanticWeight);
			List<String> rankedKeys = ranked.stream().map(HybridRankedPaper::paperKey).toList();
			if (semanticWeight == 1.0d) {
				assertThat(rankedKeys).containsExactlyElementsOf(vectorEndpoint);
			}
			if (semanticWeight == 0.0d) {
				assertThat(rankedKeys).containsExactlyElementsOf(lexicalEndpoint);
			}

			List<QueryMeasurement<HybridRankedPaper>> measurements =
					measurementsByWeight.get(semanticWeight);
			assertThat(measurements).isNotNull();
			measurements.add(RelatedPaperEvaluationMetrics.measure(
					query, ranked, HybridRankedPaper::paperKey));
		}
	}
}
