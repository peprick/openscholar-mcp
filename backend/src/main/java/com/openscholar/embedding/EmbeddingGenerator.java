package com.openscholar.embedding;

import com.openscholar.paper.EmbeddingProfile;

public interface EmbeddingGenerator {

	EmbeddingProfile profile();

	void verify();

	GeneratedEmbedding generate(String input);
}
