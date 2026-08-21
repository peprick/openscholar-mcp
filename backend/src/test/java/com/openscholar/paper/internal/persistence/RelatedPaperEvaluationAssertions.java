package com.openscholar.paper.internal.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.IntStream;

import com.openscholar.paper.PaperEmbeddingMatch;
import com.openscholar.paper.PaperView;
import com.openscholar.paper.RelatedPaperMatch;
import com.openscholar.paper.RelatedPapersView;

final class RelatedPaperEvaluationAssertions {

	private RelatedPaperEvaluationAssertions() {
	}

	static void assertWellFormed(RelatedPaperEvaluationFixture fixture) {
		assertThat(fixture.fixtureId()).isNotBlank();
		assertThat(fixture.split()).isNotNull();
		assertThat(fixture.version()).isPositive();
		assertThat(fixture.rankingMethod()).isNotBlank();
		assertThat(fixture.papers())
				.as("fixture must fit the related-paper service's 25-candidate limit")
				.isNotEmpty()
				.hasSizeLessThanOrEqualTo(26);
		assertThat(fixture.queries()).isNotEmpty();
		assertThat(fixture.papers())
				.extracting(RelatedPaperEvaluationFixture.FixturePaper::key)
				.allSatisfy(key -> assertThat(key).isNotBlank())
				.doesNotHaveDuplicates();
		assertThat(fixture.papers())
				.extracting(RelatedPaperEvaluationFixture.FixturePaper::title)
				.allSatisfy(title -> assertThat(title).isNotBlank())
				.doesNotHaveDuplicates();
		assertThat(fixture.papers()).allSatisfy(paper -> {
			assertThat(paper.type()).isNotNull();
			if (paper.abstractText() != null) {
				assertThat(paper.abstractText()).isNotBlank();
			}
			if (paper.year() != null) {
				assertThat(paper.year()).isBetween(1000, 9999);
			}
			if (paper.citationCount() != null) {
				assertThat(paper.citationCount()).isNotNegative();
			}
			if (paper.language() != null) {
				assertThat(paper.language()).matches("[a-z]{2}");
			}
		});
		assertThat(fixture.queries())
				.extracting(RelatedPaperEvaluationFixture.EvaluationQuery::key)
				.allSatisfy(key -> assertThat(key).isNotBlank())
				.doesNotHaveDuplicates();
		assertThat(fixture.queries())
				.extracting(RelatedPaperEvaluationFixture.EvaluationQuery::sourceKey)
				.allSatisfy(sourceKey -> assertThat(sourceKey).isNotBlank())
				.doesNotHaveDuplicates();

		Set<String> paperKeys = paperKeys(fixture);
		Set<String> sourceKeys = sourceKeys(fixture);
		for (RelatedPaperEvaluationFixture.EvaluationQuery query : fixture.queries()) {
			assertThat(query.cutoff()).isPositive().isLessThanOrEqualTo(fixture.papers().size() - 1);
			assertThat(paperKeys).contains(query.sourceKey());
			assertThat(query.judgments()).isNotEmpty().doesNotContainKey(query.sourceKey());
			assertThat(query.judgments().keySet())
					.allSatisfy(key -> assertThat(key).isNotBlank())
					.allMatch(paperKeys::contains);
			assertThat(query.judgments().values())
					.allSatisfy(grade -> assertThat(grade).isBetween(0, 3))
					.anyMatch(grade -> grade > 0);
			long relevantCount = query.judgments().values().stream()
					.filter(grade -> grade > 0)
					.count();
			assertThat(relevantCount)
					.as("relevant judgments fit cutoff for query %s", query.key())
					.isLessThanOrEqualTo(query.cutoff());
			assertThat(query.judgments().keySet())
					.as("source papers are globally excluded from qrels for query %s", query.key())
					.doesNotContainAnyElementsOf(sourceKeys);
		}
		assertThat(fixture.queries().stream()
				.flatMap(query -> query.judgments().values().stream()))
				.as("fixture contains at least one explicit negative judgment")
				.anyMatch(grade -> grade == 0);
	}

	static void assertDevelopmentAndHoldoutAreDisjoint(
			RelatedPaperEvaluationFixture development,
			RelatedPaperEvaluationFixture holdout) {
		assertThat(development.split()).isEqualTo(RelatedPaperEvaluationFixture.Split.DEVELOPMENT);
		assertThat(holdout.split()).isEqualTo(RelatedPaperEvaluationFixture.Split.HOLDOUT);
		assertThat(development.fixtureId()).isNotEqualTo(holdout.fixtureId());
		assertThat(paperKeys(development)).doesNotContainAnyElementsOf(paperKeys(holdout));
		assertThat(queryKeys(development)).doesNotContainAnyElementsOf(queryKeys(holdout));
		assertThat(sourceKeys(development)).doesNotContainAnyElementsOf(sourceKeys(holdout));
		assertThat(contentFingerprints(development))
				.doesNotContainAnyElementsOf(contentFingerprints(holdout));
	}

	static PaperView assertValidQuery(
			RelatedPaperEvaluationFixture.EvaluationQuery query,
			Map<String, PaperView> papersByKey,
			String evaluationLabel) {
		PaperView source = papersByKey.get(query.sourceKey());
		assertThat(source)
				.as("source paper for %s query %s", evaluationLabel, query.key())
				.isNotNull();
		assertThat(query.judgments())
				.as("judgments for %s query %s", evaluationLabel, query.key())
				.isNotEmpty()
				.doesNotContainKey(query.sourceKey());
		assertThat(query.judgments().keySet())
				.as("judged papers for %s query %s", evaluationLabel, query.key())
				.allSatisfy(key -> assertThat(papersByKey).containsKey(key));
		return source;
	}

	static void assertStableExplainableResults(
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
			assertThat(match.rankingReasons()).singleElement().satisfies(reason -> {
				assertThat(reason.feature().name()).isEqualTo(rankingMethod);
				assertThat(reason.value()).isEqualTo(match.score());
			});
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
				.containsExactlyElementsOf(
						first.results().stream().map(RelatedPaperMatch::score).toList());
	}

	static void assertStableExactVectorResults(
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

	static void assertHybridRanking(
			List<RelatedPaperHybridScorer.HybridRankedPaper> ranked,
			String sourceKey,
			int cutoff,
			double semanticWeight) {
		assertThat(ranked).hasSizeLessThanOrEqualTo(cutoff);
		assertThat(ranked).extracting(RelatedPaperHybridScorer.HybridRankedPaper::paperKey)
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

	private static Set<String> paperKeys(RelatedPaperEvaluationFixture fixture) {
		return fixture.papers().stream()
				.map(RelatedPaperEvaluationFixture.FixturePaper::key)
				.collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
	}

	private static Set<String> queryKeys(RelatedPaperEvaluationFixture fixture) {
		return fixture.queries().stream()
				.map(RelatedPaperEvaluationFixture.EvaluationQuery::key)
				.collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
	}

	private static Set<String> sourceKeys(RelatedPaperEvaluationFixture fixture) {
		return fixture.queries().stream()
				.map(RelatedPaperEvaluationFixture.EvaluationQuery::sourceKey)
				.collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
	}

	private static Set<String> contentFingerprints(RelatedPaperEvaluationFixture fixture) {
		return fixture.papers().stream()
				.map(paper -> normalizeContent(paper.title()) + "\n"
						+ normalizeContent(paper.abstractText()))
				.collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
	}

	private static String normalizeContent(String value) {
		if (value == null) {
			return "";
		}
		return value.strip()
				.toLowerCase(Locale.ROOT)
				.replaceAll("\\s+", " ");
	}
}
