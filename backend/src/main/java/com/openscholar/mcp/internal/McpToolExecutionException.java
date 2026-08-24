package com.openscholar.mcp.internal;

import java.util.Objects;

final class McpToolExecutionException extends RuntimeException {

	private final McpToolError error;

	McpToolExecutionException(McpToolError error) {
		super(Objects.requireNonNull(error, "error must not be null").toText(), null, false, false);
		this.error = error;
	}

	McpToolError error() {
		return error;
	}
}
