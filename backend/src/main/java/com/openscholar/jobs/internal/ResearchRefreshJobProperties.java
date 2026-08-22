package com.openscholar.jobs.internal;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("openscholar.jobs.refresh")
record ResearchRefreshJobProperties(
		Boolean workerEnabled,
		Boolean scheduledEnqueueEnabled,
		Duration pollDelay,
		Duration scanDelay,
		Duration leaseDuration,
		Duration retryBackoff,
		Integer maxAttempts,
		Integer jobsPerPoll,
		Integer staleTargetsPerScan) {

	ResearchRefreshJobProperties {
		workerEnabled = workerEnabled != null && workerEnabled;
		scheduledEnqueueEnabled = scheduledEnqueueEnabled != null && scheduledEnqueueEnabled;
		pollDelay = pollDelay == null ? Duration.ofSeconds(5) : pollDelay;
		scanDelay = scanDelay == null ? Duration.ofHours(1) : scanDelay;
		leaseDuration = leaseDuration == null ? Duration.ofMinutes(2) : leaseDuration;
		retryBackoff = retryBackoff == null ? Duration.ofSeconds(30) : retryBackoff;
		maxAttempts = maxAttempts == null ? 3 : maxAttempts;
		jobsPerPoll = jobsPerPoll == null ? 25 : jobsPerPoll;
		staleTargetsPerScan = staleTargetsPerScan == null ? 100 : staleTargetsPerScan;
		if (scheduledEnqueueEnabled && !workerEnabled) {
			throw new IllegalArgumentException("Scheduled refresh enqueuing requires the refresh worker");
		}
		pollDelay = positive(pollDelay, "pollDelay");
		scanDelay = positive(scanDelay, "scanDelay");
		leaseDuration = positive(leaseDuration, "leaseDuration");
		retryBackoff = positive(retryBackoff, "retryBackoff");
		if (maxAttempts < 1 || maxAttempts > 10) {
			throw new IllegalArgumentException("Refresh job maxAttempts must be between 1 and 10");
		}
		if (jobsPerPoll < 1 || jobsPerPoll > 100) {
			throw new IllegalArgumentException("Refresh jobsPerPoll must be between 1 and 100");
		}
		if (staleTargetsPerScan < 1 || staleTargetsPerScan > 1_000) {
			throw new IllegalArgumentException("Refresh staleTargetsPerScan must be between 1 and 1000");
		}
	}

	private static Duration positive(Duration value, String name) {
		if (value == null || value.isZero() || value.isNegative()) {
			throw new IllegalArgumentException("Refresh job " + name + " must be positive");
		}
		return value;
	}
}
