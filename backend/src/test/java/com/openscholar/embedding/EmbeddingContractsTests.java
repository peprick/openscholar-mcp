package com.openscholar.embedding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

class EmbeddingContractsTests {

	private static final Instant GENERATED_AT = Instant.parse("2026-08-19T12:00:00Z");

	@Test
	void generatedEmbeddingDefensivelyCopiesAValidFiniteVector() {
		List<Float> mutable = new ArrayList<>(List.of(0.25f, -0.5f));

		GeneratedEmbedding generated = new GeneratedEmbedding(mutable, GENERATED_AT);
		mutable.set(0, 99.0f);

		assertThat(generated.vector()).containsExactly(0.25f, -0.5f);
		assertThat(generated.generatedAt()).isEqualTo(GENERATED_AT);
		assertThatThrownBy(() -> generated.vector().add(1.0f))
				.isInstanceOf(UnsupportedOperationException.class);
	}

	@Test
	void generatedEmbeddingRejectsInvalidVectorContracts() {
		assertThatThrownBy(() -> new GeneratedEmbedding(List.of(), GENERATED_AT))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("must not be empty");
		assertThatThrownBy(() -> new GeneratedEmbedding(List.of(0.0f, -0.0f), GENERATED_AT))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("zero vector");
		assertThatThrownBy(() -> new GeneratedEmbedding(
				new ArrayList<>(List.of(1.0f, Float.NaN)), GENERATED_AT))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("finite");
		assertThatThrownBy(() -> new GeneratedEmbedding(
				new ArrayList<>(List.of(1.0f, Float.POSITIVE_INFINITY)), GENERATED_AT))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("finite");
		assertThatThrownBy(() -> new GeneratedEmbedding(
				new ArrayList<>(java.util.Arrays.asList(1.0f, null)), GENERATED_AT))
				.isInstanceOf(NullPointerException.class);
	}

	@Test
	void generationFailureExposesOnlyStableClassificationAndSafeMessage() {
		IllegalStateException cause = new IllegalStateException("internal detail");
		EmbeddingGenerationException failure = new EmbeddingGenerationException(
				"LOCAL_TIMEOUT",
				true,
				"Local embedding request timed out",
				cause);

		assertThat(failure.errorCode()).isEqualTo("LOCAL_TIMEOUT");
		assertThat(failure.retryable()).isTrue();
		assertThat(failure.scope()).isEqualTo(EmbeddingFailureScope.INPUT);
		assertThat(failure).hasMessage("Local embedding request timed out").hasCause(cause);

		EmbeddingGenerationException systemic = new EmbeddingGenerationException(
				"MODEL_DRIFT",
				false,
				EmbeddingFailureScope.SYSTEM,
				"Configured model changed");
		assertThat(systemic.scope()).isEqualTo(EmbeddingFailureScope.SYSTEM);
	}

	@Test
	void generationFailureRejectsUnstableCodesAndUnsafeMessages() {
		assertThatThrownBy(() -> new EmbeddingGenerationException(
				"temporary-error", true, "Temporary failure"))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("error code");
		assertThatThrownBy(() -> new EmbeddingGenerationException(
				"LOCAL_ERROR", false, "unsafe\nresponse detail"))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("single-line");
	}
}
