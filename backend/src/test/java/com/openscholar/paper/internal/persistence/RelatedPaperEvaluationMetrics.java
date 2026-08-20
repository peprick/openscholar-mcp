package com.openscholar.paper.internal.persistence;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

final class RelatedPaperEvaluationMetrics {

	private RelatedPaperEvaluationMetrics() {
	}

	static <T> QueryMeasurement<T> measure(
			RelatedPaperEvaluationFixture.EvaluationQuery query,
			List<T> ranked,
			Function<? super T, String> paperKey) {
		Objects.requireNonNull(query, "query");
		List<T> rankedSnapshot = List.copyOf(ranked);
		List<String> rankedKeys = rankedSnapshot.stream().map(paperKey).toList();
		if (rankedKeys.stream().distinct().count() != rankedKeys.size()) {
			throw new IllegalArgumentException("ranked paper keys must be unique");
		}
		return new QueryMeasurement<>(
				query.key(),
				query.cutoff(),
				recallAt(rankedKeys, query.judgments(), query.cutoff()),
				ndcgAt(rankedKeys, query.judgments(), query.cutoff()),
				precisionAtOne(rankedKeys, query.judgments()),
				reciprocalRank(rankedKeys, query.judgments(), query.cutoff()),
				rankedSnapshot);
	}

	static Summary summarize(List<? extends QueryMeasurement<?>> measurements) {
		if (measurements.isEmpty()) {
			throw new IllegalArgumentException("measurements must not be empty");
		}
		return new Summary(
				measurements.stream()
						.mapToDouble(QueryMeasurement::recall)
						.average()
						.orElseThrow(),
				measurements.stream()
						.mapToDouble(QueryMeasurement::ndcg)
						.average()
						.orElseThrow(),
				measurements.stream()
						.mapToDouble(QueryMeasurement::precisionAtOne)
						.average()
						.orElseThrow(),
				measurements.stream()
						.mapToDouble(QueryMeasurement::reciprocalRank)
						.average()
						.orElseThrow());
	}

	private static double recallAt(
			List<String> rankedKeys, Map<String, Integer> judgments, int cutoff) {
		Set<String> relevant = judgments.entrySet().stream()
				.filter(entry -> entry.getValue() > 0)
				.map(Map.Entry::getKey)
				.collect(Collectors.toUnmodifiableSet());
		if (relevant.isEmpty()) {
			throw new IllegalArgumentException("judgments must contain a relevant paper");
		}
		long retrievedRelevant = rankedKeys.stream()
				.limit(cutoff)
				.filter(relevant::contains)
				.distinct()
				.count();
		return (double) retrievedRelevant / relevant.size();
	}

	private static double ndcgAt(
			List<String> rankedKeys, Map<String, Integer> judgments, int cutoff) {
		double actualDcg = IntStream.range(0, Math.min(cutoff, rankedKeys.size()))
				.mapToDouble(index -> discountedGain(
						judgments.getOrDefault(rankedKeys.get(index), 0), index))
				.sum();
		List<Integer> idealGrades = judgments.values().stream()
				.filter(grade -> grade > 0)
				.sorted(Comparator.reverseOrder())
				.limit(cutoff)
				.toList();
		double idealDcg = IntStream.range(0, idealGrades.size())
				.mapToDouble(index -> discountedGain(idealGrades.get(index), index))
				.sum();
		if (idealDcg <= 0.0d) {
			throw new IllegalArgumentException("judgments must contain a relevant paper");
		}
		return actualDcg / idealDcg;
	}

	private static double precisionAtOne(
			List<String> rankedKeys, Map<String, Integer> judgments) {
		return !rankedKeys.isEmpty() && judgments.getOrDefault(rankedKeys.getFirst(), 0) > 0
				? 1.0d
				: 0.0d;
	}

	private static double reciprocalRank(
			List<String> rankedKeys, Map<String, Integer> judgments, int cutoff) {
		return IntStream.range(0, Math.min(cutoff, rankedKeys.size()))
				.filter(index -> judgments.getOrDefault(rankedKeys.get(index), 0) > 0)
				.mapToDouble(index -> 1.0d / (index + 1.0d))
				.findFirst()
				.orElse(0.0d);
	}

	private static double discountedGain(int grade, int zeroBasedRank) {
		if (grade <= 0) {
			return 0.0d;
		}
		double gain = Math.pow(2.0d, grade) - 1.0d;
		double discount = Math.log(zeroBasedRank + 2.0d) / Math.log(2.0d);
		return gain / discount;
	}

	record QueryMeasurement<T>(
			String queryKey,
			int cutoff,
			double recall,
			double ndcg,
			double precisionAtOne,
			double reciprocalRank,
			List<T> ranked) {

		QueryMeasurement {
			ranked = List.copyOf(ranked);
		}
	}

	record Summary(
			double macroRecall,
			double macroNdcg,
			double macroPrecisionAtOne,
			double meanReciprocalRank) {
	}
}
