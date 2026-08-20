package com.openscholar.paper.internal.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.util.List;
import java.util.Map;
import java.util.function.Function;

import org.junit.jupiter.api.Test;

class RelatedPaperEvaluationMetricsTests {

	@Test
	void measuresGradedRankingAndMacroResults() {
		RelatedPaperEvaluationFixture.EvaluationQuery adversarial = query(
				"adversarial", Map.of("best", 3, "second", 2, "negative", 0));
		RelatedPaperEvaluationFixture.EvaluationQuery perfect = query(
				"perfect", Map.of("best", 3, "second", 2));

		var adversarialMeasurement = RelatedPaperEvaluationMetrics.measure(
				adversarial, List.of("negative", "best", "second"), Function.identity());
		var perfectMeasurement = RelatedPaperEvaluationMetrics.measure(
				perfect, List.of("best", "second"), Function.identity());

		assertThat(adversarialMeasurement.recall()).isEqualTo(1.0d);
		assertThat(adversarialMeasurement.ndcg()).isCloseTo(0.665d, within(0.001d));
		assertThat(adversarialMeasurement.precisionAtOne()).isZero();
		assertThat(adversarialMeasurement.reciprocalRank()).isEqualTo(0.5d);
		assertThat(perfectMeasurement.recall()).isEqualTo(1.0d);
		assertThat(perfectMeasurement.ndcg()).isEqualTo(1.0d);
		assertThat(perfectMeasurement.precisionAtOne()).isEqualTo(1.0d);
		assertThat(perfectMeasurement.reciprocalRank()).isEqualTo(1.0d);

		RelatedPaperEvaluationMetrics.Summary summary = RelatedPaperEvaluationMetrics.summarize(
				List.of(adversarialMeasurement, perfectMeasurement));
		assertThat(summary.macroRecall()).isEqualTo(1.0d);
		assertThat(summary.macroNdcg()).isCloseTo(0.8325d, within(0.001d));
		assertThat(summary.macroPrecisionAtOne()).isEqualTo(0.5d);
		assertThat(summary.meanReciprocalRank()).isEqualTo(0.75d);
	}

	@Test
	void rejectsQueriesWithoutRelevantJudgmentsAndEmptySummaries() {
		RelatedPaperEvaluationFixture.EvaluationQuery query =
				query("no-relevance", Map.of("negative", 0));

		assertThatIllegalArgumentException().isThrownBy(() -> RelatedPaperEvaluationMetrics.measure(
				query, List.of("negative"), Function.identity()));
		assertThatIllegalArgumentException().isThrownBy(
				() -> RelatedPaperEvaluationMetrics.summarize(List.of()));
		assertThatIllegalArgumentException().isThrownBy(() -> RelatedPaperEvaluationMetrics.measure(
				query("duplicates", Map.of("relevant", 1)),
				List.of("relevant", "relevant"),
				Function.identity()));
	}

	@Test
	void reciprocalRankDoesNotCountRelevantResultsBeyondTheCutoff() {
		RelatedPaperEvaluationFixture.EvaluationQuery query =
				new RelatedPaperEvaluationFixture.EvaluationQuery(
						"bounded-rr", "source", 2, Map.of("relevant", 1));

		var measurement = RelatedPaperEvaluationMetrics.measure(
				query, List.of("negative-a", "negative-b", "relevant"), Function.identity());

		assertThat(measurement.reciprocalRank()).isZero();
	}

	private static RelatedPaperEvaluationFixture.EvaluationQuery query(
			String key, Map<String, Integer> judgments) {
		return new RelatedPaperEvaluationFixture.EvaluationQuery(
				key, "source", 3, judgments);
	}

	private static org.assertj.core.data.Offset<Double> within(double tolerance) {
		return org.assertj.core.data.Offset.offset(tolerance);
	}
}
