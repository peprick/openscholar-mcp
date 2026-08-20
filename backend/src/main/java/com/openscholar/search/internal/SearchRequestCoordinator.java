package com.openscholar.search.internal;

import java.util.Objects;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

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

	SearchRequestCoordinator() {
		for (int index = 0; index < locks.length; index++) {
			locks[index] = new ReentrantLock();
		}
	}

	<T> T execute(String fingerprint, Supplier<T> request) {
		Objects.requireNonNull(fingerprint, "fingerprint");
		Objects.requireNonNull(request, "request");
		ReentrantLock lock = locks[stripeIndex(fingerprint)];
		lock.lock();
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
}
