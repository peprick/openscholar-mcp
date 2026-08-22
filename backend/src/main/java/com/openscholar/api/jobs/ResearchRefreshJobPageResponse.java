package com.openscholar.api.jobs;

import java.util.List;

import com.openscholar.jobs.ResearchRefreshJobPage;

public record ResearchRefreshJobPageResponse(
		List<ResearchRefreshJobResponse> items,
		int page,
		int size,
		long totalElements,
		int totalPages) {

	static ResearchRefreshJobPageResponse from(ResearchRefreshJobPage view) {
		return new ResearchRefreshJobPageResponse(
				view.items().stream().map(ResearchRefreshJobResponse::from).toList(),
				view.page(), view.size(), view.totalElements(), view.totalPages());
	}
}
