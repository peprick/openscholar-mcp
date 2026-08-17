package com.openscholar.search;

import java.util.UUID;

public class SearchNotFoundException extends RuntimeException {

	public SearchNotFoundException(UUID searchId) {
		super("Search snapshot not found: " + searchId);
	}
}
