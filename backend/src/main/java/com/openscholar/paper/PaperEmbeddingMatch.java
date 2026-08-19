package com.openscholar.paper;

import java.util.Objects;
import java.util.UUID;

public record PaperEmbeddingMatch(int rank, UUID paperId, double cosineSimilarity) {

	public PaperEmbeddingMatch {
		if (rank < 1) {
			throw new IllegalArgumentException("Embedding match rank must be positive");
		}
		paperId = Objects.requireNonNull(paperId, "paperId");
		if (!Double.isFinite(cosineSimilarity)) {
			throw new IllegalArgumentException("Cosine similarity must be finite");
		}
	}
}
