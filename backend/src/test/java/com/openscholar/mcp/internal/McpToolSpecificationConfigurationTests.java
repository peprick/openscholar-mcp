package com.openscholar.mcp.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;

import io.modelcontextprotocol.server.McpStatelessServerFeatures;
import io.modelcontextprotocol.server.McpStatelessServerHandler;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpStatelessServerTransport;
import org.junit.jupiter.api.Test;
import org.springframework.ai.mcp.server.common.autoconfigure.properties.McpServerProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.mock.env.MockEnvironment;
import reactor.core.publisher.Mono;

class McpToolSpecificationConfigurationTests {

	private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
		.withUserConfiguration(McpToolSpecificationConfiguration.class);

	@Test
	void advertisesAndRegistersResourceTemplatesWithoutConcreteResources() {
		McpSchema.ResourceTemplate template = McpSchema.ResourceTemplate
			.builder("openscholar://papers/{paperId}", "paper")
			.mimeType("application/json")
			.build();
		var specification = new McpStatelessServerFeatures.SyncResourceTemplateSpecification(
				template, (context, request) -> new McpSchema.ReadResourceResult(List.of()));
		McpServerProperties properties = serverPropertiesWithResources();
		McpToolSpecificationConfiguration configuration = new McpToolSpecificationConfiguration();

		var server = configuration.openScholarMcpStatelessSyncServer(
				new TestTransport(), properties, List.of(), List.of(specification),
				configuration.openScholarMcpSchemaValidator(), new MockEnvironment());

		assertThat(server.getServerCapabilities().resources()).isNotNull();
		assertThat(server.getServerCapabilities().resources().subscribe()).isNull();
		assertThat(server.getServerCapabilities().resources().listChanged()).isNull();
		assertThat(server.listResourceTemplates()).containsExactly(template);
		assertThat(server.listResources()).isEmpty();
		server.close();
	}

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

	private static McpServerProperties serverPropertiesWithResources() {
		McpServerProperties properties = new McpServerProperties();
		properties.setName("openscholar-mcp");
		properties.setVersion("test");
		properties.setInstructions("test");
		properties.setRequestTimeout(Duration.ofSeconds(1));
		properties.getCapabilities().setResource(true);
		return properties;
	}

	private static final class TestTransport implements McpStatelessServerTransport {

		@Override
		public void setMcpHandler(McpStatelessServerHandler mcpHandler) {
			// The registration assertions exercise server state directly.
		}

		@Override
		public Mono<Void> closeGracefully() {
			return Mono.empty();
		}
	}
}
