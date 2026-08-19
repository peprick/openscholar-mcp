package com.openscholar.jobs.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;

import com.openscholar.embedding.EmbeddingGenerator;
import com.openscholar.embedding.GeneratedEmbedding;
import com.openscholar.paper.EmbeddingContentKind;
import com.openscholar.paper.EmbeddingDistanceMetric;
import com.openscholar.paper.EmbeddingProfile;
import org.junit.jupiter.api.Test;

class EmbeddingBackfillPropertiesTests {

	@Test
	void createsABoundedCommandAndTreatsABlankCursorAsAbsent() {
		EmbeddingBackfillProperties properties = new EmbeddingBackfillProperties(
				true, "paper-semantic-v1", "  ", 100, 2);

		assertThat(properties.command(List.of()).profileKey()).isEqualTo("paper-semantic-v1");
		assertThat(properties.command(List.of()).afterExclusive()).isNull();
		assertThat(properties.command(List.of()).limit()).isEqualTo(100);
		assertThat(properties.command(List.of()).maxAttempts()).isEqualTo(2);
	}

	@Test
	void parsesAnExplicitResumeCursor() {
		UUID cursor = UUID.randomUUID();

		assertThat(new EmbeddingBackfillProperties(
				true, "paper-semantic-v1", " " + cursor + " ", 25, 1)
				.command(List.of())
				.afterExclusive())
				.isEqualTo(cursor);
	}

	@Test
	void derivesTheProfileFromTheOnlyConfiguredGeneratorWhenTheSelectorIsBlank() {
		EmbeddingProfile profile = new EmbeddingProfile(
				"paper-semantic-v1-digest",
				"test",
				"test-model",
				"immutable-revision",
				EmbeddingContentKind.TITLE_ABSTRACT,
				1,
				2,
				EmbeddingDistanceMetric.COSINE);
		EmbeddingGenerator generator = new EmbeddingGenerator() {
			@Override
			public EmbeddingProfile profile() {
				return profile;
			}

			@Override
			public void verify() {
				throw new AssertionError("Properties must not verify a generator");
			}

			@Override
			public GeneratedEmbedding generate(String input) {
				throw new AssertionError("Properties must not invoke a generator");
			}
		};
		EmbeddingBackfillProperties properties = new EmbeddingBackfillProperties(
				true, "  ", null, 10, 1);

		assertThat(properties.command(List.of(generator)).profileKey())
				.isEqualTo("paper-semantic-v1-digest");
		assertThatThrownBy(() -> properties.command(List.of()))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("exactly one generator");
		assertThatThrownBy(() -> properties.command(List.of(generator, generator)))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("exactly one generator");
	}

	@Test
	void rejectsAnInvalidCursorOrCommandBoundsAtBindingTime() {
		assertThatThrownBy(() -> new EmbeddingBackfillProperties(
				true, "paper-semantic-v1", "not-a-uuid", 100, 2))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("afterExclusive");
		assertThatThrownBy(() -> new EmbeddingBackfillProperties(
				true, "paper-semantic-v1", null, 501, 2))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("limit");
		assertThatThrownBy(() -> new EmbeddingBackfillProperties(
				true, "paper-semantic-v1", null, 100, 4))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("maxAttempts");
	}
}
