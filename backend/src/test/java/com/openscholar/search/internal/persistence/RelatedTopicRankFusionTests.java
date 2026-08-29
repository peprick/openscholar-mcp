package com.openscholar.search.internal.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;

class RelatedTopicRankFusionTests {

	private static final double EPSILON = 1.0e-12d;

	@Test
	void noFeedbackReturnsTheBaselineOrderAndBaselineOnlyScores() {
		List<String> baseline = List.of("paper-c", "paper-a", "paper-b");

		var result = RelatedTopicRankFusion.fuse(baseline, List.of(List.of(), List.of()));

		assertThat(result).extracting(RelatedTopicRankFusion.FusedPaper::paperKey)
				.containsExactlyElementsOf(baseline);
		assertThat(result).extracting(RelatedTopicRankFusion.FusedPaper::baselineRank)
				.containsExactly(1, 2, 3);
		assertThat(result).extracting(RelatedTopicRankFusion.FusedPaper::bestFeedbackRank)
				.containsOnlyNulls();
		for (int index = 0; index < result.size(); index++) {
			assertThat(result.get(index).score())
					.isCloseTo(rrf(1.0d, index + 1), within(EPSILON));
		}
	}

	@Test
	void oneNonemptyFeedbackListReceivesTheCompleteFeedbackWeight() {
		var result = RelatedTopicRankFusion.fuse(
				List.of("paper-a", "paper-b", "paper-c"),
				List.of(List.of("paper-x", "paper-b"), List.of()));

		assertThat(result).extracting(RelatedTopicRankFusion.FusedPaper::paperKey)
				.containsExactly("paper-b", "paper-a", "paper-x", "paper-c");
		assertThat(candidate(result, "paper-b").score())
				.isCloseTo(rrf(1.0d, 2) + rrf(1.0d, 2), within(EPSILON));
		assertThat(candidate(result, "paper-x").score())
				.isCloseTo(rrf(1.0d, 1), within(EPSILON));
		assertThat(candidate(result, "paper-b").bestFeedbackRank()).isEqualTo(2);
		assertThat(result).extracting(RelatedTopicRankFusion.FusedPaper::paperKey)
				.doesNotHaveDuplicates();
	}

	@Test
	void twoNonemptyFeedbackListsSplitOneTotalFeedbackWeight() {
		var result = RelatedTopicRankFusion.fuse(
				List.of("anchor"),
				List.of(
						List.of("paper-z", "paper-a", "shared"),
						List.of("paper-a", "paper-z", "shared")));

		assertThat(result).extracting(RelatedTopicRankFusion.FusedPaper::paperKey)
				.containsExactly("anchor", "paper-a", "paper-z", "shared");
		assertThat(candidate(result, "paper-a").score())
				.isCloseTo(rrf(0.5d, 2) + rrf(0.5d, 1), within(EPSILON));
		assertThat(candidate(result, "paper-z").score())
				.isCloseTo(rrf(0.5d, 1) + rrf(0.5d, 2), within(EPSILON));
		assertThat(candidate(result, "shared").score())
				.isCloseTo(rrf(0.5d, 3) + rrf(0.5d, 3), within(EPSILON));
		assertThat(candidate(result, "paper-a").bestFeedbackRank()).isEqualTo(1);
		assertThat(candidate(result, "shared").bestFeedbackRank()).isEqualTo(3);
	}

	@Test
	void finalTiesPreferBaselineRankThenCanonicalKeyWhenFeedbackRanksAlsoTie() {
		var baselineTie = RelatedTopicRankFusion.fuse(
				List.of("paper-z", "paper-a"),
				List.of(List.of("paper-a", "paper-z")));
		assertThat(baselineTie).extracting(RelatedTopicRankFusion.FusedPaper::paperKey)
				.containsExactly("paper-z", "paper-a");

		var canonicalKeyTie = RelatedTopicRankFusion.fuse(
				List.of("anchor"),
				List.of(
						List.of("paper-z", "paper-a"),
						List.of("paper-a", "paper-z")));
		assertThat(canonicalKeyTie).extracting(RelatedTopicRankFusion.FusedPaper::paperKey)
				.containsExactly("anchor", "paper-a", "paper-z");
	}

	@Test
	void equalScoresWithoutBaselineRanksPreferTheBestFeedbackRank() {
		List<String> firstFeedback = rankingWith(
				"paper-early", 3, "paper-balanced", 12, 24, "first-filler");
		List<String> secondFeedback = rankingWith(
				"paper-balanced", 12, "paper-early", 24, 24, "second-filler");

		var result = RelatedTopicRankFusion.fuse(
				List.of("anchor"), List.of(firstFeedback, secondFeedback));
		var early = candidate(result, "paper-early");
		var balanced = candidate(result, "paper-balanced");

		assertThat(early.score()).isEqualTo(balanced.score());
		assertThat(early.bestFeedbackRank()).isEqualTo(3);
		assertThat(balanced.bestFeedbackRank()).isEqualTo(12);
		assertThat(result.indexOf(early)).isLessThan(result.indexOf(balanced));
	}

	@Test
	void candidatesMayOverlapAcrossChannelsButNotWithinOneRanking() {
		var result = RelatedTopicRankFusion.fuse(
				List.of("shared", "baseline-only"),
				List.of(List.of("shared", "feedback-only"), List.of("shared")));

		assertThat(result).extracting(RelatedTopicRankFusion.FusedPaper::paperKey)
				.containsExactly("shared", "baseline-only", "feedback-only")
				.doesNotHaveDuplicates();
		assertThat(candidate(result, "shared").score())
				.isCloseTo(
						rrf(1.0d, 1) + rrf(0.5d, 1) + rrf(0.5d, 1),
						within(EPSILON));

		assertThatThrownBy(() -> RelatedTopicRankFusion.fuse(
				List.of("duplicate", "duplicate"), List.of()))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("duplicate candidates");
		assertThatThrownBy(() -> RelatedTopicRankFusion.fuse(
				List.of("baseline"), List.of(List.of("duplicate", "duplicate"))))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("duplicate candidates");
	}

	@Test
	void validatesEveryFrozenPoolBound() {
		assertThatThrownBy(() -> RelatedTopicRankFusion.fuse(
				keys("baseline", RelatedTopicRankFusion.MAXIMUM_BASELINE_CANDIDATES + 1),
				List.of()))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("at most 50");
		assertThatThrownBy(() -> RelatedTopicRankFusion.fuse(
				List.of("baseline"),
				List.of(keys(
						"feedback", RelatedTopicRankFusion.MAXIMUM_FEEDBACK_CANDIDATES + 1))))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("at most 25");
		assertThatThrownBy(() -> RelatedTopicRankFusion.fuse(
				List.of("baseline"), List.of(List.of(), List.of(), List.of())))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("at most 2");
	}

	@Test
	void rejectsInvalidCollectionsKeysAndImpossibleFeedback() {
		assertThatThrownBy(() -> RelatedTopicRankFusion.fuse(null, List.of()))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("baselineRanking");
		assertThatThrownBy(() -> RelatedTopicRankFusion.fuse(List.of(), null))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("feedbackRankings");
		List<List<String>> nullFeedbackList = new ArrayList<>();
		nullFeedbackList.add(null);
		assertThatThrownBy(() -> RelatedTopicRankFusion.fuse(List.of("baseline"), nullFeedbackList))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("feedbackRankings[0]");
		assertThatThrownBy(() -> RelatedTopicRankFusion.fuse(List.of(" "), List.of()))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("bounded nonblank key");
		assertThatThrownBy(() -> RelatedTopicRankFusion.fuse(List.of(" padded "), List.of()))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("bounded nonblank key");
		assertThatThrownBy(() -> RelatedTopicRankFusion.fuse(
				List.of(), List.of(List.of("feedback-only"))))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("nonempty baseline");
	}

	@Test
	void emptyBaselineAndFeedbackProduceAnEmptyImmutableResult() {
		var result = RelatedTopicRankFusion.fuse(List.of(), List.of());

		assertThat(result).isEmpty();
		assertThatThrownBy(() -> result.add(null))
				.isInstanceOf(UnsupportedOperationException.class);
	}

	private static RelatedTopicRankFusion.FusedPaper candidate(
			List<RelatedTopicRankFusion.FusedPaper> candidates,
			String paperKey) {
		return candidates.stream()
				.filter(candidate -> candidate.paperKey().equals(paperKey))
				.findFirst()
				.orElseThrow();
	}

	private static List<String> keys(String prefix, int count) {
		return IntStream.range(0, count)
				.mapToObj(index -> prefix + "-" + index)
				.toList();
	}

	private static List<String> rankingWith(
			String firstPaper,
			int firstRank,
			String secondPaper,
			int secondRank,
			int size,
			String fillerPrefix) {
		List<String> ranking = new ArrayList<>(keys(fillerPrefix, size));
		ranking.set(firstRank - 1, firstPaper);
		ranking.set(secondRank - 1, secondPaper);
		return List.copyOf(ranking);
	}

	private static double rrf(double weight, int rank) {
		return weight / (RelatedTopicRankFusion.RRF_K + rank);
	}
}
