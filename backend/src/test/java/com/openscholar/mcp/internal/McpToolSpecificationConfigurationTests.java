package com.openscholar.mcp.internal;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class McpToolSpecificationConfigurationTests {

	private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
		.withUserConfiguration(McpToolSpecificationConfiguration.class);

	@Test
	void honorsTheMcpServerDisableSwitchWithoutRequiringServerDependencies() {
		contextRunner
			.withPropertyValues(
					"spring.ai.mcp.server.enabled=false",
					"spring.ai.mcp.server.protocol=STATELESS",
					"spring.ai.mcp.server.type=SYNC")
			.run(context -> {
				assertThat(context).hasNotFailed();
				assertThat(context).doesNotHaveBean(McpToolSpecificationConfiguration.class);
			});
	}

	@Test
	void doesNotClaimIncompatibleProtocolOrApiTypeOverrides() {
		contextRunner
			.withPropertyValues(
					"spring.ai.mcp.server.enabled=true",
					"spring.ai.mcp.server.protocol=STREAMABLE",
					"spring.ai.mcp.server.type=SYNC")
			.run(context -> {
				assertThat(context).hasNotFailed();
				assertThat(context).doesNotHaveBean(McpToolSpecificationConfiguration.class);
			});

		contextRunner
			.withPropertyValues(
					"spring.ai.mcp.server.enabled=true",
					"spring.ai.mcp.server.protocol=STATELESS",
					"spring.ai.mcp.server.type=ASYNC")
			.run(context -> {
				assertThat(context).hasNotFailed();
				assertThat(context).doesNotHaveBean(McpToolSpecificationConfiguration.class);
			});

		contextRunner
			.withPropertyValues(
					"spring.ai.mcp.server.enabled=true",
					"spring.ai.mcp.server.stdio=true",
					"spring.ai.mcp.server.protocol=STATELESS",
					"spring.ai.mcp.server.type=SYNC")
			.run(context -> {
				assertThat(context).hasNotFailed();
				assertThat(context).doesNotHaveBean(McpToolSpecificationConfiguration.class);
			});
	}
}
