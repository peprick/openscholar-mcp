package com.openscholar.search.internal;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

import com.openscholar.search.SearchCoordinationInterruptedException;
import com.openscholar.search.SearchCoordinationTimeoutException;
import org.springframework.stereotype.Component;

/**
 * Coordinates identical searches within one application instance without retaining an
 * unbounded map of caller-controlled fingerprints. Different fingerprints normally use
 * different stripes; a hash collision may briefly serialize unrelated searches as the
 * bounded-memory trade-off.
 */
@Component
class SearchRequestCoordinator {

	private static final int STRIPE_COUNT = 64;

	private final ReentrantLock[] locks = new ReentrantLock[STRIPE_COUNT];
	private final long waitTimeoutNanos;

	SearchRequestCoordinator(SearchProperties properties) {
		waitTimeoutNanos = saturatedNanos(Objects.requireNonNull(properties, "properties")
				.getCoordinationWaitTimeout());
		for (int index = 0; index < locks.length; index++) {
			locks[index] = new ReentrantLock(true);
		}
	}

	<T> T execute(String fingerprint, Supplier<T> request) {
		Objects.requireNonNull(fingerprint, "fingerprint");
		Objects.requireNonNull(request, "request");
		ReentrantLock lock = locks[stripeIndex(fingerprint)];
		boolean acquired;
		try {
			acquired = lock.tryLock(waitTimeoutNanos, TimeUnit.NANOSECONDS);
		}
		catch (InterruptedException interrupted) {
			Thread.currentThread().interrupt();
			throw new SearchCoordinationInterruptedException(interrupted);
		}
		if (!acquired) {
			throw new SearchCoordinationTimeoutException();
		}
		try {
			return request.get();
		}
		finally {
			lock.unlock();
		}
	}

	int stripeIndex(String fingerprint) {
		return Math.floorMod(Objects.requireNonNull(fingerprint, "fingerprint").hashCode(), locks.length);
	}

	private static long saturatedNanos(Duration timeout) {
		try {
			return timeout.toNanos();
		}
		catch (ArithmeticException ignored) {
			return Long.MAX_VALUE;
		}
	}
}
