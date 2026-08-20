package com.openscholar.paper.internal.persistence;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import com.openscholar.paper.internal.persistence.RelatedPaperEvaluationMetrics.QueryMeasurement;
import com.openscholar.paper.internal.persistence.RelatedPaperEvaluationMetrics.Summary;
import com.openscholar.paper.internal.persistence.RelatedPaperHybridScorer.HybridRankedPaper;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

class RelatedPaperHoldoutContractTests {

	private static final String DEVELOPMENT_FIXTURE_PATH =
			"search/relevance/related-metadata-baseline-v1.json";
	private static final String HOLDOUT_FIXTURE_PATH =
			"search/relevance/related-metadata-holdout-v1.json";
	private static final String POLICY_PATH =
			"search/relevance/related-hybrid-policy-v1.json";
	private static final ObjectMapper OBJECT_MAPPER = JsonMapper.builder().build();

	@Test
	void frozenResourcesRemainWellFormedDisjointAndPolicyBound() throws Exception {
		RelatedPaperEvaluationFixture development = RelatedPaperEvaluationFixture.load(
				OBJECT_MAPPER, DEVELOPMENT_FIXTURE_PATH);
		RelatedPaperEvaluationFixture holdout = RelatedPaperEvaluationFixture.load(
				OBJECT_MAPPER, HOLDOUT_FIXTURE_PATH);
		RelatedPaperHybridPolicy policy = RelatedPaperHybridPolicy.load(
				OBJECT_MAPPER, POLICY_PATH);

		MetadataHybridHoldoutEvaluationTests.assertFrozenInputs(development, holdout, policy);
	}

	@Test
	void frozenAcceptanceChecksPassAtThePredeclaredFloorsAndRejectLowerMacroGain()
			throws Exception {
		RelatedPaperHybridPolicy.Acceptance acceptance = loadAcceptance();
		List<QueryMeasurement<HybridRankedPaper>> lexical = List.of(
				measurement("q1", 0.50d, 0.60d),
				measurement("q2", 0.50d, 0.60d));
		List<QueryMeasurement<HybridRankedPaper>> passingHybrid = List.of(
				measurement("q1", 0.50d, 0.63d),
				measurement("q2", 0.50d, 0.63d));
		List<QueryMeasurement<HybridRankedPaper>> insufficientGain = List.of(
				measurement("q1", 0.50d, 0.62d),
				measurement("q2", 0.50d, 0.62d));

		assertThatCode(() -> assertAcceptance(acceptance, lexical, passingHybrid))
				.doesNotThrowAnyException();
		assertThatThrownBy(() -> assertAcceptance(acceptance, lexical, insufficientGain))
				.isInstanceOf(AssertionError.class);
	}

	@Test
	void frozenAcceptanceChecksEnforceRegressionMagnitudeAndCount() throws Exception {
		RelatedPaperHybridPolicy.Acceptance acceptance = loadAcceptance();
		List<QueryMeasurement<HybridRankedPaper>> oneRegressionLexical = List.of(
				measurement("q1", 0.50d, 0.80d),
				measurement("q2", 0.50d, 0.60d),
				measurement("q3", 0.50d, 0.60d));
		List<QueryMeasurement<HybridRankedPaper>> boundaryHybrid = List.of(
				measurement("q1", 0.50d, 0.70d),
				measurement("q2", 0.50d, 0.70d),
				measurement("q3", 0.50d, 0.70d));
		List<QueryMeasurement<HybridRankedPaper>> excessiveMagnitude = List.of(
				measurement("q1", 0.50d, 0.699d),
				measurement("q2", 0.50d, 0.70d),
				measurement("q3", 0.50d, 0.70d));

		assertThatCode(() -> assertAcceptance(
				acceptance, oneRegressionLexical, boundaryHybrid)).doesNotThrowAnyException();
		assertThatThrownBy(() -> assertAcceptance(
				acceptance, oneRegressionLexical, excessiveMagnitude))
				.isInstanceOf(AssertionError.class);

		List<QueryMeasurement<HybridRankedPaper>> twoRegressionLexical = List.of(
				measurement("q1", 0.50d, 0.80d),
				measurement("q2", 0.50d, 0.80d),
				measurement("q3", 0.50d, 0.60d),
				measurement("q4", 0.50d, 0.60d));
		List<QueryMeasurement<HybridRankedPaper>> twoRegressionHybrid = List.of(
				measurement("q1", 0.50d, 0.70d),
				measurement("q2", 0.50d, 0.70d),
				measurement("q3", 0.50d, 0.80d),
				measurement("q4", 0.50d, 0.80d));
		assertThatThrownBy(() -> assertAcceptance(
				acceptance, twoRegressionLexical, twoRegressionHybrid))
				.isInstanceOf(AssertionError.class);
	}

	private static RelatedPaperHybridPolicy.Acceptance loadAcceptance() throws Exception {
		return RelatedPaperHybridPolicy.load(OBJECT_MAPPER, POLICY_PATH).acceptance();
	}

	private static void assertAcceptance(
			RelatedPaperHybridPolicy.Acceptance acceptance,
			List<QueryMeasurement<HybridRankedPaper>> lexical,
			List<QueryMeasurement<HybridRankedPaper>> hybrid) {
		Summary lexicalSummary = RelatedPaperEvaluationMetrics.summarize(lexical);
		Summary hybridSummary = RelatedPaperEvaluationMetrics.summarize(hybrid);
		MetadataHybridHoldoutEvaluationTests.assertAcceptanceCriteria(
				acceptance, lexical, hybrid, lexicalSummary, hybridSummary);
	}

	private static QueryMeasurement<HybridRankedPaper> measurement(
			String queryKey, double recall, double ndcg) {
		return new QueryMeasurement<>(
				queryKey,
				5,
				recall,
				ndcg,
				1.0d,
				1.0d,
				List.of());
	}
}
