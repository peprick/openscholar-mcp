package com.openscholar.jobs.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;

import org.junit.jupiter.api.Test;

class ResearchRefreshJobPropertiesTests {

	@Test
	void appliesSafeDisabledDefaults() {
		var properties = new ResearchRefreshJobProperties(null, null, null, null, null, null, null, null, null);

		assertThat(properties.workerEnabled()).isFalse();
		assertThat(properties.scheduledEnqueueEnabled()).isFalse();
		assertThat(properties.pollDelay()).isEqualTo(Duration.ofSeconds(5));
		assertThat(properties.scanDelay()).isEqualTo(Duration.ofHours(1));
		assertThat(properties.leaseDuration()).isEqualTo(Duration.ofMinutes(2));
		assertThat(properties.retryBackoff()).isEqualTo(Duration.ofSeconds(30));
		assertThat(properties.maxAttempts()).isEqualTo(3);
		assertThat(properties.jobsPerPoll()).isEqualTo(25);
		assertThat(properties.staleTargetsPerScan()).isEqualTo(100);
	}

	@Test
	void rejectsScheduledEnqueueWithoutAWorker() {
		assertThatThrownBy(() -> new ResearchRefreshJobProperties(
				false, true, Duration.ofSeconds(1), Duration.ofHours(1), Duration.ofMinutes(1),
				Duration.ofSeconds(1), 3, 10, 10))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("requires the refresh worker");
	}

	@Test
	void rejectsInvalidBudgets() {
		assertThatThrownBy(() -> properties(Duration.ZERO, 3, 10, 10))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> properties(Duration.ofSeconds(1), 11, 10, 10))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> properties(Duration.ofSeconds(1), 3, 101, 10))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> properties(Duration.ofSeconds(1), 3, 10, 1_001))
				.isInstanceOf(IllegalArgumentException.class);
	}

	private static ResearchRefreshJobProperties properties(
			Duration pollDelay, int maxAttempts, int jobsPerPoll, int staleTargetsPerScan) {
		return new ResearchRefreshJobProperties(
				true, false, pollDelay, Duration.ofHours(1), Duration.ofMinutes(1),
				Duration.ofSeconds(1), maxAttempts, jobsPerPoll, staleTargetsPerScan);
	}
}
