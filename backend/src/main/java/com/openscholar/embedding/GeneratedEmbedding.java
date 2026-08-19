package com.openscholar.embedding;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

public record GeneratedEmbedding(List<Float> vector, Instant generatedAt) {

	public GeneratedEmbedding {
		vector = List.copyOf(Objects.requireNonNull(vector, "vector"));
		if (vector.isEmpty()) {
			throw new IllegalArgumentException("Generated embedding vector must not be empty");
		}
		boolean hasNonZeroComponent = false;
		for (Float component : vector) {
			if (component == null || !Float.isFinite(component)) {
				throw new IllegalArgumentException("Generated embedding vector components must be finite");
			}
			hasNonZeroComponent |= component != 0.0f;
		}
		if (!hasNonZeroComponent) {
			throw new IllegalArgumentException("Generated embedding vector must not be the zero vector");
		}
		generatedAt = Objects.requireNonNull(generatedAt, "generatedAt");
	}
}
