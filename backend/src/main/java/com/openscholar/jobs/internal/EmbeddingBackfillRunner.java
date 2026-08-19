package com.openscholar.jobs.internal;

import java.util.List;

import com.openscholar.embedding.EmbeddingGenerator;
import com.openscholar.jobs.EmbeddingBackfillDisposition;
import com.openscholar.jobs.EmbeddingBackfillFailure;
import com.openscholar.jobs.EmbeddingBackfillResult;
import com.openscholar.jobs.EmbeddingBackfillUseCase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;

final class EmbeddingBackfillRunner implements ApplicationRunner {

	private static final Logger LOGGER = LoggerFactory.getLogger(EmbeddingBackfillRunner.class);

	private final EmbeddingBackfillUseCase useCase;
	private final EmbeddingBackfillProperties properties;
	private final List<EmbeddingGenerator> generators;

	EmbeddingBackfillRunner(
			EmbeddingBackfillUseCase useCase,
			EmbeddingBackfillProperties properties,
			List<EmbeddingGenerator> generators) {
		this.useCase = useCase;
		this.properties = properties;
		this.generators = List.copyOf(generators);
	}

	@Override
	public void run(ApplicationArguments arguments) {
		EmbeddingBackfillResult result = useCase.run(properties.command(generators));
		if (result.disposition() == EmbeddingBackfillDisposition.ALREADY_RUNNING) {
			LOGGER.warn(
					"Embedding backfill skipped because profile {} is already running",
					result.profileKey());
			throw new IllegalStateException(
					"Embedding backfill did not run because the profile lock is already held");
		}

		LOGGER.info(
				"Embedding backfill completed for profile {}: scanned={}, stored={}, unchanged={}, deleted={}, failed={}, nextCursor={}",
				result.profileKey(),
				result.scannedCount(),
				result.storedCount(),
				result.unchangedCount(),
				result.deletedCount(),
				result.failureCount(),
				result.nextCursor());
		for (EmbeddingBackfillFailure failure : result.failures()) {
			LOGGER.warn(
					"Embedding backfill paper {} failed: code={}, generationCode={}, attempts={}",
					failure.paperId(),
					failure.code(),
					failure.generationErrorCode(),
					failure.attempts());
		}
		if (result.failureCount() > 0) {
			throw new IllegalStateException(
					"Embedding backfill completed with " + result.failureCount() + " paper failures");
		}
	}
}
