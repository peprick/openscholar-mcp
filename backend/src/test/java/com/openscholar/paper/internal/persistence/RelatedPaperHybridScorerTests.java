package com.openscholar.paper.internal.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class RelatedPaperHybridScorerTests {

	@Test
	void preservesLexicalAndSemanticEndpoints() {
		List<RelatedPaperHybridScorer.VectorRankedPaper> semantic = List.of(
				new RelatedPaperHybridScorer.VectorRankedPaper("semantic-first", 0.8d),
				new RelatedPaperHybridScorer.VectorRankedPaper("lexical-first", 0.2d),
				new RelatedPaperHybridScorer.VectorRankedPaper("semantic-only", -0.5d));
		Map<String, Double> lexicalScores = new LinkedHashMap<>();
		lexicalScores.put("lexical-first", 0.9d);
		lexicalScores.put("semantic-first", 0.3d);
		Map<String, Integer> lexicalRanks = Map.of(
				"lexical-first", 1,
				"semantic-first", 2);

		List<RelatedPaperHybridScorer.HybridCandidateFeatures> candidates =
				RelatedPaperHybridScorer.combine(semantic, lexicalScores, lexicalRanks, 3);
		List<RelatedPaperHybridScorer.HybridRankedPaper> lexical =
				RelatedPaperHybridScorer.rankHybridCandidates(candidates, 0.0d, 3);
		List<RelatedPaperHybridScorer.HybridRankedPaper> vector =
				RelatedPaperHybridScorer.rankHybridCandidates(candidates, 1.0d, 3);

		assertThat(lexical).extracting(RelatedPaperHybridScorer.HybridRankedPaper::paperKey)
				.containsExactly("lexical-first", "semantic-first");
		assertThat(vector).extracting(RelatedPaperHybridScorer.HybridRankedPaper::paperKey)
				.containsExactly("semantic-first", "lexical-first", "semantic-only");
		assertThat(candidates.getLast().lexicalRank()).isEqualTo(4);
		assertThat(candidates.getLast().semanticRank()).isEqualTo(3);
	}

	@Test
	void clampsCosineAndBreaksMixedWeightTiesByPaperKey() {
		List<RelatedPaperHybridScorer.HybridCandidateFeatures> candidates = List.of(
				new RelatedPaperHybridScorer.HybridCandidateFeatures(
						"z-paper", 0.8d, 0.2d, -0.6d, 1, 2),
				new RelatedPaperHybridScorer.HybridCandidateFeatures(
						"a-paper", 0.2d, 0.8d, 0.6d, 2, 1));

		List<RelatedPaperHybridScorer.HybridRankedPaper> ranked =
				RelatedPaperHybridScorer.rankHybridCandidates(candidates, 0.5d, 2);

		assertThat(ranked).extracting(RelatedPaperHybridScorer.HybridRankedPaper::paperKey)
				.containsExactly("a-paper", "z-paper");
		assertThat(ranked).extracting(RelatedPaperHybridScorer.HybridRankedPaper::hybridScore)
				.containsExactly(0.5d, 0.5d);
		assertThat(RelatedPaperHybridScorer.boundedCosineScore(-2.0d)).isZero();
		assertThat(RelatedPaperHybridScorer.boundedCosineScore(2.0d)).isEqualTo(1.0d);
	}

	@Test
	void permitsAnEmptyLexicalControlWhenNoCandidateMatchesLexically() {
		List<RelatedPaperHybridScorer.HybridCandidateFeatures> candidates = List.of(
				new RelatedPaperHybridScorer.HybridCandidateFeatures(
						"semantic-only", 0.0d, 0.8d, 0.6d, 2, 1));

		List<RelatedPaperHybridScorer.HybridRankedPaper> lexical =
				RelatedPaperHybridScorer.rankHybridCandidates(candidates, 0.0d, 1);

		assertThat(lexical).isEmpty();
		assertThatCode(() -> RelatedPaperEvaluationAssertions.assertHybridRanking(
				lexical, "source", 1, 0.0d)).doesNotThrowAnyException();
	}
}
