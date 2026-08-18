package com.openscholar.access;

import java.time.Duration;

public class AccessRefreshTooSoonException extends RuntimeException {

	private final Duration retryAfter;

	public AccessRefreshTooSoonException(Duration retryAfter) {
		super("Access was force-refreshed too recently");
		this.retryAfter = retryAfter;
	}

	public Duration retryAfter() {
		return retryAfter;
	}
}
