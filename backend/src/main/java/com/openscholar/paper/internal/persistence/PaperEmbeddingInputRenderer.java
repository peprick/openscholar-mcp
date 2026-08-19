package com.openscholar.paper.internal.persistence;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.util.HexFormat;
import java.util.UUID;

import com.openscholar.paper.EmbeddingContentKind;
import com.openscholar.paper.EmbeddingInputTooLargeException;
import com.openscholar.paper.EmbeddingProfile;
import com.openscholar.paper.PaperEmbeddingSource;

final class PaperEmbeddingInputRenderer {

	static final int MAX_INPUT_BYTES = 24 * 1024;

	PaperEmbeddingSource render(
			UUID paperId, EmbeddingProfile profile, String title, String abstractText) {
		if (profile.contentKind() != EmbeddingContentKind.TITLE_ABSTRACT
				|| profile.inputPolicyVersion() != 1) {
			throw new IllegalArgumentException(
					"Unsupported embedding input policy: " + profile.contentKind()
							+ " v" + profile.inputPolicyVersion());
		}
		String input = "Title: " + normalize(title) + "\nAbstract: " + normalize(abstractText);
		byte[] inputBytes = input.getBytes(StandardCharsets.UTF_8);
		if (inputBytes.length > MAX_INPUT_BYTES) {
			throw new EmbeddingInputTooLargeException(
					paperId, inputBytes.length, MAX_INPUT_BYTES);
		}
		return new PaperEmbeddingSource(
				paperId,
				profile.profileKey(),
				input,
				sha256(inputBytes));
	}

	private static String normalize(String value) {
		if (value == null) {
			return "";
		}
		String withLfLineEndings = value.replace("\r\n", "\n").replace('\r', '\n').strip();
		return Normalizer.normalize(withLfLineEndings, Normalizer.Form.NFC);
	}

	private static String sha256(byte[] value) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
		}
		catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException("SHA-256 is unavailable", exception);
		}
	}
}
