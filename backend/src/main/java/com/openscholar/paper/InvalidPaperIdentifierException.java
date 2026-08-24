package com.openscholar.paper;

public class InvalidPaperIdentifierException extends RuntimeException {

	public InvalidPaperIdentifierException() {
		super("Identifier must be a DOI, arXiv identifier, or OpenAlex work identifier.");
	}
}
