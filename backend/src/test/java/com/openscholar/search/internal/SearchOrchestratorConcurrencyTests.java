package com.openscholar.search.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import com.openscholar.TestcontainersConfiguration;
import com.openscholar.paper.DocumentType;
import com.openscholar.provider.ProviderAuthor;
import com.openscholar.provider.ProviderId;
import com.openscholar.provider.ProviderPaperRecord;
import com.openscholar.provider.ProviderSearchQuery;
import com.openscholar.provider.ProviderSearchResult;
import com.openscholar.provider.ResearchProvider;
import com.openscholar.search.CacheDisposition;
import com.openscholar.search.SearchCommand;
import com.openscholar.search.SearchResearchUseCase;
import com.openscholar.search.SearchView;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;

@Import({TestcontainersConfiguration.class, SearchOrchestratorConcurrencyTests.FakeProviderConfiguration.class})
@SpringBootTest(properties = "openscholar.search.cache-ttl=1h")
class SearchOrchestratorConcurrencyTests {

	@Autowired
	private SearchResearchUseCase searchUseCase;

	@Autowired
	private QueryFingerprinter fingerprinter;

	@Autowired
	private SearchRequestCoordinator requestCoordinator;

	@Autowired
	private BlockingResearchProvider provider;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@BeforeEach
	void resetProvider() {
		provider.reset();
	}

	@Test
	void coalescesConcurrentNormalSearchesWithTheSameFingerprint() throws Exception {
		SearchCommand command = command("coalesced search " + UUID.randomUUID(), false);
		String fingerprint = fingerprinter.fingerprint(command);
		provider.blockNextCalls(1);

		try (ConcurrentSearchRun run = submitConcurrently(command, command)) {
			assertThat(provider.awaitBlockedCalls(Duration.ofSeconds(5))).isTrue();
			provider.releaseCalls();

			List<SearchView> views = run.results();
			assertThat(provider.calls()).isEqualTo(1);
			assertThat(views).extracting(SearchView::searchId).containsOnly(views.getFirst().searchId());
			assertThat(views).extracting(SearchView::cacheDisposition)
					.containsExactlyInAnyOrder(CacheDisposition.MISS_FETCHED, CacheDisposition.EXACT_HIT);
			assertThat(snapshotCount(fingerprint)).isEqualTo(1);
		}
		finally {
			provider.releaseCalls();
		}
	}

	@Test
	void differentFingerprintStripesReachTheProviderWithoutSerializing() throws Exception {
		SearchCommand first = command("parallel search first " + UUID.randomUUID(), false);
		SearchCommand second = commandOnAnotherStripe(first);
		String firstFingerprint = fingerprinter.fingerprint(first);
		String secondFingerprint = fingerprinter.fingerprint(second);
		provider.blockNextCalls(2);

		boolean bothReachedProvider;
		try (ConcurrentSearchRun run = submitConcurrently(first, second)) {
			bothReachedProvider = provider.awaitBlockedCalls(Duration.ofSeconds(5));
			provider.releaseCalls();

			List<SearchView> views = run.results();
			assertThat(provider.calls()).isEqualTo(2);
			assertThat(views).extracting(SearchView::searchId).doesNotHaveDuplicates();
			assertThat(snapshotCount(firstFingerprint)).isEqualTo(1);
			assertThat(snapshotCount(secondFingerprint)).isEqualTo(1);
		}
		finally {
			provider.releaseCalls();
		}
		assertThat(bothReachedProvider).isTrue();
	}

	@Test
	void concurrentForceRefreshesRemainExplicitProviderFetches() throws Exception {
		String query = "explicit force refresh " + UUID.randomUUID();
		SearchView initial = searchUseCase.search(command(query, false));
		SearchCommand forceRefresh = command(query, true);
		String fingerprint = fingerprinter.fingerprint(forceRefresh);
		provider.blockNextCalls(1);

		try (ConcurrentSearchRun run = submitConcurrently(forceRefresh, forceRefresh)) {
			assertThat(provider.awaitBlockedCalls(Duration.ofSeconds(5))).isTrue();
			provider.releaseCalls();

			List<SearchView> refreshed = run.results();
			assertThat(provider.calls()).isEqualTo(3);
			assertThat(refreshed).extracting(SearchView::cacheDisposition)
					.containsOnly(CacheDisposition.FORCED_REFRESH);
			assertThat(refreshed).extracting(SearchView::searchId)
					.doesNotContain(initial.searchId())
					.doesNotHaveDuplicates();
			assertThat(snapshotCount(fingerprint)).isEqualTo(3);
		}
		finally {
			provider.releaseCalls();
		}
	}

	private SearchCommand commandOnAnotherStripe(SearchCommand first) {
		int firstStripe = requestCoordinator.stripeIndex(fingerprinter.fingerprint(first));
		for (int candidate = 0; candidate < 1_000; candidate++) {
			SearchCommand command = command("parallel search second " + candidate + " " + UUID.randomUUID(), false);
			if (requestCoordinator.stripeIndex(fingerprinter.fingerprint(command)) != firstStripe) {
				return command;
			}
		}
		throw new IllegalStateException("Could not create a search on another coordinator stripe");
	}

	private ConcurrentSearchRun submitConcurrently(SearchCommand first, SearchCommand second) throws Exception {
		ExecutorService executor = Executors.newFixedThreadPool(2);
		CountDownLatch ready = new CountDownLatch(2);
		CountDownLatch start = new CountDownLatch(1);
		Future<SearchView> firstResult = executor.submit(() -> executeAfterStart(first, ready, start));
		Future<SearchView> secondResult = executor.submit(() -> executeAfterStart(second, ready, start));
		if (!ready.await(5, TimeUnit.SECONDS)) {
			executor.shutdownNow();
			throw new IllegalStateException("Concurrent search callers did not become ready");
		}
		start.countDown();
		return new ConcurrentSearchRun(executor, List.of(firstResult, secondResult));
	}

	private SearchView executeAfterStart(
			SearchCommand command, CountDownLatch ready, CountDownLatch start) throws Exception {
		ready.countDown();
		if (!start.await(5, TimeUnit.SECONDS)) {
			throw new IllegalStateException("Concurrent search start gate was not released");
		}
		return searchUseCase.search(command);
	}

	private long snapshotCount(String fingerprint) {
		Long count = jdbcTemplate.queryForObject(
				"SELECT count(*) FROM search_snapshot WHERE fingerprint = ?",
				Long.class,
				fingerprint);
		return count == null ? 0 : count;
	}

	private static SearchCommand command(String query, boolean forceRefresh) {
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
				forceRefresh);
	}

	private record ConcurrentSearchRun(ExecutorService executor, List<Future<SearchView>> futures)
			implements AutoCloseable {

		private List<SearchView> results() throws Exception {
			return List.of(
					futures.get(0).get(10, TimeUnit.SECONDS),
					futures.get(1).get(10, TimeUnit.SECONDS));
		}

		@Override
		public void close() {
			executor.shutdownNow();
		}
	}

	@TestConfiguration(proxyBeanMethods = false)
	static class FakeProviderConfiguration {

		@Bean
		@Primary
		BlockingResearchProvider blockingResearchProvider(Clock clock) {
			return new BlockingResearchProvider(clock);
		}
	}

	static final class BlockingResearchProvider implements ResearchProvider {

		private final Clock clock;
		private final AtomicInteger calls = new AtomicInteger();
		private volatile CallGate gate = CallGate.open();

		BlockingResearchProvider(Clock clock) {
			this.clock = clock;
		}

		@Override
		public ProviderId id() {
			return ProviderId.OPENALEX;
		}

		@Override
		public ProviderSearchResult search(ProviderSearchQuery query) {
			calls.incrementAndGet();
			CallGate currentGate = gate;
			currentGate.entered().countDown();
			try {
				if (!currentGate.release().await(10, TimeUnit.SECONDS)) {
					throw new IllegalStateException("Timed out waiting to release the fake provider");
				}
			}
			catch (InterruptedException interrupted) {
				Thread.currentThread().interrupt();
				throw new IllegalStateException("Fake provider call was interrupted", interrupted);
			}

			Instant retrievedAt = clock.instant();
			long queryKey = Integer.toUnsignedLong(query.query().hashCode());
			ProviderPaperRecord record = new ProviderPaperRecord(
					ProviderId.OPENALEX,
					"W9" + queryKey,
					"10.1000/concurrency." + queryKey,
					null,
					"Concurrent search fixture",
					"A deterministic provider record used for concurrency testing.",
					LocalDate.of(2025, 1, 1),
					2025,
					DocumentType.ARTICLE,
					"en",
					"OpenScholar Test Journal",
					1,
					List.of(new ProviderAuthor("A9" + queryKey, "Test Researcher", null, 1, false)),
					true,
					URI.create("https://example.org/papers/" + queryKey),
					URI.create("https://example.org/papers/" + queryKey + ".pdf"),
					1.0,
					retrievedAt,
					Map.of("fixture", "concurrency"));
			return new ProviderSearchResult(ProviderId.OPENALEX, List.of(record), 1, null, retrievedAt);
		}

		void reset() {
			calls.set(0);
			gate = CallGate.open();
		}

		void blockNextCalls(int expectedCalls) {
			gate = CallGate.blocking(expectedCalls);
		}

		boolean awaitBlockedCalls(Duration timeout) throws InterruptedException {
			return gate.entered().await(timeout.toMillis(), TimeUnit.MILLISECONDS);
		}

		void releaseCalls() {
			gate.release().countDown();
		}

		int calls() {
			return calls.get();
		}
	}

	private record CallGate(CountDownLatch entered, CountDownLatch release) {

		private static CallGate open() {
			return new CallGate(new CountDownLatch(0), new CountDownLatch(0));
		}

		private static CallGate blocking(int expectedCalls) {
			return new CallGate(new CountDownLatch(expectedCalls), new CountDownLatch(1));
		}
	}
}
