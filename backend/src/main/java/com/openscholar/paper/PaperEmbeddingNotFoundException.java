package com.openscholar.paper;

import java.util.UUID;

public class PaperEmbeddingNotFoundException extends RuntimeException {

	public PaperEmbeddingNotFoundException(UUID paperId, String profileKey) {
		super("Paper embedding not found for paper " + paperId + " and profile " + profileKey);
	}
}
