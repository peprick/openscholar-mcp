package com.openscholar.paper.internal.persistence;

import java.util.List;
import java.util.regex.Pattern;

import com.openscholar.paper.InvalidPaperIdentifierException;
import com.openscholar.paper.PaperIdentifierNormalizer;
import com.openscholar.paper.PaperIdentifierType;

final class PaperIdentifierReferenceParser {

	private static final int MAX_INPUT_CODE_POINTS = 512;

	private static final Pattern DOI = Pattern.compile("10(?:\\.\\d+)+/.+");

	private static final Pattern ARXIV = Pattern.compile(
			"(?:\\d{4}\\.\\d{4,5}|[a-z-]+(?:\\.[a-z]{2})?/\\d{7})(?:v\\d+)?",
			Pattern.CASE_INSENSITIVE);

	private static final Pattern OPENALEX = Pattern.compile("w\\d+", Pattern.CASE_INSENSITIVE);

	private PaperIdentifierReferenceParser() {
	}

	static ParsedIdentifier parse(String input) {
		String value = input == null ? "" : input.strip();
		if (value.isEmpty()
				|| value.codePointCount(0, value.length()) > MAX_INPUT_CODE_POINTS
				|| value.codePoints().anyMatch(PaperIdentifierReferenceParser::isUnsafeCategory)) {
			throw new InvalidPaperIdentifierException();
		}

		ParsedIdentifier doi = parseDoi(value);
		if (doi != null) {
			return doi;
		}
		ParsedIdentifier arxiv = parseAs(PaperIdentifierType.ARXIV, value, ARXIV);
		if (arxiv != null) {
			return arxiv;
		}
		ParsedIdentifier openAlex = parseAs(PaperIdentifierType.OPENALEX, value, OPENALEX);
		if (openAlex != null) {
			return openAlex;
		}
		throw new InvalidPaperIdentifierException();
	}

	private static ParsedIdentifier parseDoi(String value) {
		String normalized;
		try {
			normalized = PaperIdentifierNormalizer.normalizeDoiReference(value);
		}
		catch (IllegalArgumentException invalidReference) {
			return null;
		}
		if (!DOI.matcher(normalized).matches() || !isBoundedAndGraphic(normalized)) {
			return null;
		}
		String persistedForm = PaperIdentifierNormalizer.normalize(PaperIdentifierType.DOI, value);
		List<String> lookupValues = persistedForm.equals(normalized)
				? List.of(normalized)
				: List.of(normalized, persistedForm);
		if (lookupValues.stream().anyMatch(valueCandidate -> !isBoundedAndGraphic(valueCandidate))) {
			return null;
		}
		return new ParsedIdentifier(PaperIdentifierType.DOI, normalized, lookupValues);
	}

	private static ParsedIdentifier parseAs(
			PaperIdentifierType type, String value, Pattern acceptedValue) {
		String normalized = PaperIdentifierNormalizer.normalize(type, value);
		return acceptedValue.matcher(normalized).matches() && isBoundedAndGraphic(normalized)
				? new ParsedIdentifier(type, normalized, List.of(normalized))
				: null;
	}

	private static boolean isBoundedAndGraphic(String value) {
		return value.codePointCount(0, value.length()) <= MAX_INPUT_CODE_POINTS
				&& value.codePoints().noneMatch(PaperIdentifierReferenceParser::isNonGraphic);
	}

	private static boolean isNonGraphic(int codePoint) {
		return isUnsafeCategory(codePoint)
				|| Character.isWhitespace(codePoint)
				|| Character.isSpaceChar(codePoint);
	}

	private static boolean isUnsafeCategory(int codePoint) {
		int type = Character.getType(codePoint);
		return Character.isISOControl(codePoint)
				|| type == Character.FORMAT
				|| type == Character.SURROGATE
				|| type == Character.UNASSIGNED
				|| type == Character.PRIVATE_USE;
	}

	record ParsedIdentifier(PaperIdentifierType type, String normalizedValue, List<String> lookupValues) {

		ParsedIdentifier {
			lookupValues = List.copyOf(lookupValues);
		}
	}
}
