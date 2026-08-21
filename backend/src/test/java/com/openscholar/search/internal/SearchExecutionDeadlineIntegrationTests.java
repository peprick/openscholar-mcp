package com.openscholar.search.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import com.openscholar.TestcontainersConfiguration;
import com.openscholar.provider.ProviderId;
import com.openscholar.provider.ProviderSearchQuery;
import com.openscholar.provider.ProviderSearchResult;
import com.openscholar.provider.ResearchProvider;
import com.openscholar.search.CacheDisposition;
import com.openscholar.search.SearchCommand;
import com.openscholar.search.SearchDeadlineExceededException;
import com.openscholar.search.SearchResearchUseCase;
import com.openscholar.search.SearchView;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;

@Import({
	TestcontainersConfiguration.class,
	SearchExecutionDeadlineIntegrationTests.DeadlineProviderConfiguration.class
})
@SpringBootTest(properties = {
	"openscholar.search.cache-ttl=1h",
	"openscholar.search.execution-timeout=3s"
})
class SearchExecutionDeadlineIntegrationTests {

	private static final Duration TEST_WAIT = Duration.ofSeconds(5);

	@Autowired
	private SearchResearchUseCase searchUseCase;

	@Autowired
	private QueryFingerprinter fingerprinter;

	@Autowired
	private DeadlineResearchProvider provider;

	@Autowired
	private ObservingSearchRequestCoordinator requestCoordinator;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@BeforeEach
	void resetProviderAndWarmDatabase() {
		provider.reset();
		requestCoordinator.reset();
		assertThat(jdbcTemplate.queryForObject("SELECT 1", Integer.class)).isOne();
	}

	@AfterEach
	void releaseProviderForCleanup() throws InterruptedException {
		provider.releaseForCleanup();
	}

	@Test
	void deadlineCancelsBlockedProviderWithoutSnapshotAndSameQueryCanRetry() throws Exception {
		SearchCommand command = command("deadline retry " + UUID.randomUUID());
		String fingerprint = fingerprinter.fingerprint(command);
		ExecutorService requester = Executors.newSingleThreadExecutor();

		try {
			Future<SearchView> blocked = requester.submit(() -> searchUseCase.search(command));
			assertThat(provider.awaitFirstEntered(TEST_WAIT)).isTrue();

			assertDeadlineFailure(blocked);
			assertThat(provider.awaitFirstInterrupted(TEST_WAIT)).isTrue();
			assertThat(provider.awaitFirstExited(TEST_WAIT)).isTrue();
			assertThat(provider.interruptObserved()).isTrue();
			assertThat(provider.activeCalls()).isZero();
			assertThat(snapshotCount(fingerprint)).isZero();

			SearchView retry = searchUseCase.search(command);

			assertThat(retry.cacheDisposition()).isEqualTo(CacheDisposition.MISS_FETCHED);
			assertThat(retry.warnings()).doesNotContain("SEARCH_COORDINATION_TIMEOUT");
			assertThat(provider.calls()).isEqualTo(2);
			assertThat(snapshotCount(fingerprint)).isOne();
		}
		finally {
			requester.shutdownNow();
			provider.releaseForCleanup();
			awaitTermination(requester);
		}
	}

	@Test
	void timedOutLeaderReleasesSameKeyCoordinationForWaitingFollower() throws Exception {
		SearchCommand command = command("deadline coordination handoff " + UUID.randomUUID());
		String fingerprint = fingerprinter.fingerprint(command);
		ExecutorService requesters = Executors.newFixedThreadPool(2);

		try {
			Future<SearchView> leader = requesters.submit(() -> searchUseCase.search(command));
			assertThat(provider.awaitFirstEntered(TEST_WAIT)).isTrue();
			assertThatThrownBy(() -> leader.get(1, TimeUnit.SECONDS))
					.isInstanceOf(TimeoutException.class);

			Future<SearchView> follower = requesters.submit(() -> searchUseCase.search(command));
			assertThat(requestCoordinator.awaitSecondExecution(TEST_WAIT)).isTrue();
			assertThat(follower).isNotDone();
			assertThat(provider.calls()).isOne();

			assertDeadlineFailure(leader);
			assertThat(provider.awaitFirstInterrupted(TEST_WAIT)).isTrue();
			assertThat(provider.awaitFirstExited(TEST_WAIT)).isTrue();
			SearchView recovered = follower.get(TEST_WAIT.toMillis(), TimeUnit.MILLISECONDS);

			assertThat(recovered.cacheDisposition()).isEqualTo(CacheDisposition.MISS_FETCHED);
			assertThat(recovered.warnings()).doesNotContain("SEARCH_COORDINATION_TIMEOUT");
			assertThat(provider.calls()).isEqualTo(2);
			assertThat(provider.activeCalls()).isZero();
			assertThat(snapshotCount(fingerprint)).isOne();
		}
		finally {
			requesters.shutdownNow();
			provider.releaseForCleanup();
			awaitTermination(requesters);
		}
	}

	private static void awaitTermination(ExecutorService executor) throws InterruptedException {
		if (!executor.awaitTermination(TEST_WAIT.toMillis(), TimeUnit.MILLISECONDS)) {
			throw new IllegalStateException("Search deadline test requester did not terminate");
		}
	}

	private static void assertDeadlineFailure(Future<SearchView> result) {
		assertThatThrownBy(() -> result.get(TEST_WAIT.toMillis(), TimeUnit.MILLISECONDS))
				.isInstanceOf(ExecutionException.class)
				.hasCauseInstanceOf(SearchDeadlineExceededException.class);
	}

	private long snapshotCount(String fingerprint) {
		Long count = jdbcTemplate.queryForObject(
				"SELECT count(*) FROM search_snapshot WHERE fingerprint = ?",
				Long.class,
				fingerprint);
		return count == null ? 0 : count;
	}

	private static SearchCommand command(String query) {
		return new SearchCommand(
				query,
				null,
				null,
				Set.of(),
				false,
				0,
				Set.of(),
				10,
				"*",
				false);
	}

	@TestConfiguration(proxyBeanMethods = false)
	static class DeadlineProviderConfiguration {

		@Bean
		@Primary
		DeadlineResearchProvider deadlineResearchProvider(Clock clock) {
			return new DeadlineResearchProvider(clock);
		}

		@Bean
		@Primary
		ObservingSearchRequestCoordinator observingSearchRequestCoordinator(SearchProperties properties) {
			return new ObservingSearchRequestCoordinator(properties);
		}
	}

	static final class ObservingSearchRequestCoordinator extends SearchRequestCoordinator {

		private final AtomicInteger executions = new AtomicInteger();
		private volatile CountDownLatch secondExecution = new CountDownLatch(1);

		ObservingSearchRequestCoordinator(SearchProperties properties) {
			super(properties);
		}

		@Override
		<T> T execute(String fingerprint, java.util.function.Supplier<T> request) {
			if (executions.incrementAndGet() == 2) {
				secondExecution.countDown();
			}
			return super.execute(fingerprint, request);
		}

		void reset() {
			executions.set(0);
			secondExecution = new CountDownLatch(1);
		}

		boolean awaitSecondExecution(Duration timeout) throws InterruptedException {
			return secondExecution.await(timeout.toMillis(), TimeUnit.MILLISECONDS);
		}
	}

	static final class DeadlineResearchProvider implements ResearchProvider {

		private static final String CLEANUP_MESSAGE = "First provider call was released only for test cleanup";

		private final Clock clock;
		private final AtomicInteger calls = new AtomicInteger();
		private final AtomicInteger activeCalls = new AtomicInteger();
		private volatile Invocation invocation = new Invocation();

		DeadlineResearchProvider(Clock clock) {
			this.clock = clock;
		}

		@Override
		public ProviderId id() {
			return ProviderId.OPENALEX;
		}

		@Override
		public ProviderSearchResult search(ProviderSearchQuery query) {
			int call = calls.incrementAndGet();
			if (call == 1) {
				blockFirstCall();
			}
			return new ProviderSearchResult(ProviderId.OPENALEX, List.of(), 0, null, clock.instant());
		}

		private void blockFirstCall() {
			Invocation current = invocation;
			activeCalls.incrementAndGet();
			current.entered().countDown();
			try {
				current.release().await();
				throw new IllegalStateException(CLEANUP_MESSAGE);
			}
			catch (InterruptedException interrupted) {
				current.interruptObserved().set(true);
				current.interrupted().countDown();
				Thread.currentThread().interrupt();
				throw new CancellationException("Provider call interrupted by search deadline");
			}
			finally {
				activeCalls.decrementAndGet();
				current.exited().countDown();
			}
		}

		void reset() {
			if (activeCalls.get() != 0) {
				throw new IllegalStateException("Previous provider invocation is still active");
			}
			calls.set(0);
			invocation = new Invocation();
		}

		boolean awaitFirstEntered(Duration timeout) throws InterruptedException {
			return invocation.entered().await(timeout.toMillis(), TimeUnit.MILLISECONDS);
		}

		boolean awaitFirstInterrupted(Duration timeout) throws InterruptedException {
			return invocation.interrupted().await(timeout.toMillis(), TimeUnit.MILLISECONDS);
		}

		boolean awaitFirstExited(Duration timeout) throws InterruptedException {
			return invocation.exited().await(timeout.toMillis(), TimeUnit.MILLISECONDS);
		}

		void releaseForCleanup() throws InterruptedException {
			Invocation current = invocation;
			current.release().countDown();
			if (current.entered().getCount() == 0
					&& !current.exited().await(TEST_WAIT.toMillis(), TimeUnit.MILLISECONDS)) {
				throw new IllegalStateException("Deadline provider did not exit during cleanup");
			}
		}

		boolean interruptObserved() {
			return invocation.interruptObserved().get();
		}

		int calls() {
			return calls.get();
		}

		int activeCalls() {
			return activeCalls.get();
		}
	}

	private record Invocation(
			CountDownLatch entered,
			CountDownLatch release,
			CountDownLatch interrupted,
			CountDownLatch exited,
			AtomicBoolean interruptObserved) {

		private Invocation() {
			this(
					new CountDownLatch(1),
					new CountDownLatch(1),
					new CountDownLatch(1),
					new CountDownLatch(1),
					new AtomicBoolean());
		}
	}
}
