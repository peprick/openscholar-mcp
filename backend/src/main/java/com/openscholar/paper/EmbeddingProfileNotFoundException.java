package com.openscholar.paper;

public class EmbeddingProfileNotFoundException extends RuntimeException {

	public EmbeddingProfileNotFoundException(String profileKey) {
		super("Embedding profile not found: " + profileKey);
	}
}
