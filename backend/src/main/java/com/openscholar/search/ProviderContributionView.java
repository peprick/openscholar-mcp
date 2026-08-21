package com.openscholar.search;

import java.time.Instant;
import java.util.Objects;

import com.openscholar.provider.ProviderId;

public record ProviderContributionView(
		ProviderId provider, String providerRecordId, Instant retrievedAt) {

	public ProviderContributionView {
		provider = Objects.requireNonNull(provider, "provider");
		providerRecordId = Objects.requireNonNull(providerRecordId, "providerRecordId");
		retrievedAt = Objects.requireNonNull(retrievedAt, "retrievedAt");
	}
}
