package com.openscholar.jobs;

public interface EmbeddingBackfillUseCase {

	EmbeddingBackfillResult run(EmbeddingBackfillCommand command);
}
