package com.openscholar.search;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record SearchView(
		UUID searchId,
		String query,
		String queryFingerprint,
		CacheDisposition cacheDisposition,
		SearchMode requestedMode,
		SearchExecutionSource executionSource,
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

	public SearchView(
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
		this(searchId, query, queryFingerprint, cacheDisposition, SearchMode.AUTO,
				sourceFor(cacheDisposition), searchedAt, freshUntil, nextCursor,
				providerCoverage, warnings, results);
	}

	private static SearchExecutionSource sourceFor(CacheDisposition disposition) {
		return switch (disposition) {
			case EXACT_HIT -> SearchExecutionSource.EXACT_CACHE;
			case STALE_FALLBACK -> SearchExecutionSource.STALE_CACHE;
			case LOCAL_RESULT -> SearchExecutionSource.LOCAL_CATALOG;
			default -> SearchExecutionSource.PROVIDER_FETCH;
		};
	}
}
