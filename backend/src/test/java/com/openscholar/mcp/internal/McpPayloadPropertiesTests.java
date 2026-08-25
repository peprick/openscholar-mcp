package com.openscholar.mcp.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class McpPayloadPropertiesTests {

	@Test
	void suppliesSafeDefaults() {
		McpPayloadProperties properties = new McpPayloadProperties(null, null, null);

		assertThat(properties.maxRequestBytes()).isEqualTo(1_048_576);
		assertThat(properties.maxToolResultBytes()).isEqualTo(1_048_576);
		assertThat(properties.maxResourceResultBytes()).isEqualTo(1_048_576);
	}

	@Test
	void preservesExplicitLimits() {
		McpPayloadProperties properties = new McpPayloadProperties(4_096L, 8_192L, 16_384L);

		assertThat(properties.maxRequestBytes()).isEqualTo(4_096);
		assertThat(properties.maxToolResultBytes()).isEqualTo(8_192);
		assertThat(properties.maxResourceResultBytes()).isEqualTo(16_384);
	}

	@Test
	void rejectsUnsafeLimits() {
		assertThatThrownBy(() -> new McpPayloadProperties(1_023L, 2_048L, null))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("max-request-bytes");
		assertThatThrownBy(() -> new McpPayloadProperties(2_048L, 16_777_217L, null))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("max-tool-result-bytes");
		assertThatThrownBy(() -> new McpPayloadProperties(2_048L, 2_048L, 16_777_217L))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("max-resource-result-bytes");
	}
}
