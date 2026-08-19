package com.openscholar.library;

import java.util.UUID;

public final class SavedPaperNotFoundException extends RuntimeException {

	public SavedPaperNotFoundException(UUID collectionId, UUID paperId) {
		super("Paper " + paperId + " is not saved in collection " + collectionId);
	}
}
