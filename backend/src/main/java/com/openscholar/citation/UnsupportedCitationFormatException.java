package com.openscholar.citation;

public class UnsupportedCitationFormatException extends IllegalArgumentException {

	public UnsupportedCitationFormatException() {
		super("Citation format must be one of: bibtex, csl-json");
	}
}
