package com.openscholar.provider;

import java.time.Duration;

public class ProviderException extends RuntimeException {

	private final ProviderId provider;
	private final String errorCode;
	private final boolean retryable;
	private final Duration retryAfter;

	public ProviderException(
			ProviderId provider,
			String errorCode,
			String message,
			boolean retryable,
			Duration retryAfter,
			Throwable cause) {
		super(message, cause);
		this.provider = provider;
		this.errorCode = errorCode;
		this.retryable = retryable;
		this.retryAfter = retryAfter;
	}

	public ProviderId provider() {
		return provider;
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
