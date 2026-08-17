package com.openscholar.access;

import java.time.Duration;

public class AccessUnavailableException extends RuntimeException {

	private final boolean retryable;
	private final Duration retryAfter;

	public AccessUnavailableException(
			String message, boolean retryable, Duration retryAfter, Throwable cause) {
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
