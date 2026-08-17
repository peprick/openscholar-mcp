package com.openscholar.paper.internal.persistence;

public final class PaperCatalogConflictException extends IllegalStateException {

	PaperCatalogConflictException(String message) {
		super(message);
	}
}
