package com.openscholar.access.internal.verification;

import java.net.URI;
import java.util.Objects;

public record LinkVerificationResult(
		Status status,
		URI finalUri,
		int redirectCount,
		int httpStatus,
		LinkVerificationFailure failure) {

	private static final int NO_HTTP_STATUS = -1;

	public LinkVerificationResult {
		Objects.requireNonNull(status, "status");
		Objects.requireNonNull(failure, "failure");
		if ((status == Status.VERIFIED) != (failure == LinkVerificationFailure.NONE)) {
			throw new IllegalArgumentException("verified results must have no failure and rejected results must have one");
		}
		if (redirectCount < 0) {
			throw new IllegalArgumentException("redirectCount must not be negative");
		}
	}

	static LinkVerificationResult verified(URI uri, int redirects, int httpStatus) {
		return new LinkVerificationResult(Status.VERIFIED, uri, redirects, httpStatus, LinkVerificationFailure.NONE);
	}

	static LinkVerificationResult rejected(
			URI uri,
			int redirects,
			int httpStatus,
			LinkVerificationFailure failure) {
		return new LinkVerificationResult(Status.REJECTED, uri, redirects, httpStatus, failure);
	}

	static LinkVerificationResult rejected(URI uri, LinkVerificationFailure failure) {
		return rejected(uri, 0, NO_HTTP_STATUS, failure);
	}

	public boolean verified() {
		return status == Status.VERIFIED;
	}

	public enum Status {
		VERIFIED,
		REJECTED
	}
}
