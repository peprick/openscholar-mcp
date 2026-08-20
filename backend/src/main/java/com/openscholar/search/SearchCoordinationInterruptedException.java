package com.openscholar.search;

public final class SearchCoordinationInterruptedException extends RuntimeException {

	public SearchCoordinationInterruptedException(InterruptedException cause) {
		super("Search coordination wait was interrupted", cause);
	}
}
