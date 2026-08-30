package com.openscholar.search.internal.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.openscholar.search.internal.persistence.RelatedTopicReuseHoldoutScoringResult.AggregateMetrics;
import com.openscholar.search.internal.persistence.RelatedTopicReuseHoldoutScoringResult.GateId;
import com.openscholar.search.internal.persistence.RelatedTopicReuseHoldoutScoringResult.GateOutcome;
import com.openscholar.search.internal.persistence.RelatedTopicReuseHoldoutScoringResult.MetricDeltas;
import com.openscholar.search.internal.persistence.RelatedTopicReuseHoldoutScoringResult.QueryScore;
import com.openscholar.search.internal.persistence.RelatedTopicReuseHoldoutScoringResult.RankingMetrics;
import com.openscholar.search.internal.persistence.RelatedTopicReuseHoldoutScoringResult.RankingSummary;
import com.openscholar.search.internal.persistence.RelatedTopicReuseHoldoutScoringResult.ScoreIdentity;
import com.openscholar.search.internal.persistence.RelatedTopicReuseHoldoutScoringResult.StructuralAssessment;

class RelatedTopicReuseHoldoutScoringResultTests {

	private static final String BUNDLE_ID = "bundle-alpha";
	private static final String CORPUS_ID = "corpus-alpha";
	private static final String POLICY_SHA256 = "a".repeat(64);
	private static final String CORPUS_SHA256 = "b".repeat(64);
	private static final String MANIFEST_SHA256 = "c".repeat(64);
	private static final String JUDGMENTS_SHA256 = "d".repeat(64);
	private static final String SNAPSHOT_SHA256 = "e".repeat(64);
	private static final String CANDIDATE_REVISION = "f".repeat(40);
	private static final String OPPORTUNITY_QUERY = "query-alpha";
	private static final String EMPTY_QUERY = "query-empty";
	private static final double CONTROL_NDCG = 0.6d;
	private static final double CANDIDATE_NDCG = 0.5d;
	private static final double NDCG_DELTA = CANDIDATE_NDCG - CONTROL_NDCG;
	private static final double NDCG_REGRESSION = -NDCG_DELTA;
	private static final double COMPARISON_EPSILON = 1.0e-12d;

	@Test
	void scoreIdentityRejectsInvalidCommitmentsKeysAndQueryBounds() {
		assertThat(identity(List.of(OPPORTUNITY_QUERY, EMPTY_QUERY)).rankingSnapshotSha256())
				.isEqualTo(SNAPSHOT_SHA256);

		for (int digestIndex = 0; digestIndex < 5; digestIndex++) {
			int invalidDigestIndex = digestIndex;
			assertThatThrownBy(() -> identityWithDigest(invalidDigestIndex, "0".repeat(63)))
					.as("digest %s must be an exact lowercase SHA-256", digestIndex)
					.isInstanceOf(IllegalArgumentException.class)
					.hasMessageContaining("invalid commitment");
		}
		assertThatThrownBy(() -> identity(
				"bundle_with_unsafe_characters",
				SNAPSHOT_SHA256,
				1L,
				CANDIDATE_REVISION,
				10,
				List.of(OPPORTUNITY_QUERY)))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("bundleId");
		assertThatThrownBy(() -> identity(List.of("Bad_Query")))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("queryOrder");
		assertThatThrownBy(() -> identity(List.of(OPPORTUNITY_QUERY, OPPORTUNITY_QUERY)))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("duplicates");
		assertThatThrownBy(() -> identity(List.of()))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("invalid holdout score identity");
		assertThatThrownBy(() -> identity(queryKeys(21)))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("invalid holdout score identity");
		assertThatThrownBy(() -> identity(
				BUNDLE_ID, SNAPSHOT_SHA256, 0L, CANDIDATE_REVISION, 10,
				List.of(OPPORTUNITY_QUERY)))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> identity(
				BUNDLE_ID, SNAPSHOT_SHA256, 1L, CANDIDATE_REVISION, 9,
				List.of(OPPORTUNITY_QUERY)))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> identity(
				BUNDLE_ID, SNAPSHOT_SHA256, 1L, "f".repeat(39), 10,
				List.of(OPPORTUNITY_QUERY)))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("candidateRevision");
	}

	@Test
	void rankingAndQueryCountsStayInsideFrozenBounds() {
		assertThatThrownBy(() -> new RankingMetrics(201, 0, 0.0d, 0.0d, 0.0d, 0.0d))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new RankingMetrics(20, 11, 0.55d, 0.5d, 0.0d, 0.1d))
				.isInstanceOf(IllegalArgumentException.class);

		assertThat(queryWithCounts(10, 10, 10, 200, 200).novelRelevantAt10())
				.isEqualTo(10);
		assertThatThrownBy(() -> queryWithCounts(11, 0, 0, 0, 0))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> queryWithCounts(0, 11, 0, 0, 0))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> queryWithCounts(0, 0, 11, 0, 0))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> queryWithCounts(0, 0, 0, 201, 0))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> queryWithCounts(0, 0, 0, 0, 201))
				.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void rankingMetricsMustBeInternallyRecomputable() {
		assertThat(new RankingMetrics(
				3, 2, 2.0d / 3.0d, 0.5d, 0.0d, 0.5d).recallAt10())
				.isEqualTo(2.0d / 3.0d);
		assertThat(new RankingMetrics(
				2, 0, 0.0d, 0.0d, 0.0d, 0.0d).retrievedRelevantCount())
				.isZero();

		assertThatThrownBy(() -> new RankingMetrics(
				3, 2, 0.5d, 0.5d, 0.0d, 0.5d))
				.as("recall must equal retrieved divided by relevant")
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("internally recomputable");
		assertThatThrownBy(() -> new RankingMetrics(
				2, 1, 0.5d, 0.5d, 0.5d, 0.5d))
				.as("precision at one is binary")
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new RankingMetrics(
				0, 0, null, null, 1.0d, null))
				.as("a query with no relevant candidates cannot have precision at one")
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new RankingMetrics(
				2, 0, 0.0d, 0.1d, 0.0d, 0.0d))
				.as("zero retrieval forces zero nDCG")
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("internally recomputable");
		assertThatThrownBy(() -> new RankingMetrics(
				2, 0, 0.0d, 0.0d, 0.0d, 0.1d))
				.as("zero retrieval forces zero reciprocal rank")
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("internally recomputable");
		assertThatThrownBy(() -> new RankingMetrics(
				2, 1, 0.5d, 0.0d, 0.0d, 0.5d))
				.as("positive retrieval requires positive nDCG")
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("internally recomputable");

		for (int rank = 1; rank <= 10; rank++) {
			double reciprocalRank = 1.0d / rank;
			double precisionAt1 = rank == 1 ? 1.0d : 0.0d;
			assertThat(new RankingMetrics(
					10, 1, 0.1d, 0.1d, precisionAt1, reciprocalRank)
					.reciprocalRankAt10())
					.as("reciprocal rank at position %s", rank)
					.isEqualTo(reciprocalRank);
		}
		assertThatThrownBy(() -> new RankingMetrics(
				10, 1, 0.1d, 0.1d, 0.0d, 0.3d))
				.as("reciprocal rank must identify an exact position through ten")
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("internally recomputable");
		assertThatThrownBy(() -> new RankingMetrics(
				2, 1, 0.5d, 0.5d, 1.0d, 0.5d))
				.as("precision one requires reciprocal rank one")
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("internally recomputable");
		assertThatThrownBy(() -> new RankingMetrics(
				2, 1, 0.5d, 0.5d, 0.0d, 1.0d))
				.as("reciprocal rank one requires precision one")
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("internally recomputable");
	}

	@Test
	void nullableMetricsAndSummaryValuesFollowApplicabilityCounts() {
		RankingMetrics noRelevant = noRelevantMetrics();
		assertThat(noRelevant.recallAt10()).isNull();
		assertThat(noRelevant.ndcgAt10()).isNull();
		assertThat(noRelevant.reciprocalRankAt10()).isNull();
		assertThat(new RankingSummary(2, 0, 0, 2, 0, null, null, 0.0d, null)
				.recallQueryCount()).isZero();

		assertThatThrownBy(() -> new RankingMetrics(
				0, 0, 0.0d, null, 0.0d, null))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new RankingMetrics(
				1, 0, null, null, 0.0d, null))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new RankingSummary(
				2, 0, 0, 2, 0, 0.0d, null, 0.0d, null))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new RankingSummary(
				2, 1, 1, 2, 1, null, null, 0.0d, null))
				.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void eachQueryDeltaMustExactlyMatchItsControlAndCandidateMetrics() {
		assertThat(opportunityQuery().deltas().ndcgAt10()).isEqualTo(NDCG_DELTA);

		assertThatThrownBy(() -> queryWithMetricsAndDeltas(
				controlMetrics(),
				candidateMetrics(),
				new MetricDeltas(0.4d, NDCG_DELTA, 0.0d, 0.0d)))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("metric deltas");
		assertThatThrownBy(() -> queryWithMetricsAndDeltas(
				controlMetrics(),
				new RankingMetrics(
						3, 2, 2.0d / 3.0d, CANDIDATE_NDCG, 1.0d, 1.0d),
				new MetricDeltas(0.5d, NDCG_DELTA, 0.0d, 0.0d)))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("metric deltas");
		assertThatThrownBy(() -> queryWithMetricsAndDeltas(
				noRelevantMetrics(),
				noRelevantMetrics(),
				new MetricDeltas(0.0d, null, 0.0d, null)))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("metric deltas");
	}

	@Test
	void queryStructuralMetricFlagsFollowKindDeltasAndFrozenEpsilon() {
		RankingMetrics zeroRetrieval = new RankingMetrics(
				2, 0, 0.0d, 0.0d, 0.0d, 0.0d);
		MetricDeltas regression = new MetricDeltas(-0.5d, -0.6d, -1.0d, -1.0d);
		QueryScore controlRegression = queryWithStructuralFlags(
				RelatedTopicReuseHoldoutBundle.QueryKind.NO_SEED_FALLBACK_CONTROL,
				controlMetrics(), zeroRetrieval, regression,
				false, false, true);
		assertThat(controlRegression.recallNonregression()).isFalse();
		assertThat(controlRegression.controlNonregression()).isFalse();
		assertThatThrownBy(() -> queryWithStructuralFlags(
				RelatedTopicReuseHoldoutBundle.QueryKind.NO_SEED_FALLBACK_CONTROL,
				controlMetrics(), zeroRetrieval, regression,
				true, false, true))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("frozen epsilon rules");
		assertThatThrownBy(() -> queryWithStructuralFlags(
				RelatedTopicReuseHoldoutBundle.QueryKind.NO_SEED_FALLBACK_CONTROL,
				controlMetrics(), zeroRetrieval, regression,
				false, true, true))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("frozen epsilon rules");

		MetricDeltas noChange = new MetricDeltas(0.0d, 0.0d, 0.0d, 0.0d);
		QueryScore filteredNoGain = queryWithStructuralFlags(
				RelatedTopicReuseHoldoutBundle.QueryKind
						.FILTERED_LEXICAL_BRIDGE_OPPORTUNITY,
				controlMetrics(), controlMetrics(), noChange,
				true, true, false);
		assertThat(filteredNoGain.filteredOpportunityStrictImprovement()).isFalse();
		assertThatThrownBy(() -> queryWithStructuralFlags(
				RelatedTopicReuseHoldoutBundle.QueryKind
						.FILTERED_LEXICAL_BRIDGE_OPPORTUNITY,
				controlMetrics(), controlMetrics(), noChange,
				true, true, true))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("frozen epsilon rules");

		RankingMetrics epsilonControl = new RankingMetrics(
				2, 1, 0.5d, 0.5d, 0.0d, 0.5d);
		double boundaryNdcg = 0.5d - COMPARISON_EPSILON;
		RankingMetrics epsilonCandidate = new RankingMetrics(
				2, 1, 0.5d, boundaryNdcg, 0.0d, 0.5d);
		double boundaryDelta = boundaryNdcg - 0.5d;
		assertThat(boundaryDelta).isGreaterThanOrEqualTo(-COMPARISON_EPSILON);
		assertThat(queryWithStructuralFlags(
				RelatedTopicReuseHoldoutBundle.QueryKind.NO_SEED_FALLBACK_CONTROL,
				epsilonControl,
				epsilonCandidate,
				new MetricDeltas(0.0d, boundaryDelta, 0.0d, 0.0d),
				true, true, true).controlNonregression()).isTrue();

		double beyondBoundaryNdcg = Math.nextDown(boundaryNdcg);
		double beyondBoundaryDelta = beyondBoundaryNdcg - 0.5d;
		assertThat(beyondBoundaryDelta).isLessThan(-COMPARISON_EPSILON);
		assertThat(queryWithStructuralFlags(
				RelatedTopicReuseHoldoutBundle.QueryKind.NO_SEED_FALLBACK_CONTROL,
				epsilonControl,
				new RankingMetrics(
						2, 1, 0.5d, beyondBoundaryNdcg, 0.0d, 0.5d),
				new MetricDeltas(0.0d, beyondBoundaryDelta, 0.0d, 0.0d),
				true, false, true).controlNonregression()).isFalse();
	}

	@Test
	void summariesAndIdentityBindTheExactQuerySetAndComputedMeans() {
		RelatedTopicReuseHoldoutScoringResult valid = validResult();
		assertThat(valid.control().queryCount()).isEqualTo(2);
		assertThat(valid.control().recallQueryCount()).isOne();

		RankingSummary oneQuerySummary = new RankingSummary(
				1, 1, 1, 1, 1, 0.5d, CONTROL_NDCG, 1.0d, 1.0d);
		assertThatThrownBy(() -> copyResult(
				valid.identity(),
				valid.queries(),
				oneQuerySummary,
				valid.candidate(),
				valid.aggregate(),
				valid.structural(),
				valid.gates(),
				true,
				false, false, false, false))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("complete bounded query set");

		RankingSummary wrongMean = new RankingSummary(
				2, 1, 1, 2, 1, 0.4d, CONTROL_NDCG, 0.5d, 1.0d);
		assertThatThrownBy(() -> copyResult(
				valid.identity(), valid.queries(), wrongMean, valid.candidate(),
				valid.aggregate(), valid.structural(), valid.gates(), true,
				false, false, false, false))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("summary does not match");

		ScoreIdentity reversedIdentity = identity(List.of(EMPTY_QUERY, OPPORTUNITY_QUERY));
		assertThatThrownBy(() -> copyResult(
				reversedIdentity, valid.queries(), valid.control(), valid.candidate(),
				valid.aggregate(), valid.structural(), valid.gates(), true,
				false, false, false, false))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("sealed query order");
	}

	@Test
	void aggregatesMustBeExactlyRecomputableFromQueryEvidence() {
		RelatedTopicReuseHoldoutScoringResult valid = validResult();
		List<AggregateMetrics> inconsistent = List.of(
				aggregateWithCounts(0, 1, 1, NDCG_REGRESSION, 0, 1, 0, 0, 0),
				aggregateWithCounts(1, 0, 1, NDCG_REGRESSION, 0, 1, 0, 0, 0),
				aggregateWithCounts(1, 1, 0, 0.0d, 0, 1, 0, 0, 0),
				aggregateWithCounts(1, 1, 1, Math.nextUp(NDCG_REGRESSION), 0, 1, 0, 0, 0),
				aggregateWithCounts(1, 1, 1, NDCG_REGRESSION, 1, 1, 0, 0, 0),
				aggregateWithCounts(1, 1, 1, NDCG_REGRESSION, 0, 0, 0, 0, 0),
				aggregateWithCounts(1, 1, 1, NDCG_REGRESSION, 0, 1, 1, 0, 0),
				aggregateWithCounts(1, 1, 1, NDCG_REGRESSION, 0, 1, 0, 1, 0),
				aggregateWithCounts(1, 1, 1, NDCG_REGRESSION, 0, 1, 0, 0, 1));

		for (AggregateMetrics aggregate : inconsistent) {
			assertThatThrownBy(() -> copyResult(
					valid.identity(), valid.queries(), valid.control(), valid.candidate(),
					aggregate, valid.structural(), valid.gates(), true,
					false, false, false, false))
					.as("aggregate evidence %s", aggregate)
					.isInstanceOf(IllegalArgumentException.class)
					.hasMessageContaining("aggregate metrics");
		}

		AggregateMetrics wrongMacroDelta = new AggregateMetrics(
				0.4d,
				NDCG_DELTA,
				0.0d,
				0.0d,
				1, 1, 1, NDCG_REGRESSION,
				0, 1, 0, 0, 0, 0L, 0L);
		assertThatThrownBy(() -> copyResult(
				valid.identity(), valid.queries(), valid.control(), valid.candidate(),
				wrongMacroDelta, valid.structural(), valid.gates(), true,
				false, false, false, false))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("aggregate metrics");
	}

	@Test
	void structuralCountsMustMatchEveryPerQueryBoolean() {
		RelatedTopicReuseHoldoutScoringResult valid = validResult();
		for (int countIndex = 0; countIndex < 9; countIndex++) {
			StructuralAssessment inconsistent = structuralWithOneFailure(countIndex);
			assertThatThrownBy(() -> copyResult(
					valid.identity(), valid.queries(), valid.control(), valid.candidate(),
					valid.aggregate(), inconsistent, valid.gates(), true,
					false, false, false, false))
					.as("structural count %s must bind query evidence", countIndex)
					.isInstanceOf(IllegalArgumentException.class)
					.hasMessageContaining("structural assessment");
		}
		assertThatThrownBy(() -> new StructuralAssessment(
				21, 0, 0, 0, 0, 0, 0, 0, 0))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("frozen bounds");
	}

	@Test
	void localScoringResultCannotGrantAnyAuthorization() {
		RelatedTopicReuseHoldoutScoringResult valid = validResult();
		assertThat(valid.readerFacing()).isFalse();
		assertThat(valid.externalBundleAcceptanceAuthorized()).isFalse();
		assertThat(valid.custodyReleaseAuthorized()).isFalse();
		assertThat(valid.productActivationAuthorized()).isFalse();

		for (int authorizationIndex = 0; authorizationIndex < 4; authorizationIndex++) {
			boolean readerFacing = authorizationIndex == 0;
			boolean externalAcceptance = authorizationIndex == 1;
			boolean custodyRelease = authorizationIndex == 2;
			boolean productActivation = authorizationIndex == 3;
			assertThatThrownBy(() -> copyResult(
					valid.identity(), valid.queries(), valid.control(), valid.candidate(),
					valid.aggregate(), valid.structural(), valid.gates(), true,
					readerFacing, externalAcceptance, custodyRelease, productActivation))
					.as("authorization flag %s", authorizationIndex)
					.isInstanceOf(IllegalArgumentException.class)
					.hasMessageContaining("cannot authorize");
		}

		assertThatThrownBy(() -> copyResult(
				valid.identity(), valid.queries(), valid.control(), valid.candidate(),
				valid.aggregate(), valid.structural(), valid.gates(), false,
				false, false, false, false))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("conjunction");
		assertThatThrownBy(() -> valid.queries().clear())
				.isInstanceOf(UnsupportedOperationException.class);
		assertThatThrownBy(() -> valid.gates().clear())
				.isInstanceOf(UnsupportedOperationException.class);
		assertThatThrownBy(() -> valid.identity().queryOrder().clear())
				.isInstanceOf(UnsupportedOperationException.class);
	}

	private static RelatedTopicReuseHoldoutScoringResult validResult() {
		List<QueryScore> queries = List.of(opportunityQuery(), emptyQuery());
		return copyResult(
				identity(queries.stream().map(QueryScore::queryKey).toList()),
				queries,
				new RankingSummary(
						2, 1, 1, 2, 1,
						0.5d, CONTROL_NDCG, 0.5d, 1.0d),
				new RankingSummary(
						2, 1, 1, 2, 1,
						1.0d, CANDIDATE_NDCG, 0.5d, 1.0d),
				aggregateWithCounts(1, 1, 1, NDCG_REGRESSION, 0, 1, 0, 0, 0),
				new StructuralAssessment(0, 0, 0, 0, 0, 0, 0, 0, 0),
				passingGates(),
				true,
				false, false, false, false);
	}

	private static QueryScore opportunityQuery() {
		return queryWithMetricsAndDeltas(
				controlMetrics(),
				candidateMetrics(),
				new MetricDeltas(0.5d, NDCG_DELTA, 0.0d, 0.0d));
	}

	private static QueryScore emptyQuery() {
		return new QueryScore(
				EMPTY_QUERY,
				RelatedTopicReuseHoldoutBundle.QueryKind.NO_SEED_FALLBACK_CONTROL,
				noRelevantMetrics(),
				noRelevantMetrics(),
				new MetricDeltas(null, null, 0.0d, null),
				0, 0, 0, false, 0, 0,
				true, true, true, true, true, true, true, true, true);
	}

	private static QueryScore queryWithMetricsAndDeltas(
			RankingMetrics control,
			RankingMetrics candidate,
			MetricDeltas deltas) {
		return new QueryScore(
				OPPORTUNITY_QUERY,
				RelatedTopicReuseHoldoutBundle.QueryKind.LEXICAL_BRIDGE_OPPORTUNITY,
				control,
				candidate,
				deltas,
				1, 0, 1, false, 0, 0,
				true, true, true, true, true, true, true, true, true);
	}

	private static QueryScore queryWithCounts(
			int novelRelevant,
			int controlAdversaries,
			int candidateAdversaries,
			int ownerViolations,
			int filterViolations) {
		return new QueryScore(
				OPPORTUNITY_QUERY,
				RelatedTopicReuseHoldoutBundle.QueryKind.LEXICAL_BRIDGE_OPPORTUNITY,
				controlMetrics(),
				candidateMetrics(),
				new MetricDeltas(0.5d, NDCG_DELTA, 0.0d, 0.0d),
				novelRelevant,
				controlAdversaries,
				candidateAdversaries,
				false,
				ownerViolations,
				filterViolations,
				true, true, true, true, true, true, true, true, true);
	}

	private static QueryScore queryWithStructuralFlags(
			RelatedTopicReuseHoldoutBundle.QueryKind queryKind,
			RankingMetrics control,
			RankingMetrics candidate,
			MetricDeltas deltas,
			boolean recallNonregression,
			boolean controlNonregression,
			boolean filteredOpportunityStrictImprovement) {
		return new QueryScore(
				OPPORTUNITY_QUERY,
				queryKind,
				control,
				candidate,
				deltas,
				0, 0, 0, false, 0, 0,
				true, true, true,
				recallNonregression,
				controlNonregression,
				filteredOpportunityStrictImprovement,
				true, true, true);
	}

	private static RankingMetrics controlMetrics() {
		return new RankingMetrics(2, 1, 0.5d, CONTROL_NDCG, 1.0d, 1.0d);
	}

	private static RankingMetrics candidateMetrics() {
		return new RankingMetrics(2, 2, 1.0d, CANDIDATE_NDCG, 1.0d, 1.0d);
	}

	private static RankingMetrics noRelevantMetrics() {
		return new RankingMetrics(0, 0, null, null, 0.0d, null);
	}

	private static AggregateMetrics aggregateWithCounts(
			int strictOpportunityImprovements,
			int novelRelevant,
			int ndcgRegressionCount,
			double maximumNdcgRegression,
			int controlAdversaries,
			int candidateAdversaries,
			int rankOneIrrelevant,
			int ownerLeaks,
			int filterViolations) {
		return new AggregateMetrics(
				0.5d,
				NDCG_DELTA,
				0.0d,
				0.0d,
				strictOpportunityImprovements,
				novelRelevant,
				ndcgRegressionCount,
				maximumNdcgRegression,
				controlAdversaries,
				candidateAdversaries,
				rankOneIrrelevant,
				ownerLeaks,
				filterViolations,
				0L,
				0L);
	}

	private static StructuralAssessment structuralWithOneFailure(int countIndex) {
		int[] counts = new int[9];
		counts[countIndex] = 1;
		return new StructuralAssessment(
				counts[0], counts[1], counts[2], counts[3], counts[4],
				counts[5], counts[6], counts[7], counts[8]);
	}

	private static List<GateOutcome> passingGates() {
		return Arrays.stream(GateId.values())
				.map(gate -> new GateOutcome(gate, true))
				.toList();
	}

	private static ScoreIdentity identity(List<String> queryOrder) {
		return identity(
				BUNDLE_ID,
				SNAPSHOT_SHA256,
				1L,
				CANDIDATE_REVISION,
				10,
				queryOrder);
	}

	private static ScoreIdentity identity(
			String bundleId,
			String snapshotSha256,
			long judgmentsBytes,
			String candidateRevision,
			int cutoff,
			List<String> queryOrder) {
		return new ScoreIdentity(
				"related-topic-reuse-holdout-v1",
				bundleId,
				CORPUS_ID,
				POLICY_SHA256,
				CORPUS_SHA256,
				MANIFEST_SHA256,
				JUDGMENTS_SHA256,
				snapshotSha256,
				judgmentsBytes,
				candidateRevision,
				cutoff,
				queryOrder);
	}

	private static ScoreIdentity identityWithDigest(int digestIndex, String digest) {
		List<String> queryOrder = List.of(OPPORTUNITY_QUERY);
		return new ScoreIdentity(
				"related-topic-reuse-holdout-v1",
				BUNDLE_ID,
				CORPUS_ID,
				digestIndex == 0 ? digest : POLICY_SHA256,
				digestIndex == 1 ? digest : CORPUS_SHA256,
				digestIndex == 2 ? digest : MANIFEST_SHA256,
				digestIndex == 3 ? digest : JUDGMENTS_SHA256,
				digestIndex == 4 ? digest : SNAPSHOT_SHA256,
				1L,
				CANDIDATE_REVISION,
				10,
				queryOrder);
	}

	private static List<String> queryKeys(int count) {
		List<String> keys = new ArrayList<>(count);
		for (int index = 0; index < count; index++) {
			keys.add("query-" + index);
		}
		return List.copyOf(keys);
	}

	private static RelatedTopicReuseHoldoutScoringResult copyResult(
			ScoreIdentity identity,
			List<QueryScore> queries,
			RankingSummary control,
			RankingSummary candidate,
			AggregateMetrics aggregate,
			StructuralAssessment structural,
			List<GateOutcome> gates,
			boolean policyGatesPassed,
			boolean readerFacing,
			boolean externalBundleAcceptanceAuthorized,
			boolean custodyReleaseAuthorized,
			boolean productActivationAuthorized) {
		return new RelatedTopicReuseHoldoutScoringResult(
				identity,
				queries,
				control,
				candidate,
				aggregate,
				structural,
				gates,
				policyGatesPassed,
				readerFacing,
				externalBundleAcceptanceAuthorized,
				custodyReleaseAuthorized,
				productActivationAuthorized);
	}
}
