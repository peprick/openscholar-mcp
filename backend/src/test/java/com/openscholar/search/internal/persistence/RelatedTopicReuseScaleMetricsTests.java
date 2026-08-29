package com.openscholar.search.internal.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

class RelatedTopicReuseScaleMetricsTests {

	@Test
	void nearestRankSummaryIncludesEveryFrozenPercentileAndBound() {
		List<Long> descendingMillis = new ArrayList<>();
		for (long millis = 100L; millis >= 1L; millis--) {
			descendingMillis.add(millis * 1_000_000L);
		}

		var summary = RelatedTopicReuseScaleMetrics.summarize(descendingMillis);

		assertThat(summary.sampleCount()).isEqualTo(100);
		assertThat(summary.minimumMillis()).isEqualTo(1.0d);
		assertThat(summary.p50Millis()).isEqualTo(50.0d);
		assertThat(summary.p95Millis()).isEqualTo(95.0d);
		assertThat(summary.p99Millis()).isEqualTo(99.0d);
		assertThat(summary.maximumMillis()).isEqualTo(100.0d);
	}

	@Test
	void oneSamplePopulatesEveryPercentileWithoutInventingInterpolation() {
		var summary = RelatedTopicReuseScaleMetrics.summarize(List.of(1_250_000L));

		assertThat(summary.minimumMillis()).isEqualTo(1.25d);
		assertThat(summary.p50Millis()).isEqualTo(1.25d);
		assertThat(summary.p95Millis()).isEqualTo(1.25d);
		assertThat(summary.p99Millis()).isEqualTo(1.25d);
		assertThat(summary.maximumMillis()).isEqualTo(1.25d);
	}

	@Test
	void projectedTotalIsExactAndRejectsInvalidDurations() {
		assertThat(RelatedTopicReuseScaleMetrics.projectedTotal(10L, 20L, 30L))
				.isEqualTo(60L);
		assertThatThrownBy(() -> RelatedTopicReuseScaleMetrics.projectedTotal(-1L, 0L, 0L))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> RelatedTopicReuseScaleMetrics.projectedTotal(
				Long.MAX_VALUE, 1L, 0L))
				.isInstanceOf(ArithmeticException.class);
	}

	@Test
	void invalidSampleCollectionsFailClosed() {
		assertThatThrownBy(() -> RelatedTopicReuseScaleMetrics.summarize(null))
				.isInstanceOf(NullPointerException.class);
		assertThatThrownBy(() -> RelatedTopicReuseScaleMetrics.summarize(List.of()))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> RelatedTopicReuseScaleMetrics.summarize(List.of(-1L)))
				.isInstanceOf(IllegalArgumentException.class);
	}
}
