package com.openscholar.embedding;

import java.util.Objects;
import java.util.regex.Pattern;

public final class EmbeddingGenerationException extends RuntimeException {

	private static final Pattern ERROR_CODE = Pattern.compile("[A-Z][A-Z0-9_]{2,63}");
	private static final int MAXIMUM_MESSAGE_LENGTH = 256;

	private final String errorCode;
	private final boolean retryable;
	private final EmbeddingFailureScope scope;

	public EmbeddingGenerationException(String errorCode, boolean retryable, String message) {
		this(errorCode, retryable, EmbeddingFailureScope.INPUT, message, null);
	}

	public EmbeddingGenerationException(
			String errorCode,
			boolean retryable,
			String message,
			Throwable cause) {
		this(errorCode, retryable, EmbeddingFailureScope.INPUT, message, cause);
	}

	public EmbeddingGenerationException(
			String errorCode,
			boolean retryable,
			EmbeddingFailureScope scope,
			String message) {
		this(errorCode, retryable, scope, message, null);
	}

	public EmbeddingGenerationException(
			String errorCode,
			boolean retryable,
			EmbeddingFailureScope scope,
			String message,
			Throwable cause) {
		super(requireSafeMessage(message), cause);
		this.errorCode = requireStableCode(errorCode);
		this.retryable = retryable;
		this.scope = Objects.requireNonNull(scope, "scope");
	}

	public String errorCode() {
		return errorCode;
	}

	public boolean retryable() {
		return retryable;
	}

	public EmbeddingFailureScope scope() {
		return scope;
	}

	private static String requireStableCode(String value) {
		String code = Objects.requireNonNull(value, "errorCode").strip();
		if (!ERROR_CODE.matcher(code).matches()) {
			throw new IllegalArgumentException(
					"Embedding generation error code must be 3 to 64 uppercase letters, digits, or underscores");
		}
		return code;
	}

	private static String requireSafeMessage(String value) {
		String message = Objects.requireNonNull(value, "message").strip();
		if (message.isEmpty()
				|| message.length() > MAXIMUM_MESSAGE_LENGTH
				|| message.codePoints().anyMatch(Character::isISOControl)) {
			throw new IllegalArgumentException(
					"Embedding generation message must be a single-line value of at most 256 characters");
		}
		return message;
	}
}
