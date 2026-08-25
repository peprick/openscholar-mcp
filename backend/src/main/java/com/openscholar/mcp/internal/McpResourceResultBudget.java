package com.openscholar.mcp.internal;

import java.nio.charset.StandardCharsets;
import java.util.Objects;

import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Component
class McpResourceResultBudget {

	private final ObjectMapper objectMapper;
	private final long maximumBytes;

	McpResourceResultBudget(ObjectMapper objectMapper, McpPayloadProperties properties) {
		this.objectMapper = objectMapper;
		this.maximumBytes = properties.maxResourceResultBytes();
	}

	String toJson(Object result) {
		Objects.requireNonNull(result, "result");
		try {
			byte[] json = objectMapper.writeValueAsBytes(result);
			if (json.length > maximumBytes) {
				throw new McpResourceResultTooLargeException(maximumBytes);
			}
			return new String(json, StandardCharsets.UTF_8);
		}
		catch (JacksonException exception) {
			throw new IllegalStateException("The MCP resource result could not be serialized safely", exception);
		}
	}
}
