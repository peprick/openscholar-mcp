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
import com.openscholar.paper.CanonicalPaperCandidate;
import com.openscholar.paper.DocumentType;
import com.openscholar.paper.PaperCatalog;
import com.openscholar.paper.PaperIdentifier;
import com.openscholar.paper.PaperIdentifierType;
import com.openscholar.paper.PaperView;
import com.openscholar.paper.ProviderRecordCandidate;
import com.openscholar.paper.RelatedPaperMatch;
import com.openscholar.paper.RelatedPaperUseCase;
import com.openscholar.paper.RelatedPapersView;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ClassPathResource;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@Transactional
class MetadataFullTextSearchEvaluationTests {

	private static final String FIXTURE_PATH =
			"search/relevance/related-metadata-baseline-v1.json";
	private static final Instant RETRIEVED_AT = Instant.parse("2026-08-19T10:00:00Z");
	private static final double MINIMUM_QUERY_RECALL = 0.50d;
	private static final double MINIMUM_QUERY_NDCG = 0.60d;
	private static final double MINIMUM_MACRO_RECALL = 0.90d;
	private static final double MINIMUM_MACRO_NDCG = 0.80d;
	private static final double MINIMUM_MACRO_PRECISION_AT_ONE = 0.60d;
	private static final double MINIMUM_MACRO_MRR = 0.80d;

	@Autowired
	private ObjectMapper objectMapper;

	@Autowired
	private PaperCatalog paperCatalog;

	@Autowired
	private RelatedPaperUseCase relatedPapers;

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
}
