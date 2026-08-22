package com.openscholar.jobs.internal;

import java.time.Clock;
import java.util.Objects;
import java.util.UUID;

import com.openscholar.jobs.ResearchRefreshJobNotFoundException;
import com.openscholar.jobs.ResearchRefreshJobPage;
import com.openscholar.jobs.ResearchRefreshJobTrigger;
import com.openscholar.jobs.ResearchRefreshJobType;
import com.openscholar.jobs.ResearchRefreshJobUseCase;
import com.openscholar.jobs.ResearchRefreshJobView;
import com.openscholar.paper.PaperDetailsUseCase;
import com.openscholar.search.SearchResearchUseCase;
import com.openscholar.security.CurrentUserIdProvider;
import org.springframework.stereotype.Service;

@Service
class ResearchRefreshJobService implements ResearchRefreshJobUseCase {

	private static final int MAXIMUM_PAGE_SIZE = 100;

	private final ResearchRefreshJobStore store;
	private final ResearchRefreshJobProperties properties;
	private final PaperDetailsUseCase paperDetails;
	private final SearchResearchUseCase searches;
	private final ResearchRefreshJobMetrics metrics;
	private final CurrentUserIdProvider currentUser;
	private final Clock clock;

	ResearchRefreshJobService(
			ResearchRefreshJobStore store,
			ResearchRefreshJobProperties properties,
			PaperDetailsUseCase paperDetails,
			SearchResearchUseCase searches,
			ResearchRefreshJobMetrics metrics,
			CurrentUserIdProvider currentUser,
			Clock clock) {
		this.store = store;
		this.properties = properties;
		this.paperDetails = paperDetails;
		this.searches = searches;
		this.metrics = metrics;
		this.currentUser = currentUser;
		this.clock = clock;
	}

	@Override
	public ResearchRefreshJobView enqueue(ResearchRefreshJobType jobType, UUID targetId) {
		ResearchRefreshJobType requiredType = Objects.requireNonNull(jobType, "jobType");
		UUID requiredTarget = Objects.requireNonNull(targetId, "targetId");
		validateTarget(requiredType, requiredTarget);
		ResearchRefreshJobView job = store.enqueue(
				requiredType,
				requiredTarget,
				ResearchRefreshJobTrigger.MANUAL,
				properties.maxAttempts(),
				clock.instant());
		metrics.enqueued(requiredType, "MANUAL");
		return job;
	}

	@Override
	public ResearchRefreshJobView get(UUID jobId) {
		UUID requiredId = Objects.requireNonNull(jobId, "jobId");
		return store.findVisible(requiredId, currentUser.currentUserId())
				.orElseThrow(() -> new ResearchRefreshJobNotFoundException(requiredId));
	}

	@Override
	public ResearchRefreshJobPage list(int page, int size) {
		if (page < 0) {
			throw new IllegalArgumentException("Refresh job page must not be negative");
		}
		if (size < 1 || size > MAXIMUM_PAGE_SIZE) {
			throw new IllegalArgumentException("Refresh job page size must be between 1 and 100");
		}
		return store.listVisible(currentUser.currentUserId(), page, size);
	}

	@Override
	public ResearchRefreshJobView retry(UUID jobId) {
		return store.retryVisible(
				Objects.requireNonNull(jobId, "jobId"), currentUser.currentUserId(), clock.instant());
	}

	private void validateTarget(ResearchRefreshJobType jobType, UUID targetId) {
		switch (jobType) {
			case PAPER_ACCESS -> paperDetails.get(targetId);
			case SEARCH_METADATA -> searches.get(targetId);
		}
	}
}
