package com.openscholar.citation;

import java.util.Locale;

public enum CitationFormat {
	BIBTEX("bibtex", "application/x-bibtex", ".bib"),
	CSL_JSON("csl-json", "application/vnd.citationstyles.csl+json", ".csl.json");

	private final String apiValue;
	private final String mediaType;
	private final String fileExtension;

	CitationFormat(String apiValue, String mediaType, String fileExtension) {
		this.apiValue = apiValue;
		this.mediaType = mediaType;
		this.fileExtension = fileExtension;
	}

	public String apiValue() {
		return apiValue;
	}

	public String mediaType() {
		return mediaType;
	}

	public String fileExtension() {
		return fileExtension;
	}

	public static CitationFormat fromApiValue(String value) {
		String normalized = value == null ? "" : value.strip().toLowerCase(Locale.ROOT);
		for (CitationFormat format : values()) {
			if (format.apiValue.equals(normalized)) {
				return format;
			}
		}
		throw new UnsupportedCitationFormatException();
	}
}
