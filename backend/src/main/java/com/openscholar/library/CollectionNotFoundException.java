package com.openscholar.library;

import java.util.UUID;

public final class CollectionNotFoundException extends RuntimeException {

	public CollectionNotFoundException(UUID collectionId) {
		super("Collection not found: " + collectionId);
	}
}
