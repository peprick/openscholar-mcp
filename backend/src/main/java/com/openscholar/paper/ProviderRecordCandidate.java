package com.openscholar.paper;

import java.net.URI;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;

public record ProviderRecordCandidate(
		String provider,
		String providerRecordId,
		Instant providerUpdatedAt,
		Instant retrievedAt,
		URI sourceUrl,
		boolean reportedOpenAccess,
		URI landingPageUrl,
		URI pdfUrl,
		Map<String, Object> metadataFragment) {

	public ProviderRecordCandidate {
		provider = Objects.requireNonNull(provider, "provider");
		providerRecordId = Objects.requireNonNull(providerRecordId, "providerRecordId");
		retrievedAt = Objects.requireNonNull(retrievedAt, "retrievedAt");
		metadataFragment = metadataFragment == null ? Map.of() : Map.copyOf(metadataFragment);
	}
}
