package com.openscholar.search;

public final class SearchDeadlineExceededException extends RuntimeException {

	public SearchDeadlineExceededException() {
		super("Search execution deadline exceeded");
	}
}
