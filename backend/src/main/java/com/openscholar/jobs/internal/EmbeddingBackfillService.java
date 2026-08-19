package com.openscholar.jobs.internal;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import com.openscholar.embedding.EmbeddingFailureScope;
import com.openscholar.embedding.EmbeddingGenerationException;
import com.openscholar.embedding.EmbeddingGenerator;
import com.openscholar.embedding.GeneratedEmbedding;
import com.openscholar.jobs.EmbeddingBackfillCommand;
import com.openscholar.jobs.EmbeddingBackfillDisposition;
import com.openscholar.jobs.EmbeddingBackfillFailure;
import com.openscholar.jobs.EmbeddingBackfillFailureCode;
import com.openscholar.jobs.EmbeddingBackfillResult;
import com.openscholar.jobs.EmbeddingBackfillUseCase;
import com.openscholar.paper.EmbeddingInputTooLargeException;
import com.openscholar.paper.EmbeddingProfile;
import com.openscholar.paper.PaperEmbeddingCandidate;
import com.openscholar.paper.PaperEmbeddingSource;
import com.openscholar.paper.PaperEmbeddingStore;
import com.openscholar.paper.PaperEmbeddingWorkPage;
import com.openscholar.paper.PaperNotFoundException;
import com.openscholar.paper.StalePaperEmbeddingException;
import com.openscholar.paper.StoreEmbeddingOutcome;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
class EmbeddingBackfillService implements EmbeddingBackfillUseCase {

	private final Map<String, RegisteredGenerator> generatorsByProfile;
	private final PaperEmbeddingStore embeddingStore;
	private final EmbeddingBackfillLock backfillLock;

	EmbeddingBackfillService(
			List<EmbeddingGenerator> generators,
			PaperEmbeddingStore embeddingStore,
			EmbeddingBackfillLock backfillLock) {
		this.generatorsByProfile = indexGenerators(generators);
		this.embeddingStore = Objects.requireNonNull(embeddingStore, "embeddingStore");
		this.backfillLock = Objects.requireNonNull(backfillLock, "backfillLock");
	}

	@Override
	@Transactional(propagation = Propagation.NEVER)
	public EmbeddingBackfillResult run(EmbeddingBackfillCommand command) {
		EmbeddingBackfillCommand requiredCommand = Objects.requireNonNull(command, "command");
		RegisteredGenerator registeredGenerator = generatorsByProfile.get(requiredCommand.profileKey());
		if (registeredGenerator == null) {
			throw new IllegalStateException(
					"No embedding generator is configured for profile " + requiredCommand.profileKey());
		}

		Optional<EmbeddingBackfillLease> lease = backfillLock.tryAcquire(requiredCommand.profileKey());
		if (lease.isEmpty()) {
			return new EmbeddingBackfillResult(
					requiredCommand.profileKey(),
					EmbeddingBackfillDisposition.ALREADY_RUNNING,
					0,
					0,
					0,
					0,
					List.of(),
					requiredCommand.afterExclusive());
		}

		try (EmbeddingBackfillLease ignored = lease.orElseThrow()) {
			return runWithLock(requiredCommand, registeredGenerator);
		}
	}

	private EmbeddingBackfillResult runWithLock(
			EmbeddingBackfillCommand command,
			RegisteredGenerator registeredGenerator) {
		EmbeddingGenerator generator = registeredGenerator.generator();
		EmbeddingProfile profile = registeredGenerator.profile();

		verifyGenerator(generator, command.maxAttempts());
		EmbeddingProfile storedProfile = embeddingStore.registerProfile(profile);
		if (!profile.equals(storedProfile)) {
			throw new IllegalStateException("Embedding store returned a different registered profile");
		}

		PaperEmbeddingWorkPage page = Objects.requireNonNull(
				embeddingStore.findMissing(profile.profileKey(), command.afterExclusive(), command.limit()),
				"embedding work page");
		validatePage(page, command.limit());

		int storedCount = 0;
		int unchangedCount = 0;
		int deletedCount = 0;
		List<EmbeddingBackfillFailure> failures = new ArrayList<>();
		for (UUID paperId : page.paperIds()) {
			PaperOutcome outcome = processPaper(paperId, profile, generator, command.maxAttempts());
			switch (outcome.kind()) {
				case STORED -> storedCount++;
				case UNCHANGED -> unchangedCount++;
				case DELETED -> deletedCount++;
				case FAILED -> failures.add(outcome.failure());
			}
		}

		return new EmbeddingBackfillResult(
				profile.profileKey(),
				EmbeddingBackfillDisposition.COMPLETED,
				page.paperIds().size(),
				storedCount,
				unchangedCount,
				deletedCount,
				failures,
				page.nextCursor());
	}

	private PaperOutcome processPaper(
			UUID paperId,
			EmbeddingProfile profile,
			EmbeddingGenerator generator,
			int maxAttempts) {
		for (int attempt = 1; attempt <= maxAttempts; attempt++) {
			PaperEmbeddingSource source;
			try {
				source = embeddingStore.prepareSource(paperId, profile.profileKey());
			}
			catch (PaperNotFoundException deleted) {
				return PaperOutcome.deleted();
			}
			catch (EmbeddingInputTooLargeException tooLarge) {
				return PaperOutcome.failed(new EmbeddingBackfillFailure(
						paperId,
						EmbeddingBackfillFailureCode.INPUT_TOO_LARGE,
						null,
						attempt - 1));
			}
			validateSource(source, paperId, profile.profileKey());

			GeneratedEmbedding generated;
			try {
				generated = Objects.requireNonNull(
						generator.generate(source.input()),
						"embedding generator result");
			}
			catch (EmbeddingGenerationException generationFailure) {
				if (generationFailure.retryable() && attempt < maxAttempts) {
					continue;
				}
				if (generationFailure.scope() == EmbeddingFailureScope.SYSTEM) {
					throw generationFailure;
				}
				return PaperOutcome.failed(new EmbeddingBackfillFailure(
						paperId,
						generationFailure.retryable()
								? EmbeddingBackfillFailureCode.ATTEMPT_BUDGET_EXHAUSTED
								: EmbeddingBackfillFailureCode.GENERATION_REJECTED,
						generationFailure.errorCode(),
						attempt));
			}
			validateGeneratedEmbedding(generated, profile);

			try {
				StoreEmbeddingOutcome stored = Objects.requireNonNull(
						embeddingStore.saveIfSourceCurrent(new PaperEmbeddingCandidate(
								paperId,
								profile.profileKey(),
								source.contentChecksum(),
								generated.vector(),
								generated.generatedAt())),
						"embedding store outcome");
				return stored == StoreEmbeddingOutcome.STORED
						? PaperOutcome.stored()
						: PaperOutcome.unchanged();
			}
			catch (PaperNotFoundException deleted) {
				return PaperOutcome.deleted();
			}
			catch (EmbeddingInputTooLargeException tooLarge) {
				return PaperOutcome.failed(new EmbeddingBackfillFailure(
						paperId,
						EmbeddingBackfillFailureCode.INPUT_TOO_LARGE,
						null,
						attempt));
			}
			catch (StalePaperEmbeddingException staleSource) {
				if (attempt == maxAttempts) {
					return PaperOutcome.failed(new EmbeddingBackfillFailure(
							paperId,
							EmbeddingBackfillFailureCode.ATTEMPT_BUDGET_EXHAUSTED,
							null,
							attempt));
				}
			}
		}

		throw new IllegalStateException("Embedding backfill exhausted attempts without an outcome");
	}

	private static void verifyGenerator(EmbeddingGenerator generator, int maxAttempts) {
		for (int attempt = 1; attempt <= maxAttempts; attempt++) {
			try {
				generator.verify();
				return;
			}
			catch (EmbeddingGenerationException failure) {
				if (!failure.retryable() || attempt == maxAttempts) {
					throw failure;
				}
			}
		}
		throw new IllegalStateException("Embedding generator verification exhausted attempts");
	}

	private static Map<String, RegisteredGenerator> indexGenerators(List<EmbeddingGenerator> generators) {
		List<EmbeddingGenerator> requiredGenerators = List.copyOf(
				Objects.requireNonNull(generators, "generators"));
		Map<String, RegisteredGenerator> indexed = new LinkedHashMap<>();
		for (EmbeddingGenerator generator : requiredGenerators) {
			EmbeddingGenerator requiredGenerator = Objects.requireNonNull(generator, "embedding generator");
			EmbeddingProfile profile = Objects.requireNonNull(
					requiredGenerator.profile(),
					"embedding generator profile");
			RegisteredGenerator previous = indexed.putIfAbsent(
					profile.profileKey(),
					new RegisteredGenerator(requiredGenerator, profile));
			if (previous != null) {
				throw new IllegalStateException(
						"Multiple embedding generators are configured for profile " + profile.profileKey());
			}
		}
		return Map.copyOf(indexed);
	}

	private static void validatePage(PaperEmbeddingWorkPage page, int limit) {
		if (page.paperIds().size() > limit) {
			throw new IllegalStateException("Embedding store returned more work than requested");
		}
		if (new HashSet<>(page.paperIds()).size() != page.paperIds().size()) {
			throw new IllegalStateException("Embedding store returned duplicate paper IDs");
		}
	}

	private static void validateSource(
			PaperEmbeddingSource source,
			UUID expectedPaperId,
			String expectedProfileKey) {
		if (!expectedPaperId.equals(source.paperId())
				|| !expectedProfileKey.equals(source.profileKey())) {
			throw new IllegalStateException("Embedding store returned a source for a different paper or profile");
		}
	}

	private static void validateGeneratedEmbedding(
			GeneratedEmbedding generated,
			EmbeddingProfile profile) {
		if (generated.vector().size() != profile.dimensions()) {
			throw new IllegalStateException(
					"Embedding generator returned an unexpected vector dimension");
		}
	}

	private record RegisteredGenerator(EmbeddingGenerator generator, EmbeddingProfile profile) {
	}

	private record PaperOutcome(PaperOutcomeKind kind, EmbeddingBackfillFailure failure) {

		private PaperOutcome {
			Objects.requireNonNull(kind, "kind");
			if ((kind == PaperOutcomeKind.FAILED) != (failure != null)) {
				throw new IllegalArgumentException("Only failed paper outcomes may carry a failure");
			}
		}

		private static PaperOutcome stored() {
			return new PaperOutcome(PaperOutcomeKind.STORED, null);
		}

		private static PaperOutcome unchanged() {
			return new PaperOutcome(PaperOutcomeKind.UNCHANGED, null);
		}

		private static PaperOutcome deleted() {
			return new PaperOutcome(PaperOutcomeKind.DELETED, null);
		}

		private static PaperOutcome failed(EmbeddingBackfillFailure failure) {
			return new PaperOutcome(PaperOutcomeKind.FAILED, Objects.requireNonNull(failure, "failure"));
		}
	}

	private enum PaperOutcomeKind {
		STORED,
		UNCHANGED,
		DELETED,
		FAILED
	}
}
