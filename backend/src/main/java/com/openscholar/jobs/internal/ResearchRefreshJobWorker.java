package com.openscholar.jobs.internal;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

import com.openscholar.access.AccessDisposition;
import com.openscholar.access.AccessUnavailableException;
import com.openscholar.access.PaperAccessUseCase;
import com.openscholar.jobs.ResearchRefreshJobStatus;
import com.openscholar.jobs.ResearchRefreshJobType;
import com.openscholar.paper.PaperNotFoundException;
import com.openscholar.search.CacheDisposition;
import com.openscholar.search.SearchCoordinationInterruptedException;
import com.openscholar.search.SearchCoordinationTimeoutException;
import com.openscholar.search.SearchDeadlineExceededException;
import com.openscholar.search.SearchExecutionInterruptedException;
import com.openscholar.search.SearchNotFoundException;
import com.openscholar.search.SearchRefreshUseCase;
import com.openscholar.search.SearchUnavailableException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "openscholar.jobs.refresh", name = "worker-enabled", havingValue = "true")
class ResearchRefreshJobWorker {

	private static final Logger LOGGER = LoggerFactory.getLogger(ResearchRefreshJobWorker.class);

	private final ResearchRefreshJobStore store;
	private final ResearchRefreshJobProperties properties;
	private final SearchRefreshUseCase searchRefresh;
	private final PaperAccessUseCase paperAccess;
	private final ResearchRefreshJobMetrics metrics;
	private final Clock clock;

	ResearchRefreshJobWorker(
			ResearchRefreshJobStore store,
			ResearchRefreshJobProperties properties,
			SearchRefreshUseCase searchRefresh,
			PaperAccessUseCase paperAccess,
			ResearchRefreshJobMetrics metrics,
			Clock clock) {
		this.store = store;
		this.properties = properties;
		this.searchRefresh = searchRefresh;
		this.paperAccess = paperAccess;
		this.metrics = metrics;
		this.clock = clock;
	}

	@Scheduled(fixedDelayString = "${openscholar.jobs.refresh.poll-delay:5s}")
	void poll() {
		runAvailable(properties.jobsPerPoll());
	}

	int runAvailable(int maximumJobs) {
		int completed = 0;
		while (completed < maximumJobs) {
			var claimed = store.claim(clock.instant(), properties.leaseDuration());
			if (claimed.isEmpty()) {
				break;
			}
			execute(claimed.orElseThrow());
			completed++;
		}
		return completed;
	}

	private void execute(ResearchRefreshJobStore.ClaimedResearchRefreshJob claimed) {
		Instant startedAt = clock.instant();
		try {
			switch (claimed.job().jobType()) {
				case PAPER_ACCESS -> {
					var result = paperAccess.resolve(claimed.job().targetId(), false);
					if (result.disposition() == AccessDisposition.STALE_FALLBACK) {
						throw new StaleRefreshFallbackException(claimed.job().jobType());
					}
				}
				case SEARCH_METADATA -> {
					var result = searchRefresh.refresh(claimed.job().targetId());
					if (result.cacheDisposition() == CacheDisposition.STALE_FALLBACK) {
						throw new StaleRefreshFallbackException(claimed.job().jobType());
					}
				}
			}
			if (store.succeed(claimed.job().id(), claimed.leaseToken(), clock.instant())) {
				metrics.completed(claimed.job().jobType(), ResearchRefreshJobStatus.SUCCEEDED,
						Duration.between(startedAt, clock.instant()));
			}
			else {
				LOGGER.warn("Refresh job completion lost its lease: jobId={}", claimed.job().id());
			}
		}
		catch (RuntimeException exception) {
			JobFailure failure = classify(exception);
			Duration retryDelay = retryDelay(claimed.job().attemptCount());
			boolean updated = store.fail(
					claimed,
					failure.code(),
					failure.detail(),
					failure.retryable(),
					clock.instant(),
					retryDelay);
			if (!updated) {
				LOGGER.warn("Refresh job failure lost its lease: jobId={}", claimed.job().id());
				return;
			}
			if (!failure.retryable() || claimed.job().attemptCount() >= claimed.job().maxAttempts()) {
				metrics.completed(claimed.job().jobType(), ResearchRefreshJobStatus.FAILED,
						Duration.between(startedAt, clock.instant()));
			}
		}
	}

	private Duration retryDelay(int attemptCount) {
		long multiplier = 1L << Math.min(Math.max(0, attemptCount - 1), 9);
		return properties.retryBackoff().multipliedBy(multiplier);
	}

	private static JobFailure classify(RuntimeException exception) {
		if (exception instanceof StaleRefreshFallbackException fallback) {
			return switch (fallback.jobType()) {
				case SEARCH_METADATA -> retryable(
						"SEARCH_REFRESH_STALE_FALLBACK",
						"Research providers returned only a stale metadata fallback.");
				case PAPER_ACCESS -> retryable(
						"ACCESS_REFRESH_STALE_FALLBACK",
						"Access providers returned only a stale access fallback.");
			};
		}
		if (exception instanceof PaperNotFoundException || exception instanceof SearchNotFoundException) {
			return new JobFailure("REFRESH_TARGET_NOT_FOUND", "The refresh target no longer exists.", false);
		}
		if (exception instanceof SearchUnavailableException unavailable) {
			return new JobFailure("SEARCH_PROVIDER_UNAVAILABLE",
					"Research providers could not complete the metadata refresh.", unavailable.retryable());
		}
		if (exception instanceof AccessUnavailableException unavailable) {
			return new JobFailure("ACCESS_PROVIDERS_UNAVAILABLE",
					"Access providers could not complete the access refresh.", unavailable.retryable());
		}
		if (exception instanceof SearchDeadlineExceededException) {
			return retryable("SEARCH_DEADLINE_EXCEEDED", "The metadata refresh exceeded its deadline.");
		}
		if (exception instanceof SearchExecutionInterruptedException
				|| exception instanceof SearchCoordinationInterruptedException) {
			return retryable("SEARCH_EXECUTION_INTERRUPTED", "The metadata refresh was interrupted.");
		}
		if (exception instanceof SearchCoordinationTimeoutException) {
			return retryable("SEARCH_COORDINATION_TIMEOUT", "The metadata refresh could not acquire coordination.");
		}
		LOGGER.error("Unexpected durable refresh job failure: exceptionType={}", exception.getClass().getName());
		return new JobFailure("REFRESH_JOB_FAILED", "The refresh job could not complete safely.", false);
	}

	private static JobFailure retryable(String code, String detail) {
		return new JobFailure(code, detail, true);
	}

	private record JobFailure(String code, String detail, boolean retryable) {
	}

	private static final class StaleRefreshFallbackException extends RuntimeException {

		private final ResearchRefreshJobType jobType;

		private StaleRefreshFallbackException(ResearchRefreshJobType jobType) {
			super("Refresh completed with stale fallback data");
			this.jobType = jobType;
		}

		private ResearchRefreshJobType jobType() {
			return jobType;
		}
	}
}
