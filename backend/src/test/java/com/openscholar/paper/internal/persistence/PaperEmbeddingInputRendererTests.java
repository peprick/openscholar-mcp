package com.openscholar.paper.internal.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;

import com.openscholar.paper.EmbeddingContentKind;
import com.openscholar.paper.EmbeddingDistanceMetric;
import com.openscholar.paper.EmbeddingInputTooLargeException;
import com.openscholar.paper.EmbeddingProfile;
import com.openscholar.paper.PaperEmbeddingSource;
import org.junit.jupiter.api.Test;

class PaperEmbeddingInputRendererTests {

	private static final UUID PAPER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

	private final PaperEmbeddingInputRenderer renderer = new PaperEmbeddingInputRenderer();

	@Test
	void normalizesUnicodeLineEndingsAndOuterWhitespaceBeforeChecksummingUtf8Input() {
		EmbeddingProfile profile = profile();

		PaperEmbeddingSource decomposed = renderer.render(
				PAPER_ID,
				profile,
				"  Cafe\u0301\r\nResearch  ",
				"  Line one\rLine two  ");
		PaperEmbeddingSource composed = renderer.render(
				PAPER_ID,
				profile,
				"Caf\u00e9\nResearch",
				"Line one\nLine two");

		assertThat(decomposed).isEqualTo(composed);
		assertThat(decomposed.input())
				.isEqualTo("Title: Caf\u00e9\nResearch\nAbstract: Line one\nLine two");
		assertThat(decomposed.contentChecksum())
				.isEqualTo("67f1d59535955a8e0e1416af660641398b2a49755adb2f054ecff1352cacd95b");
	}

	@Test
	void rendersMissingAbstractAsAnExplicitEmptyField() {
		PaperEmbeddingSource source = renderer.render(
				PAPER_ID,
				profile(),
				"A title",
				null);

		assertThat(source.input()).isEqualTo("Title: A title\nAbstract: ");
		assertThat(source.contentChecksum()).matches("[0-9a-f]{64}");
	}

	@Test
	void rejectsInputsAboveTheVersionedUtf8ByteLimitInsteadOfTruncatingThem() {
		assertThatThrownBy(() -> renderer.render(
				PAPER_ID,
				profile(),
				"A title",
				"a".repeat(PaperEmbeddingInputRenderer.MAX_INPUT_BYTES)))
				.isInstanceOf(EmbeddingInputTooLargeException.class)
				.hasMessageContaining("maximum is " + PaperEmbeddingInputRenderer.MAX_INPUT_BYTES);
	}

	private static EmbeddingProfile profile() {
		return new EmbeddingProfile(
				"fixture-title-abstract-v1",
				"TEST",
				"fixture-model",
				"revision-1",
				EmbeddingContentKind.TITLE_ABSTRACT,
				1,
				3,
				EmbeddingDistanceMetric.COSINE);
	}
}
