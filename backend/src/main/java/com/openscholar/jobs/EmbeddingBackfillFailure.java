package com.openscholar.jobs;

import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

public record EmbeddingBackfillFailure(
		UUID paperId,
		EmbeddingBackfillFailureCode code,
		String generationErrorCode,
		int attempts) {

	private static final Pattern GENERATION_ERROR_CODE = Pattern.compile("[A-Z][A-Z0-9_]{2,63}");

	public EmbeddingBackfillFailure {
		paperId = Objects.requireNonNull(paperId, "paperId");
		code = Objects.requireNonNull(code, "code");
		generationErrorCode = cleanGenerationErrorCode(generationErrorCode);

		switch (code) {
			case INPUT_TOO_LARGE -> {
				if (generationErrorCode != null || attempts < 0 || attempts > 3) {
					throw new IllegalArgumentException(
							"An oversized-input failure allows zero to three generation attempts and no provider code");
				}
			}
			case GENERATION_REJECTED -> {
				if (generationErrorCode == null || attempts < 1 || attempts > 3) {
					throw new IllegalArgumentException(
							"A generation failure requires a provider code and one to three attempts");
				}
			}
			case ATTEMPT_BUDGET_EXHAUSTED -> {
				if (attempts < 1 || attempts > 3) {
					throw new IllegalArgumentException(
							"An exhausted attempt budget requires one to three attempts");
				}
			}
		}
	}

	private static String cleanGenerationErrorCode(String value) {
		if (value == null) {
			return null;
		}
		String code = value.strip();
		if (!GENERATION_ERROR_CODE.matcher(code).matches()) {
			throw new IllegalArgumentException("Generation error code is invalid");
		}
		return code;
	}
}
