package com.openscholar.jobs.internal;

import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;

import com.openscholar.jobs.EmbeddingBackfillDisposition;
import com.openscholar.jobs.EmbeddingBackfillFailure;
import com.openscholar.jobs.EmbeddingBackfillFailureCode;
import com.openscholar.jobs.EmbeddingBackfillResult;
import org.junit.jupiter.api.Test;

class EmbeddingBackfillRunnerTests {

	private static final String PROFILE_KEY = "paper-semantic-v1-digest";
	private static final EmbeddingBackfillProperties PROPERTIES =
			new EmbeddingBackfillProperties(true, PROFILE_KEY, null, 10, 2);

	@Test
	void returnsNormallyForAFullyProcessedPage() {
		EmbeddingBackfillRunner runner = new EmbeddingBackfillRunner(
				command -> new EmbeddingBackfillResult(
						command.profileKey(),
						EmbeddingBackfillDisposition.COMPLETED,
						1,
						1,
						0,
						0,
						List.of(),
						null),
				PROPERTIES,
				List.of());

		assertThatNoException().isThrownBy(() -> runner.run(null));
	}

	@Test
	void failsTheProcessWhenTheProfileLockIsAlreadyHeld() {
		EmbeddingBackfillRunner runner = new EmbeddingBackfillRunner(
				command -> new EmbeddingBackfillResult(
						command.profileKey(),
						EmbeddingBackfillDisposition.ALREADY_RUNNING,
						0,
						0,
						0,
						0,
						List.of(),
						null),
				PROPERTIES,
				List.of());

		assertThatThrownBy(() -> runner.run(null))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("profile lock");
	}

	@Test
	void failsTheProcessAfterReportingAPartialPage() {
		EmbeddingBackfillRunner runner = new EmbeddingBackfillRunner(
				command -> new EmbeddingBackfillResult(
						command.profileKey(),
						EmbeddingBackfillDisposition.COMPLETED,
						1,
						0,
						0,
						0,
						List.of(new EmbeddingBackfillFailure(
								UUID.fromString("00000000-0000-0000-0000-000000000001"),
								EmbeddingBackfillFailureCode.GENERATION_REJECTED,
								"INPUT_REJECTED",
								1)),
						UUID.fromString("00000000-0000-0000-0000-000000000001")),
				PROPERTIES,
				List.of());

		assertThatThrownBy(() -> runner.run(null))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("1 paper failures");
	}
}
