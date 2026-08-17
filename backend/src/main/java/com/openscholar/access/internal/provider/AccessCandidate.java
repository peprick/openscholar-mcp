package com.openscholar.access.internal.provider;

import java.net.URI;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

public record AccessCandidate(
		AccessSource source,
		String sourceKey,
		boolean best,
		String hostType,
		String version,
		String license,
		URI landingPageUrl,
		URI pdfUrl,
		Instant providerUpdatedAt,
		Map<String, String> evidence) {

	private static final int MAX_SOURCE_KEY_LENGTH = 200;
	private static final int MAX_EVIDENCE_ENTRIES = 12;
	private static final int MAX_EVIDENCE_VALUE_LENGTH = 1_000;

	public AccessCandidate {
		source = Objects.requireNonNull(source, "source");
		sourceKey = requireText(sourceKey, "sourceKey", MAX_SOURCE_KEY_LENGTH);
		hostType = boundedText(hostType, 100);
		version = boundedText(version, 100);
		license = boundedText(license, 500);
		landingPageUrl = safeHttpUri(landingPageUrl, "landingPageUrl");
		pdfUrl = safeHttpUri(pdfUrl, "pdfUrl");
		if (landingPageUrl == null && pdfUrl == null) {
			throw new IllegalArgumentException("An access candidate must include a landing-page or PDF URL");
		}
		evidence = boundedEvidence(evidence);
	}

	private static Map<String, String> boundedEvidence(Map<String, String> value) {
		if (value == null || value.isEmpty()) {
			return Map.of();
		}
		if (value.size() > MAX_EVIDENCE_ENTRIES) {
			throw new IllegalArgumentException("Candidate evidence contains too many entries");
		}
		Map<String, String> copy = new LinkedHashMap<>();
		value.forEach((key, item) -> {
			String cleanKey = requireText(key, "evidence key", 100);
			String cleanValue = boundedText(item, MAX_EVIDENCE_VALUE_LENGTH);
			if (cleanValue != null) {
				copy.put(cleanKey, cleanValue);
			}
		});
		return Map.copyOf(copy);
	}

	private static URI safeHttpUri(URI value, String name) {
		if (value == null) {
			return null;
		}
		String scheme = value.getScheme();
		if (!value.isAbsolute()
				|| scheme == null
				|| (!scheme.equalsIgnoreCase("http") && !scheme.equalsIgnoreCase("https"))
				|| value.getHost() == null
				|| value.getUserInfo() != null) {
			throw new IllegalArgumentException(name + " must be an absolute HTTP(S) URL without credentials");
		}
		return value;
	}

	private static String requireText(String value, String name, int maximumLength) {
		String clean = boundedText(value, maximumLength);
		if (clean == null) {
			throw new IllegalArgumentException(name + " must not be blank");
		}
		return clean;
	}

	private static String boundedText(String value, int maximumLength) {
		if (value == null || value.isBlank()) {
			return null;
		}
		String clean = value.strip();
		if (clean.length() > maximumLength || clean.codePoints().anyMatch(Character::isISOControl)) {
			throw new IllegalArgumentException("Text value is invalid or too long");
		}
		return clean;
	}
}
