package com.openscholar.api.jobs;

import java.time.Instant;
import java.util.UUID;

import com.openscholar.jobs.ResearchRefreshJobStatus;
import com.openscholar.jobs.ResearchRefreshJobTrigger;
import com.openscholar.jobs.ResearchRefreshJobType;
import com.openscholar.jobs.ResearchRefreshJobView;

public record ResearchRefreshJobResponse(
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

	static ResearchRefreshJobResponse from(ResearchRefreshJobView view) {
		return new ResearchRefreshJobResponse(
				view.id(), view.jobType(), view.targetId(), view.trigger(), view.status(), view.attemptCount(),
				view.maxAttempts(), view.availableAt(), view.leasedUntil(), view.lastErrorCode(),
				view.lastErrorDetail(), view.createdAt(), view.startedAt(), view.completedAt(), view.updatedAt());
	}
}
