package com.openscholar.search.internal.persistence;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class RelatedTopicRankFusion {

	static final int RRF_K = 60;
	static final int MAXIMUM_BASELINE_CANDIDATES = 50;
	static final int MAXIMUM_FEEDBACK_LISTS = 2;
	static final int MAXIMUM_FEEDBACK_CANDIDATES = 25;

	private static final int MISSING_RANK = Integer.MAX_VALUE;
	private static final double BASELINE_WEIGHT = 1.0d;
	private static final double TOTAL_FEEDBACK_WEIGHT = 1.0d;
	private static final int MAXIMUM_PAPER_KEY_LENGTH = 200;

	private RelatedTopicRankFusion() {
	}

	static List<FusedPaper> fuse(
			List<String> baselineRanking,
			List<List<String>> feedbackRankings) {
		List<String> baseline = validateRanking(
				baselineRanking, "baselineRanking", MAXIMUM_BASELINE_CANDIDATES);
		List<List<String>> feedback = validateFeedbackRankings(feedbackRankings);
		List<List<String>> nonemptyFeedback = feedback.stream()
				.filter(ranking -> !ranking.isEmpty())
				.toList();

		if (nonemptyFeedback.isEmpty()) {
			return baselineResult(baseline);
		}
		if (baseline.isEmpty()) {
			throw new IllegalArgumentException(
					"Nonempty feedback rankings require a nonempty baseline ranking");
		}

		Map<String, MutableFusedPaper> accumulated = new LinkedHashMap<>();
		for (int index = 0; index < baseline.size(); index++) {
			int rank = index + 1;
			accumulated.computeIfAbsent(baseline.get(index), MutableFusedPaper::new)
					.addBaseline(rank, reciprocalRankContribution(BASELINE_WEIGHT, rank));
		}

		double feedbackWeight = TOTAL_FEEDBACK_WEIGHT / nonemptyFeedback.size();
		for (List<String> ranking : nonemptyFeedback) {
			for (int index = 0; index < ranking.size(); index++) {
				int rank = index + 1;
				accumulated.computeIfAbsent(ranking.get(index), MutableFusedPaper::new)
						.addFeedback(rank, reciprocalRankContribution(feedbackWeight, rank));
			}
		}

		return accumulated.values().stream()
				.map(MutableFusedPaper::finish)
				.sorted(fusedOrder())
				.toList();
	}

	private static List<FusedPaper> baselineResult(List<String> baseline) {
		List<FusedPaper> result = new ArrayList<>(baseline.size());
		for (int index = 0; index < baseline.size(); index++) {
			int rank = index + 1;
			result.add(new FusedPaper(
					baseline.get(index),
					reciprocalRankContribution(BASELINE_WEIGHT, rank),
					rank,
					null));
		}
		return List.copyOf(result);
	}

	private static List<List<String>> validateFeedbackRankings(
			List<List<String>> feedbackRankings) {
		if (feedbackRankings == null) {
			throw new IllegalArgumentException("feedbackRankings must not be null");
		}
		if (feedbackRankings.size() > MAXIMUM_FEEDBACK_LISTS) {
			throw new IllegalArgumentException(
					"feedbackRankings must contain at most " + MAXIMUM_FEEDBACK_LISTS + " lists");
		}
		List<List<String>> validated = new ArrayList<>(feedbackRankings.size());
		for (int index = 0; index < feedbackRankings.size(); index++) {
			validated.add(validateRanking(
					feedbackRankings.get(index),
					"feedbackRankings[" + index + "]",
					MAXIMUM_FEEDBACK_CANDIDATES));
		}
		return List.copyOf(validated);
	}

	private static List<String> validateRanking(
			List<String> ranking,
			String field,
			int maximumCandidates) {
		if (ranking == null) {
			throw new IllegalArgumentException(field + " must not be null");
		}
		if (ranking.size() > maximumCandidates) {
			throw new IllegalArgumentException(
					field + " must contain at most " + maximumCandidates + " candidates");
		}
		Set<String> unique = new HashSet<>();
		List<String> validated = new ArrayList<>(ranking.size());
		for (int index = 0; index < ranking.size(); index++) {
			String paperKey = validatePaperKey(ranking.get(index), field + "[" + index + "]");
			if (!unique.add(paperKey)) {
				throw new IllegalArgumentException(
						field + " must not contain duplicate candidates: " + paperKey);
			}
			validated.add(paperKey);
		}
		return List.copyOf(validated);
	}

	private static String validatePaperKey(String paperKey, String field) {
		if (paperKey == null
				|| paperKey.isBlank()
				|| !paperKey.equals(paperKey.strip())
				|| paperKey.length() > MAXIMUM_PAPER_KEY_LENGTH
				|| paperKey.codePoints().anyMatch(Character::isISOControl)) {
			throw new IllegalArgumentException(
					field + " must be a bounded nonblank key without whitespace or controls");
		}
		return paperKey;
	}

	private static double reciprocalRankContribution(double weight, int rank) {
		return weight / (RRF_K + rank);
	}

	private static Comparator<FusedPaper> fusedOrder() {
		return Comparator.comparingDouble(FusedPaper::score)
				.reversed()
				.thenComparingInt(candidate -> rankOrMissing(candidate.baselineRank()))
				.thenComparingInt(candidate -> rankOrMissing(candidate.bestFeedbackRank()))
				.thenComparing(FusedPaper::paperKey);
	}

	private static int rankOrMissing(Integer rank) {
		return rank == null ? MISSING_RANK : rank;
	}

	record FusedPaper(
			String paperKey,
			double score,
			Integer baselineRank,
			Integer bestFeedbackRank) {

		FusedPaper {
			if (paperKey == null || paperKey.isBlank()
					|| !Double.isFinite(score) || score <= 0.0d
					|| baselineRank != null && baselineRank < 1
					|| bestFeedbackRank != null && bestFeedbackRank < 1) {
				throw new IllegalArgumentException("Invalid fused related-topic candidate");
			}
		}
	}

	private static final class MutableFusedPaper {

		private final String paperKey;
		private double score;
		private Integer baselineRank;
		private Integer bestFeedbackRank;

		private MutableFusedPaper(String paperKey) {
			this.paperKey = paperKey;
		}

		private void addBaseline(int rank, double contribution) {
			if (baselineRank != null) {
				throw new IllegalStateException("Baseline candidate was accumulated more than once");
			}
			baselineRank = rank;
			score += contribution;
		}

		private void addFeedback(int rank, double contribution) {
			bestFeedbackRank = bestFeedbackRank == null
					? rank
					: Math.min(bestFeedbackRank, rank);
			score += contribution;
		}

		private FusedPaper finish() {
			return new FusedPaper(paperKey, score, baselineRank, bestFeedbackRank);
		}
	}
}
