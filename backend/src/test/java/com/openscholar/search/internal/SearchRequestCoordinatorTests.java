package com.openscholar.search.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import com.openscholar.search.SearchCoordinationInterruptedException;
import com.openscholar.search.SearchCoordinationTimeoutException;
import org.junit.jupiter.api.Test;

class SearchRequestCoordinatorTests {

	@Test
	void timesOutWithoutInvokingTheWaitingSupplierAndReusesTheStripeAfterRelease() throws Exception {
		SearchRequestCoordinator coordinator = coordinator(Duration.ofMillis(50));
		String leaderFingerprint = "leader";
		String collidingFingerprint = fingerprintOnSameStripe(coordinator, leaderFingerprint);
		CountDownLatch leaderEntered = new CountDownLatch(1);
		CountDownLatch releaseLeader = new CountDownLatch(1);
		AtomicBoolean waitingSupplierInvoked = new AtomicBoolean();
		ExecutorService executor = Executors.newSingleThreadExecutor();
		Future<String> leader = executor.submit(() -> coordinator.execute(leaderFingerprint, () -> {
			leaderEntered.countDown();
			await(releaseLeader);
			return "leader-result";
		}));

		try {
			assertThat(leaderEntered.await(5, TimeUnit.SECONDS)).isTrue();
			assertThatThrownBy(() -> coordinator.execute(collidingFingerprint, () -> {
				waitingSupplierInvoked.set(true);
				return "unexpected";
			}))
					.isInstanceOf(SearchCoordinationTimeoutException.class)
					.hasMessage("Search coordination wait timed out");
			assertThat(waitingSupplierInvoked).isFalse();

			releaseLeader.countDown();
			assertThat(leader.get(5, TimeUnit.SECONDS)).isEqualTo("leader-result");
			assertThat(coordinator.execute(collidingFingerprint, () -> "reused"))
					.isEqualTo("reused");
		}
		finally {
			releaseLeader.countDown();
			executor.shutdownNow();
		}
	}

	@Test
	void waitingCallerSucceedsWhenTheStripeBecomesAvailableBeforeTheLimit() throws Exception {
		SearchRequestCoordinator coordinator = coordinator(Duration.ofSeconds(2));
		CountDownLatch leaderEntered = new CountDownLatch(1);
		CountDownLatch releaseLeader = new CountDownLatch(1);
		CountDownLatch followerReady = new CountDownLatch(1);
		ExecutorService executor = Executors.newFixedThreadPool(2);
		Future<String> leader = executor.submit(() -> coordinator.execute("shared", () -> {
			leaderEntered.countDown();
			await(releaseLeader);
			return "leader-result";
		}));

		try {
			assertThat(leaderEntered.await(5, TimeUnit.SECONDS)).isTrue();
			Future<String> follower = executor.submit(() -> {
				followerReady.countDown();
				return coordinator.execute("shared", () -> "follower-result");
			});
			assertThat(followerReady.await(5, TimeUnit.SECONDS)).isTrue();

			releaseLeader.countDown();

			assertThat(leader.get(5, TimeUnit.SECONDS)).isEqualTo("leader-result");
			assertThat(follower.get(5, TimeUnit.SECONDS)).isEqualTo("follower-result");
		}
		finally {
			releaseLeader.countDown();
			executor.shutdownNow();
		}
	}

	@Test
	void preInterruptedCallerGetsTheDistinctFailureWithItsInterruptFlagRestored() throws Exception {
		SearchRequestCoordinator coordinator = coordinator(Duration.ofSeconds(1));
		AtomicBoolean supplierInvoked = new AtomicBoolean();
		ExecutorService executor = Executors.newSingleThreadExecutor();

		try {
			Future<InterruptedOutcome> outcome = executor.submit(() -> {
				Thread.currentThread().interrupt();
				try {
					coordinator.execute("pre-interrupted", () -> {
						supplierInvoked.set(true);
						return "unexpected";
					});
					throw new AssertionError("Expected coordination to reject the interrupted caller");
				}
				catch (SearchCoordinationInterruptedException exception) {
					return new InterruptedOutcome(exception, Thread.currentThread().isInterrupted());
				}
				finally {
					Thread.interrupted();
				}
			});

			InterruptedOutcome result = outcome.get(5, TimeUnit.SECONDS);
			assertThat(result.failure())
					.hasMessage("Search coordination wait was interrupted")
					.hasCauseInstanceOf(InterruptedException.class);
			assertThat(result.interruptRestored()).isTrue();
			assertThat(supplierInvoked).isFalse();
		}
		finally {
			executor.shutdownNow();
		}
	}

	private static SearchRequestCoordinator coordinator(Duration timeout) {
		SearchProperties properties = new SearchProperties();
		properties.setCoordinationWaitTimeout(timeout);
		return new SearchRequestCoordinator(properties);
	}

	private static String fingerprintOnSameStripe(
			SearchRequestCoordinator coordinator, String fingerprint) {
		int stripe = coordinator.stripeIndex(fingerprint);
		for (int candidate = 0; candidate < 10_000; candidate++) {
			String value = "collision-" + candidate;
			if (!value.equals(fingerprint) && coordinator.stripeIndex(value) == stripe) {
				return value;
			}
		}
		throw new AssertionError("Could not find a fingerprint on the same coordinator stripe");
	}

	private static void await(CountDownLatch latch) {
		try {
			if (!latch.await(5, TimeUnit.SECONDS)) {
				throw new AssertionError("Test latch was not released");
			}
		}
		catch (InterruptedException interrupted) {
			Thread.currentThread().interrupt();
			throw new AssertionError("Test thread was interrupted", interrupted);
		}
	}

	private record InterruptedOutcome(
			SearchCoordinationInterruptedException failure, boolean interruptRestored) {
	}
}
