package com.openscholar.search.internal.persistence;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import com.openscholar.search.internal.persistence.RelatedTopicReuseHoldoutBundle.Candidate;
import com.openscholar.search.internal.persistence.RelatedTopicReuseHoldoutBundle.Query;
import com.openscholar.search.internal.persistence.RelatedTopicReuseHoldoutBundle.QueryJudgments;
import com.openscholar.search.internal.persistence.RelatedTopicReuseHoldoutRankingSnapshot.RankedPaper;
import com.openscholar.search.internal.persistence.RelatedTopicReuseHoldoutScoringResult.AggregateMetrics;
import com.openscholar.search.internal.persistence.RelatedTopicReuseHoldoutScoringResult.GateId;
import com.openscholar.search.internal.persistence.RelatedTopicReuseHoldoutScoringResult.GateOutcome;
import com.openscholar.search.internal.persistence.RelatedTopicReuseHoldoutScoringResult.MetricDeltas;
import com.openscholar.search.internal.persistence.RelatedTopicReuseHoldoutScoringResult.QueryScore;
import com.openscholar.search.internal.persistence.RelatedTopicReuseHoldoutScoringResult.RankingMetrics;
import com.openscholar.search.internal.persistence.RelatedTopicReuseHoldoutScoringResult.RankingSummary;
import com.openscholar.search.internal.persistence.RelatedTopicReuseHoldoutScoringResult.ScoreIdentity;
import com.openscholar.search.internal.persistence.RelatedTopicReuseHoldoutScoringResult.StructuralAssessment;

/** Deterministic scorer for the sealed, post-ranking blind-holdout capability. */
final class RelatedTopicReuseHoldoutScorer {

	private RelatedTopicReuseHoldoutScorer() {
	}

	static RelatedTopicReuseHoldoutScoringResult score(
			RelatedTopicReuseHoldoutBundle.VerifiedScoringInputs input) {
		if (input == null || !input.isAuthorized()) {
			throw new IllegalArgumentException("verified holdout scoring input is required");
		}
		RelatedTopicReuseHoldoutPolicy.BoundPolicy boundPolicy = input.boundPolicy();
		RelatedTopicReuseHoldoutPolicy policy = boundPolicy.policy();
		RelatedTopicReuseHoldoutBundle bundle = input.bundle();
		RelatedTopicReuseHoldoutRankingSnapshot snapshot = input.rankingSnapshot();
		validateIdentity(boundPolicy, bundle, snapshot);

		Map<String, Query> queryByKey = orderedByKey(
				bundle.corpus().queries(), Query::key, "query");
		Map<String, QueryJudgments> judgmentsByKey = orderedByKey(
				bundle.judgments().queries(), QueryJudgments::queryKey, "judgment query");
		Map<String, Candidate> candidateByKey = orderedByKey(
				bundle.corpus().candidates(), Candidate::key, "candidate");
		Map<String, RelatedTopicReuseHoldoutBundle.LineageKind> lineageByKey =
				new LinkedHashMap<>();
		bundle.corpus().lineages().forEach(lineage -> {
			if (lineageByKey.putIfAbsent(lineage.key(), lineage.kind()) != null) {
				throw new IllegalArgumentException("verified corpus contains duplicate lineages");
			}
		});

		double epsilon = policy.evaluation().comparisonEpsilon();
		List<QueryScore> queryScores = new ArrayList<>(snapshot.queries().size());
		for (RelatedTopicReuseHoldoutRankingSnapshot.QueryRanking ranking : snapshot.queries()) {
			Query query = require(queryByKey, ranking.queryKey(), "query");
			QueryJudgments judgments = require(
					judgmentsByKey, ranking.queryKey(), "query judgments");
			queryScores.add(scoreQuery(
					query,
					judgments,
					ranking,
					candidateByKey,
					lineageByKey,
					policy.evaluation().relevanceThreshold(),
					snapshot.cutoff(),
					epsilon));
		}

		RankingSummary control = summarize(
				queryScores.stream().map(QueryScore::control).toList());
		RankingSummary candidate = summarize(
				queryScores.stream().map(QueryScore::candidate).toList());
		AggregateMetrics aggregate = aggregate(queryScores, control, candidate, snapshot, epsilon);
		StructuralAssessment structural = structural(queryScores);
		List<GateOutcome> gates = gates(policy.gates(), aggregate, structural, epsilon);
		return new RelatedTopicReuseHoldoutScoringResult(
				new ScoreIdentity(
						policy.evaluation().protocolId(),
						snapshot.bundleId(),
						snapshot.corpusId(),
						snapshot.policySha256(),
						snapshot.corpusSha256(),
						snapshot.manifestSha256(),
						snapshot.judgmentsSha256(),
						snapshot.evidenceSha256(),
						snapshot.judgmentsBytes(),
						snapshot.candidateRevision(),
						snapshot.cutoff(),
						snapshot.queryOrder()),
				queryScores,
				control,
				candidate,
				aggregate,
				structural,
				gates,
				gates.stream().allMatch(GateOutcome::passed),
				false,
				false,
				false,
				false);
	}

	private static void validateIdentity(
			RelatedTopicReuseHoldoutPolicy.BoundPolicy boundPolicy,
			RelatedTopicReuseHoldoutBundle bundle,
			RelatedTopicReuseHoldoutRankingSnapshot snapshot) {
		RelatedTopicReuseHoldoutPolicy policy = boundPolicy.policy();
		List<String> queryOrder = bundle.corpus().queries().stream().map(Query::key).toList();
		if (!snapshot.bundleId().equals(bundle.bundleId())
				|| !snapshot.corpusId().equals(bundle.corpusId())
				|| !snapshot.manifestSha256().equals(bundle.manifestSha256())
				|| !snapshot.policySha256().equals(boundPolicy.sha256())
				|| !snapshot.candidateRevision().equals(policy.candidateFreezeRevision())
				|| snapshot.cutoff() != policy.evaluation().cutoff()
				|| !snapshot.queryOrder().equals(queryOrder)
				|| !bundle.judgments().corpusId().equals(bundle.corpusId())) {
			throw new IllegalArgumentException(
					"verified scoring inputs do not share one frozen identity");
		}
	}

	private static QueryScore scoreQuery(
			Query query,
			QueryJudgments judgments,
			RelatedTopicReuseHoldoutRankingSnapshot.QueryRanking ranking,
			Map<String, Candidate> candidateByKey,
			Map<String, RelatedTopicReuseHoldoutBundle.LineageKind> lineageByKey,
			int relevanceThreshold,
			int cutoff,
			double epsilon) {
		var run = ranking.initialRun();
		RankingMetrics control = measure(
				run.controlTop10(), judgments.grades(), relevanceThreshold, cutoff);
		RankingMetrics candidate = measure(
				run.candidateTop10(), judgments.grades(), relevanceThreshold, cutoff);
		MetricDeltas deltas = new MetricDeltas(
				delta(candidate.recallAt10(), control.recallAt10()),
				delta(candidate.ndcgAt10(), control.ndcgAt10()),
				candidate.precisionAt1() - control.precisionAt1(),
				delta(candidate.reciprocalRankAt10(), control.reciprocalRankAt10()));

		Set<String> baselineKeys = new LinkedHashSet<>(keys(run.controlTop10()));
		int novelRelevant = (int) keys(run.candidateTop10()).stream()
				.filter(key -> grade(judgments.grades(), key) >= relevanceThreshold)
				.filter(key -> !baselineKeys.contains(key))
				.count();
		Set<String> adversaries = judgments.adversaries().stream()
				.map(RelatedTopicReuseHoldoutBundle.Adversary::candidateKey)
				.collect(java.util.stream.Collectors.toUnmodifiableSet());
		int controlAdversaries = countMembers(run.controlTop10(), adversaries);
		int candidateAdversaries = countMembers(run.candidateTop10(), adversaries);
		boolean rankOneIrrelevant = !run.candidateTop10().isEmpty()
				&& grade(judgments.grades(), run.candidateTop10().getFirst().paperKey())
						< relevanceThreshold;

		Set<String> inspectedKeys = inspectedKeys(run);
		int ownerLeaks = 0;
		int filterViolations = 0;
		for (String key : inspectedKeys) {
			Candidate observed = require(candidateByKey, key, "observed candidate");
			RelatedTopicReuseHoldoutBundle.LineageKind lineage = require(
					lineageByKey, observed.lineageKey(), "candidate lineage");
			if (!lineage.targetVisible()) {
				ownerLeaks++;
			}
			if (!RelatedTopicReuseHoldoutCandidateFilters.matches(observed, query.filters())) {
				filterViolations++;
			}
		}

		boolean feedbackNonempty = run.feedbackPools().stream()
				.anyMatch(pool -> !pool.candidates().isEmpty());
		boolean exactFallback = feedbackNonempty
				|| keys(run.candidateTop10()).equals(keys(run.controlTop10()));
		boolean repeatedStable = run.equals(ranking.repeatedRun());
		boolean hiddenNoninterference = run.feedbackPools().equals(
				ranking.hiddenPerturbation().visibleFeedbackPools())
				&& run.candidateTop10().equals(
						ranking.hiddenPerturbation().visibleCandidateTop10());
		boolean recallNonregression = !regression(deltas.recallAt10(), epsilon);
		boolean controlNonregression = query.kind().opportunity()
				|| (!regression(deltas.recallAt10(), epsilon)
						&& !regression(deltas.ndcgAt10(), epsilon)
						&& !regression(deltas.precisionAt1(), epsilon)
						&& !regression(deltas.reciprocalRankAt10(), epsilon));
		boolean filteredImprovement = query.kind()
				!= RelatedTopicReuseHoldoutBundle.QueryKind.FILTERED_LEXICAL_BRIDGE_OPPORTUNITY
				|| gain(deltas.recallAt10(), epsilon);
		boolean authorHit = query.kind()
				!= RelatedTopicReuseHoldoutBundle.QueryKind.AUTHOR_NO_RELATED_SIGNAL_CONTROL
				|| authorBaselineHit(run.controlTop10(), judgments.grades());
		boolean authorZero = query.kind()
				!= RelatedTopicReuseHoldoutBundle.QueryKind.AUTHOR_NO_RELATED_SIGNAL_CONTROL
				|| zeroSeedsAndFeedback(run);
		boolean noSeedZero = query.kind()
				!= RelatedTopicReuseHoldoutBundle.QueryKind.NO_SEED_FALLBACK_CONTROL
				|| zeroSeedsAndFeedback(run);

		return new QueryScore(
				query.key(),
				query.kind(),
				control,
				candidate,
				deltas,
				novelRelevant,
				controlAdversaries,
				candidateAdversaries,
				rankOneIrrelevant,
				ownerLeaks,
				filterViolations,
				repeatedStable,
				hiddenNoninterference,
				exactFallback,
				recallNonregression,
				controlNonregression,
				filteredImprovement,
				authorHit,
				authorZero,
				noSeedZero);
	}

	private static RankingMetrics measure(
			List<RankedPaper> ranking,
			Map<String, Integer> grades,
			int relevanceThreshold,
			int cutoff) {
		List<RankedPaper> top = ranking.stream().limit(cutoff).toList();
		int relevantCount = (int) grades.values().stream()
				.filter(grade -> grade >= relevanceThreshold)
				.count();
		int retrievedRelevant = (int) top.stream()
				.filter(paper -> grade(grades, paper.paperKey()) >= relevanceThreshold)
				.count();
		double precision = !top.isEmpty()
				&& grade(grades, top.getFirst().paperKey()) >= relevanceThreshold
						? 1.0d : 0.0d;
		if (relevantCount == 0) {
			return new RankingMetrics(0, 0, null, null, precision, null);
		}
		double dcg = 0.0d;
		double reciprocalRank = 0.0d;
		for (int index = 0; index < top.size(); index++) {
			int grade = grade(grades, top.get(index).paperKey());
			dcg += gainValue(grade) / log2(index + 2.0d);
			if (reciprocalRank == 0.0d && grade >= relevanceThreshold) {
				reciprocalRank = 1.0d / (index + 1.0d);
			}
		}
		List<Integer> idealGrades = grades.values().stream()
				.filter(grade -> grade >= relevanceThreshold)
				.sorted(Comparator.reverseOrder())
				.limit(cutoff)
				.toList();
		double idealDcg = 0.0d;
		for (int index = 0; index < idealGrades.size(); index++) {
			idealDcg += gainValue(idealGrades.get(index)) / log2(index + 2.0d);
		}
		return new RankingMetrics(
				relevantCount,
				retrievedRelevant,
				retrievedRelevant / (double) relevantCount,
				dcg / idealDcg,
				precision,
				reciprocalRank);
	}

	private static RankingSummary summarize(List<RankingMetrics> metrics) {
		List<Double> recalls = metrics.stream().map(RankingMetrics::recallAt10)
				.filter(Objects::nonNull).toList();
		List<Double> ndcgs = metrics.stream().map(RankingMetrics::ndcgAt10)
				.filter(Objects::nonNull).toList();
		List<Double> reciprocalRanks = metrics.stream()
				.map(RankingMetrics::reciprocalRankAt10)
				.filter(Objects::nonNull)
				.toList();
		return new RankingSummary(
				metrics.size(),
				recalls.size(),
				ndcgs.size(),
				metrics.size(),
				reciprocalRanks.size(),
				mean(recalls),
				mean(ndcgs),
				meanRequired(metrics.stream().map(RankingMetrics::precisionAt1).toList()),
				mean(reciprocalRanks));
	}

	private static AggregateMetrics aggregate(
			List<QueryScore> queries,
			RankingSummary control,
			RankingSummary candidate,
			RelatedTopicReuseHoldoutRankingSnapshot snapshot,
			double epsilon) {
		List<Double> ndcgRegressions = queries.stream()
				.map(query -> query.deltas().ndcgAt10())
				.filter(delta -> regression(delta, epsilon))
				.map(delta -> -delta)
				.toList();
		return new AggregateMetrics(
				delta(candidate.macroRecallAt10(), control.macroRecallAt10()),
				delta(candidate.macroNdcgAt10(), control.macroNdcgAt10()),
				candidate.macroPrecisionAt1() - control.macroPrecisionAt1(),
				delta(
						candidate.meanReciprocalRankAt10(),
						control.meanReciprocalRankAt10()),
				(int) queries.stream()
						.filter(query -> query.queryKind().opportunity())
						.filter(query -> gain(query.deltas().recallAt10(), epsilon))
						.count(),
				queries.stream().mapToInt(QueryScore::novelRelevantAt10).sum(),
				ndcgRegressions.size(),
				ndcgRegressions.stream().mapToDouble(Double::doubleValue).max().orElse(0.0d),
				queries.stream().mapToInt(QueryScore::controlExplicitAdversaryAt10Count).sum(),
				queries.stream().mapToInt(QueryScore::candidateExplicitAdversaryAt10Count).sum(),
				(int) queries.stream().filter(QueryScore::rankOneIrrelevant).count(),
				queries.stream().mapToInt(QueryScore::ownerScopeViolationCount).sum(),
				queries.stream().mapToInt(QueryScore::filterViolationCount).sum(),
				snapshot.counters().providerCallCount(),
				snapshot.counters().experimentalSnapshotWriteCount());
	}

	private static StructuralAssessment structural(List<QueryScore> queries) {
		return new StructuralAssessment(
				(int) queries.stream().filter(query -> !query.recallNonregression()).count(),
				(int) queries.stream().filter(query -> !query.controlNonregression()).count(),
				(int) queries.stream()
						.filter(query -> !query.filteredOpportunityStrictImprovement()).count(),
				(int) queries.stream()
						.filter(query -> !query.authorRelevantBaselineHit()).count(),
				(int) queries.stream()
						.filter(query -> !query.authorZeroEligibleSeedsAndFeedback()).count(),
				(int) queries.stream()
						.filter(query -> !query.noSeedZeroEligibleSeedsAndFeedback()).count(),
				(int) queries.stream().filter(query -> !query.repeatedStable()).count(),
				(int) queries.stream().filter(query -> !query.hiddenNoninterference()).count(),
				(int) queries.stream().filter(query -> !query.exactFallback()).count());
	}

	private static List<GateOutcome> gates(
			RelatedTopicReuseHoldoutPolicy.Gates gates,
			AggregateMetrics aggregate,
			StructuralAssessment structural,
			double epsilon) {
		return List.of(
				outcome(GateId.MINIMUM_MACRO_NDCG_DELTA,
						minimum(aggregate.macroNdcgAt10Delta(), gates.minimumMacroNdcgDelta(), epsilon)),
				outcome(GateId.MINIMUM_MACRO_RECALL_DELTA,
						minimum(aggregate.macroRecallAt10Delta(), gates.minimumMacroRecallDelta(), epsilon)),
				outcome(GateId.MINIMUM_MACRO_PRECISION_AT_1_DELTA,
						minimum(aggregate.macroPrecisionAt1Delta(), gates.minimumMacroPrecisionAt1Delta(), epsilon)),
				outcome(GateId.MINIMUM_MACRO_MEAN_RECIPROCAL_RANK_AT_10_DELTA,
						minimum(
								aggregate.macroMeanReciprocalRankAt10Delta(),
								gates.minimumMacroMeanReciprocalRankAt10Delta(),
								epsilon)),
				outcome(GateId.MINIMUM_STRICT_OPPORTUNITY_RECALL_IMPROVEMENTS,
						aggregate.strictOpportunityRecallImprovementCount()
								>= gates.minimumStrictOpportunityRecallImprovements()),
				outcome(GateId.MINIMUM_NOVEL_RELEVANT_AT_10,
						aggregate.novelRelevantAt10() >= gates.minimumNovelRelevantAt10()),
				outcome(GateId.MAXIMUM_PER_QUERY_NDCG_REGRESSION_COUNT,
						aggregate.perQueryNdcgRegressionCount()
								<= gates.maximumPerQueryNdcgRegressionCount()),
				outcome(GateId.MAXIMUM_PER_QUERY_NDCG_REGRESSION_MAGNITUDE,
						maximum(
								aggregate.maximumPerQueryNdcgRegression(),
								gates.maximumPerQueryNdcgRegressionMagnitude(),
								epsilon)),
				outcome(GateId.NO_PER_QUERY_RECALL_REGRESSION,
						!gates.requireNoPerQueryRecallRegression()
								|| structural.recallRegressionQueryCount() == 0),
				outcome(GateId.NO_CONTROL_REGRESSION,
						!gates.requireNoControlRegression()
								|| structural.controlRegressionQueryCount() == 0),
				outcome(GateId.FILTERED_OPPORTUNITY_STRICT_RECALL_IMPROVEMENT,
						!gates.requireFilteredOpportunityStrictRecallImprovement()
								|| structural.filteredOpportunityFailureCount() == 0),
				outcome(GateId.AUTHOR_CONTROL_RELEVANT_BASELINE_HIT,
						!gates.requireAuthorControlRelevantBaselineHit()
								|| structural.authorRelevantBaselineFailureCount() == 0),
				outcome(GateId.AUTHOR_CONTROL_ZERO_ELIGIBLE_SEEDS_AND_FEEDBACK,
						!gates.requireAuthorControlZeroEligibleSeedsAndFeedback()
								|| structural.authorZeroSeedFeedbackFailureCount() == 0),
				outcome(GateId.NO_SEED_ZERO_ELIGIBLE_SEEDS_AND_FEEDBACK,
						!gates.requireNoSeedZeroEligibleSeedsAndFeedback()
								|| structural.noSeedZeroSeedFeedbackFailureCount() == 0),
				outcome(GateId.MAXIMUM_RANK_ONE_IRRELEVANT_COUNT,
						aggregate.rankOneIrrelevantCount()
								<= gates.maximumRankOneIrrelevantCount()),
				outcome(GateId.MAXIMUM_OWNER_SCOPE_LEAK_COUNT,
						aggregate.ownerScopeLeakCount() <= gates.maximumOwnerScopeLeakCount()),
				outcome(GateId.MAXIMUM_FILTER_VIOLATION_COUNT,
						aggregate.filterViolationCount() <= gates.maximumFilterViolationCount()),
				outcome(GateId.MAXIMUM_PROVIDER_CALL_COUNT,
						aggregate.providerCallCount() <= gates.maximumProviderCallCount()),
				outcome(GateId.MAXIMUM_EXPERIMENTAL_SNAPSHOT_WRITE_COUNT,
						aggregate.experimentalSnapshotWriteCount()
								<= gates.maximumExperimentalSnapshotWriteCount()),
				outcome(GateId.REPEATED_ORDER_AND_SCORES,
						!gates.requireRepeatedOrderAndScores()
								|| structural.repeatedInstabilityCount() == 0),
				outcome(GateId.HIDDEN_CANDIDATE_NONINTERFERENCE,
						!gates.requireHiddenCandidateNoninterference()
								|| structural.hiddenInterferenceCount() == 0),
				outcome(GateId.EXACT_FALLBACK_WITHOUT_FEEDBACK,
						!gates.requireExactFallbackWithoutFeedback()
								|| structural.fallbackMismatchCount() == 0));
	}

	private static boolean authorBaselineHit(
			List<RankedPaper> control,
			Map<String, Integer> grades) {
		List<String> relevant = grades.entrySet().stream()
				.filter(entry -> entry.getValue() >= 1)
				.map(Map.Entry::getKey)
				.toList();
		return relevant.size() == 1
				&& grades.get(relevant.getFirst()) == 3
				&& control.stream()
						.anyMatch(paper -> paper.paperKey().equals(relevant.getFirst()));
	}

	private static boolean zeroSeedsAndFeedback(
			RelatedTopicReuseHoldoutRankingSnapshot.RankingRun run) {
		return run.eligibleSeedKeys().isEmpty() && run.feedbackPools().isEmpty();
	}

	private static Set<String> inspectedKeys(
			RelatedTopicReuseHoldoutRankingSnapshot.RankingRun run) {
		Set<String> keys = new LinkedHashSet<>();
		run.controlPool().stream().map(RankedPaper::paperKey).forEach(keys::add);
		run.feedbackPools().stream().flatMap(pool -> pool.candidates().stream())
				.map(RankedPaper::paperKey).forEach(keys::add);
		run.candidateTop10().stream().map(RankedPaper::paperKey).forEach(keys::add);
		return Set.copyOf(keys);
	}

	private static List<String> keys(List<RankedPaper> papers) {
		return papers.stream().map(RankedPaper::paperKey).toList();
	}

	private static int countMembers(List<RankedPaper> papers, Set<String> members) {
		return (int) papers.stream().map(RankedPaper::paperKey).filter(members::contains).count();
	}

	private static int grade(Map<String, Integer> grades, String key) {
		return grades.getOrDefault(key, 0);
	}

	private static double gainValue(int grade) {
		return (1 << grade) - 1.0d;
	}

	private static double log2(double value) {
		return StrictMath.log(value) / StrictMath.log(2.0d);
	}

	private static Double delta(Double candidate, Double control) {
		if (candidate == null && control == null) {
			return null;
		}
		if (candidate == null || control == null) {
			throw new IllegalArgumentException("metric applicability changed between rankings");
		}
		return candidate - control;
	}

	private static Double mean(List<Double> values) {
		return values.isEmpty() ? null : meanRequired(values);
	}

	private static double meanRequired(List<Double> values) {
		if (values.isEmpty()) {
			throw new IllegalArgumentException("at least one metric value is required");
		}
		double sum = 0.0d;
		for (double value : values) {
			sum += value;
		}
		return sum / values.size();
	}

	private static boolean gain(Double delta, double epsilon) {
		return delta != null && delta > epsilon;
	}

	private static boolean regression(Double delta, double epsilon) {
		return delta != null && delta < -epsilon;
	}

	private static boolean regression(double delta, double epsilon) {
		return delta < -epsilon;
	}

	private static boolean minimum(Double observed, double threshold, double epsilon) {
		return observed != null && observed + epsilon >= threshold;
	}

	private static boolean minimum(double observed, double threshold, double epsilon) {
		return observed + epsilon >= threshold;
	}

	private static boolean maximum(double observed, double threshold, double epsilon) {
		return observed <= threshold + epsilon;
	}

	private static GateOutcome outcome(GateId gate, boolean passed) {
		return new GateOutcome(gate, passed);
	}

	private static <T> Map<String, T> orderedByKey(
			List<T> values,
			java.util.function.Function<T, String> key,
			String kind) {
		Map<String, T> result = new LinkedHashMap<>();
		for (T value : values) {
			if (result.putIfAbsent(key.apply(value), value) != null) {
				throw new IllegalArgumentException("verified input contains duplicate " + kind + " keys");
			}
		}
		return Map.copyOf(result);
	}

	private static <T> T require(Map<String, T> values, String key, String kind) {
		T value = values.get(key);
		if (value == null) {
			throw new IllegalArgumentException("verified input is missing " + kind + ": " + key);
		}
		return value;
	}
}
