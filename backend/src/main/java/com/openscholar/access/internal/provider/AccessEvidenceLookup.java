package com.openscholar.access.internal.provider;

import java.util.Locale;
import java.util.regex.Pattern;

import com.openscholar.paper.PaperIdentifierNormalizer;

public record AccessEvidenceLookup(String normalizedDoi, String canonicalArxivId) {

	private static final Pattern DOI = Pattern.compile("^10(?:\\.\\d+)+/.+$", Pattern.CASE_INSENSITIVE);
	private static final Pattern MODERN_ARXIV = Pattern.compile("^\\d{4}\\.\\d{4,5}(?:v\\d+)?$", Pattern.CASE_INSENSITIVE);
	private static final Pattern LEGACY_ARXIV = Pattern.compile(
			"^[a-z][a-z0-9.-]*/\\d{7}(?:v\\d+)?$",
			Pattern.CASE_INSENSITIVE);

	public AccessEvidenceLookup {
		normalizedDoi = normalizeDoi(normalizedDoi);
		canonicalArxivId = normalizeArxivId(canonicalArxivId);
	}

	public static String normalizeDoi(String value) {
		String clean = blankToNull(value);
		if (clean == null) {
			return null;
		}
		try {
			clean = PaperIdentifierNormalizer.normalizeDoiReference(clean);
		}
		catch (IllegalArgumentException invalidReference) {
			throw new IllegalArgumentException("DOI must be a normalized DOI identifier", invalidReference);
		}
		if (clean.codePointCount(0, clean.length()) > 512
				|| !DOI.matcher(clean).matches()
				|| containsUnsafeCodePoint(clean)
				|| clean.codePoints().anyMatch(AccessEvidenceLookup::isWhitespace)) {
			throw new IllegalArgumentException("DOI must be a normalized DOI identifier");
		}
		return clean;
	}

	public static String normalizeArxivId(String value) {
		String clean = blankToNull(value);
		if (clean == null) {
			return null;
		}
		clean = clean.replaceFirst("(?i)^arxiv:\\s*", "")
				.replaceFirst("(?i)^https?://(?:www\\.)?arxiv\\.org/(?:abs|pdf)/", "")
				.replaceFirst("(?i)\\.pdf$", "")
				.strip()
				.toLowerCase(Locale.ROOT);
		if ((!MODERN_ARXIV.matcher(clean).matches() && !LEGACY_ARXIV.matcher(clean).matches())
				|| containsControlCharacter(clean)) {
			throw new IllegalArgumentException("arXiv ID must be a canonical arXiv identifier");
		}
		return clean;
	}

	public static String arxivBaseId(String value) {
		String normalized = normalizeArxivId(value);
		return normalized == null ? null : normalized.replaceFirst("(?i)v\\d+$", "");
	}

	private static boolean containsControlCharacter(String value) {
		return value.codePoints().anyMatch(Character::isISOControl);
	}

	private static boolean containsUnsafeCodePoint(String value) {
		return value.codePoints().anyMatch(codePoint -> {
			int type = Character.getType(codePoint);
			return Character.isISOControl(codePoint)
					|| type == Character.FORMAT
					|| type == Character.SURROGATE
					|| type == Character.UNASSIGNED
					|| type == Character.PRIVATE_USE;
		});
	}

	private static boolean isWhitespace(int codePoint) {
		return Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint);
	}

	private static String blankToNull(String value) {
		return value == null || value.isBlank() ? null : value.strip();
	}
}
