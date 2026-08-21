package com.openscholar.mcp.internal;

final class McpToolResultTooLargeException extends RuntimeException {

	McpToolResultTooLargeException(long maximumBytes) {
		super("The serialized structured tool result exceeds the configured "
				+ maximumBytes + "-byte limit.");
	}
}
