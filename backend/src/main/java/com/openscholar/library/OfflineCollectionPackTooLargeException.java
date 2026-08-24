package com.openscholar.library;

public class OfflineCollectionPackTooLargeException extends RuntimeException {

	public OfflineCollectionPackTooLargeException() {
		super("The collection exceeds the supported offline metadata pack limits.");
	}
}
