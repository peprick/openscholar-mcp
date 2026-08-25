package com.openscholar.privacy.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;

import org.junit.jupiter.api.Test;

class PrivacyExportPropertiesTests {

	@Test
	void suppliesSmallBoundedDefaults() {
		PrivacyExportProperties properties = new PrivacyExportProperties(null, null, null);

		assertThat(properties.globalPermits()).isEqualTo(4);
		assertThat(properties.perPrincipalPermits()).isOne();
		assertThat(properties.retryAfter()).isEqualTo(Duration.ofSeconds(10));
	}

	@Test
	void preservesValidExplicitConfiguration() {
		PrivacyExportProperties properties = new PrivacyExportProperties(
				8, 2, Duration.ofSeconds(45));

		assertThat(properties.globalPermits()).isEqualTo(8);
		assertThat(properties.perPrincipalPermits()).isEqualTo(2);
		assertThat(properties.retryAfter()).isEqualTo(Duration.ofSeconds(45));
	}

	@Test
	void acceptsEveryInclusiveSafetyBoundary() {
		PrivacyExportProperties minimums = new PrivacyExportProperties(
				1, 1, Duration.ofSeconds(1));
		PrivacyExportProperties maximums = new PrivacyExportProperties(
				16, 4, Duration.ofMinutes(5));

		assertThat(minimums.globalPermits()).isOne();
		assertThat(minimums.perPrincipalPermits()).isOne();
		assertThat(minimums.retryAfter()).isEqualTo(Duration.ofSeconds(1));
		assertThat(maximums.globalPermits()).isEqualTo(16);
		assertThat(maximums.perPrincipalPermits()).isEqualTo(4);
		assertThat(maximums.retryAfter()).isEqualTo(Duration.ofMinutes(5));
	}

	@Test
	void rejectsUnsafeConcurrencyAndRetryBounds() {
		assertInvalid(0, 1, Duration.ofSeconds(10), "global-permits");
		assertInvalid(17, 1, Duration.ofSeconds(10), "global-permits");
		assertInvalid(4, 0, Duration.ofSeconds(10), "per-principal-permits");
		assertInvalid(4, 5, Duration.ofSeconds(10), "per-principal-permits");
		assertInvalid(1, 2, Duration.ofSeconds(10), "must not exceed");
		assertInvalid(4, 1, Duration.ofMillis(999), "retry-after");
		assertInvalid(4, 1, Duration.ofMinutes(5).plusMillis(1), "retry-after");
	}

	private static void assertInvalid(
			int globalPermits,
			int perPrincipalPermits,
			Duration retryAfter,
			String message) {
		assertThatThrownBy(() -> new PrivacyExportProperties(
				globalPermits, perPrincipalPermits, retryAfter))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining(message);
	}
}
