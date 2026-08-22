package com.openscholar.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class OidcSecurityPropertiesTests {

	@Test
	void disabledModeAllowsEmptyHostedConfiguration() {
		OidcSecurityProperties properties = new OidcSecurityProperties(false, null, null, null, null);

		assertThat(properties.enabled()).isFalse();
		assertThatThrownBy(properties::mcpProtectedResourceMetadataUri)
				.isInstanceOf(IllegalStateException.class);
	}

	@Test
	void validatesHostedConfigurationAndDerivesPathSpecificMetadataUri() {
		OidcSecurityProperties properties = new OidcSecurityProperties(
				true,
				URI.create("https://issuer.example/tenant"),
				URI.create("https://issuer.example/tenant/jwks"),
				"  https://research.example/mcp  ",
				URI.create("https://research.example:8443/mcp"));

		assertThat(properties.audience()).isEqualTo("https://research.example/mcp");
		assertThat(properties.mcpProtectedResourceMetadataUri())
				.isEqualTo(URI.create("https://research.example:8443/.well-known/oauth-protected-resource/mcp"));
	}

	@Test
	void allowsPlainHttpOnlyForExplicitLoopbackDevelopment() {
		OidcSecurityProperties properties = new OidcSecurityProperties(
				true,
				URI.create("http://localhost:9000/issuer"),
				URI.create("http://127.0.0.1:9000/jwks"),
				"openscholar-local",
				URI.create("http://[::1]:8080/mcp"));

		assertThat(properties.mcpProtectedResourceMetadataUri())
				.isEqualTo(URI.create("http://[::1]:8080/.well-known/oauth-protected-resource/mcp"));
	}

	@ParameterizedTest
	@ValueSource(strings = {
		"http://issuer.example",
		"ftp://issuer.example",
		"https://user@issuer.example",
		"https://issuer.example?tenant=unsafe",
		"https://issuer.example#fragment"
	})
	void rejectsUnsafeIssuerUris(String issuer) {
		assertThatThrownBy(() -> enabled(URI.create(issuer), URI.create("https://issuer.example/jwks"),
				URI.create("https://research.example/mcp")))
				.isInstanceOf(IllegalArgumentException.class);
	}

	@ParameterizedTest
	@ValueSource(strings = {
		"https://research.example",
		"https://research.example/mcp/",
		"https://research.example/other",
		"http://research.example/mcp"
	})
	void rejectsNonCanonicalOrInsecureMcpResources(String resource) {
		assertThatThrownBy(() -> enabled(
				URI.create("https://issuer.example"),
				URI.create("https://issuer.example/jwks"),
				URI.create(resource)))
				.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void requiresEveryHostedValueAndBoundsIdentityFields() {
		assertThatThrownBy(() -> new OidcSecurityProperties(
				true, null, URI.create("https://issuer.example/jwks"), "aud", URI.create("https://app.example/mcp")))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new OidcSecurityProperties(
				true, URI.create("https://issuer.example"), null, "aud", URI.create("https://app.example/mcp")))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new OidcSecurityProperties(
				true, URI.create("https://issuer.example"), URI.create("https://issuer.example/jwks"), " ",
				URI.create("https://app.example/mcp")))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new OidcSecurityProperties(
				true, URI.create("https://issuer.example/" + "a".repeat(500)),
				URI.create("https://issuer.example/jwks"), "aud", URI.create("https://app.example/mcp")))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new OidcSecurityProperties(
				true, URI.create("https://issuer.example"), URI.create("https://issuer.example/jwks"),
				"a".repeat(256), URI.create("https://app.example/mcp")))
				.isInstanceOf(IllegalArgumentException.class);
	}

	private static OidcSecurityProperties enabled(URI issuer, URI jwks, URI resource) {
		return new OidcSecurityProperties(true, issuer, jwks, "openscholar", resource);
	}
}
