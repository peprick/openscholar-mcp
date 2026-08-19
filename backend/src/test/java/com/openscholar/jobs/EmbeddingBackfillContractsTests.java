package com.openscholar.jobs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class EmbeddingBackfillContractsTests {

	private static final String PROFILE_KEY = "test-profile-v1";
	private static final UUID PAPER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

	@Test
	void commandAllowsOnlyBoundedExplicitWork() {
		assertThat(new EmbeddingBackfillCommand(PROFILE_KEY, PAPER_ID, 500, 3))
				.extracting(
						EmbeddingBackfillCommand::profileKey,
						EmbeddingBackfillCommand::afterExclusive,
						EmbeddingBackfillCommand::limit,
						EmbeddingBackfillCommand::maxAttempts)
				.containsExactly(PROFILE_KEY, PAPER_ID, 500, 3);

		assertThatThrownBy(() -> new EmbeddingBackfillCommand(PROFILE_KEY, null, 0, 1))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("limit");
		assertThatThrownBy(() -> new EmbeddingBackfillCommand(PROFILE_KEY, null, 501, 1))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("limit");
		assertThatThrownBy(() -> new EmbeddingBackfillCommand(PROFILE_KEY, null, 1, 0))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("maxAttempts");
		assertThatThrownBy(() -> new EmbeddingBackfillCommand(PROFILE_KEY, null, 1, 4))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("maxAttempts");
	}

	@Test
	void resultDefensivelyCopiesSafeFailuresAndChecksAccounting() {
		List<EmbeddingBackfillFailure> mutable = new ArrayList<>(List.of(
				new EmbeddingBackfillFailure(
						PAPER_ID,
						EmbeddingBackfillFailureCode.ATTEMPT_BUDGET_EXHAUSTED,
						"LOCAL_TIMEOUT",
						3)));

		EmbeddingBackfillResult result = new EmbeddingBackfillResult(
				PROFILE_KEY,
				EmbeddingBackfillDisposition.COMPLETED,
				1,
				0,
				0,
				0,
				mutable,
				null);
		mutable.clear();

		assertThat(result.failureCount()).isOne();
		assertThat(result.failures()).singleElement().satisfies(failure -> {
			assertThat(failure.paperId()).isEqualTo(PAPER_ID);
			assertThat(failure.generationErrorCode()).isEqualTo("LOCAL_TIMEOUT");
		});
		assertThatThrownBy(() -> new EmbeddingBackfillResult(
				PROFILE_KEY,
				EmbeddingBackfillDisposition.COMPLETED,
				2,
				1,
				0,
				0,
				List.of(),
				null))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("counts");
		assertThatThrownBy(() -> new EmbeddingBackfillResult(
				PROFILE_KEY,
				EmbeddingBackfillDisposition.COMPLETED,
				1,
				Integer.MAX_VALUE,
				Integer.MAX_VALUE,
				3,
				List.of(),
				null))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("scanned count");
		assertThatThrownBy(() -> new EmbeddingBackfillResult(
				PROFILE_KEY,
				EmbeddingBackfillDisposition.COMPLETED,
				0,
				0,
				0,
				0,
				List.of(),
				PAPER_ID))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("next cursor");
	}
}
