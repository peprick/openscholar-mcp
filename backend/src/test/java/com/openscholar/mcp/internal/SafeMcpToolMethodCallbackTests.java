package com.openscholar.mcp.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import io.modelcontextprotocol.json.schema.JsonSchemaValidator;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;
import org.springframework.ai.mcp.annotation.method.tool.ReturnMode;

class SafeMcpToolMethodCallbackTests {

	private static final String SCHEMA_LEAK_MARKER = "private-output-schema-diagnostic";

	@Test
	void replacesInvalidSuccessfulStructuredOutputWithTheGenericSafeError() throws Exception {
		Map<String, Object> inputSchema = Map.of("type", "object");
		Map<String, Object> outputSchema = Map.of(
				"type", "object",
				"required", java.util.List.of("expected"),
				"properties", Map.of("expected", Map.of("type", "string")));
		McpSchema.Tool tool = McpSchema.Tool.builder("schema_drift")
			.inputSchema(inputSchema)
			.outputSchema(outputSchema)
			.build();
		AtomicInteger validations = new AtomicInteger();
		JsonSchemaValidator validator = (schema, value) -> {
			validations.incrementAndGet();
			return schema == outputSchema
					? JsonSchemaValidator.ValidationResponse.asInvalid(SCHEMA_LEAK_MARKER)
					: JsonSchemaValidator.ValidationResponse.asValid(null);
		};
		SchemaDriftTool target = new SchemaDriftTool();
		Method method = SchemaDriftTool.class.getDeclaredMethod("invoke");
		SafeMcpToolMethodCallback callback = new SafeMcpToolMethodCallback(
				tool, validator, ReturnMode.STRUCTURED, method, target);

		McpSchema.CallToolResult result = callback.apply(null,
				new McpSchema.CallToolRequest("schema_drift", Map.of()));

		assertThat(validations).hasValue(2);
		assertThat(result.isError()).isTrue();
		assertThat(result.structuredContent()).isNull();
		assertThat(result.content()).hasSize(1);
		assertThat(result.content().getFirst().toString()).doesNotContain(SCHEMA_LEAK_MARKER, "expected");
		assertThat(result.meta()).containsOnlyKeys(SafeMcpToolMethodCallback.ERROR_META_KEY);
		assertThat(result.meta().get(SafeMcpToolMethodCallback.ERROR_META_KEY))
			.isEqualTo(McpToolError.nonRetryable(McpToolErrorCode.MCP_TOOL_FAILED).asMeta());
	}

	@Test
	void treatsSchemaValidatorInfrastructureFailuresAsGenericServerErrors() throws Exception {
		Map<String, Object> inputSchema = Map.of("type", "object", "properties", Map.of());
		McpSchema.Tool tool = McpSchema.Tool.builder("validator_failure")
			.inputSchema(inputSchema)
			.outputSchema(Map.of("type", "object"))
			.build();
		JsonSchemaValidator validator = (schema, value) -> {
			throw new IllegalStateException(SCHEMA_LEAK_MARKER);
		};
		SchemaDriftTool target = new SchemaDriftTool();
		Method method = SchemaDriftTool.class.getDeclaredMethod("invoke");
		SafeMcpToolMethodCallback callback = new SafeMcpToolMethodCallback(
				tool, validator, ReturnMode.STRUCTURED, method, target);

		McpSchema.CallToolResult result = callback.apply(null,
				new McpSchema.CallToolRequest("validator_failure", Map.of()));

		assertThat(result.isError()).isTrue();
		assertThat(result.structuredContent()).isNull();
		assertThat(result.content().getFirst().toString()).doesNotContain(SCHEMA_LEAK_MARKER);
		assertThat(result.meta().get(SafeMcpToolMethodCallback.ERROR_META_KEY))
			.isEqualTo(McpToolError.nonRetryable(McpToolErrorCode.MCP_TOOL_FAILED).asMeta());
	}

	static final class SchemaDriftTool {

		public DriftedOutput invoke() {
			return new DriftedOutput("private-output-value");
		}
	}

	record DriftedOutput(String actual) {
	}
}
