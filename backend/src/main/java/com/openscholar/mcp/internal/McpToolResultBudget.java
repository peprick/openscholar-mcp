package com.openscholar.mcp.internal;

import java.util.Objects;

import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Component
class McpToolResultBudget {

	private final ObjectMapper objectMapper;
	private final long maximumBytes;

	McpToolResultBudget(ObjectMapper objectMapper, McpPayloadProperties properties) {
		this.objectMapper = objectMapper;
		this.maximumBytes = properties.maxToolResultBytes();
	}

	<T> T requireWithinLimit(T result) {
		Objects.requireNonNull(result, "result");
		try {
			if (objectMapper.writeValueAsBytes(result).length > maximumBytes) {
				throw new McpToolResultTooLargeException(maximumBytes);
			}
			return result;
		}
		catch (JacksonException exception) {
			throw new IllegalStateException("The MCP tool result could not be measured safely", exception);
		}
	}
}
