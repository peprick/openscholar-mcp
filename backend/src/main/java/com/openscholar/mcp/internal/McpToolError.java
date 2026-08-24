package com.openscholar.mcp.internal;

import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

record McpToolError(McpToolErrorCode code, boolean retryable, Long retryAfterSeconds) {

	static final int SCHEMA_VERSION = 1;

	static final long MAX_RETRY_AFTER_SECONDS = 86_400;

	McpToolError {
		Objects.requireNonNull(code, "code must not be null");
		if (retryAfterSeconds != null
				&& (!retryable || retryAfterSeconds < 1 || retryAfterSeconds > MAX_RETRY_AFTER_SECONDS)) {
			throw new IllegalArgumentException("retryAfterSeconds must be omitted or a bounded positive retry delay");
		}
	}

	static McpToolError nonRetryable(McpToolErrorCode code) {
		return new McpToolError(code, false, null);
	}

	static McpToolError retryable(McpToolErrorCode code) {
		return new McpToolError(code, true, null);
	}

	static McpToolError from(McpToolErrorCode code, boolean retryable, Duration retryAfter) {
		return new McpToolError(code, retryable, normalizeRetryAfter(retryable, retryAfter));
	}

	String category() {
		return code.category();
	}

	String message() {
		return code.message();
	}

	String action() {
		return code.action(retryable);
	}

	Map<String, Object> asMeta() {
		Map<String, Object> fields = new LinkedHashMap<>();
		fields.put("schemaVersion", SCHEMA_VERSION);
		fields.put("code", code.name());
		fields.put("category", category());
		fields.put("message", message());
		fields.put("retryable", retryable);
		fields.put("action", action());
		if (retryAfterSeconds != null) {
			fields.put("retryAfterSeconds", retryAfterSeconds);
		}
		return Collections.unmodifiableMap(fields);
	}

	String toText() {
		String retryAfter = retryAfterSeconds == null ? "" : "; retryAfterSeconds=" + retryAfterSeconds;
		return "%s: %s [category=%s; retryable=%s; action=%s%s]".formatted(
				code.name(), message(), category(), retryable, action(), retryAfter);
	}

	private static Long normalizeRetryAfter(boolean retryable, Duration retryAfter) {
		if (!retryable || retryAfter == null || retryAfter.isZero() || retryAfter.isNegative()) {
			return null;
		}
		long seconds = retryAfter.getSeconds();
		if (retryAfter.getNano() > 0) {
			if (seconds == Long.MAX_VALUE) {
				return null;
			}
			seconds++;
		}
		return seconds >= 1 && seconds <= MAX_RETRY_AFTER_SECONDS ? seconds : null;
	}
}
