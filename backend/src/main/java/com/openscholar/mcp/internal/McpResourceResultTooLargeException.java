package com.openscholar.mcp.internal;

final class McpResourceResultTooLargeException extends RuntimeException {

	McpResourceResultTooLargeException(long maximumBytes) {
		super("The serialized MCP resource result exceeds the configured "
				+ maximumBytes + "-byte limit.");
	}
}
