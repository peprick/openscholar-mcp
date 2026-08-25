package com.openscholar.mcp.internal;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.json.schema.JsonSchemaValidator;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpStatelessSyncServer;
import io.modelcontextprotocol.server.McpStatelessServerFeatures;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpStatelessServerTransport;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.method.tool.ReturnMode;
import org.springframework.ai.mcp.annotation.spring.SyncMcpAnnotationProviders;
import org.springframework.ai.mcp.server.common.autoconfigure.properties.McpServerProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.web.context.support.StandardServletEnvironment;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "spring.ai.mcp.server", name = "enabled",
		havingValue = "true", matchIfMissing = true)
@ConditionalOnProperty(prefix = "spring.ai.mcp.server", name = "stdio",
		havingValue = "false", matchIfMissing = true)
@ConditionalOnProperty(prefix = "spring.ai.mcp.server", name = "protocol", havingValue = "STATELESS")
@ConditionalOnProperty(prefix = "spring.ai.mcp.server", name = "type",
		havingValue = "SYNC", matchIfMissing = true)
class McpToolSpecificationConfiguration {

	@Bean("openScholarMcpSchemaValidator")
	JsonSchemaValidator openScholarMcpSchemaValidator() {
		return McpJsonDefaults.getSchemaValidator();
	}

	@Bean
	List<McpStatelessServerFeatures.SyncToolSpecification> openScholarMcpToolSpecifications(
			OpenScholarMcpTools tools,
			@Qualifier("openScholarMcpSchemaValidator") JsonSchemaValidator schemaValidator) {
		Map<String, Method> methodsByToolName = annotatedToolMethods();
		List<McpStatelessServerFeatures.SyncToolSpecification> generated =
				SyncMcpAnnotationProviders.statelessToolSpecifications(List.of(tools));
		Map<String, McpStatelessServerFeatures.SyncToolSpecification> specifications = new LinkedHashMap<>();

		for (McpStatelessServerFeatures.SyncToolSpecification specification : generated) {
			String toolName = specification.tool().name();
			Method method = methodsByToolName.get(toolName);
			if (method == null) {
				throw new IllegalStateException("Generated MCP tool has no matching annotated method: " + toolName);
			}
			ReturnMode returnMode = specification.tool().outputSchema() != null
					? ReturnMode.STRUCTURED
					: returnMode(method);
			McpSchema.Tool safeTool = withClosedInputSchema(specification.tool());
			var safeSpecification = new McpStatelessServerFeatures.SyncToolSpecification(
					safeTool, new SafeMcpToolMethodCallback(safeTool, schemaValidator,
							returnMode, method, tools));
			if (specifications.put(toolName, safeSpecification) != null) {
				throw new IllegalStateException("Duplicate generated MCP tool name: " + toolName);
			}
		}

		if (!specifications.keySet().equals(methodsByToolName.keySet())) {
			throw new IllegalStateException("Not every annotated OpenScholar MCP tool was registered");
		}
		return List.copyOf(specifications.values());
	}

	@Bean("openScholarMcpResourceTemplateSpecifications")
	List<McpStatelessServerFeatures.SyncResourceTemplateSpecification>
			openScholarMcpResourceTemplateSpecifications(OpenScholarMcpResources resources) {
		return List.copyOf(resources.resourceTemplateSpecifications());
	}

	private static McpSchema.Tool withClosedInputSchema(McpSchema.Tool tool) {
		Map<String, Object> inputSchema = new LinkedHashMap<>(tool.inputSchema());
		inputSchema.put("additionalProperties", false);
		return new McpSchema.Tool(tool.name(), tool.title(), tool.description(), Map.copyOf(inputSchema),
				tool.outputSchema(), tool.annotations(), tool.meta(), tool.icons());
	}

	@Bean
	McpStatelessSyncServer openScholarMcpStatelessSyncServer(McpStatelessServerTransport transport,
			McpServerProperties properties,
			@Qualifier("openScholarMcpToolSpecifications")
			List<McpStatelessServerFeatures.SyncToolSpecification> toolSpecifications,
			@Qualifier("openScholarMcpResourceTemplateSpecifications")
			List<McpStatelessServerFeatures.SyncResourceTemplateSpecification> resourceTemplateSpecifications,
			@Qualifier("openScholarMcpSchemaValidator") JsonSchemaValidator schemaValidator,
			Environment environment) {
		McpSchema.ServerCapabilities.Builder capabilities = McpSchema.ServerCapabilities.builder();
		if (properties.getCapabilities().isTool()) {
			capabilities.tools(false);
		}
		if (properties.getCapabilities().isResource()) {
			capabilities.resources(null, null);
		}
		if (properties.getCapabilities().isPrompt()) {
			capabilities.prompts(false);
		}
		if (properties.getCapabilities().isCompletion()) {
			capabilities.completions();
		}

		var server = McpServer.sync(transport)
			.serverInfo(new McpSchema.Implementation(properties.getName(), properties.getVersion()))
			.capabilities(capabilities.build())
			.instructions(properties.getInstructions())
			.requestTimeout(properties.getRequestTimeout())
			.jsonSchemaValidator(schemaValidator)
			.validateToolInputs(false);
		if (properties.getCapabilities().isTool()) {
			server.tools(toolSpecifications);
		}
		if (properties.getCapabilities().isResource()) {
			server.resourceTemplates(resourceTemplateSpecifications);
		}
		if (environment instanceof StandardServletEnvironment) {
			server.immediateExecution(true);
		}
		return server.build();
	}

	private static Map<String, Method> annotatedToolMethods() {
		Map<String, Method> methods = new LinkedHashMap<>();
		Arrays.stream(OpenScholarMcpTools.class.getDeclaredMethods())
			.filter(method -> method.isAnnotationPresent(McpTool.class))
			.sorted(java.util.Comparator.comparing(Method::getName))
			.forEach(method -> {
				McpTool annotation = method.getAnnotation(McpTool.class);
				String toolName = annotation.name().isBlank() ? method.getName() : annotation.name();
				if (methods.put(toolName, method) != null) {
					throw new IllegalStateException("Duplicate annotated MCP tool name: " + toolName);
				}
			});
		return Map.copyOf(methods);
	}

	private static ReturnMode returnMode(Method method) {
		Class<?> returnType = method.getReturnType();
		return returnType == Void.class || returnType == void.class ? ReturnMode.VOID : ReturnMode.TEXT;
	}
}
