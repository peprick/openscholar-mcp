package com.openscholar.search;

public final class SearchCoordinationTimeoutException extends RuntimeException {

	public SearchCoordinationTimeoutException() {
		super("Search coordination wait timed out");
	}
}
