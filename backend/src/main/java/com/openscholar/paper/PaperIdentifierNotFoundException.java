package com.openscholar.paper;

public class PaperIdentifierNotFoundException extends RuntimeException {

	public PaperIdentifierNotFoundException() {
		super("No visible paper was found for that identifier.");
	}
}
