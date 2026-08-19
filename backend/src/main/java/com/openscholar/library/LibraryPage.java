package com.openscholar.library;

import java.util.List;

public record LibraryPage<T>(
		List<T> items,
		int page,
		int size,
		long totalElements,
		int totalPages) {

	public LibraryPage {
		items = items == null ? List.of() : List.copyOf(items);
		if (page < 0 || size < 1 || totalElements < 0 || totalPages < 0) {
			throw new IllegalArgumentException("Invalid page metadata");
		}
	}
}
