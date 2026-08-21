package com.openscholar.provider;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * One bounded discovery attempt across every enabled research provider.
 * Successful and failed providers are retained independently so callers can
 * persist useful partial results without hiding incomplete coverage.
 */
public record ProviderSearchBatchResult(
		List<ProviderSearchResult> results,
		List<ProviderException> failures,
		String nextCursor,
		Instant retrievedAt) {

	public ProviderSearchBatchResult {
		results = results == null ? List.of() : List.copyOf(results);
		failures = failures == null ? List.of() : List.copyOf(failures);
		retrievedAt = Objects.requireNonNull(retrievedAt, "retrievedAt");
		if (results.isEmpty() && failures.isEmpty()) {
			throw new IllegalArgumentException("A provider search batch must contain an outcome");
		}
	}
}
