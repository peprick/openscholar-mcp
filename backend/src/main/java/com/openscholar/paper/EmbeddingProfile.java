package com.openscholar.paper;

import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

public record EmbeddingProfile(
		String profileKey,
		String provider,
		String model,
		String modelRevision,
		EmbeddingContentKind contentKind,
		int inputPolicyVersion,
		int dimensions,
		EmbeddingDistanceMetric distanceMetric) {

	private static final Pattern PROFILE_KEY = Pattern.compile("[a-z0-9][a-z0-9._-]{2,127}");

	public EmbeddingProfile {
		profileKey = cleanRequired(profileKey, "Embedding profile key must not be blank");
		if (!PROFILE_KEY.matcher(profileKey).matches()) {
			throw new IllegalArgumentException(
					"Embedding profile key must contain 3 to 128 lowercase letters, digits, dots, underscores, or hyphens");
		}
		provider = cleanRequired(provider, "Embedding provider must not be blank");
		model = cleanRequired(model, "Embedding model must not be blank");
		modelRevision = cleanRequired(modelRevision, "Embedding model revision must not be blank");
		if (provider.length() > 64) {
			throw new IllegalArgumentException("Embedding provider must not exceed 64 characters");
		}
		if (model.length() > 255) {
			throw new IllegalArgumentException("Embedding model must not exceed 255 characters");
		}
		if (modelRevision.length() > 255) {
			throw new IllegalArgumentException("Embedding model revision must not exceed 255 characters");
		}
		if (modelRevision.toLowerCase(Locale.ROOT).matches("main|master|latest")) {
			throw new IllegalArgumentException(
					"Embedding model revision must identify an immutable artifact");
		}
		contentKind = Objects.requireNonNull(contentKind, "contentKind");
		if (inputPolicyVersion < 1) {
			throw new IllegalArgumentException("Embedding input policy version must be positive");
		}
		if (dimensions < 1 || dimensions > 2000) {
			throw new IllegalArgumentException("Embedding dimensions must be between 1 and 2000");
		}
		distanceMetric = Objects.requireNonNull(distanceMetric, "distanceMetric");
	}

	private static String cleanRequired(String value, String message) {
		String clean = Objects.requireNonNull(value, message).strip();
		if (clean.isEmpty()) {
			throw new IllegalArgumentException(message);
		}
		return clean;
	}
}
