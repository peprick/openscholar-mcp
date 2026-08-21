package com.openscholar.paper.internal.persistence;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

final class RelatedPaperHybridScorer {

	static final double FROZEN_SEMANTIC_WEIGHT = 0.50d;

	private RelatedPaperHybridScorer() {
	}

	static List<HybridCandidateFeatures> combine(
			List<VectorRankedPaper> vectorCandidates,
			Map<String, Double> lexicalScores,
			Map<String, Integer> lexicalRanks,
			int candidateCount) {
		return IntStream.range(0, vectorCandidates.size())
			.mapToObj(index -> {
				VectorRankedPaper vector = vectorCandidates.get(index);
				return new HybridCandidateFeatures(
						vector.paperKey(),
						lexicalScores.getOrDefault(vector.paperKey(), 0.0d),
						boundedCosineScore(vector.cosineSimilarity()),
						vector.cosineSimilarity(),
						lexicalRanks.getOrDefault(vector.paperKey(), candidateCount + 1),
						index + 1);
			})
			.toList();
	}

	static List<HybridRankedPaper> rankHybridCandidates(
			List<HybridCandidateFeatures> candidates, double semanticWeight, int cutoff) {
		return candidates.stream()
			.map(candidate -> new HybridRankedPaper(
					candidate.paperKey(),
					weightedHybridScore(candidate, semanticWeight),
					candidate.lexicalScore(),
					candidate.semanticScore(),
					candidate.cosineSimilarity(),
					candidate.lexicalRank(),
					candidate.semanticRank()))
			.filter(candidate -> candidate.hybridScore() > 0.0d)
			.sorted(hybridComparator(semanticWeight))
			.limit(cutoff)
			.toList();
	}

	static double boundedCosineScore(double cosineSimilarity) {
		return Math.max(0.0d, Math.min(1.0d, (cosineSimilarity + 1.0d) / 2.0d));
	}

	private static double weightedHybridScore(
			HybridCandidateFeatures candidate, double semanticWeight) {
		return semanticWeight * candidate.semanticScore()
			+ (1.0d - semanticWeight) * candidate.lexicalScore();
	}

	private static Comparator<HybridRankedPaper> hybridComparator(double semanticWeight) {
		Comparator<HybridRankedPaper> comparator = Comparator
			.comparingDouble(HybridRankedPaper::hybridScore)
			.reversed();
		if (semanticWeight == 0.0d) {
			return comparator.thenComparingInt(HybridRankedPaper::lexicalRank)
					.thenComparing(HybridRankedPaper::paperKey);
		}
		if (semanticWeight == 1.0d) {
			return comparator.thenComparingInt(HybridRankedPaper::semanticRank)
					.thenComparing(HybridRankedPaper::paperKey);
		}
		return comparator.thenComparing(HybridRankedPaper::paperKey);
	}

	record VectorRankedPaper(String paperKey, double cosineSimilarity) {
	}

	record HybridCandidateFeatures(
			String paperKey,
			double lexicalScore,
			double semanticScore,
			double cosineSimilarity,
			int lexicalRank,
			int semanticRank) {
	}

	record HybridRankedPaper(
			String paperKey,
			double hybridScore,
			double lexicalScore,
			double semanticScore,
			double cosineSimilarity,
			int lexicalRank,
			int semanticRank) {
	}

}
