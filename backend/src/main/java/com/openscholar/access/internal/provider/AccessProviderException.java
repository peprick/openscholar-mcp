package com.openscholar.access.internal.provider;

import java.time.Duration;
import java.util.Objects;

public final class AccessProviderException extends RuntimeException {

	private final AccessSource source;
	private final String errorCode;
	private final boolean retryable;
	private final Duration retryAfter;

	public AccessProviderException(
			AccessSource source,
			String errorCode,
			String message,
			boolean retryable,
			Duration retryAfter,
			Throwable cause) {
		super(message, cause);
		this.source = Objects.requireNonNull(source, "source");
		this.errorCode = Objects.requireNonNull(errorCode, "errorCode");
		this.retryable = retryable;
		this.retryAfter = retryAfter;
	}

	public AccessSource source() {
		return source;
	}

	public String errorCode() {
		return errorCode;
	}

	public boolean retryable() {
		return retryable;
	}

	public Duration retryAfter() {
		return retryAfter;
	}
}
