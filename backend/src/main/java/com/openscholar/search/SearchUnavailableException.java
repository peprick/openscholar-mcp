package com.openscholar.search;

import java.time.Duration;

public class SearchUnavailableException extends RuntimeException {

	private final boolean retryable;
	private final Duration retryAfter;

	public SearchUnavailableException(String message, boolean retryable, Duration retryAfter, Throwable cause) {
		super(message, cause);
		this.retryable = retryable;
		this.retryAfter = retryAfter;
	}

	public boolean retryable() {
		return retryable;
	}

	public Duration retryAfter() {
		return retryAfter;
	}
}
