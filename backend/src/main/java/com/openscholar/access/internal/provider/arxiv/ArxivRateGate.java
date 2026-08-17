package com.openscholar.access.internal.provider.arxiv;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

final class ArxivRateGate {

	@FunctionalInterface
	interface Sleeper {
		void sleep(long nanoseconds) throws InterruptedException;
	}

	private final long intervalNanos;
	private final LongSupplier nanoTime;
	private final Sleeper sleeper;
	private long nextPermitNanos = Long.MIN_VALUE;

	ArxivRateGate(Duration interval) {
		this(interval, System::nanoTime, nanoseconds -> TimeUnit.NANOSECONDS.sleep(nanoseconds));
	}

	ArxivRateGate(Duration interval, LongSupplier nanoTime, Sleeper sleeper) {
		Objects.requireNonNull(interval, "interval");
		if (interval.isNegative()) {
			throw new IllegalArgumentException("arXiv request interval must not be negative");
		}
		this.intervalNanos = interval.toNanos();
		this.nanoTime = Objects.requireNonNull(nanoTime, "nanoTime");
		this.sleeper = Objects.requireNonNull(sleeper, "sleeper");
	}

	synchronized void acquire() throws InterruptedException {
		long now = nanoTime.getAsLong();
		if (nextPermitNanos != Long.MIN_VALUE && now < nextPermitNanos) {
			sleeper.sleep(nextPermitNanos - now);
			now = nanoTime.getAsLong();
		}
		nextPermitNanos = saturatedAdd(Math.max(now, nextPermitNanos), intervalNanos);
	}

	private static long saturatedAdd(long left, long right) {
		try {
			return Math.addExact(left, right);
		}
		catch (ArithmeticException overflow) {
			return Long.MAX_VALUE;
		}
	}
}
