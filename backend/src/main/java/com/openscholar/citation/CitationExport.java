package com.openscholar.citation;

import java.util.Objects;

public record CitationExport(
		CitationFormat format,
		String citationKey,
		String filename,
		String mediaType,
		String body) {

	public CitationExport {
		format = Objects.requireNonNull(format, "format");
		citationKey = requireText(citationKey, "citationKey");
		filename = requireText(filename, "filename");
		mediaType = requireText(mediaType, "mediaType");
		body = Objects.requireNonNull(body, "body");
	}

	private static String requireText(String value, String name) {
		if (value == null || value.isBlank()) {
			throw new IllegalArgumentException(name + " must not be blank");
		}
		return value;
	}
}
