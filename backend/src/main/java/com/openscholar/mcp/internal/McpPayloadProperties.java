package com.openscholar.mcp.internal;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("openscholar.mcp.payload")
record McpPayloadProperties(Long maxRequestBytes, Long maxToolResultBytes,
		Long maxResourceResultBytes) {

	private static final long DEFAULT_MAX_REQUEST_BYTES = 1_048_576;
	private static final long DEFAULT_MAX_TOOL_RESULT_BYTES = 1_048_576;
	private static final long DEFAULT_MAX_RESOURCE_RESULT_BYTES = 1_048_576;
	private static final long MINIMUM_BYTES = 1_024;
	private static final long MAXIMUM_BYTES = 16_777_216;

	McpPayloadProperties {
		maxRequestBytes = maxRequestBytes == null ? DEFAULT_MAX_REQUEST_BYTES : maxRequestBytes;
		maxToolResultBytes = maxToolResultBytes == null
				? DEFAULT_MAX_TOOL_RESULT_BYTES
				: maxToolResultBytes;
		maxResourceResultBytes = maxResourceResultBytes == null
				? DEFAULT_MAX_RESOURCE_RESULT_BYTES
				: maxResourceResultBytes;
		validate("max-request-bytes", maxRequestBytes);
		validate("max-tool-result-bytes", maxToolResultBytes);
		validate("max-resource-result-bytes", maxResourceResultBytes);
	}

	private static void validate(String name, long bytes) {
		if (bytes < MINIMUM_BYTES || bytes > MAXIMUM_BYTES) {
			throw new IllegalArgumentException(
					"MCP payload " + name + " must be between " + MINIMUM_BYTES
							+ " and " + MAXIMUM_BYTES);
		}
	}
}
