package com.openscholar.mcp.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;

import org.junit.jupiter.api.Test;

class McpRateLimitPropertiesTests {

	@Test
	void suppliesSafeDefaults() {
		McpRateLimitProperties properties = new McpRateLimitProperties(null, null, null, null);

		assertThat(properties.enabled()).isTrue();
		assertThat(properties.requestsPerWindow()).isEqualTo(120);
		assertThat(properties.window()).isEqualTo(Duration.ofMinutes(1));
		assertThat(properties.maximumTrackedClients()).isEqualTo(10_000);
	}

	@Test
	void preservesExplicitConfiguration() {
		McpRateLimitProperties properties = new McpRateLimitProperties(
				false, 25, Duration.ofSeconds(10), 500);

		assertThat(properties.enabled()).isFalse();
		assertThat(properties.requestsPerWindow()).isEqualTo(25);
		assertThat(properties.window()).isEqualTo(Duration.ofSeconds(10));
		assertThat(properties.maximumTrackedClients()).isEqualTo(500);
	}

	@Test
	void rejectsUnsafeLimits() {
		assertThatThrownBy(() -> new McpRateLimitProperties(true, 0, Duration.ofSeconds(1), 1))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("requests-per-window");
		assertThatThrownBy(() -> new McpRateLimitProperties(true, 1, Duration.ZERO, 1))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("window");
		assertThatThrownBy(() -> new McpRateLimitProperties(true, 1, Duration.ofNanos(1), 1))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("window");
		assertThatThrownBy(() -> new McpRateLimitProperties(true, 1, Duration.ofSeconds(1), 0))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("maximum-tracked-clients");
	}
}
