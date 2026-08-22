package com.openscholar.jobs.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.openscholar.access.AccessDisposition;
import com.openscholar.access.AccessStatus;
import com.openscholar.access.PaperAccessUseCase;
import com.openscholar.access.PaperAccessView;
import com.openscholar.jobs.ResearchRefreshJobStatus;
import com.openscholar.jobs.ResearchRefreshJobTrigger;
import com.openscholar.jobs.ResearchRefreshJobType;
import com.openscholar.jobs.ResearchRefreshJobView;
import com.openscholar.paper.PaperNotFoundException;
import com.openscholar.search.CacheDisposition;
import com.openscholar.search.SearchRefreshUseCase;
import com.openscholar.search.SearchUnavailableException;
import com.openscholar.search.SearchView;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ResearchRefreshJobWorkerTests {

	private static final Instant NOW = Instant.parse("2026-08-21T10:00:00Z");

	private FakeStore store;
	private FakeSearchRefresh searchRefresh;
	private FakePaperAccess paperAccess;
	private SimpleMeterRegistry meterRegistry;
	private ResearchRefreshJobWorker worker;

	@BeforeEach
	void setUp() {
		store = new FakeStore();
		searchRefresh = new FakeSearchRefresh();
		paperAccess = new FakePaperAccess();
		meterRegistry = new SimpleMeterRegistry();
		worker = new ResearchRefreshJobWorker(
				store,
				new ResearchRefreshJobProperties(
						true, false, Duration.ofSeconds(1), Duration.ofHours(1), Duration.ofMinutes(2),
						Duration.ofSeconds(30), 3, 25, 100),
				searchRefresh,
				paperAccess,
				new ResearchRefreshJobMetrics(meterRegistry),
				Clock.fixed(NOW, ZoneOffset.UTC));
	}

	@Test
	void completesSearchAndAccessJobs() {
		var search = claimed(ResearchRefreshJobType.SEARCH_METADATA, 1, 3);
		var access = claimed(ResearchRefreshJobType.PAPER_ACCESS, 1, 3);
		store.claims.add(search);
		store.claims.add(access);

		assertThat(worker.runAvailable(3)).isEqualTo(2);

		assertThat(searchRefresh.refreshedTargets).containsExactly(search.job().targetId());
		assertThat(paperAccess.refreshedTargets).containsExactly(access.job().targetId());
		assertThat(store.succeeded).containsExactly(
				new Completion(search.job().id(), search.leaseToken()),
				new Completion(access.job().id(), access.leaseToken()));
		assertThat(meterRegistry.get("openscholar.refresh.jobs.completed")
				.tag("status", "SUCCEEDED")
				.tag("type", "SEARCH_METADATA").counter().count()).isEqualTo(1d);
		assertThat(meterRegistry.get("openscholar.refresh.jobs.completed")
				.tag("status", "SUCCEEDED")
				.tag("type", "PAPER_ACCESS").counter().count()).isEqualTo(1d);
	}

	@Test
	void requeuesRetryableProviderFailureWithoutRecordingATerminalOutcome() {
		var claimed = claimed(ResearchRefreshJobType.SEARCH_METADATA, 1, 3);
		store.claims.add(claimed);
		searchRefresh.failure = new SearchUnavailableException(
				"temporarily unavailable", true, Duration.ofSeconds(10), null);

		assertThat(worker.runAvailable(1)).isEqualTo(1);

		assertThat(store.failures).containsExactly(new Failure(
				claimed,
				"SEARCH_PROVIDER_UNAVAILABLE",
				"Research providers could not complete the metadata refresh.",
				true,
				NOW,
				Duration.ofSeconds(30)));
		assertThat(meterRegistry.find("openscholar.refresh.jobs.completed").counter()).isNull();
	}

	@Test
	void recordsMissingTargetsAsTerminalFailures() {
		var claimed = claimed(ResearchRefreshJobType.PAPER_ACCESS, 1, 3);
		store.claims.add(claimed);
		paperAccess.failure = new PaperNotFoundException(claimed.job().targetId());

		assertThat(worker.runAvailable(1)).isEqualTo(1);

		assertThat(store.failures).containsExactly(new Failure(
				claimed,
				"REFRESH_TARGET_NOT_FOUND",
				"The refresh target no longer exists.",
				false,
				NOW,
				Duration.ofSeconds(30)));
		assertThat(store.succeeded).isEmpty();
		assertThat(meterRegistry.get("openscholar.refresh.jobs.completed")
				.tag("status", "FAILED").counter().count()).isEqualTo(1d);
	}

	@Test
	void requeuesAStaleSearchFallbackUsingTheExistingRetryBudget() {
		var claimed = claimed(ResearchRefreshJobType.SEARCH_METADATA, 1, 3);
		store.claims.add(claimed);
		searchRefresh.result = new SearchView(
				claimed.job().targetId(),
				"durable refresh",
				"a".repeat(64),
				CacheDisposition.STALE_FALLBACK,
				NOW.minusSeconds(3_600),
				NOW.minusSeconds(1),
				null,
				List.of(),
				List.of("OPENALEX_UNAVAILABLE"),
				List.of());

		assertThat(worker.runAvailable(1)).isEqualTo(1);

		assertThat(store.failures).containsExactly(new Failure(
				claimed,
				"SEARCH_REFRESH_STALE_FALLBACK",
				"Research providers returned only a stale metadata fallback.",
				true,
				NOW,
				Duration.ofSeconds(30)));
		assertThat(store.succeeded).isEmpty();
	}

	@Test
	void requeuesAStaleAccessFallbackUsingTheExistingRetryBudget() {
		var claimed = claimed(ResearchRefreshJobType.PAPER_ACCESS, 1, 3);
		store.claims.add(claimed);
		paperAccess.result = new PaperAccessView(
				claimed.job().targetId(),
				AccessStatus.OPEN_PDF,
				AccessDisposition.STALE_FALLBACK,
				NOW.minusSeconds(3_600),
				NOW.minusSeconds(1),
				List.of(),
				List.of("UNPAYWALL_UNAVAILABLE"),
				List.of());

		assertThat(worker.runAvailable(1)).isEqualTo(1);

		assertThat(store.failures).containsExactly(new Failure(
				claimed,
				"ACCESS_REFRESH_STALE_FALLBACK",
				"Access providers returned only a stale access fallback.",
				true,
				NOW,
				Duration.ofSeconds(30)));
		assertThat(store.succeeded).isEmpty();
	}

	private static ResearchRefreshJobStore.ClaimedResearchRefreshJob claimed(
			ResearchRefreshJobType type, int attempt, int maximumAttempts) {
		UUID id = UUID.randomUUID();
		var view = new ResearchRefreshJobView(
				id,
				type,
				UUID.randomUUID(),
				ResearchRefreshJobTrigger.MANUAL,
				ResearchRefreshJobStatus.RUNNING,
				attempt,
				maximumAttempts,
				NOW,
				NOW.plusSeconds(120),
				null,
				null,
				NOW,
				NOW,
				null,
				NOW);
		return new ResearchRefreshJobStore.ClaimedResearchRefreshJob(view, UUID.randomUUID());
	}

	private static final class FakeStore extends ResearchRefreshJobStore {

		private final ArrayDeque<ResearchRefreshJobStore.ClaimedResearchRefreshJob> claims = new ArrayDeque<>();
		private final List<Completion> succeeded = new ArrayList<>();
		private final List<Failure> failures = new ArrayList<>();

		private FakeStore() {
			super(null);
		}

		@Override
		Optional<ClaimedResearchRefreshJob> claim(Instant now, Duration leaseDuration) {
			return Optional.ofNullable(claims.poll());
		}

		@Override
		boolean succeed(UUID jobId, UUID leaseToken, Instant now) {
			succeeded.add(new Completion(jobId, leaseToken));
			return true;
		}

		@Override
		boolean fail(
				ClaimedResearchRefreshJob claimed,
				String errorCode,
				String errorDetail,
				boolean retryable,
				Instant now,
				Duration retryDelay) {
			failures.add(new Failure(claimed, errorCode, errorDetail, retryable, now, retryDelay));
			return true;
		}
	}

	private static final class FakeSearchRefresh implements SearchRefreshUseCase {

		private final List<UUID> refreshedTargets = new ArrayList<>();
		private RuntimeException failure;
		private SearchView result;

		@Override
		public SearchView refresh(UUID searchId) {
			refreshedTargets.add(searchId);
			if (failure != null) {
				throw failure;
			}
			return result == null
					? new SearchView(
							searchId,
							"durable refresh",
							"a".repeat(64),
							CacheDisposition.FORCED_REFRESH,
							NOW,
							NOW.plusSeconds(3_600),
							null,
							List.of(),
							List.of(),
							List.of())
					: result;
		}
	}

	private static final class FakePaperAccess implements PaperAccessUseCase {

		private final List<UUID> refreshedTargets = new ArrayList<>();
		private RuntimeException failure;
		private PaperAccessView result;

		@Override
		public PaperAccessView get(UUID paperId) {
			return null;
		}

		@Override
		public PaperAccessView resolve(UUID paperId, boolean forceRefresh) {
			refreshedTargets.add(paperId);
			if (failure != null) {
				throw failure;
			}
			return result == null
					? new PaperAccessView(
							paperId,
							AccessStatus.OPEN_PDF,
							AccessDisposition.REFRESHED,
							NOW,
							NOW.plusSeconds(3_600),
							List.of(),
							List.of(),
							List.of())
					: result;
		}
	}

	private record Completion(UUID jobId, UUID leaseToken) {
	}

	private record Failure(
			ResearchRefreshJobStore.ClaimedResearchRefreshJob claimed,
			String errorCode,
			String errorDetail,
			boolean retryable,
			Instant now,
			Duration retryDelay) {
	}
}
