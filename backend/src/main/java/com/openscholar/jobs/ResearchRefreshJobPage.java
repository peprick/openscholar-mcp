package com.openscholar.jobs;

import java.util.List;

public record ResearchRefreshJobPage(
		List<ResearchRefreshJobView> items,
		int page,
		int size,
		long totalElements,
		int totalPages) {

	public ResearchRefreshJobPage {
		items = items == null ? List.of() : List.copyOf(items);
	}
}
