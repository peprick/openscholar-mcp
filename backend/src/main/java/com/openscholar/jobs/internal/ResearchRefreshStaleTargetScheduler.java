package com.openscholar.jobs.internal;

import java.time.Clock;

import com.openscholar.jobs.ResearchRefreshJobTrigger;
import com.openscholar.jobs.ResearchRefreshJobType;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "openscholar.jobs.refresh", name = "scheduled-enqueue-enabled",
		havingValue = "true")
class ResearchRefreshStaleTargetScheduler {

	private final ResearchRefreshJobStore store;
	private final ResearchRefreshJobProperties properties;
	private final ResearchRefreshJobMetrics metrics;
	private final Clock clock;

	ResearchRefreshStaleTargetScheduler(
			ResearchRefreshJobStore store,
			ResearchRefreshJobProperties properties,
			ResearchRefreshJobMetrics metrics,
			Clock clock) {
		this.store = store;
		this.properties = properties;
		this.metrics = metrics;
		this.clock = clock;
	}

	@Scheduled(fixedDelayString = "${openscholar.jobs.refresh.scan-delay:1h}")
	void enqueueStaleTargets() {
		var now = clock.instant();
		for (var paperId : store.staleAccessTargets(now, properties.staleTargetsPerScan())) {
			store.enqueue(ResearchRefreshJobType.PAPER_ACCESS, paperId, ResearchRefreshJobTrigger.SCHEDULED,
					properties.maxAttempts(), now);
			metrics.enqueued(ResearchRefreshJobType.PAPER_ACCESS, "SCHEDULED");
		}
		for (var searchId : store.staleSearchTargets(now, properties.staleTargetsPerScan())) {
			store.enqueue(ResearchRefreshJobType.SEARCH_METADATA, searchId, ResearchRefreshJobTrigger.SCHEDULED,
					properties.maxAttempts(), now);
			metrics.enqueued(ResearchRefreshJobType.SEARCH_METADATA, "SCHEDULED");
		}
	}
}
