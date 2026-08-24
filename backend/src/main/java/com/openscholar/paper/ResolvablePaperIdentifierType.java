package com.openscholar.paper;

import java.util.Objects;

public enum ResolvablePaperIdentifierType {

	DOI,

	ARXIV,

	OPENALEX;

	public static ResolvablePaperIdentifierType fromCatalogType(PaperIdentifierType type) {
		return switch (Objects.requireNonNull(type, "type")) {
			case DOI -> DOI;
			case ARXIV -> ARXIV;
			case OPENALEX -> OPENALEX;
			default -> throw new IllegalArgumentException("Paper identifier type is not directly resolvable");
		};
	}
}
