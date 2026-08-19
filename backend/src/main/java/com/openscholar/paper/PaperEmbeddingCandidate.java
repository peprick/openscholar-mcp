package com.openscholar.paper;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

public record PaperEmbeddingCandidate(
		UUID paperId,
		String profileKey,
		String contentChecksum,
		List<Float> vector,
		Instant generatedAt) {

	private static final Pattern SHA_256 = Pattern.compile("[0-9a-f]{64}");

	public PaperEmbeddingCandidate {
		paperId = Objects.requireNonNull(paperId, "paperId");
		profileKey = Objects.requireNonNull(profileKey, "profileKey");
		contentChecksum = Objects.requireNonNull(contentChecksum, "contentChecksum");
		if (!SHA_256.matcher(contentChecksum).matches()) {
			throw new IllegalArgumentException("Embedding content checksum must be lowercase SHA-256");
		}
		vector = List.copyOf(Objects.requireNonNull(vector, "vector"));
		generatedAt = Objects.requireNonNull(generatedAt, "generatedAt");
	}
}
