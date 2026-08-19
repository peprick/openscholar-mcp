package com.openscholar.mcp.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class McpSecurityPropertiesTests {

	@Test
	void normalizesTheKeyAndConfiguredOrigins() {
		McpSecurityProperties properties = new McpSecurityProperties(
				"  local-secret  ",
				Arrays.asList(
						" HTTPS://Agent.Example:443 ",
						"https://agent.example:443",
						"http://LOCALHOST:3000",
						null,
						"  "));

		assertThat(properties.localApiKey()).isEqualTo("local-secret");
		assertThat(properties.hasApiKey()).isTrue();
		assertThat(properties.allowedOrigins())
			.containsExactly("https://agent.example:443", "http://localhost:3000")
			.isUnmodifiable();
	}

	@Test
	void suppliesSafeDefaultsAndAllowsRequestsWithoutAnOrigin() {
		McpSecurityProperties properties = new McpSecurityProperties(null, null);

		assertThat(properties.localApiKey()).isEmpty();
		assertThat(properties.hasApiKey()).isFalse();
		assertThat(properties.allowedOrigins()).isEmpty();
		assertThat(properties.allowsOrigin(null)).isTrue();
		assertThat(properties.allowsOrigin("https://agent.example")).isFalse();
	}

	@Test
	void comparesNormalizedOriginsButKeepsPortsExact() {
		McpSecurityProperties properties = new McpSecurityProperties(
				"key",
				List.of("https://agent.example:443", "http://localhost:3000"));

		assertThat(properties.allowsOrigin(" HTTPS://AGENT.EXAMPLE:443 ")).isTrue();
		assertThat(properties.allowsOrigin("http://LOCALHOST:3000")).isTrue();
		assertThat(properties.allowsOrigin("https://agent.example")).isFalse();
		assertThat(properties.allowsOrigin("http://localhost:3001")).isFalse();
	}

	@ParameterizedTest
	@ValueSource(strings = {
		"not-an-origin",
		"ftp://agent.example",
		"https://user@agent.example",
		"https://agent.example/",
		"https://agent.example/path",
		"https://agent.example?query=true",
		"https://agent.example#fragment"
	})
	void rejectsInvalidConfiguredOrigins(String origin) {
		assertThatThrownBy(() -> new McpSecurityProperties("key", List.of(origin)))
			.isInstanceOf(IllegalArgumentException.class);
	}

	@ParameterizedTest
	@ValueSource(strings = {
		"",
		"not-an-origin",
		"ftp://agent.example",
		"https://user@agent.example",
		"https://agent.example/",
		"https://agent.example/path",
		"https://agent.example?query=true",
		"https://agent.example#fragment"
	})
	void rejectsInvalidRequestOriginsWithoutThrowing(String origin) {
		McpSecurityProperties properties = new McpSecurityProperties("key", List.of("https://agent.example"));

		assertThat(properties.allowsOrigin(origin)).isFalse();
	}
}
