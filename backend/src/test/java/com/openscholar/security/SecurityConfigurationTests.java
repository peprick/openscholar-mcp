package com.openscholar.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;

class SecurityConfigurationTests {

	private static final String ISSUER = "https://issuer.example/tenant";
	private static final String AUDIENCE = "https://research.example/mcp";

	private final OidcSecurityProperties properties = new OidcSecurityProperties(
			true,
			URI.create(ISSUER),
			URI.create(ISSUER + "/jwks"),
			AUDIENCE,
			URI.create("https://research.example/mcp"));

	@Test
	void acceptsOnlyCurrentIssuerAudienceAndExpiry() {
		Instant now = Instant.now();

		assertThat(SecurityConfiguration.tokenValidator(properties).validate(
				jwt(ISSUER, List.of(AUDIENCE), now.minusSeconds(5), now.plusSeconds(300))).hasErrors())
				.isFalse();
	}

	@Test
	void rejectsMissingOrWrongAudienceAndWrongIssuer() {
		Instant now = Instant.now();

		assertThat(SecurityConfiguration.tokenValidator(properties).validate(
				jwt(ISSUER, null, now, now.plusSeconds(300))).hasErrors()).isTrue();
		assertThat(SecurityConfiguration.tokenValidator(properties).validate(
				jwt(ISSUER, List.of("another-api"), now, now.plusSeconds(300))).hasErrors()).isTrue();
		assertThat(SecurityConfiguration.tokenValidator(properties).validate(
				jwt("https://attacker.example", List.of(AUDIENCE), now, now.plusSeconds(300))).hasErrors()).isTrue();
	}

	@Test
	void rejectsExpiredAndNonExpiringTokens() {
		Instant now = Instant.now();

		assertThat(SecurityConfiguration.tokenValidator(properties).validate(
				jwt(ISSUER, List.of(AUDIENCE), now.minusSeconds(600), now.minusSeconds(300))).hasErrors())
				.isTrue();
		assertThat(SecurityConfiguration.tokenValidator(properties).validate(
				jwt(ISSUER, List.of(AUDIENCE), now, null)).hasErrors())
				.isTrue();
	}

	private static Jwt jwt(String issuer, List<String> audience, Instant issuedAt, Instant expiresAt) {
		Jwt.Builder builder = Jwt.withTokenValue("synthetic-token")
				.header("alg", "RS256")
				.issuer(issuer)
				.subject("subject-1")
				.issuedAt(issuedAt);
		if (audience != null) {
			builder.audience(audience);
		}
		if (expiresAt != null) {
			builder.expiresAt(expiresAt);
		}
		return builder.build();
	}
}
