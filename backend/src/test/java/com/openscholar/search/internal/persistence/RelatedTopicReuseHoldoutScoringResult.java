package com.openscholar.search.internal.persistence;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/** Immutable, non-reader-facing result of the preregistered holdout scorer. */
record RelatedTopicReuseHoldoutScoringResult(
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

	private static final int MAXIMUM_QUERY_COUNT = 20;
	private static final int MAXIMUM_CANDIDATE_COUNT = 200;
	private static final int CUTOFF = 10;
	private static final double COMPARISON_EPSILON = 0.000000000001d;
	private static final Pattern SAFE_KEY = Pattern.compile("[a-z0-9]+(?:-[a-z0-9]+)*");
	private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");
	private static final Pattern GIT_REVISION = Pattern.compile("[0-9a-f]{40}");

	RelatedTopicReuseHoldoutScoringResult {
		identity = Objects.requireNonNull(identity, "identity");
		queries = List.copyOf(Objects.requireNonNull(queries, "queries"));
		control = Objects.requireNonNull(control, "control");
		candidate = Objects.requireNonNull(candidate, "candidate");
		aggregate = Objects.requireNonNull(aggregate, "aggregate");
		structural = Objects.requireNonNull(structural, "structural");
		gates = List.copyOf(Objects.requireNonNull(gates, "gates"));
		if (!queries.stream().map(QueryScore::queryKey).toList()
				.equals(identity.queryOrder())) {
			throw new IllegalArgumentException(
					"query scores must exactly follow the sealed query order");
		}
		if (!gates.stream().map(GateOutcome::gate).toList()
				.equals(List.of(GateId.values()))) {
			throw new IllegalArgumentException(
					"gate outcomes must contain the complete frozen gate order");
		}
		if (policyGatesPassed != gates.stream().allMatch(GateOutcome::passed)) {
			throw new IllegalArgumentException(
					"policyGatesPassed must be the conjunction of every gate outcome");
		}
		if (readerFacing || externalBundleAcceptanceAuthorized
				|| custodyReleaseAuthorized || productActivationAuthorized) {
			throw new IllegalArgumentException(
					"local holdout scoring cannot authorize reader exposure, custody, or activation");
		}
		int queryCount = queries.size();
		if (queryCount < 1 || queryCount > MAXIMUM_QUERY_COUNT
				|| control.queryCount() != queryCount
				|| candidate.queryCount() != queryCount) {
			throw new IllegalArgumentException(
					"score summaries must bind the complete bounded query set");
		}
		validateSummary(control, queries.stream().map(QueryScore::control).toList());
		validateSummary(candidate, queries.stream().map(QueryScore::candidate).toList());
		validateAggregate(aggregate, control, candidate, queries);
		validateStructural(structural, queries);
	}

	record ScoreIdentity(
			String evaluationProtocolId,
			String bundleId,
			String corpusId,
			String policySha256,
			String corpusSha256,
			String manifestSha256,
			String judgmentsSha256,
			String rankingSnapshotSha256,
			long judgmentsBytes,
			String candidateRevision,
			int cutoff,
			List<String> queryOrder) {

		ScoreIdentity {
			requireText(evaluationProtocolId, "evaluationProtocolId");
			requireKey(bundleId, "bundleId");
			requireKey(corpusId, "corpusId");
			requirePattern(policySha256, "policySha256", SHA256);
			requirePattern(corpusSha256, "corpusSha256", SHA256);
			requirePattern(manifestSha256, "manifestSha256", SHA256);
			requirePattern(judgmentsSha256, "judgmentsSha256", SHA256);
			requirePattern(rankingSnapshotSha256, "rankingSnapshotSha256", SHA256);
			requirePattern(candidateRevision, "candidateRevision", GIT_REVISION);
			queryOrder = List.copyOf(Objects.requireNonNull(queryOrder, "queryOrder"));
			Set<String> uniqueQueryKeys = new HashSet<>();
			for (String queryKey : queryOrder) {
				requireKey(queryKey, "queryOrder");
				if (!uniqueQueryKeys.add(queryKey)) {
					throw new IllegalArgumentException("queryOrder must not contain duplicates");
				}
			}
			if (judgmentsBytes < 1 || cutoff != CUTOFF || queryOrder.isEmpty()
					|| queryOrder.size() > MAXIMUM_QUERY_COUNT) {
				throw new IllegalArgumentException("invalid holdout score identity");
			}
		}
	}

	record QueryScore(
			String queryKey,
			RelatedTopicReuseHoldoutBundle.QueryKind queryKind,
			RankingMetrics control,
			RankingMetrics candidate,
			MetricDeltas deltas,
			int novelRelevantAt10,
			int controlExplicitAdversaryAt10Count,
			int candidateExplicitAdversaryAt10Count,
			boolean rankOneIrrelevant,
			int ownerScopeViolationCount,
			int filterViolationCount,
			boolean repeatedStable,
			boolean hiddenNoninterference,
			boolean exactFallback,
			boolean recallNonregression,
			boolean controlNonregression,
			boolean filteredOpportunityStrictImprovement,
			boolean authorRelevantBaselineHit,
			boolean authorZeroEligibleSeedsAndFeedback,
			boolean noSeedZeroEligibleSeedsAndFeedback) {

		QueryScore {
			Objects.requireNonNull(queryKey, "queryKey");
			Objects.requireNonNull(queryKind, "queryKind");
			Objects.requireNonNull(control, "control");
			Objects.requireNonNull(candidate, "candidate");
			Objects.requireNonNull(deltas, "deltas");
			if (novelRelevantAt10 < 0
					|| controlExplicitAdversaryAt10Count < 0
					|| candidateExplicitAdversaryAt10Count < 0
					|| ownerScopeViolationCount < 0
					|| filterViolationCount < 0
					|| novelRelevantAt10 > CUTOFF
					|| controlExplicitAdversaryAt10Count > CUTOFF
					|| candidateExplicitAdversaryAt10Count > CUTOFF
					|| ownerScopeViolationCount > MAXIMUM_CANDIDATE_COUNT
					|| filterViolationCount > MAXIMUM_CANDIDATE_COUNT) {
				throw new IllegalArgumentException("query score counts must not be negative");
			}
			requireKey(queryKey, "queryKey");
			if (control.relevantCandidateCount() != candidate.relevantCandidateCount()
					|| !sameNullable(
							deltas.recallAt10(), delta(candidate.recallAt10(), control.recallAt10()))
					|| !sameNullable(
							deltas.ndcgAt10(), delta(candidate.ndcgAt10(), control.ndcgAt10()))
					|| Double.compare(
							deltas.precisionAt1(),
							candidate.precisionAt1() - control.precisionAt1()) != 0
					|| !sameNullable(
							deltas.reciprocalRankAt10(),
							delta(candidate.reciprocalRankAt10(), control.reciprocalRankAt10()))) {
				throw new IllegalArgumentException(
						"query metric deltas must match the two rankings");
			}
			boolean expectedRecallNonregression = !regression(deltas.recallAt10());
			boolean expectedControlNonregression = queryKind.opportunity()
					|| (!regression(deltas.recallAt10())
							&& !regression(deltas.ndcgAt10())
							&& !regression(deltas.precisionAt1())
							&& !regression(deltas.reciprocalRankAt10()));
			boolean expectedFilteredImprovement = queryKind
					!= RelatedTopicReuseHoldoutBundle.QueryKind
							.FILTERED_LEXICAL_BRIDGE_OPPORTUNITY
					|| gain(deltas.recallAt10());
			if (recallNonregression != expectedRecallNonregression
					|| controlNonregression != expectedControlNonregression
					|| filteredOpportunityStrictImprovement != expectedFilteredImprovement) {
				throw new IllegalArgumentException(
						"query structural metric flags must match the frozen epsilon rules");
			}
		}
	}

	record RankingMetrics(
			int relevantCandidateCount,
			int retrievedRelevantCount,
			Double recallAt10,
			Double ndcgAt10,
			double precisionAt1,
			Double reciprocalRankAt10) {

		RankingMetrics {
			if (relevantCandidateCount < 0
					|| relevantCandidateCount > MAXIMUM_CANDIDATE_COUNT
					|| retrievedRelevantCount < 0
					|| retrievedRelevantCount > relevantCandidateCount
					|| retrievedRelevantCount > CUTOFF
					|| !binary(precisionAt1)
					|| !nullableUnit(recallAt10)
					|| !nullableUnit(ndcgAt10)
					|| !nullableUnit(reciprocalRankAt10)
					|| (relevantCandidateCount == 0
							&& (recallAt10 != null || ndcgAt10 != null
									|| reciprocalRankAt10 != null
									|| Double.compare(precisionAt1, 0.0d) != 0))
					|| (relevantCandidateCount > 0
							&& (recallAt10 == null || ndcgAt10 == null
									|| reciprocalRankAt10 == null))) {
				throw new IllegalArgumentException("invalid per-query ranking metrics");
			}
			if (relevantCandidateCount > 0) {
				double expectedRecall = retrievedRelevantCount
						/ (double) relevantCandidateCount;
				boolean noRetrievedRelevant = retrievedRelevantCount == 0;
				if (Double.compare(recallAt10, expectedRecall) != 0
						|| (noRetrievedRelevant && (Double.compare(ndcgAt10, 0.0d) != 0
								|| Double.compare(reciprocalRankAt10, 0.0d) != 0))
						|| (!noRetrievedRelevant && (ndcgAt10 <= 0.0d
								|| !reciprocalAtCutoff(reciprocalRankAt10)))
						|| ((Double.compare(precisionAt1, 1.0d) == 0)
								!= (Double.compare(reciprocalRankAt10, 1.0d) == 0))) {
					throw new IllegalArgumentException(
							"ranking metrics must be internally recomputable");
				}
			}
		}
	}

	record MetricDeltas(
			Double recallAt10,
			Double ndcgAt10,
			double precisionAt1,
			Double reciprocalRankAt10) {

		MetricDeltas {
			if (!signedUnit(precisionAt1)
					|| !nullableSignedUnit(recallAt10)
					|| !nullableSignedUnit(ndcgAt10)
					|| !nullableSignedUnit(reciprocalRankAt10)) {
				throw new IllegalArgumentException("invalid per-query metric deltas");
			}
		}
	}

	record RankingSummary(
			int queryCount,
			int recallQueryCount,
			int ndcgQueryCount,
			int precisionAt1QueryCount,
			int reciprocalRankQueryCount,
			Double macroRecallAt10,
			Double macroNdcgAt10,
			double macroPrecisionAt1,
			Double meanReciprocalRankAt10) {

		RankingSummary {
			if (queryCount < 1
					|| recallQueryCount < 0 || recallQueryCount > queryCount
					|| ndcgQueryCount != recallQueryCount
					|| reciprocalRankQueryCount != recallQueryCount
					|| precisionAt1QueryCount != queryCount
					|| !nullableUnit(macroRecallAt10)
					|| !nullableUnit(macroNdcgAt10)
					|| !unit(macroPrecisionAt1)
					|| !nullableUnit(meanReciprocalRankAt10)
					|| (recallQueryCount == 0
							&& (macroRecallAt10 != null || macroNdcgAt10 != null
									|| meanReciprocalRankAt10 != null))
					|| (recallQueryCount > 0
							&& (macroRecallAt10 == null || macroNdcgAt10 == null
									|| meanReciprocalRankAt10 == null))) {
				throw new IllegalArgumentException("invalid ranking summary");
			}
		}
	}

	record AggregateMetrics(
			Double macroRecallAt10Delta,
			Double macroNdcgAt10Delta,
			double macroPrecisionAt1Delta,
			Double macroMeanReciprocalRankAt10Delta,
			int strictOpportunityRecallImprovementCount,
			int novelRelevantAt10,
			int perQueryNdcgRegressionCount,
			double maximumPerQueryNdcgRegression,
			int controlExplicitAdversaryAt10Count,
			int candidateExplicitAdversaryAt10Count,
			int rankOneIrrelevantCount,
			int ownerScopeLeakCount,
			int filterViolationCount,
			long providerCallCount,
			long experimentalSnapshotWriteCount) {

		AggregateMetrics {
			if (!nullableSignedUnit(macroRecallAt10Delta)
					|| !nullableSignedUnit(macroNdcgAt10Delta)
					|| !signedUnit(macroPrecisionAt1Delta)
					|| !nullableSignedUnit(macroMeanReciprocalRankAt10Delta)
					|| strictOpportunityRecallImprovementCount < 0
					|| novelRelevantAt10 < 0
					|| perQueryNdcgRegressionCount < 0
					|| !unit(maximumPerQueryNdcgRegression)
					|| controlExplicitAdversaryAt10Count < 0
					|| candidateExplicitAdversaryAt10Count < 0
					|| rankOneIrrelevantCount < 0
					|| ownerScopeLeakCount < 0
					|| filterViolationCount < 0
					|| providerCallCount < 0
					|| experimentalSnapshotWriteCount < 0) {
				throw new IllegalArgumentException("invalid aggregate holdout metrics");
			}
			if (strictOpportunityRecallImprovementCount > MAXIMUM_QUERY_COUNT
					|| novelRelevantAt10 > MAXIMUM_QUERY_COUNT * CUTOFF
					|| perQueryNdcgRegressionCount > MAXIMUM_QUERY_COUNT
					|| controlExplicitAdversaryAt10Count > MAXIMUM_QUERY_COUNT * CUTOFF
					|| candidateExplicitAdversaryAt10Count > MAXIMUM_QUERY_COUNT * CUTOFF
					|| rankOneIrrelevantCount > MAXIMUM_QUERY_COUNT
					|| ownerScopeLeakCount > MAXIMUM_QUERY_COUNT * MAXIMUM_CANDIDATE_COUNT
					|| filterViolationCount > MAXIMUM_QUERY_COUNT * MAXIMUM_CANDIDATE_COUNT
					|| (perQueryNdcgRegressionCount == 0
							&& Double.compare(maximumPerQueryNdcgRegression, 0.0d) != 0)) {
				throw new IllegalArgumentException("aggregate holdout metrics exceed frozen bounds");
			}
		}
	}

	record StructuralAssessment(
			int recallRegressionQueryCount,
			int controlRegressionQueryCount,
			int filteredOpportunityFailureCount,
			int authorRelevantBaselineFailureCount,
			int authorZeroSeedFeedbackFailureCount,
			int noSeedZeroSeedFeedbackFailureCount,
			int repeatedInstabilityCount,
			int hiddenInterferenceCount,
			int fallbackMismatchCount) {

		StructuralAssessment {
			if (recallRegressionQueryCount < 0
					|| controlRegressionQueryCount < 0
					|| filteredOpportunityFailureCount < 0
					|| authorRelevantBaselineFailureCount < 0
					|| authorZeroSeedFeedbackFailureCount < 0
					|| noSeedZeroSeedFeedbackFailureCount < 0
					|| repeatedInstabilityCount < 0
					|| hiddenInterferenceCount < 0
					|| fallbackMismatchCount < 0) {
				throw new IllegalArgumentException("structural failure counts must not be negative");
			}
			if (recallRegressionQueryCount > MAXIMUM_QUERY_COUNT
					|| controlRegressionQueryCount > MAXIMUM_QUERY_COUNT
					|| filteredOpportunityFailureCount > MAXIMUM_QUERY_COUNT
					|| authorRelevantBaselineFailureCount > MAXIMUM_QUERY_COUNT
					|| authorZeroSeedFeedbackFailureCount > MAXIMUM_QUERY_COUNT
					|| noSeedZeroSeedFeedbackFailureCount > MAXIMUM_QUERY_COUNT
					|| repeatedInstabilityCount > MAXIMUM_QUERY_COUNT
					|| hiddenInterferenceCount > MAXIMUM_QUERY_COUNT
					|| fallbackMismatchCount > MAXIMUM_QUERY_COUNT) {
				throw new IllegalArgumentException("structural failure counts exceed frozen bounds");
			}
		}
	}

	enum GateId {
		MINIMUM_MACRO_NDCG_DELTA,
		MINIMUM_MACRO_RECALL_DELTA,
		MINIMUM_MACRO_PRECISION_AT_1_DELTA,
		MINIMUM_MACRO_MEAN_RECIPROCAL_RANK_AT_10_DELTA,
		MINIMUM_STRICT_OPPORTUNITY_RECALL_IMPROVEMENTS,
		MINIMUM_NOVEL_RELEVANT_AT_10,
		MAXIMUM_PER_QUERY_NDCG_REGRESSION_COUNT,
		MAXIMUM_PER_QUERY_NDCG_REGRESSION_MAGNITUDE,
		NO_PER_QUERY_RECALL_REGRESSION,
		NO_CONTROL_REGRESSION,
		FILTERED_OPPORTUNITY_STRICT_RECALL_IMPROVEMENT,
		AUTHOR_CONTROL_RELEVANT_BASELINE_HIT,
		AUTHOR_CONTROL_ZERO_ELIGIBLE_SEEDS_AND_FEEDBACK,
		NO_SEED_ZERO_ELIGIBLE_SEEDS_AND_FEEDBACK,
		MAXIMUM_RANK_ONE_IRRELEVANT_COUNT,
		MAXIMUM_OWNER_SCOPE_LEAK_COUNT,
		MAXIMUM_FILTER_VIOLATION_COUNT,
		MAXIMUM_PROVIDER_CALL_COUNT,
		MAXIMUM_EXPERIMENTAL_SNAPSHOT_WRITE_COUNT,
		REPEATED_ORDER_AND_SCORES,
		HIDDEN_CANDIDATE_NONINTERFERENCE,
		EXACT_FALLBACK_WITHOUT_FEEDBACK
	}

	record GateOutcome(GateId gate, boolean passed) {

		GateOutcome {
			Objects.requireNonNull(gate, "gate");
		}
	}

	private static boolean unit(double value) {
		return Double.isFinite(value) && value >= 0.0d && value <= 1.0d;
	}

	private static boolean binary(double value) {
		return Double.compare(value, 0.0d) == 0 || Double.compare(value, 1.0d) == 0;
	}

	private static boolean reciprocalAtCutoff(double value) {
		for (int rank = 1; rank <= CUTOFF; rank++) {
			if (Double.compare(value, 1.0d / rank) == 0) {
				return true;
			}
		}
		return false;
	}

	private static boolean signedUnit(double value) {
		return Double.isFinite(value) && value >= -1.0d && value <= 1.0d;
	}

	private static boolean nullableUnit(Double value) {
		return value == null || unit(value);
	}

	private static boolean nullableSignedUnit(Double value) {
		return value == null || signedUnit(value);
	}

	private static void validateSummary(
			RankingSummary summary, List<RankingMetrics> metrics) {
		int applicable = (int) metrics.stream()
				.filter(metric -> metric.recallAt10() != null)
				.count();
		if (summary.queryCount() != metrics.size()
				|| summary.recallQueryCount() != applicable
				|| summary.ndcgQueryCount() != applicable
				|| summary.reciprocalRankQueryCount() != applicable
				|| summary.precisionAt1QueryCount() != metrics.size()
				|| !sameNullable(summary.macroRecallAt10(), meanNullable(
						metrics.stream().map(RankingMetrics::recallAt10).toList()))
				|| !sameNullable(summary.macroNdcgAt10(), meanNullable(
						metrics.stream().map(RankingMetrics::ndcgAt10).toList()))
				|| Double.compare(summary.macroPrecisionAt1(), meanRequired(
						metrics.stream().map(RankingMetrics::precisionAt1).toList())) != 0
				|| !sameNullable(summary.meanReciprocalRankAt10(), meanNullable(
						metrics.stream().map(RankingMetrics::reciprocalRankAt10).toList()))) {
			throw new IllegalArgumentException("ranking summary does not match query metrics");
		}
	}

	private static void validateAggregate(
			AggregateMetrics aggregate,
			RankingSummary control,
			RankingSummary candidate,
			List<QueryScore> queries) {
		int queryCount = queries.size();
		int strictOpportunityImprovements = (int) queries.stream()
				.filter(query -> query.queryKind().opportunity())
				.filter(query -> query.deltas().recallAt10() != null
						&& query.deltas().recallAt10() > COMPARISON_EPSILON)
				.count();
		List<Double> ndcgRegressions = queries.stream()
				.map(query -> query.deltas().ndcgAt10())
				.filter(delta -> delta != null && delta < -COMPARISON_EPSILON)
				.map(delta -> -delta)
				.toList();
		double maximumNdcgRegression = ndcgRegressions.stream()
				.mapToDouble(Double::doubleValue)
				.max()
				.orElse(0.0d);
		if (!sameNullable(aggregate.macroRecallAt10Delta(),
				delta(candidate.macroRecallAt10(), control.macroRecallAt10()))
				|| !sameNullable(aggregate.macroNdcgAt10Delta(),
						delta(candidate.macroNdcgAt10(), control.macroNdcgAt10()))
				|| Double.compare(
						aggregate.macroPrecisionAt1Delta(),
						candidate.macroPrecisionAt1() - control.macroPrecisionAt1()) != 0
				|| !sameNullable(aggregate.macroMeanReciprocalRankAt10Delta(),
						delta(
								candidate.meanReciprocalRankAt10(),
								control.meanReciprocalRankAt10()))
				|| aggregate.strictOpportunityRecallImprovementCount()
						!= strictOpportunityImprovements
				|| aggregate.perQueryNdcgRegressionCount() != ndcgRegressions.size()
				|| Double.compare(
						aggregate.maximumPerQueryNdcgRegression(),
						maximumNdcgRegression) != 0
				|| aggregate.rankOneIrrelevantCount() > queryCount
				|| aggregate.novelRelevantAt10() != queries.stream()
						.mapToInt(QueryScore::novelRelevantAt10).sum()
				|| aggregate.controlExplicitAdversaryAt10Count() != queries.stream()
						.mapToInt(QueryScore::controlExplicitAdversaryAt10Count).sum()
				|| aggregate.candidateExplicitAdversaryAt10Count() != queries.stream()
						.mapToInt(QueryScore::candidateExplicitAdversaryAt10Count).sum()
				|| aggregate.rankOneIrrelevantCount() != queries.stream()
						.mapToInt(query -> query.rankOneIrrelevant() ? 1 : 0).sum()
				|| aggregate.ownerScopeLeakCount() != queries.stream()
						.mapToInt(QueryScore::ownerScopeViolationCount).sum()
				|| aggregate.filterViolationCount() != queries.stream()
						.mapToInt(QueryScore::filterViolationCount).sum()) {
			throw new IllegalArgumentException("aggregate metrics do not match query evidence");
		}
	}

	private static void validateStructural(
			StructuralAssessment structural, List<QueryScore> queries) {
		int queryCount = queries.size();
		if (structural.recallRegressionQueryCount() != countFalse(
				queries, QueryScore::recallNonregression)
				|| structural.controlRegressionQueryCount() != countFalse(
						queries, QueryScore::controlNonregression)
				|| structural.filteredOpportunityFailureCount() != countFalse(
						queries, QueryScore::filteredOpportunityStrictImprovement)
				|| structural.authorRelevantBaselineFailureCount() != countFalse(
						queries, QueryScore::authorRelevantBaselineHit)
				|| structural.authorZeroSeedFeedbackFailureCount() != countFalse(
						queries, QueryScore::authorZeroEligibleSeedsAndFeedback)
				|| structural.noSeedZeroSeedFeedbackFailureCount() != countFalse(
						queries, QueryScore::noSeedZeroEligibleSeedsAndFeedback)
				|| structural.repeatedInstabilityCount() != countFalse(
						queries, QueryScore::repeatedStable)
				|| structural.hiddenInterferenceCount() != countFalse(
						queries, QueryScore::hiddenNoninterference)
				|| structural.fallbackMismatchCount() != countFalse(
						queries, QueryScore::exactFallback)
				|| structural.recallRegressionQueryCount() > queryCount
				|| structural.controlRegressionQueryCount() > queryCount) {
			throw new IllegalArgumentException("structural assessment does not match query evidence");
		}
	}

	private static int countFalse(
			List<QueryScore> queries,
			java.util.function.Predicate<QueryScore> predicate) {
		return (int) queries.stream().filter(predicate.negate()).count();
	}

	private static Double delta(Double candidate, Double control) {
		if (candidate == null && control == null) {
			return null;
		}
		if (candidate == null || control == null) {
			throw new IllegalArgumentException("ranking metric applicability must remain stable");
		}
		return candidate - control;
	}

	private static Double meanNullable(List<Double> values) {
		List<Double> applicable = values.stream().filter(Objects::nonNull).toList();
		return applicable.isEmpty() ? null : meanRequired(applicable);
	}

	private static double meanRequired(List<Double> values) {
		double sum = 0.0d;
		for (double value : values) {
			sum += value;
		}
		return sum / values.size();
	}

	private static boolean sameNullable(Double left, Double right) {
		return left == null ? right == null
				: right != null && Double.compare(left, right) == 0;
	}

	private static boolean gain(Double delta) {
		return delta != null && delta > COMPARISON_EPSILON;
	}

	private static boolean regression(Double delta) {
		return delta != null && delta < -COMPARISON_EPSILON;
	}

	private static boolean regression(double delta) {
		return delta < -COMPARISON_EPSILON;
	}

	private static void requireText(String value, String field) {
		if (value == null || value.isBlank() || value.length() > 100) {
			throw new IllegalArgumentException(field + " must be bounded text");
		}
	}

	private static void requireKey(String value, String field) {
		if (value == null || value.length() < 3 || value.length() > 100
				|| !SAFE_KEY.matcher(value).matches()) {
			throw new IllegalArgumentException(field + " must be a bounded safe key");
		}
	}

	private static void requirePattern(String value, String field, Pattern pattern) {
		if (value == null || !pattern.matcher(value).matches()) {
			throw new IllegalArgumentException(field + " has an invalid commitment");
		}
	}
}
