package com.openscholar.paper;

public class EmbeddingProfileConflictException extends RuntimeException {

	public EmbeddingProfileConflictException(String message) {
		super(message);
	}

	public EmbeddingProfileConflictException(String message, Throwable cause) {
		super(message, cause);
	}
}
