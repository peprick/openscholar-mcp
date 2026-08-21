package com.openscholar.search.internal;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

import com.openscholar.search.SearchDeadlineExceededException;
import com.openscholar.search.SearchExecutionInterruptedException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.stereotype.Component;

/**
 * Enforces the application-level search budget independently of the calling REST or
 * MCP thread. Cancellation is signalled before interrupting the worker so checkpoints
 * can preserve deadline precedence when a lower-level library translates the interrupt
 * into its own exception.
 */
@Component
class SearchExecutionDeadline {

	private final long timeoutNanos;
	private final AsyncTaskExecutor executor;
	private final LongSupplier ticker;
	private final ThreadLocal<ExecutionState> currentExecution = new ThreadLocal<>();

	@Autowired
	SearchExecutionDeadline(
			SearchProperties properties,
			@Qualifier(SearchConfiguration.EXECUTION_EXECUTOR_BEAN) AsyncTaskExecutor executor) {
		this(properties.getExecutionTimeout(), executor, System::nanoTime);
	}

	SearchExecutionDeadline(Duration timeout, AsyncTaskExecutor executor) {
		this(timeout, executor, System::nanoTime);
	}

	SearchExecutionDeadline(Duration timeout, AsyncTaskExecutor executor, LongSupplier ticker) {
		this.timeoutNanos = saturatedNanos(timeout);
		this.executor = Objects.requireNonNull(executor, "executor");
		this.ticker = Objects.requireNonNull(ticker, "ticker");
	}

	<T> T execute(Supplier<T> operation) {
		Objects.requireNonNull(operation, "operation");
		if (Thread.currentThread().isInterrupted()) {
			throw interrupted(new InterruptedException("Search caller was already interrupted"));
		}

		ExecutionState execution = new ExecutionState(ticker.getAsLong(), timeoutNanos, ticker);
		Future<T> future;
		try {
			future = executor.submit(() -> run(execution, operation));
		}
		catch (TaskRejectedException rejected) {
			throw interrupted(rejected);
		}

		try {
			long remainingNanos = execution.remainingNanos();
			if (remainingNanos <= 0) {
				return cancelForDeadline(execution, future, null);
			}
			return future.get(remainingNanos, TimeUnit.NANOSECONDS);
		}
		catch (TimeoutException timeout) {
			return cancelForDeadline(execution, future, timeout);
		}
		catch (InterruptedException interrupted) {
			execution.cancel(CancellationReason.INTERRUPTED);
			future.cancel(true);
			Thread.currentThread().interrupt();
			throw publicCancellation(execution.reason(), interrupted);
		}
		catch (CancellationException cancelled) {
			throw publicCancellation(execution.reason(), cancelled);
		}
		catch (ExecutionException failed) {
			throw propagate(execution, failed.getCause());
		}
	}

	void checkpoint() {
		ExecutionState execution = currentExecution.get();
		if (execution != null) {
			execution.checkpoint();
		}
	}

	private <T> T run(ExecutionState execution, Supplier<T> operation) {
		currentExecution.set(execution);
		try {
			execution.checkpoint();
			try {
				T result = operation.get();
				execution.checkpoint();
				return result;
			}
			catch (RuntimeException | Error failure) {
				execution.checkpoint();
				throw failure;
			}
		}
		finally {
			currentExecution.remove();
		}
	}

	private static <T> T cancelForDeadline(
			ExecutionState execution, Future<T> future, Throwable cause) {
		execution.cancel(CancellationReason.DEADLINE_EXCEEDED);
		future.cancel(true);
		throw publicCancellation(execution.reason(), cause);
	}

	private static RuntimeException propagate(ExecutionState execution, Throwable failure) {
		if (failure instanceof ExecutionCancelledException cancelled) {
			return publicCancellation(cancelled.reason(), cancelled);
		}
		CancellationReason cancellation = execution.reason();
		if (cancellation != null) {
			return publicCancellation(cancellation, failure);
		}
		if (failure instanceof RuntimeException runtimeException) {
			return runtimeException;
		}
		if (failure instanceof Error error) {
			throw error;
		}
		return new IllegalStateException("Search execution failed", failure);
	}

	private static RuntimeException publicCancellation(CancellationReason reason, Throwable cause) {
		if (reason == CancellationReason.DEADLINE_EXCEEDED) {
			return new SearchDeadlineExceededException();
		}
		return interrupted(cause);
	}

	private static SearchExecutionInterruptedException interrupted(Throwable cause) {
		return new SearchExecutionInterruptedException(cause);
	}

	private static long saturatedNanos(Duration timeout) {
		Objects.requireNonNull(timeout, "timeout");
		if (timeout.compareTo(Duration.ofMillis(1)) < 0) {
			throw new IllegalArgumentException("Search execution timeout must be at least one millisecond");
		}
		try {
			return timeout.toNanos();
		}
		catch (ArithmeticException ignored) {
			return Long.MAX_VALUE;
		}
	}

	private enum CancellationReason {
		DEADLINE_EXCEEDED,
		INTERRUPTED
	}

	private static final class ExecutionState {

		private final long startedAtNanos;
		private final long timeoutNanos;
		private final LongSupplier ticker;
		private final AtomicReference<CancellationReason> cancellation = new AtomicReference<>();

		private ExecutionState(long startedAtNanos, long timeoutNanos, LongSupplier ticker) {
			this.startedAtNanos = startedAtNanos;
			this.timeoutNanos = timeoutNanos;
			this.ticker = ticker;
		}

		private long remainingNanos() {
			return timeoutNanos - elapsedNanos();
		}

		private long elapsedNanos() {
			return ticker.getAsLong() - startedAtNanos;
		}

		private CancellationReason reason() {
			return cancellation.get();
		}

		private void cancel(CancellationReason reason) {
			cancellation.compareAndSet(null, reason);
		}

		private void checkpoint() {
			CancellationReason reason = cancellation.get();
			if (reason == null && elapsedNanos() >= timeoutNanos) {
				cancel(CancellationReason.DEADLINE_EXCEEDED);
				reason = cancellation.get();
			}
			if (reason == null && Thread.currentThread().isInterrupted()) {
				cancel(CancellationReason.INTERRUPTED);
				reason = cancellation.get();
			}
			if (reason != null) {
				throw new ExecutionCancelledException(reason);
			}
		}
	}

	private static final class ExecutionCancelledException extends CancellationException {

		private final CancellationReason reason;

		private ExecutionCancelledException(CancellationReason reason) {
			super("Search execution was cancelled internally");
			this.reason = reason;
		}

		private CancellationReason reason() {
			return reason;
		}
	}
}
