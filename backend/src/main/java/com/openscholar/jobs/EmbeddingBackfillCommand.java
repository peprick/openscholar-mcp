package com.openscholar.jobs;

import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

public record EmbeddingBackfillCommand(
		String profileKey,
		UUID afterExclusive,
		int limit,
		int maxAttempts) {

	private static final Pattern PROFILE_KEY = Pattern.compile("[a-z0-9][a-z0-9._-]{2,127}");

	public EmbeddingBackfillCommand {
		profileKey = Objects.requireNonNull(profileKey, "profileKey").strip();
		if (!PROFILE_KEY.matcher(profileKey).matches()) {
			throw new IllegalArgumentException("Embedding backfill profile key is invalid");
		}
		if (limit < 1 || limit > 500) {
			throw new IllegalArgumentException("Embedding backfill limit must be between 1 and 500");
		}
		if (maxAttempts < 1 || maxAttempts > 3) {
			throw new IllegalArgumentException("Embedding backfill maxAttempts must be between 1 and 3");
		}
	}
}
