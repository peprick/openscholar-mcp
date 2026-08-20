package com.openscholar.search;

import java.util.UUID;

public class SearchPageExhaustedException extends RuntimeException {

	public SearchPageExhaustedException(UUID searchId) {
		super("Search snapshot has no next page: " + searchId);
	}
}
