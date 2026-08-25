package com.openscholar.privacy.internal;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("openscholar.privacy.export")
record PrivacyExportProperties(
		Integer globalPermits,
		Integer perPrincipalPermits,
		Duration retryAfter) {

	private static final int DEFAULT_GLOBAL_PERMITS = 4;
	private static final int DEFAULT_PER_PRINCIPAL_PERMITS = 1;
	private static final Duration DEFAULT_RETRY_AFTER = Duration.ofSeconds(10);

	PrivacyExportProperties {
		globalPermits = globalPermits == null ? DEFAULT_GLOBAL_PERMITS : globalPermits;
		perPrincipalPermits = perPrincipalPermits == null
				? DEFAULT_PER_PRINCIPAL_PERMITS
				: perPrincipalPermits;
		retryAfter = retryAfter == null ? DEFAULT_RETRY_AFTER : retryAfter;

		if (globalPermits < 1 || globalPermits > 16) {
			throw new IllegalArgumentException("Privacy export global-permits must be between 1 and 16");
		}
		if (perPrincipalPermits < 1 || perPrincipalPermits > 4) {
			throw new IllegalArgumentException(
					"Privacy export per-principal-permits must be between 1 and 4");
		}
		if (perPrincipalPermits > globalPermits) {
			throw new IllegalArgumentException(
					"Privacy export per-principal-permits must not exceed global-permits");
		}
		if (retryAfter.compareTo(Duration.ofSeconds(1)) < 0
				|| retryAfter.compareTo(Duration.ofMinutes(5)) > 0) {
			throw new IllegalArgumentException(
					"Privacy export retry-after must be between one second and five minutes");
		}
	}
}
