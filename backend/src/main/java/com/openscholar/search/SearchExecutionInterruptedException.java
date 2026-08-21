package com.openscholar.search;

public final class SearchExecutionInterruptedException extends RuntimeException {

	public SearchExecutionInterruptedException(Throwable cause) {
		super("Search execution was interrupted", cause);
	}
}
