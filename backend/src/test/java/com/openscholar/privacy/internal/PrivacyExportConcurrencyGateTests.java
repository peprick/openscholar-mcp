package com.openscholar.privacy.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import com.openscholar.privacy.PrivacyExportBusyException;
import org.junit.jupiter.api.Test;

class PrivacyExportConcurrencyGateTests {

	private static final UUID ALICE = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
	private static final UUID BOB = UUID.fromString("00000000-0000-0000-0000-0000000000b2");
	private static final UUID CAROL = UUID.fromString("00000000-0000-0000-0000-0000000000c3");

	@Test
	void appliesPerPrincipalAndGlobalLimitsWithoutConsumingAPermitOnRejection() {
		PrivacyExportConcurrencyGate gate = gate(2, 1);
		PrivacyExportConcurrencyGate.Permit alice = gate.acquire(ALICE);
		PrivacyExportBusyException samePrincipal = catchBusy(() -> gate.acquire(ALICE));

		assertThat(samePrincipal.retryAfter()).isEqualTo(Duration.ofSeconds(7));
		assertThat(samePrincipal.getMessage())
				.isEqualTo("Personal-data export capacity is temporarily full. Try again later.")
				.doesNotContain(ALICE.toString());

		PrivacyExportConcurrencyGate.Permit bob = gate.acquire(BOB);
		assertThatThrownBy(() -> gate.acquire(CAROL))
				.isInstanceOf(PrivacyExportBusyException.class);

		alice.close();
		try (PrivacyExportConcurrencyGate.Permit ignored = gate.acquire(CAROL)) {
			assertThatThrownBy(() -> gate.acquire(ALICE))
					.isInstanceOf(PrivacyExportBusyException.class);
		}
		bob.close();
	}

	@Test
	void closingAPermitIsIdempotentAndRestoresCapacity() {
		PrivacyExportConcurrencyGate gate = gate(1, 1);
		PrivacyExportConcurrencyGate.Permit first = gate.acquire(ALICE);

		first.close();
		first.close();

		try (PrivacyExportConcurrencyGate.Permit ignored = gate.acquire(BOB)) {
			assertThatThrownBy(() -> gate.acquire(CAROL))
					.isInstanceOf(PrivacyExportBusyException.class);
		}
	}

	@Test
	void concurrentBurstNeverExceedsGlobalOrPerPrincipalLimits() throws Exception {
		PrivacyExportConcurrencyGate gate = gate(3, 2);
		List<UUID> principals = List.of(ALICE, ALICE, ALICE, BOB, BOB, BOB,
				CAROL, CAROL, CAROL, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
		CountDownLatch ready = new CountDownLatch(principals.size());
		CountDownLatch start = new CountDownLatch(1);
		CountDownLatch attempted = new CountDownLatch(principals.size());
		CountDownLatch release = new CountDownLatch(1);
		AtomicInteger accepted = new AtomicInteger();
		AtomicInteger rejected = new AtomicInteger();
		Map<UUID, AtomicInteger> acceptedByPrincipal = new ConcurrentHashMap<>();
		ConcurrentLinkedQueue<Throwable> unexpected = new ConcurrentLinkedQueue<>();
		ExecutorService executor = Executors.newFixedThreadPool(principals.size());
		List<Future<?>> futures = new ArrayList<>();

		try {
			for (UUID principal : principals) {
				futures.add(executor.submit(() -> contend(
						gate, principal, ready, start, attempted, release,
						accepted, rejected, acceptedByPrincipal, unexpected)));
			}

			assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
			start.countDown();
			assertThat(attempted.await(5, TimeUnit.SECONDS)).isTrue();
			assertThat(unexpected).isEmpty();
			assertThat(accepted).hasValue(3);
			assertThat(rejected).hasValue(principals.size() - 3);
			assertThat(acceptedByPrincipal.values())
					.allSatisfy(count -> assertThat(count).hasValueLessThanOrEqualTo(2));
		}
		finally {
			release.countDown();
			executor.shutdown();
		}
		for (Future<?> future : futures) {
			future.get(5, TimeUnit.SECONDS);
		}
		assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();

		try (PrivacyExportConcurrencyGate.Permit alice = gate.acquire(ALICE);
				PrivacyExportConcurrencyGate.Permit bob = gate.acquire(BOB);
				PrivacyExportConcurrencyGate.Permit carol = gate.acquire(CAROL)) {
			assertThatThrownBy(() -> gate.acquire(UUID.randomUUID()))
					.isInstanceOf(PrivacyExportBusyException.class);
		}
	}

	private static PrivacyExportConcurrencyGate gate(int globalPermits, int perPrincipalPermits) {
		return new PrivacyExportConcurrencyGate(new PrivacyExportProperties(
				globalPermits,
				perPrincipalPermits,
				Duration.ofSeconds(7)));
	}

	private static PrivacyExportBusyException catchBusy(Runnable action) {
		try {
			action.run();
			throw new AssertionError("Expected the privacy export gate to reject the acquisition");
		}
		catch (PrivacyExportBusyException exception) {
			return exception;
		}
	}

	private static void contend(
			PrivacyExportConcurrencyGate gate,
			UUID principal,
			CountDownLatch ready,
			CountDownLatch start,
			CountDownLatch attempted,
			CountDownLatch release,
			AtomicInteger accepted,
			AtomicInteger rejected,
			Map<UUID, AtomicInteger> acceptedByPrincipal,
			ConcurrentLinkedQueue<Throwable> unexpected) {
		ready.countDown();
		await(start);
		PrivacyExportConcurrencyGate.Permit permit;
		try {
			permit = gate.acquire(principal);
			accepted.incrementAndGet();
			acceptedByPrincipal.computeIfAbsent(principal, ignored -> new AtomicInteger())
					.incrementAndGet();
		}
		catch (PrivacyExportBusyException exception) {
			rejected.incrementAndGet();
			attempted.countDown();
			return;
		}
		catch (Throwable exception) {
			unexpected.add(exception);
			attempted.countDown();
			return;
		}

		attempted.countDown();
		try (permit) {
			await(release);
		}
	}

	private static void await(CountDownLatch latch) {
		try {
			latch.await();
		}
		catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
			throw new AssertionError("Concurrent gate test was interrupted", exception);
		}
	}
}
