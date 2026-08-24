package com.openscholar.paper;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class PaperIdentifierNormalizer {

	private static final Pattern CANONICAL_DOI_URL = Pattern.compile(
			"(?i)^https?://(?:dx\\.)?doi\\.org/");

	private PaperIdentifierNormalizer() {
	}

	public static String normalize(PaperIdentifierType type, String value) {
		Objects.requireNonNull(type, "type");
		String cleanValue = Objects.requireNonNull(value, "value").strip();
		String normalized = cleanValue.toLowerCase(Locale.ROOT);
		if (type == PaperIdentifierType.DOI) {
			normalized = normalized
					.replaceFirst("^https?://(?:dx\\.)?doi\\.org/", "")
					.replaceFirst("^doi:\\s*", "");
		}
		if (type == PaperIdentifierType.OPENALEX) {
			normalized = normalized.replaceFirst("^https?://openalex\\.org/(?:works/)?", "");
		}
		if (type == PaperIdentifierType.ARXIV) {
			normalized = normalized
					.replaceFirst("^https?://(?:export\\.)?arxiv\\.org/(?:abs|pdf)/", "")
					.replaceFirst("^arxiv:\\s*", "")
					.replaceFirst("\\.pdf$", "");
			if (normalized.matches(
					"(?:\\d{4}\\.\\d{4,5}|[a-z-]+(?:\\.[a-z]{2})?/\\d{7})v\\d+")) {
				normalized = normalized.replaceFirst("v\\d+$", "");
			}
		}
		return normalized.strip();
	}

	public static String normalizeDoiReference(String value) {
		String candidate = Objects.requireNonNull(value, "value")
			.strip()
			.replaceFirst("(?i)^doi:\\s*", "");
		if (candidate.regionMatches(true, 0, "http://", 0, "http://".length())
				|| candidate.regionMatches(true, 0, "https://", 0, "https://".length())) {
			candidate = doiFromCanonicalUrl(candidate);
		}
		return candidate.toLowerCase(Locale.ROOT);
	}

	private static String doiFromCanonicalUrl(String value) {
		Matcher prefix = CANONICAL_DOI_URL.matcher(value);
		if (!prefix.find()) {
			throw new IllegalArgumentException("DOI URL is not canonical");
		}
		String rawPath = value.substring(prefix.end());
		if (rawPath.isEmpty()) {
			throw new IllegalArgumentException("DOI URL path is missing");
		}
		if (rawPath.indexOf('?') >= 0 || rawPath.indexOf('#') >= 0) {
			throw new IllegalArgumentException("DOI URL query and fragment values are not accepted");
		}
		return decodePercentEncodedPath(rawPath);
	}

	private static String decodePercentEncodedPath(String rawPath) {
		StringBuilder decoded = new StringBuilder(rawPath.length());
		int index = 0;
		while (index < rawPath.length()) {
			if (rawPath.charAt(index) != '%') {
				decoded.append(rawPath.charAt(index));
				index++;
				continue;
			}
			byte[] bytes = new byte[(rawPath.length() - index) / 3 + 1];
			int byteCount = 0;
			while (index < rawPath.length() && rawPath.charAt(index) == '%') {
				if (index + 2 >= rawPath.length()) {
					throw new IllegalArgumentException("DOI URL contains an incomplete escape");
				}
				int high = Character.digit(rawPath.charAt(index + 1), 16);
				int low = Character.digit(rawPath.charAt(index + 2), 16);
				if (high < 0 || low < 0) {
					throw new IllegalArgumentException("DOI URL contains an invalid escape");
				}
				bytes[byteCount++] = (byte) ((high << 4) + low);
				index += 3;
			}
			try {
				decoded.append(StandardCharsets.UTF_8.newDecoder()
					.onMalformedInput(CodingErrorAction.REPORT)
					.onUnmappableCharacter(CodingErrorAction.REPORT)
					.decode(ByteBuffer.wrap(bytes, 0, byteCount)));
			}
			catch (CharacterCodingException invalidEncoding) {
				throw new IllegalArgumentException("DOI URL contains an invalid UTF-8 escape", invalidEncoding);
			}
		}
		return decoded.toString();
	}
}
