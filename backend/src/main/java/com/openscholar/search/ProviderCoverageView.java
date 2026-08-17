package com.openscholar.search;

import com.openscholar.provider.ProviderId;

public record ProviderCoverageView(
		ProviderId provider,
		String status,
		int returnedCount,
		long totalMatches) {
}
