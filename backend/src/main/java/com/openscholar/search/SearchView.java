package com.openscholar.search;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record SearchView(
		UUID searchId,
		String query,
		String queryFingerprint,
		CacheDisposition cacheDisposition,
		Instant searchedAt,
		Instant freshUntil,
		String nextCursor,
		List<ProviderCoverageView> providerCoverage,
		List<String> warnings,
		List<SearchResultView> results) {

	public SearchView {
		providerCoverage = providerCoverage == null ? List.of() : List.copyOf(providerCoverage);
		warnings = warnings == null ? List.of() : List.copyOf(warnings);
		results = results == null ? List.of() : List.copyOf(results);
	}
}
