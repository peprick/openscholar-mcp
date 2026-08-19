package com.openscholar.jobs.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.Method;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import com.openscholar.embedding.EmbeddingFailureScope;
import com.openscholar.embedding.EmbeddingGenerationException;
import com.openscholar.embedding.EmbeddingGenerator;
import com.openscholar.embedding.GeneratedEmbedding;
import com.openscholar.jobs.EmbeddingBackfillCommand;
import com.openscholar.jobs.EmbeddingBackfillDisposition;
import com.openscholar.jobs.EmbeddingBackfillFailureCode;
import com.openscholar.paper.EmbeddingContentKind;
import com.openscholar.paper.EmbeddingDistanceMetric;
import com.openscholar.paper.EmbeddingInputTooLargeException;
import com.openscholar.paper.EmbeddingProfile;
import com.openscholar.paper.PaperEmbeddingCandidate;
import com.openscholar.paper.PaperEmbeddingMatch;
import com.openscholar.paper.PaperEmbeddingSource;
import com.openscholar.paper.PaperEmbeddingStore;
import com.openscholar.paper.PaperEmbeddingWorkPage;
import com.openscholar.paper.PaperNotFoundException;
import com.openscholar.paper.StalePaperEmbeddingException;
import com.openscholar.paper.StoreEmbeddingOutcome;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class EmbeddingBackfillServiceTests {

	private static final String PROFILE_KEY = "test-profile-v1";
	private static final EmbeddingProfile PROFILE = new EmbeddingProfile(
			PROFILE_KEY,
			"test",
			"test-model",
			"immutable-revision-1",
			EmbeddingContentKind.TITLE_ABSTRACT,
			1,
			2,
			EmbeddingDistanceMetric.COSINE);
	private static final Instant GENERATED_AT = Instant.parse("2026-08-19T12:00:00Z");
	private static final UUID PAPER_ONE = uuid(1);
	private static final UUID PAPER_TWO = uuid(2);
	private static final UUID PAPER_THREE = uuid(3);
	private static final UUID PAPER_FOUR = uuid(4);
	private static final UUID PAPER_FIVE = uuid(5);
	private static final UUID PAPER_SIX = uuid(6);

	@Test
	void processesOneBoundedPageInOrderWithoutAnActiveGenerationTransaction() throws Exception {
		List<String> events = new ArrayList<>();
		FakeGenerator generator = new FakeGenerator(PROFILE, events);
		FakeEmbeddingStore store = new FakeEmbeddingStore(events);
		store.page = new PaperEmbeddingWorkPage(List.of(PAPER_ONE, PAPER_TWO), PAPER_TWO, true);
		store.save = candidate -> candidate.paperId().equals(PAPER_ONE)
				? StoreEmbeddingOutcome.STORED
				: StoreEmbeddingOutcome.UNCHANGED;
		FakeBackfillLock lock = new FakeBackfillLock(true, events);
		EmbeddingBackfillService service = new EmbeddingBackfillService(List.of(generator), store, lock);

		var result = service.run(new EmbeddingBackfillCommand(PROFILE_KEY, null, 2, 1));

		assertThat(result.disposition()).isEqualTo(EmbeddingBackfillDisposition.COMPLETED);
		assertThat(result.scannedCount()).isEqualTo(2);
		assertThat(result.storedCount()).isOne();
		assertThat(result.unchangedCount()).isOne();
		assertThat(result.deletedCount()).isZero();
		assertThat(result.failures()).isEmpty();
		assertThat(result.nextCursor()).isEqualTo(PAPER_TWO);
		assertThat(generator.transactionObservedDuringGeneration).isFalse();
		assertThat(events).containsExactly(
				"lock",
				"verify",
				"register",
				"find",
				"prepare:" + PAPER_ONE,
				"generate:input-" + PAPER_ONE,
				"save:" + PAPER_ONE,
				"prepare:" + PAPER_TWO,
				"generate:input-" + PAPER_TWO,
				"save:" + PAPER_TWO,
				"unlock");

		Method run = EmbeddingBackfillService.class.getMethod("run", EmbeddingBackfillCommand.class);
		Transactional transactional = run.getAnnotation(Transactional.class);
		assertThat(transactional).isNotNull();
		assertThat(transactional.propagation()).isEqualTo(Propagation.NEVER);
	}

	@Test
	void returnsAlreadyRunningWithoutVerifyingOrTouchingTheStore() {
		FakeGenerator generator = new FakeGenerator(PROFILE, new ArrayList<>());
		FakeEmbeddingStore store = new FakeEmbeddingStore(new ArrayList<>());
		FakeBackfillLock lock = new FakeBackfillLock(false, new ArrayList<>());
		EmbeddingBackfillService service = new EmbeddingBackfillService(List.of(generator), store, lock);
		UUID cursor = uuid(99);

		var result = service.run(new EmbeddingBackfillCommand(PROFILE_KEY, cursor, 25, 2));

		assertThat(result.disposition()).isEqualTo(EmbeddingBackfillDisposition.ALREADY_RUNNING);
		assertThat(result.scannedCount()).isZero();
		assertThat(result.nextCursor()).isEqualTo(cursor);
		assertThat(generator.verifyCalls).isZero();
		assertThat(generator.inputs).isEmpty();
		assertThat(store.registerCalls).isZero();
		assertThat(store.findCalls).isZero();
		assertThat(store.prepareCalls).isZero();
		assertThat(store.saveCalls).isZero();
		assertThat(lock.closed).isFalse();
	}

	@Test
	void rerendersAndRegeneratesAfterAStaleSave() {
		FakeGenerator generator = new FakeGenerator(PROFILE, new ArrayList<>());
		FakeEmbeddingStore store = new FakeEmbeddingStore(new ArrayList<>());
		store.page = new PaperEmbeddingWorkPage(List.of(PAPER_ONE), null, false);
		AtomicInteger prepares = new AtomicInteger();
		store.prepare = paperId -> prepares.getAndIncrement() == 0
				? source(paperId, "old input", 'a')
				: source(paperId, "new input", 'b');
		AtomicInteger saves = new AtomicInteger();
		store.save = candidate -> {
			if (saves.getAndIncrement() == 0) {
				throw new StalePaperEmbeddingException(candidate.paperId(), candidate.profileKey());
			}
			assertThat(candidate.contentChecksum()).isEqualTo("b".repeat(64));
			return StoreEmbeddingOutcome.STORED;
		};
		FakeBackfillLock lock = new FakeBackfillLock(true, new ArrayList<>());
		EmbeddingBackfillService service = new EmbeddingBackfillService(List.of(generator), store, lock);

		var result = service.run(new EmbeddingBackfillCommand(PROFILE_KEY, null, 1, 2));

		assertThat(result.storedCount()).isOne();
		assertThat(result.failures()).isEmpty();
		assertThat(generator.inputs).containsExactly("old input", "new input");
		assertThat(store.prepareCalls).isEqualTo(2);
		assertThat(store.saveCalls).isEqualTo(2);
		assertThat(lock.closed).isTrue();
	}

	@Test
	void continuesAfterPerPaperFailuresAndAccountsForEveryScannedPaper() {
		FakeGenerator generator = new FakeGenerator(PROFILE, new ArrayList<>());
		generator.results.add(new EmbeddingGenerationException(
				"LOCAL_TIMEOUT", true, "Local generation timed out"));
		generator.results.add(new EmbeddingGenerationException(
				"LOCAL_TIMEOUT", true, "Local generation timed out"));
		generator.results.add(new EmbeddingGenerationException(
				"LOCAL_REJECTED", false, "Local generation rejected the input"));
		generator.results.add(generated());
		generator.results.add(generated());

		FakeEmbeddingStore store = new FakeEmbeddingStore(new ArrayList<>());
		store.page = new PaperEmbeddingWorkPage(
				List.of(PAPER_ONE, PAPER_TWO, PAPER_THREE, PAPER_FOUR, PAPER_FIVE, PAPER_SIX),
				null,
				false);
		store.prepare = paperId -> {
			if (paperId.equals(PAPER_THREE)) {
				throw new EmbeddingInputTooLargeException(paperId, 25_000, 24_576);
			}
			if (paperId.equals(PAPER_FOUR)) {
				throw new PaperNotFoundException(paperId);
			}
			return source(paperId, "input-" + paperId, 'a');
		};
		store.save = candidate -> candidate.paperId().equals(PAPER_FIVE)
				? StoreEmbeddingOutcome.UNCHANGED
				: StoreEmbeddingOutcome.STORED;
		EmbeddingBackfillService service = new EmbeddingBackfillService(
				List.of(generator), store, new FakeBackfillLock(true, new ArrayList<>()));

		var result = service.run(new EmbeddingBackfillCommand(PROFILE_KEY, null, 10, 2));

		assertThat(result.scannedCount()).isEqualTo(6);
		assertThat(result.storedCount()).isOne();
		assertThat(result.unchangedCount()).isOne();
		assertThat(result.deletedCount()).isOne();
		assertThat(result.failureCount()).isEqualTo(3);
		assertThat(result.failures()).satisfiesExactly(
				failure -> {
					assertThat(failure.paperId()).isEqualTo(PAPER_ONE);
					assertThat(failure.code())
							.isEqualTo(EmbeddingBackfillFailureCode.ATTEMPT_BUDGET_EXHAUSTED);
					assertThat(failure.generationErrorCode()).isEqualTo("LOCAL_TIMEOUT");
					assertThat(failure.attempts()).isEqualTo(2);
				},
				failure -> {
					assertThat(failure.paperId()).isEqualTo(PAPER_TWO);
					assertThat(failure.code()).isEqualTo(EmbeddingBackfillFailureCode.GENERATION_REJECTED);
					assertThat(failure.generationErrorCode()).isEqualTo("LOCAL_REJECTED");
					assertThat(failure.attempts()).isOne();
				},
				failure -> {
					assertThat(failure.paperId()).isEqualTo(PAPER_THREE);
					assertThat(failure.code()).isEqualTo(EmbeddingBackfillFailureCode.INPUT_TOO_LARGE);
					assertThat(failure.generationErrorCode()).isNull();
					assertThat(failure.attempts()).isZero();
				});
		assertThat(generator.inputs).hasSize(5);
	}

	@Test
	void reportsExhaustedSourceChangesAndContinues() {
		FakeGenerator generator = new FakeGenerator(PROFILE, new ArrayList<>());
		FakeEmbeddingStore store = new FakeEmbeddingStore(new ArrayList<>());
		store.page = new PaperEmbeddingWorkPage(List.of(PAPER_ONE, PAPER_TWO), null, false);
		store.save = candidate -> {
			if (candidate.paperId().equals(PAPER_ONE)) {
				throw new StalePaperEmbeddingException(candidate.paperId(), candidate.profileKey());
			}
			return StoreEmbeddingOutcome.STORED;
		};
		EmbeddingBackfillService service = new EmbeddingBackfillService(
				List.of(generator), store, new FakeBackfillLock(true, new ArrayList<>()));

		var result = service.run(new EmbeddingBackfillCommand(PROFILE_KEY, null, 2, 3));

		assertThat(result.storedCount()).isOne();
		assertThat(result.failures()).singleElement().satisfies(failure -> {
			assertThat(failure.paperId()).isEqualTo(PAPER_ONE);
			assertThat(failure.code()).isEqualTo(EmbeddingBackfillFailureCode.ATTEMPT_BUDGET_EXHAUSTED);
			assertThat(failure.attempts()).isEqualTo(3);
		});
		assertThat(generator.inputs).hasSize(4);
	}

	@Test
	void continuesWhenTheSourceBecomesOversizedBetweenGenerationAndSave() {
		FakeGenerator generator = new FakeGenerator(PROFILE, new ArrayList<>());
		FakeEmbeddingStore store = new FakeEmbeddingStore(new ArrayList<>());
		store.page = new PaperEmbeddingWorkPage(List.of(PAPER_ONE, PAPER_TWO), null, false);
		store.save = candidate -> {
			if (candidate.paperId().equals(PAPER_ONE)) {
				throw new EmbeddingInputTooLargeException(PAPER_ONE, 25_000, 24_576);
			}
			return StoreEmbeddingOutcome.STORED;
		};
		EmbeddingBackfillService service = new EmbeddingBackfillService(
				List.of(generator), store, new FakeBackfillLock(true, new ArrayList<>()));

		var result = service.run(new EmbeddingBackfillCommand(PROFILE_KEY, null, 2, 2));

		assertThat(result.scannedCount()).isEqualTo(2);
		assertThat(result.storedCount()).isOne();
		assertThat(result.failureCount()).isOne();
		assertThat(result.failures()).singleElement().satisfies(failure -> {
			assertThat(failure.paperId()).isEqualTo(PAPER_ONE);
			assertThat(failure.code()).isEqualTo(EmbeddingBackfillFailureCode.INPUT_TOO_LARGE);
			assertThat(failure.generationErrorCode()).isNull();
			assertThat(failure.attempts()).isOne();
		});
		assertThat(store.saveCalls).isEqualTo(2);
	}

	@Test
	void retainsTheAttemptCountWhenARepreparedSourceBecomesOversized() {
		FakeGenerator generator = new FakeGenerator(PROFILE, new ArrayList<>());
		generator.results.add(new EmbeddingGenerationException(
				"LOCAL_TIMEOUT", true, "Local generation timed out"));
		FakeEmbeddingStore store = new FakeEmbeddingStore(new ArrayList<>());
		store.page = new PaperEmbeddingWorkPage(List.of(PAPER_ONE), null, false);
		AtomicInteger prepares = new AtomicInteger();
		store.prepare = paperId -> {
			if (prepares.getAndIncrement() == 0) {
				return source(paperId, "initial input", 'a');
			}
			throw new EmbeddingInputTooLargeException(paperId, 25_000, 24_576);
		};
		EmbeddingBackfillService service = new EmbeddingBackfillService(
				List.of(generator), store, new FakeBackfillLock(true, new ArrayList<>()));

		var result = service.run(new EmbeddingBackfillCommand(PROFILE_KEY, null, 1, 2));

		assertThat(result.failures()).singleElement().satisfies(failure -> {
			assertThat(failure.code()).isEqualTo(EmbeddingBackfillFailureCode.INPUT_TOO_LARGE);
			assertThat(failure.attempts()).isOne();
		});
		assertThat(generator.inputs).containsExactly("initial input");
		assertThat(store.saveCalls).isZero();
	}

	@Test
	void failsFastOnGeneratorVectorContractErrorsAndStillReleasesTheLock() {
		FakeGenerator generator = new FakeGenerator(PROFILE, new ArrayList<>());
		generator.results.add(new GeneratedEmbedding(List.of(1.0f), GENERATED_AT));
		FakeEmbeddingStore store = new FakeEmbeddingStore(new ArrayList<>());
		store.page = new PaperEmbeddingWorkPage(List.of(PAPER_ONE), null, false);
		FakeBackfillLock lock = new FakeBackfillLock(true, new ArrayList<>());
		EmbeddingBackfillService service = new EmbeddingBackfillService(List.of(generator), store, lock);

		assertThatThrownBy(() -> service.run(new EmbeddingBackfillCommand(PROFILE_KEY, null, 1, 1)))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("vector dimension");
		assertThat(store.saveCalls).isZero();
		assertThat(lock.closed).isTrue();
	}

	@Test
	void retriesInitialVerificationWithinTheConfiguredBudget() {
		FakeGenerator generator = new FakeGenerator(PROFILE, new ArrayList<>());
		generator.verificationFailures.add(new EmbeddingGenerationException(
				"LOCAL_TIMEOUT",
				true,
				EmbeddingFailureScope.SYSTEM,
				"Local verification timed out"));
		FakeEmbeddingStore store = new FakeEmbeddingStore(new ArrayList<>());
		EmbeddingBackfillService service = new EmbeddingBackfillService(
				List.of(generator), store, new FakeBackfillLock(true, new ArrayList<>()));

		var result = service.run(new EmbeddingBackfillCommand(PROFILE_KEY, null, 1, 2));

		assertThat(result.scannedCount()).isZero();
		assertThat(generator.verifyCalls).isEqualTo(2);
	}

	@Test
	void abortsThePageOnASystemicGenerationFailure() {
		FakeGenerator generator = new FakeGenerator(PROFILE, new ArrayList<>());
		generator.results.add(new EmbeddingGenerationException(
				"MODEL_DRIFT",
				false,
				EmbeddingFailureScope.SYSTEM,
				"Configured model changed"));
		FakeEmbeddingStore store = new FakeEmbeddingStore(new ArrayList<>());
		store.page = new PaperEmbeddingWorkPage(List.of(PAPER_ONE, PAPER_TWO), null, false);
		FakeBackfillLock lock = new FakeBackfillLock(true, new ArrayList<>());
		EmbeddingBackfillService service = new EmbeddingBackfillService(List.of(generator), store, lock);

		assertThatThrownBy(() -> service.run(
				new EmbeddingBackfillCommand(PROFILE_KEY, null, 2, 2)))
				.isInstanceOfSatisfying(EmbeddingGenerationException.class, failure -> {
					assertThat(failure.errorCode()).isEqualTo("MODEL_DRIFT");
					assertThat(failure.scope()).isEqualTo(EmbeddingFailureScope.SYSTEM);
				});
		assertThat(generator.inputs).hasSize(1);
		assertThat(store.prepareCalls).isOne();
		assertThat(store.saveCalls).isZero();
		assertThat(lock.closed).isTrue();
	}

	@Test
	void reportsAGenericBudgetFailureForMixedRetryableAndStaleAttempts() {
		FakeGenerator generator = new FakeGenerator(PROFILE, new ArrayList<>());
		generator.results.add(new EmbeddingGenerationException(
				"LOCAL_TIMEOUT", true, "Local generation timed out"));
		generator.results.add(generated());
		FakeEmbeddingStore store = new FakeEmbeddingStore(new ArrayList<>());
		store.page = new PaperEmbeddingWorkPage(List.of(PAPER_ONE), null, false);
		store.save = candidate -> {
			throw new StalePaperEmbeddingException(candidate.paperId(), candidate.profileKey());
		};
		EmbeddingBackfillService service = new EmbeddingBackfillService(
				List.of(generator), store, new FakeBackfillLock(true, new ArrayList<>()));

		var result = service.run(new EmbeddingBackfillCommand(PROFILE_KEY, null, 1, 2));

		assertThat(result.failures()).singleElement().satisfies(failure -> {
			assertThat(failure.code())
					.isEqualTo(EmbeddingBackfillFailureCode.ATTEMPT_BUDGET_EXHAUSTED);
			assertThat(failure.generationErrorCode()).isNull();
			assertThat(failure.attempts()).isEqualTo(2);
		});
	}

	@Test
	void failsFastForMissingOrDuplicateGeneratorConfiguration() {
		FakeEmbeddingStore store = new FakeEmbeddingStore(new ArrayList<>());
		FakeBackfillLock lock = new FakeBackfillLock(true, new ArrayList<>());
		EmbeddingBackfillService withoutGenerators = new EmbeddingBackfillService(List.of(), store, lock);

		assertThatThrownBy(() -> withoutGenerators.run(
				new EmbeddingBackfillCommand(PROFILE_KEY, null, 1, 1)))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("No embedding generator");
		assertThat(lock.acquireCalls).isZero();

		FakeGenerator first = new FakeGenerator(PROFILE, new ArrayList<>());
		FakeGenerator second = new FakeGenerator(PROFILE, new ArrayList<>());
		assertThatThrownBy(() -> new EmbeddingBackfillService(List.of(first, second), store, lock))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("Multiple embedding generators");
	}

	private static GeneratedEmbedding generated() {
		return new GeneratedEmbedding(List.of(0.25f, -0.5f), GENERATED_AT);
	}

	private static PaperEmbeddingSource source(UUID paperId, String input, char checksumCharacter) {
		return new PaperEmbeddingSource(
				paperId,
				PROFILE_KEY,
				input,
				String.valueOf(checksumCharacter).repeat(64));
	}

	private static UUID uuid(long value) {
		return new UUID(0, value);
	}

	private static final class FakeGenerator implements EmbeddingGenerator {

		private final EmbeddingProfile profile;
		private final List<String> events;
		private final Deque<Object> results = new ArrayDeque<>();
		private final Deque<RuntimeException> verificationFailures = new ArrayDeque<>();
		private final List<String> inputs = new ArrayList<>();
		private int verifyCalls;
		private boolean transactionObservedDuringGeneration;

		private FakeGenerator(EmbeddingProfile profile, List<String> events) {
			this.profile = profile;
			this.events = events;
		}

		@Override
		public EmbeddingProfile profile() {
			return profile;
		}

		@Override
		public void verify() {
			verifyCalls++;
			events.add("verify");
			if (!verificationFailures.isEmpty()) {
				throw verificationFailures.removeFirst();
			}
		}

		@Override
		public GeneratedEmbedding generate(String input) {
			inputs.add(input);
			events.add("generate:" + input);
			transactionObservedDuringGeneration |= TransactionSynchronizationManager.isActualTransactionActive();
			if (TransactionSynchronizationManager.isActualTransactionActive()) {
				throw new AssertionError("Embedding generation must run outside a transaction");
			}
			if (results.isEmpty()) {
				return generated();
			}
			Object result = results.removeFirst();
			if (result instanceof RuntimeException failure) {
				throw failure;
			}
			return (GeneratedEmbedding) result;
		}
	}

	private static final class FakeEmbeddingStore implements PaperEmbeddingStore {

		private final List<String> events;
		private PaperEmbeddingWorkPage page = new PaperEmbeddingWorkPage(List.of(), null, false);
		private Function<UUID, PaperEmbeddingSource> prepare = paperId ->
				source(paperId, "input-" + paperId, 'a');
		private Function<PaperEmbeddingCandidate, StoreEmbeddingOutcome> save = candidate ->
				StoreEmbeddingOutcome.STORED;
		private int registerCalls;
		private int findCalls;
		private int prepareCalls;
		private int saveCalls;

		private FakeEmbeddingStore(List<String> events) {
			this.events = events;
		}

		@Override
		public EmbeddingProfile registerProfile(EmbeddingProfile profile) {
			registerCalls++;
			events.add("register");
			return profile;
		}

		@Override
		public PaperEmbeddingSource prepareSource(UUID paperId, String profileKey) {
			prepareCalls++;
			events.add("prepare:" + paperId);
			return prepare.apply(paperId);
		}

		@Override
		public StoreEmbeddingOutcome saveIfSourceCurrent(PaperEmbeddingCandidate candidate) {
			saveCalls++;
			events.add("save:" + candidate.paperId());
			return save.apply(candidate);
		}

		@Override
		public PaperEmbeddingWorkPage findMissing(String profileKey, UUID afterExclusive, int limit) {
			findCalls++;
			events.add("find");
			return page;
		}

		@Override
		public List<PaperEmbeddingMatch> findNearest(UUID sourcePaperId, String profileKey, int limit) {
			throw new AssertionError("Nearest-neighbor lookup must not run during backfill");
		}
	}

	private static final class FakeBackfillLock implements EmbeddingBackfillLock {

		private final boolean acquired;
		private final List<String> events;
		private int acquireCalls;
		private boolean closed;

		private FakeBackfillLock(boolean acquired, List<String> events) {
			this.acquired = acquired;
			this.events = events;
		}

		@Override
		public Optional<EmbeddingBackfillLease> tryAcquire(String profileKey) {
			acquireCalls++;
			events.add("lock");
			if (!acquired) {
				return Optional.empty();
			}
			return Optional.of(() -> {
				closed = true;
				events.add("unlock");
			});
		}
	}
}
