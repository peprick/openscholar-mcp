package com.openscholar.access.internal.verification;

import java.time.Duration;

record LinkVerificationPolicy(
		boolean allowHttp,
		int maxRedirects,
		Duration connectTimeout,
		Duration requestTimeout,
		int maxProbeBytes) {

	private static final int MINIMUM_PDF_SIGNATURE_BYTES = 5;
	private static final int MAXIMUM_REDIRECTS = 10;
	private static final int MAXIMUM_PROBE_BYTES = 64 * 1024;

	LinkVerificationPolicy {
		if (maxRedirects < 0 || maxRedirects > MAXIMUM_REDIRECTS) {
			throw new IllegalArgumentException("maxRedirects must be between 0 and 10");
		}
		connectTimeout = requirePositive(connectTimeout, "connectTimeout");
		requestTimeout = requirePositive(requestTimeout, "requestTimeout");
		if (maxProbeBytes < MINIMUM_PDF_SIGNATURE_BYTES || maxProbeBytes > MAXIMUM_PROBE_BYTES) {
			throw new IllegalArgumentException("maxProbeBytes must be between 5 and 65536");
		}
	}

	static LinkVerificationPolicy secureDefaults() {
		return new LinkVerificationPolicy(false, 4, Duration.ofSeconds(3), Duration.ofSeconds(10), 4096);
	}

	private static Duration requirePositive(Duration value, String name) {
		if (value == null || value.isZero() || value.isNegative()) {
			throw new IllegalArgumentException(name + " must be positive");
		}
		return value;
	}
}
