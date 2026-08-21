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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import com.openscholar.search.SearchDeadlineExceededException;
import com.openscholar.search.SearchExecutionInterruptedException;
import org.junit.jupiter.api.Test;
import org.springframework.core.task.SimpleAsyncTaskExecutor;

class SearchExecutionDeadlineTests {

	private static final Duration TEST_WAIT = Duration.ofSeconds(5);

	@Test
	void returnsValuesThatCompleteBeforeTheDeadline() {
		try (SimpleAsyncTaskExecutor executor = executor()) {
			SearchExecutionDeadline deadline = deadline(Duration.ofSeconds(1), executor);

			assertThat(deadline.execute(() -> "completed")).isEqualTo("completed");
		}
	}

	@Test
	void propagatesTheOriginalRuntimeFailureInstance() {
		try (SimpleAsyncTaskExecutor executor = executor()) {
			SearchExecutionDeadline deadline = deadline(Duration.ofSeconds(1), executor);
			IllegalStateException failure = new IllegalStateException("original failure");

			assertThatThrownBy(() -> deadline.execute(() -> {
				throw failure;
			})).isSameAs(failure);
		}
	}

	@Test
	void timeoutInterruptsTheWorkerAndRepeatedExecutionsLeaveNoActiveWork() throws Exception {
		try (SimpleAsyncTaskExecutor executor = executor()) {
			SearchExecutionDeadline deadline = deadline(Duration.ofSeconds(1), executor);
			AtomicInteger activeWorkers = new AtomicInteger();

			for (int invocation = 0; invocation < 3; invocation++) {
				CountDownLatch entered = new CountDownLatch(1);
				CountDownLatch exited = new CountDownLatch(1);
				AtomicBoolean interrupted = new AtomicBoolean();

				assertThatThrownBy(() -> deadline.execute(() -> blockingAction(
						activeWorkers, entered, exited, interrupted)))
						.isInstanceOf(SearchDeadlineExceededException.class)
						.hasMessage("Search execution deadline exceeded");

				assertThat(entered.await(TEST_WAIT.toMillis(), TimeUnit.MILLISECONDS)).isTrue();
				assertThat(exited.await(TEST_WAIT.toMillis(), TimeUnit.MILLISECONDS)).isTrue();
				assertThat(interrupted).isTrue();
				assertThat(activeWorkers).hasValue(0);
			}
		}
	}

	@Test
	void preInterruptedCallerGetsTheDistinctFailureAndRetainsItsInterruptFlag() throws Exception {
		try (SimpleAsyncTaskExecutor executionExecutor = executor()) {
			SearchExecutionDeadline deadline = deadline(Duration.ofSeconds(1), executionExecutor);
			ExecutorService callerExecutor = Executors.newSingleThreadExecutor();
			AtomicBoolean supplierInvoked = new AtomicBoolean();

			try {
				Future<InterruptedOutcome> outcome = callerExecutor.submit(() -> {
					Thread.currentThread().interrupt();
					try {
						deadline.execute(() -> {
							supplierInvoked.set(true);
							return "unexpected";
						});
						throw new AssertionError("Expected interrupted search execution");
					}
					catch (SearchExecutionInterruptedException exception) {
						return new InterruptedOutcome(exception, Thread.currentThread().isInterrupted());
					}
					finally {
						Thread.interrupted();
					}
				});

				InterruptedOutcome result = outcome.get(TEST_WAIT.toMillis(), TimeUnit.MILLISECONDS);
				assertThat(result.failure())
						.hasMessage("Search execution was interrupted")
						.hasCauseInstanceOf(InterruptedException.class);
				assertThat(result.interruptRestored()).isTrue();
				assertThat(supplierInvoked).isFalse();
			}
			finally {
				callerExecutor.shutdownNow();
			}
		}
	}

	@Test
	void interruptingAWaitingCallerCancelsAndInterruptsItsWorker() throws Exception {
		try (SimpleAsyncTaskExecutor executionExecutor = executor()) {
			SearchExecutionDeadline deadline = deadline(Duration.ofSeconds(5), executionExecutor);
			ExecutorService callerExecutor = Executors.newSingleThreadExecutor();
			AtomicReference<Thread> callerThread = new AtomicReference<>();
			AtomicBoolean workerInterrupted = new AtomicBoolean();
			CountDownLatch workerEntered = new CountDownLatch(1);
			CountDownLatch workerExited = new CountDownLatch(1);

			try {
				Future<InterruptedOutcome> outcome = callerExecutor.submit(() -> {
					callerThread.set(Thread.currentThread());
					try {
						deadline.execute(() -> blockingAction(
								new AtomicInteger(), workerEntered, workerExited, workerInterrupted));
						throw new AssertionError("Expected interrupted search execution");
					}
					catch (SearchExecutionInterruptedException exception) {
						return new InterruptedOutcome(exception, Thread.currentThread().isInterrupted());
					}
					finally {
						Thread.interrupted();
					}
				});

				assertThat(workerEntered.await(TEST_WAIT.toMillis(), TimeUnit.MILLISECONDS)).isTrue();
				callerThread.get().interrupt();

				InterruptedOutcome result = outcome.get(TEST_WAIT.toMillis(), TimeUnit.MILLISECONDS);
				assertThat(result.failure())
						.hasMessage("Search execution was interrupted")
						.hasCauseInstanceOf(InterruptedException.class);
				assertThat(result.interruptRestored()).isTrue();
				assertThat(workerExited.await(TEST_WAIT.toMillis(), TimeUnit.MILLISECONDS)).isTrue();
				assertThat(workerInterrupted).isTrue();
			}
			finally {
				callerExecutor.shutdownNow();
			}
		}
	}

	private static SearchExecutionDeadline deadline(
			Duration timeout, SimpleAsyncTaskExecutor executor) {
		return new SearchExecutionDeadline(timeout, executor);
	}

	private static SimpleAsyncTaskExecutor executor() {
		SimpleAsyncTaskExecutor executor = new SimpleAsyncTaskExecutor("search-deadline-test-");
		executor.setVirtualThreads(true);
		executor.setTaskTerminationTimeout(TEST_WAIT.toMillis());
		return executor;
	}

	private static String blockingAction(
			AtomicInteger activeWorkers,
			CountDownLatch entered,
			CountDownLatch exited,
			AtomicBoolean interrupted) {
		activeWorkers.incrementAndGet();
		entered.countDown();
		try {
			new CountDownLatch(1).await();
			throw new AssertionError("Blocking worker should only exit through interruption");
		}
		catch (InterruptedException exception) {
			interrupted.set(true);
			Thread.currentThread().interrupt();
			throw new IllegalStateException("Worker was canceled", exception);
		}
		finally {
			activeWorkers.decrementAndGet();
			exited.countDown();
		}
	}

	private record InterruptedOutcome(
			SearchExecutionInterruptedException failure, boolean interruptRestored) {
	}
}
