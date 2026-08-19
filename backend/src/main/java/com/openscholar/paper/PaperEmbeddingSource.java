package com.openscholar.paper;

import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

public record PaperEmbeddingSource(
		UUID paperId,
		String profileKey,
		String input,
		String contentChecksum) {

	private static final Pattern SHA_256 = Pattern.compile("[0-9a-f]{64}");

	public PaperEmbeddingSource {
		paperId = Objects.requireNonNull(paperId, "paperId");
		profileKey = Objects.requireNonNull(profileKey, "profileKey");
		input = Objects.requireNonNull(input, "input");
		contentChecksum = Objects.requireNonNull(contentChecksum, "contentChecksum");
		if (!SHA_256.matcher(contentChecksum).matches()) {
			throw new IllegalArgumentException("Embedding content checksum must be lowercase SHA-256");
		}
	}
}
