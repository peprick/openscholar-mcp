package com.openscholar.mcp.internal;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.function.BiFunction;

import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.json.schema.JsonSchemaValidator;
import io.modelcontextprotocol.spec.McpSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.mcp.annotation.context.McpSyncRequestContext;
import org.springframework.ai.mcp.annotation.method.tool.AbstractSyncMcpToolMethodCallback;
import org.springframework.ai.mcp.annotation.method.tool.ReturnMode;

final class SafeMcpToolMethodCallback
		extends AbstractSyncMcpToolMethodCallback<McpTransportContext, McpSyncRequestContext>
		implements BiFunction<McpTransportContext, McpSchema.CallToolRequest, McpSchema.CallToolResult> {

	static final String ERROR_META_KEY = "com.openscholar/error";

	private static final Logger LOGGER = LoggerFactory.getLogger(SafeMcpToolMethodCallback.class);

	private final String toolName;

	private final McpSchema.Tool tool;

	private final JsonSchemaValidator schemaValidator;

	SafeMcpToolMethodCallback(McpSchema.Tool tool, JsonSchemaValidator schemaValidator, ReturnMode returnMode,
			Method toolMethod, Object toolObject) {
		super(returnMode, toolMethod, toolObject, Exception.class);
		this.tool = tool;
		this.toolName = tool.name();
		this.schemaValidator = schemaValidator;
	}

	@Override
	public McpSchema.CallToolResult apply(McpTransportContext transportContext, McpSchema.CallToolRequest request) {
		try {
			validateSyncRequest(request);
		}
		catch (Exception exception) {
			return errorResult(McpToolError.nonRetryable(McpToolErrorCode.INVALID_REQUEST));
		}

		Map<String, Object> arguments = request.arguments() == null ? Map.of() : request.arguments();
		try {
			if (!hasOnlyDeclaredArguments(arguments)
					|| !schemaValidator.validate(tool.inputSchema(), arguments).valid()) {
				return errorResult(McpToolError.nonRetryable(McpToolErrorCode.INVALID_REQUEST));
			}
		}
		catch (Exception exception) {
			return unexpectedErrorResult(exception);
		}

		Object[] methodArguments;
		try {
			methodArguments = buildMethodArguments(transportContext, arguments, request);
		}
		catch (Exception exception) {
			return errorResult(McpToolError.nonRetryable(McpToolErrorCode.INVALID_REQUEST));
		}

		Object value;
		try {
			value = callMethod(methodArguments);
		}
		catch (Exception exception) {
			return invocationErrorResult(exception);
		}

		try {
			McpSchema.CallToolResult result = processResult(value);
			if (Boolean.TRUE.equals(result.isError())) {
				return unexpectedErrorResult(new IllegalStateException("Annotated tool returned an unmanaged error result"));
			}
			if (!hasValidStructuredOutput(result)) {
				return unexpectedErrorResult(new IllegalStateException("Annotated tool returned invalid structured output"));
			}
			return result;
		}
		catch (Exception exception) {
			return unexpectedErrorResult(exception);
		}
	}

	@Override
	protected McpSchema.CallToolResult createSyncErrorResult(Exception exception) {
		return invocationErrorResult(exception);
	}

	@Override
	protected boolean isExchangeOrContextType(Class<?> parameterType) {
		return McpTransportContext.class.isAssignableFrom(parameterType)
				|| McpSyncRequestContext.class.isAssignableFrom(parameterType);
	}

	@Override
	protected McpSyncRequestContext createRequestContext(McpTransportContext transportContext,
			McpSchema.CallToolRequest request) {
		throw new UnsupportedOperationException("Stateless tool methods do not support request context parameters");
	}

	@Override
	protected McpTransportContext resolveTransportContext(McpTransportContext transportContext) {
		return transportContext;
	}

	private McpSchema.CallToolResult invocationErrorResult(Exception exception) {
		McpToolExecutionException failure = findMappedFailure(exception);
		return failure == null ? unexpectedErrorResult(exception) : errorResult(failure.error());
	}

	private McpSchema.CallToolResult unexpectedErrorResult(Exception exception) {
		LOGGER.error("Unexpected MCP callback failure: tool={}, exceptionType={}", toolName,
				exception.getClass().getName());
		return errorResult(McpToolError.nonRetryable(McpToolErrorCode.MCP_TOOL_FAILED));
	}

	private boolean hasValidStructuredOutput(McpSchema.CallToolResult result) {
		if (tool.outputSchema() == null) {
			return true;
		}
		return result.structuredContent() != null
				&& schemaValidator.validate(tool.outputSchema(), result.structuredContent()).valid();
	}

	private boolean hasOnlyDeclaredArguments(Map<String, Object> arguments) {
		Object properties = tool.inputSchema().get("properties");
		if (!(properties instanceof Map<?, ?> declaredProperties)) {
			return arguments.isEmpty();
		}
		return declaredProperties.keySet().containsAll(arguments.keySet());
	}

	private static McpToolExecutionException findMappedFailure(Throwable failure) {
		Throwable current = failure;
		while (current != null) {
			if (current instanceof McpToolExecutionException mappedFailure) {
				return mappedFailure;
			}
			Throwable cause = current.getCause();
			if (cause == current) {
				break;
			}
			current = cause;
		}
		return null;
	}

	private static McpSchema.CallToolResult errorResult(McpToolError error) {
		return McpSchema.CallToolResult.builder()
			.addTextContent(error.toText())
			.isError(true)
			.meta(Map.of(ERROR_META_KEY, error.asMeta()))
			.build();
	}
}
