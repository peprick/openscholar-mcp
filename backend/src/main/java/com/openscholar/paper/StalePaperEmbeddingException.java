package com.openscholar.paper;

import java.util.UUID;

public class StalePaperEmbeddingException extends RuntimeException {

	public StalePaperEmbeddingException(UUID paperId, String profileKey) {
		super("Paper content changed while generating embedding for paper "
				+ paperId + " and profile " + profileKey);
	}
}
