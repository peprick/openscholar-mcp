package com.openscholar.provider;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

public record ProviderSearchResult(
		ProviderId provider,
		List<ProviderPaperRecord> records,
		long totalMatches,
		String nextCursor,
		Instant retrievedAt) {

	public ProviderSearchResult {
		provider = Objects.requireNonNull(provider, "provider");
		records = records == null ? List.of() : List.copyOf(records);
		retrievedAt = Objects.requireNonNull(retrievedAt, "retrievedAt");
	}
}
