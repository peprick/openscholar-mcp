package com.openscholar.search.internal.persistence;

import java.util.List;
import java.util.Objects;

final class RelatedTopicReuseScaleMetrics {

	private static final double NANOS_PER_MILLISECOND = 1_000_000.0d;

	private RelatedTopicReuseScaleMetrics() {
	}

	static Summary summarize(List<Long> samplesNanos) {
		List<Long> samples = List.copyOf(Objects.requireNonNull(samplesNanos, "samplesNanos"));
		if (samples.isEmpty() || samples.stream().anyMatch(sample -> sample == null || sample < 0L)) {
			throw new IllegalArgumentException("Latency samples must be non-empty and non-negative");
		}
		List<Long> sorted = samples.stream().sorted().toList();
		return new Summary(
				sorted.size(),
				millis(sorted.getFirst()),
				millis(nearestRank(sorted, 0.50d)),
				millis(nearestRank(sorted, 0.95d)),
				millis(nearestRank(sorted, 0.99d)),
				millis(sorted.getLast()));
	}

	static long projectedTotal(long controlNanos, long feedbackNanos, long fusionNanos) {
		if (controlNanos < 0L || feedbackNanos < 0L || fusionNanos < 0L) {
			throw new IllegalArgumentException("Stage durations must be non-negative");
		}
		return Math.addExact(Math.addExact(controlNanos, feedbackNanos), fusionNanos);
	}

	private static long nearestRank(List<Long> sorted, double percentile) {
		int index = Math.max(0, (int) Math.ceil(sorted.size() * percentile) - 1);
		return sorted.get(index);
	}

	private static double millis(long nanos) {
		return nanos / NANOS_PER_MILLISECOND;
	}

	record Summary(
			int sampleCount,
			double minimumMillis,
			double p50Millis,
			double p95Millis,
			double p99Millis,
			double maximumMillis) {

		Summary {
			if (sampleCount < 1
					|| !finiteNonNegative(minimumMillis)
					|| !finiteNonNegative(p50Millis)
					|| !finiteNonNegative(p95Millis)
					|| !finiteNonNegative(p99Millis)
					|| !finiteNonNegative(maximumMillis)
					|| minimumMillis > p50Millis
					|| p50Millis > p95Millis
					|| p95Millis > p99Millis
					|| p99Millis > maximumMillis) {
				throw new IllegalArgumentException("Invalid related-topic scale latency summary");
			}
		}

		private static boolean finiteNonNegative(double value) {
			return Double.isFinite(value) && value >= 0.0d;
		}
	}
}
