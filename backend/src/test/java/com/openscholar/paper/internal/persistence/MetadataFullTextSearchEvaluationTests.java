package com.openscholar.paper.internal.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.IntStream;

import com.openscholar.TestcontainersConfiguration;
import com.openscholar.embedding.EmbeddingGenerator;
import com.openscholar.jobs.EmbeddingBackfillCommand;
import com.openscholar.jobs.EmbeddingBackfillDisposition;
import com.openscholar.jobs.EmbeddingBackfillResult;
import com.openscholar.jobs.EmbeddingBackfillUseCase;
import com.openscholar.paper.CanonicalPaperCandidate;
import com.openscholar.paper.DocumentType;
import com.openscholar.paper.EmbeddingContentKind;
import com.openscholar.paper.EmbeddingDistanceMetric;
import com.openscholar.paper.PaperCatalog;
import com.openscholar.paper.PaperEmbeddingMatch;
import com.openscholar.paper.PaperEmbeddingStore;
import com.openscholar.paper.PaperIdentifier;
import com.openscholar.paper.PaperIdentifierType;
import com.openscholar.paper.PaperView;
import com.openscholar.paper.ProviderRecordCandidate;
import com.openscholar.paper.RelatedPaperMatch;
import com.openscholar.paper.RelatedPaperUseCase;
import com.openscholar.paper.RelatedPapersView;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ClassPathResource;
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
		EvaluationFixture fixture = loadFixture();
		assertThat(fixture.version()).isEqualTo(1);
		assertThat(fixture.rankingMethod())
				.isEqualTo(RelatedPaperMatch.POSTGRES_FULL_TEXT_REASON);
		assertThat(fixture.papers()).extracting(FixturePaper::key).doesNotHaveDuplicates();
		assertThat(fixture.queries()).extracting(EvaluationQuery::key).doesNotHaveDuplicates();

		Map<String, PaperView> papersByKey = savePapers(fixture.papers());
		Map<UUID, String> keysByPaperId = new LinkedHashMap<>();
		papersByKey.forEach((key, paper) -> keysByPaperId.put(paper.id(), key));
		List<QueryMeasurement> measurements = new ArrayList<>();

		for (EvaluationQuery query : fixture.queries()) {
			PaperView source = papersByKey.get(query.sourceKey());
			assertThat(source)
					.as("source paper for evaluation query %s", query.key())
					.isNotNull();
			assertThat(query.judgments())
					.as("judgments for evaluation query %s", query.key())
					.isNotEmpty()
					.doesNotContainKey(query.sourceKey());
			assertThat(query.judgments().keySet())
					.as("judged papers for evaluation query %s", query.key())
					.allSatisfy(key -> assertThat(papersByKey).containsKey(key));

			RelatedPapersView first = relatedPapers.findRelated(source.id(), query.cutoff());
			RelatedPapersView repeated = relatedPapers.findRelated(source.id(), query.cutoff());

			assertStableExplainableResults(first, repeated, source.id(), fixture.rankingMethod());
			List<String> rankedKeys = first.results().stream()
					.map(match -> keyFor(keysByPaperId, match.paper().id(), query.key()))
					.toList();
			double recall = recallAt(rankedKeys, query.judgments(), query.cutoff());
			double ndcg = ndcgAt(rankedKeys, query.judgments(), query.cutoff());
			double precisionAtOne = precisionAtOne(rankedKeys, query.judgments());
			double reciprocalRank = reciprocalRank(rankedKeys, query.judgments());
			measurements.add(new QueryMeasurement(
					query.key(),
					query.cutoff(),
					recall,
					ndcg,
					precisionAtOne,
					reciprocalRank,
					rankedKeys));

			assertThat(recall)
					.as("Recall@%d for %s with ranking %s", query.cutoff(), query.key(), rankedKeys)
					.isGreaterThanOrEqualTo(MINIMUM_QUERY_RECALL);
			assertThat(ndcg)
					.as("nDCG@%d for %s with ranking %s", query.cutoff(), query.key(), rankedKeys)
					.isGreaterThanOrEqualTo(MINIMUM_QUERY_NDCG);
		}

		double macroRecall = measurements.stream()
				.mapToDouble(QueryMeasurement::recall)
				.average()
				.orElseThrow();
		double macroNdcg = measurements.stream()
				.mapToDouble(QueryMeasurement::ndcg)
				.average()
				.orElseThrow();
		double macroPrecisionAtOne = measurements.stream()
				.mapToDouble(QueryMeasurement::precisionAtOne)
				.average()
				.orElseThrow();
		double meanReciprocalRank = measurements.stream()
				.mapToDouble(QueryMeasurement::reciprocalRank)
				.average()
				.orElseThrow();
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
				measurement.rankedKeys()));
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
		EvaluationFixture fixture = loadFixture();
		assertThat(fixture.version()).isEqualTo(1);
		assertThat(fixture.papers()).hasSize(18);
		assertThat(fixture.papers()).extracting(FixturePaper::key).doesNotHaveDuplicates();
		assertThat(fixture.queries()).extracting(EvaluationQuery::key).doesNotHaveDuplicates();
		assertThat(HYBRID_SEMANTIC_WEIGHTS)
				.containsExactly(0.0d, 0.25d, 0.50d, 0.75d, 1.0d)
				.doesNotHaveDuplicates()
				.allSatisfy(weight -> assertThat(weight).isBetween(0.0d, 1.0d));

		Map<String, PaperView> papersByKey = savePapers(fixture.papers());
		Map<UUID, String> keysByPaperId = new LinkedHashMap<>();
		papersByKey.forEach((key, paper) -> keysByPaperId.put(paper.id(), key));

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
		List<VectorQueryMeasurement> measurements = new ArrayList<>();
		Map<Double, List<HybridQueryMeasurement>> hybridMeasurements = new LinkedHashMap<>();
		HYBRID_SEMANTIC_WEIGHTS.forEach(
				weight -> hybridMeasurements.put(weight, new ArrayList<>()));
		for (EvaluationQuery query : fixture.queries()) {
			PaperView source = papersByKey.get(query.sourceKey());
			assertThat(source)
					.as("source paper for vector evaluation query %s", query.key())
					.isNotNull();
			assertThat(query.judgments())
					.as("judgments for vector evaluation query %s", query.key())
					.isNotEmpty()
					.doesNotContainKey(query.sourceKey());
			assertThat(query.judgments().keySet())
					.as("judged papers for vector evaluation query %s", query.key())
					.allSatisfy(key -> assertThat(papersByKey).containsKey(key));

			List<PaperEmbeddingMatch> first = embeddingStore.findNearest(
					source.id(), profile.profileKey(), candidateCount);
			List<PaperEmbeddingMatch> repeated = embeddingStore.findNearest(
					source.id(), profile.profileKey(), candidateCount);
			assertStableExactVectorResults(first, repeated, source.id(), candidateCount);

			List<VectorRankedPaper> allVectorCandidates = first.stream()
					.map(match -> new VectorRankedPaper(
							keyFor(keysByPaperId, match.paperId(), query.key()),
							match.cosineSimilarity()))
					.toList();
			List<VectorRankedPaper> ranked = allVectorCandidates.stream()
					.limit(query.cutoff())
					.toList();
			List<String> rankedKeys = ranked.stream().map(VectorRankedPaper::paperKey).toList();
			double recall = recallAt(rankedKeys, query.judgments(), query.cutoff());
			double ndcg = ndcgAt(rankedKeys, query.judgments(), query.cutoff());
			measurements.add(new VectorQueryMeasurement(
					query.key(),
					query.cutoff(),
					recall,
					ndcg,
					precisionAtOne(rankedKeys, query.judgments()),
					reciprocalRank(rankedKeys, query.judgments()),
					ranked));

			assertThat(recall)
					.as("vector Recall@%d for %s with ranking %s", query.cutoff(), query.key(), ranked)
					.isGreaterThanOrEqualTo(MINIMUM_VECTOR_QUERY_RECALL);
			assertThat(ndcg)
					.as("vector nDCG@%d for %s with ranking %s", query.cutoff(), query.key(), ranked)
					.isGreaterThanOrEqualTo(MINIMUM_VECTOR_QUERY_NDCG);

			measureExploratoryHybrid(
					query,
					source,
					keysByPaperId,
					allVectorCandidates,
					candidateCount,
					hybridMeasurements);
		}

		double macroRecall = measurements.stream()
				.mapToDouble(VectorQueryMeasurement::recall)
				.average()
				.orElseThrow();
		double macroNdcg = measurements.stream()
				.mapToDouble(VectorQueryMeasurement::ndcg)
				.average()
				.orElseThrow();
		double macroPrecisionAtOne = measurements.stream()
				.mapToDouble(VectorQueryMeasurement::precisionAtOne)
				.average()
				.orElseThrow();
		double meanReciprocalRank = measurements.stream()
				.mapToDouble(VectorQueryMeasurement::reciprocalRank)
				.average()
				.orElseThrow();
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
				.map(weight -> summarizeHybridWeight(weight, hybridMeasurements.get(weight)))
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

	private EvaluationFixture loadFixture() throws Exception {
		ClassPathResource resource = new ClassPathResource(FIXTURE_PATH);
		try (InputStream input = resource.getInputStream()) {
			return objectMapper.readValue(input, EvaluationFixture.class);
		}
	}

	private Map<String, PaperView> savePapers(List<FixturePaper> fixturePapers) {
		Map<String, PaperView> papers = new LinkedHashMap<>();
		int index = 1;
		for (FixturePaper fixturePaper : fixturePapers) {
			String providerRecordId = "W-EVAL-RELATED-%03d".formatted(index++);
			CanonicalPaperCandidate candidate = new CanonicalPaperCandidate(
					fixturePaper.title(),
					fixturePaper.abstractText(),
					null,
					fixturePaper.year(),
					fixturePaper.type(),
					fixturePaper.language(),
					"OpenScholar synthetic relevance fixture",
					fixturePaper.citationCount(),
					RETRIEVED_AT,
					List.of(new PaperIdentifier(
							PaperIdentifierType.OPENALEX, "", providerRecordId)),
					List.of());
			ProviderRecordCandidate providerRecord = new ProviderRecordCandidate(
					"OpenAlex",
					providerRecordId,
					RETRIEVED_AT,
					RETRIEVED_AT,
					URI.create("https://openalex.org/" + providerRecordId),
					true,
					URI.create("https://openalex.org/" + providerRecordId),
					null,
					Map.of("fixtureKey", fixturePaper.key()));
			PaperView paper = paperCatalog.upsert(candidate, providerRecord, RETRIEVED_AT);
			assertThat(papers.put(fixturePaper.key(), paper))
					.as("duplicate fixture paper key %s", fixturePaper.key())
					.isNull();
		}
		return papers;
	}

	private void measureExploratoryHybrid(
			EvaluationQuery query,
			PaperView source,
			Map<UUID, String> keysByPaperId,
			List<VectorRankedPaper> vectorCandidates,
			int candidateCount,
			Map<Double, List<HybridQueryMeasurement>> measurementsByWeight) {
		RelatedPapersView lexical = relatedPapers.findRelated(source.id(), candidateCount);
		RelatedPapersView repeatedLexical = relatedPapers.findRelated(source.id(), candidateCount);
		assertStableExplainableResults(
				lexical, repeatedLexical, source.id(), RelatedPaperMatch.POSTGRES_FULL_TEXT_REASON);
		assertThat(lexical.results()).hasSizeLessThanOrEqualTo(candidateCount);
		Map<String, Double> lexicalScores = new LinkedHashMap<>();
		Map<String, Integer> lexicalRanks = new LinkedHashMap<>();
		for (RelatedPaperMatch match : lexical.results()) {
			String paperKey = keyFor(keysByPaperId, match.paper().id(), query.key());
			assertThat(lexicalScores.put(paperKey, match.score())).isNull();
			assertThat(lexicalRanks.put(paperKey, match.rank())).isNull();
			assertThat(match.score()).isBetween(0.0d, 1.0d);
		}

		List<HybridCandidateFeatures> candidates = IntStream.range(0, vectorCandidates.size())
				.mapToObj(index -> {
					VectorRankedPaper vector = vectorCandidates.get(index);
					return new HybridCandidateFeatures(
							vector.paperKey(),
							lexicalScores.getOrDefault(vector.paperKey(), 0.0d),
							boundedCosineScore(vector.cosineSimilarity()),
							vector.cosineSimilarity(),
							lexicalRanks.getOrDefault(vector.paperKey(), candidateCount + 1),
							index + 1);
				})
				.toList();
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
				.map(match -> keyFor(keysByPaperId, match.paper().id(), query.key()))
				.toList();

		for (double semanticWeight : HYBRID_SEMANTIC_WEIGHTS) {
			List<HybridRankedPaper> ranked =
					rankHybridCandidates(candidates, semanticWeight, query.cutoff());
			assertHybridRanking(ranked, query.sourceKey(), query.cutoff(), semanticWeight);
			List<String> rankedKeys = ranked.stream().map(HybridRankedPaper::paperKey).toList();
			if (semanticWeight == 1.0d) {
				assertThat(rankedKeys).containsExactlyElementsOf(vectorEndpoint);
			}
			if (semanticWeight == 0.0d) {
				assertThat(rankedKeys).containsExactlyElementsOf(lexicalEndpoint);
			}

			List<HybridQueryMeasurement> measurements = measurementsByWeight.get(semanticWeight);
			assertThat(measurements).isNotNull();
			measurements.add(new HybridQueryMeasurement(
					query.key(),
					query.cutoff(),
					recallAt(rankedKeys, query.judgments(), query.cutoff()),
					ndcgAt(rankedKeys, query.judgments(), query.cutoff()),
					precisionAtOne(rankedKeys, query.judgments()),
					reciprocalRank(rankedKeys, query.judgments()),
					ranked));
		}
	}

	private static List<HybridRankedPaper> rankHybridCandidates(
			List<HybridCandidateFeatures> candidates, double semanticWeight, int cutoff) {
		return candidates.stream()
				.map(candidate -> new HybridRankedPaper(
						candidate.paperKey(),
						weightedHybridScore(candidate, semanticWeight),
						candidate.lexicalScore(),
						candidate.semanticScore(),
						candidate.cosineSimilarity(),
						candidate.lexicalRank(),
						candidate.semanticRank()))
				.filter(candidate -> candidate.hybridScore() > 0.0d)
				.sorted(hybridComparator(semanticWeight))
				.limit(cutoff)
				.toList();
	}

	private static void assertHybridRanking(
			List<HybridRankedPaper> ranked,
			String sourceKey,
			int cutoff,
			double semanticWeight) {
		assertThat(ranked).isNotEmpty().hasSizeLessThanOrEqualTo(cutoff);
		assertThat(ranked).extracting(HybridRankedPaper::paperKey)
				.doesNotContain(sourceKey)
				.doesNotHaveDuplicates();
		assertThat(ranked).allSatisfy(candidate -> {
			assertThat(Double.isFinite(candidate.hybridScore())).isTrue();
			assertThat(candidate.hybridScore()).isBetween(0.0d, 1.0d);
			assertThat(candidate.hybridScore())
					.isEqualTo(semanticWeight * candidate.semanticScore()
							+ (1.0d - semanticWeight) * candidate.lexicalScore());
		});
		for (int index = 1; index < ranked.size(); index++) {
			assertThat(ranked.get(index - 1).hybridScore())
					.isGreaterThanOrEqualTo(ranked.get(index).hybridScore());
		}
	}

	private static double boundedCosineScore(double cosineSimilarity) {
		return Math.max(0.0d, Math.min(1.0d, (cosineSimilarity + 1.0d) / 2.0d));
	}

	private static double weightedHybridScore(
			HybridCandidateFeatures candidate, double semanticWeight) {
		return semanticWeight * candidate.semanticScore()
				+ (1.0d - semanticWeight) * candidate.lexicalScore();
	}

	private static Comparator<HybridRankedPaper> hybridComparator(double semanticWeight) {
		Comparator<HybridRankedPaper> comparator = Comparator
				.comparingDouble(HybridRankedPaper::hybridScore)
				.reversed();
		if (semanticWeight == 0.0d) {
			return comparator.thenComparingInt(HybridRankedPaper::lexicalRank)
					.thenComparing(HybridRankedPaper::paperKey);
		}
		if (semanticWeight == 1.0d) {
			return comparator.thenComparingInt(HybridRankedPaper::semanticRank)
					.thenComparing(HybridRankedPaper::paperKey);
		}
		return comparator.thenComparing(HybridRankedPaper::paperKey);
	}

	private static HybridWeightMeasurement summarizeHybridWeight(
			double semanticWeight, List<HybridQueryMeasurement> queries) {
		assertThat(queries).isNotEmpty();
		return new HybridWeightMeasurement(
				semanticWeight,
				queries.stream()
						.mapToDouble(HybridQueryMeasurement::recall)
						.average()
						.orElseThrow(),
				queries.stream()
						.mapToDouble(HybridQueryMeasurement::ndcg)
						.average()
						.orElseThrow(),
				queries.stream()
						.mapToDouble(HybridQueryMeasurement::precisionAtOne)
						.average()
						.orElseThrow(),
				queries.stream()
						.mapToDouble(HybridQueryMeasurement::reciprocalRank)
						.average()
						.orElseThrow(),
				List.copyOf(queries));
	}

	private static void assertStableExplainableResults(
			RelatedPapersView first,
			RelatedPapersView repeated,
			UUID sourcePaperId,
			String rankingMethod) {
		assertThat(first.sourcePaperId()).isEqualTo(sourcePaperId);
		assertThat(first.results()).extracting(match -> match.paper().id())
				.doesNotContain(sourcePaperId)
				.doesNotHaveDuplicates();
		assertThat(first.results()).extracting(RelatedPaperMatch::rank)
				.containsExactlyElementsOf(
						IntStream.rangeClosed(1, first.results().size()).boxed().toList());
		assertThat(first.results()).allSatisfy(match -> {
			assertThat(Double.isFinite(match.score())).isTrue();
			assertThat(match.score()).isPositive();
			assertThat(match.rankingReasons()).containsExactly(rankingMethod);
		});
		for (int index = 1; index < first.results().size(); index++) {
			assertThat(first.results().get(index - 1).score())
					.isGreaterThanOrEqualTo(first.results().get(index).score());
		}
		assertThat(repeated.sourcePaperId()).isEqualTo(first.sourcePaperId());
		assertThat(repeated.results()).extracting(match -> match.paper().id())
				.containsExactlyElementsOf(
						first.results().stream().map(match -> match.paper().id()).toList());
		assertThat(repeated.results()).extracting(RelatedPaperMatch::score)
				.containsExactlyElementsOf(first.results().stream().map(RelatedPaperMatch::score).toList());
	}

	private static void assertStableExactVectorResults(
			List<PaperEmbeddingMatch> first,
			List<PaperEmbeddingMatch> repeated,
			UUID sourcePaperId,
			int cutoff) {
		assertThat(first).hasSize(cutoff);
		assertThat(first).extracting(PaperEmbeddingMatch::paperId)
				.doesNotContain(sourcePaperId)
				.doesNotHaveDuplicates();
		assertThat(first).extracting(PaperEmbeddingMatch::rank)
				.containsExactlyElementsOf(IntStream.rangeClosed(1, cutoff).boxed().toList());
		assertThat(first).allSatisfy(match -> {
			assertThat(Double.isFinite(match.cosineSimilarity())).isTrue();
			assertThat(match.cosineSimilarity()).isBetween(-1.000001d, 1.000001d);
		});
		for (int index = 1; index < first.size(); index++) {
			assertThat(first.get(index - 1).cosineSimilarity())
					.isGreaterThanOrEqualTo(first.get(index).cosineSimilarity());
		}
		assertThat(repeated).containsExactlyElementsOf(first);
	}

	private static String keyFor(Map<UUID, String> keysByPaperId, UUID paperId, String queryKey) {
		String key = keysByPaperId.get(paperId);
		assertThat(key)
				.as("ranked paper %s for query %s belongs to the evaluation corpus", paperId, queryKey)
				.isNotNull();
		return key;
	}

	private static double recallAt(
			List<String> rankedKeys, Map<String, Integer> judgments, int cutoff) {
		Set<String> relevant = judgments.entrySet().stream()
				.filter(entry -> entry.getValue() > 0)
				.map(Map.Entry::getKey)
				.collect(java.util.stream.Collectors.toUnmodifiableSet());
		assertThat(relevant).isNotEmpty();
		long retrievedRelevant = rankedKeys.stream()
				.limit(cutoff)
				.filter(relevant::contains)
				.distinct()
				.count();
		return (double) retrievedRelevant / relevant.size();
	}

	private static double ndcgAt(
			List<String> rankedKeys, Map<String, Integer> judgments, int cutoff) {
		double actualDcg = IntStream.range(0, Math.min(cutoff, rankedKeys.size()))
				.mapToDouble(index -> discountedGain(
						judgments.getOrDefault(rankedKeys.get(index), 0), index))
				.sum();
		List<Integer> idealGrades = judgments.values().stream()
				.filter(grade -> grade > 0)
				.sorted(Comparator.reverseOrder())
				.limit(cutoff)
				.toList();
		double idealDcg = IntStream.range(0, idealGrades.size())
				.mapToDouble(index -> discountedGain(idealGrades.get(index), index))
				.sum();
		assertThat(idealDcg).isPositive();
		return actualDcg / idealDcg;
	}

	private static double precisionAtOne(
			List<String> rankedKeys, Map<String, Integer> judgments) {
		return !rankedKeys.isEmpty() && judgments.getOrDefault(rankedKeys.getFirst(), 0) > 0
				? 1.0d
				: 0.0d;
	}

	private static double reciprocalRank(
			List<String> rankedKeys, Map<String, Integer> judgments) {
		return IntStream.range(0, rankedKeys.size())
				.filter(index -> judgments.getOrDefault(rankedKeys.get(index), 0) > 0)
				.mapToDouble(index -> 1.0d / (index + 1.0d))
				.findFirst()
				.orElse(0.0d);
	}

	private static double discountedGain(int grade, int zeroBasedRank) {
		if (grade <= 0) {
			return 0.0d;
		}
		double gain = Math.pow(2.0d, grade) - 1.0d;
		double discount = Math.log(zeroBasedRank + 2.0d) / Math.log(2.0d);
		return gain / discount;
	}

	private record EvaluationFixture(
			int version,
			String rankingMethod,
			List<FixturePaper> papers,
			List<EvaluationQuery> queries) {
	}

	private record FixturePaper(
			String key,
			String title,
			String abstractText,
			Integer year,
			DocumentType type,
			String language,
			Integer citationCount) {
	}

	private record EvaluationQuery(
			String key,
			String sourceKey,
			int cutoff,
			Map<String, Integer> judgments) {
	}

	private record QueryMeasurement(
			String queryKey,
			int cutoff,
			double recall,
			double ndcg,
			double precisionAtOne,
			double reciprocalRank,
			List<String> rankedKeys) {
	}

	private record VectorRankedPaper(String paperKey, double cosineSimilarity) {
	}

	private record VectorQueryMeasurement(
			String queryKey,
			int cutoff,
			double recall,
			double ndcg,
			double precisionAtOne,
			double reciprocalRank,
			List<VectorRankedPaper> ranked) {
	}

	private record HybridCandidateFeatures(
			String paperKey,
			double lexicalScore,
			double semanticScore,
			double cosineSimilarity,
			int lexicalRank,
			int semanticRank) {
	}

	private record HybridRankedPaper(
			String paperKey,
			double hybridScore,
			double lexicalScore,
			double semanticScore,
			double cosineSimilarity,
			int lexicalRank,
			int semanticRank) {
	}

	private record HybridQueryMeasurement(
			String queryKey,
			int cutoff,
			double recall,
			double ndcg,
			double precisionAtOne,
			double reciprocalRank,
			List<HybridRankedPaper> ranked) {
	}

	private record HybridWeightMeasurement(
			double semanticWeight,
			double macroRecall,
			double macroNdcg,
			double macroPrecisionAtOne,
			double meanReciprocalRank,
			List<HybridQueryMeasurement> queries) {
	}
}
