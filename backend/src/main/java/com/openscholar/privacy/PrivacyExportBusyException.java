package com.openscholar.privacy;

import java.time.Duration;
import java.util.Objects;

public final class PrivacyExportBusyException extends RuntimeException {

	private final Duration retryAfter;

	public PrivacyExportBusyException(Duration retryAfter) {
		super("Personal-data export capacity is temporarily full. Try again later.");
		this.retryAfter = Objects.requireNonNull(retryAfter, "retryAfter");
	}

	public Duration retryAfter() {
		return retryAfter;
	}
}
