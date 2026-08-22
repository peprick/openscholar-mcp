package com.openscholar.jobs;

import java.time.Instant;
import java.util.UUID;

public record ResearchRefreshJobView(
		UUID id,
		ResearchRefreshJobType jobType,
		UUID targetId,
		ResearchRefreshJobTrigger trigger,
		ResearchRefreshJobStatus status,
		int attemptCount,
		int maxAttempts,
		Instant availableAt,
		Instant leasedUntil,
		String lastErrorCode,
		String lastErrorDetail,
		Instant createdAt,
		Instant startedAt,
		Instant completedAt,
		Instant updatedAt) {
}
