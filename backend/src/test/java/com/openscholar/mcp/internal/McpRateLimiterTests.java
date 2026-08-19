package com.openscholar.mcp.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

class McpRateLimiterTests {

	@Test
	void enforcesTheConfiguredLimitAndRoundsRetryAfterUpToWholeSeconds() {
		MutableClock clock = new MutableClock(Instant.parse("2026-08-19T00:00:00Z"));
		McpRateLimiter limiter = limiter(true, 2, Duration.ofMillis(1_500), 100, clock);

		assertThat(limiter.acquire("192.0.2.1").permitted()).isTrue();
		assertThat(limiter.acquire("192.0.2.1").permitted()).isTrue();
		McpRateLimiter.Decision rejected = limiter.acquire("192.0.2.1");

		assertThat(rejected.permitted()).isFalse();
		assertThat(rejected.retryAfterSeconds()).isEqualTo(2);
	}

	@Test
	void resetsAClientBudgetAfterItsWindowExpires() {
		MutableClock clock = new MutableClock(Instant.parse("2026-08-19T00:00:00Z"));
		McpRateLimiter limiter = limiter(true, 1, Duration.ofSeconds(10), 100, clock);

		assertThat(limiter.acquire("192.0.2.1").permitted()).isTrue();
		assertThat(limiter.acquire("192.0.2.1").permitted()).isFalse();
		clock.advance(Duration.ofSeconds(10));

		assertThat(limiter.acquire("192.0.2.1").permitted()).isTrue();
	}

	@Test
	void isolatesClientBudgetsAndBoundsTrackedClientState() {
		MutableClock clock = new MutableClock(Instant.parse("2026-08-19T00:00:00Z"));
		McpRateLimiter limiter = limiter(true, 1, Duration.ofSeconds(10), 1, clock);

		assertThat(limiter.acquire("192.0.2.1").permitted()).isTrue();
		assertThat(limiter.acquire("192.0.2.2").permitted()).isFalse();
		clock.advance(Duration.ofSeconds(10));

		assertThat(limiter.acquire("192.0.2.2").permitted()).isTrue();
	}

	@Test
	void bypassesRateLimitingWhenDisabled() {
		MutableClock clock = new MutableClock(Instant.parse("2026-08-19T00:00:00Z"));
		McpRateLimiter limiter = limiter(false, 1, Duration.ofMinutes(1), 1, clock);

		for (int request = 0; request < 10; request++) {
			assertThat(limiter.acquire("192.0.2." + request).permitted()).isTrue();
		}
	}

	@Test
	void keepsTheTrackedClientLimitAtomicDuringConcurrentAcquisitionAndExpiry() throws Exception {
		MutableClock clock = new MutableClock(Instant.parse("2026-08-19T00:00:00Z"));
		McpRateLimiter limiter = limiter(true, 1, Duration.ofMillis(1), 8, clock);

		assertThat(acquireConcurrently(limiter, "first", 64)).isEqualTo(8);
		clock.advance(Duration.ofMillis(1));
		assertThat(acquireConcurrently(limiter, "second", 64)).isEqualTo(8);
	}

	private static long acquireConcurrently(McpRateLimiter limiter, String prefix, int clientCount)
			throws Exception {
		ExecutorService executor = Executors.newFixedThreadPool(16);
		CountDownLatch start = new CountDownLatch(1);
		List<Future<McpRateLimiter.Decision>> futures = new ArrayList<>();
		try {
			for (int client = 0; client < clientCount; client++) {
				String clientIdentifier = prefix + '-' + client;
				futures.add(executor.submit(() -> {
					start.await();
					return limiter.acquire(clientIdentifier);
				}));
			}
			start.countDown();

			long permitted = 0;
			for (Future<McpRateLimiter.Decision> future : futures) {
				if (future.get(5, TimeUnit.SECONDS).permitted()) {
					permitted++;
				}
			}
			return permitted;
		}
		finally {
			executor.shutdownNow();
		}
	}

	private static McpRateLimiter limiter(
			boolean enabled,
			int requestsPerWindow,
			Duration window,
			int maximumTrackedClients,
			Clock clock) {
		return new McpRateLimiter(
				new McpRateLimitProperties(
						enabled, requestsPerWindow, window, maximumTrackedClients),
				clock);
	}

	private static final class MutableClock extends Clock {

		private Instant current;

		private MutableClock(Instant current) {
			this.current = current;
		}

		private void advance(Duration duration) {
			current = current.plus(duration);
		}

		@Override
		public ZoneId getZone() {
			return ZoneOffset.UTC;
		}

		@Override
		public Clock withZone(ZoneId zone) {
			return this;
		}

		@Override
		public Instant instant() {
			return current;
		}
	}
}
