package com.openscholar.access.internal;

import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

import org.springframework.stereotype.Component;

@Component
class PaperAccessRequestCoordinator {

	private static final int STRIPE_COUNT = 64;

	private final ReentrantLock[] locks = new ReentrantLock[STRIPE_COUNT];

	PaperAccessRequestCoordinator() {
		for (int index = 0; index < locks.length; index++) {
			locks[index] = new ReentrantLock();
		}
	}

	<T> T execute(UUID paperId, Supplier<T> request) {
		ReentrantLock lock = locks[Math.floorMod(paperId.hashCode(), locks.length)];
		lock.lock();
		try {
			return request.get();
		}
		finally {
			lock.unlock();
		}
	}
}
