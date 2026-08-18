package com.openscholar.paper;

import java.net.URI;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record PaperProviderRecordView(
		UUID id,
		String provider,
		String providerRecordId,
		URI sourceUrl,
		Instant providerUpdatedAt,
		Instant retrievedAt,
		boolean reportedOpenAccess) {

	public PaperProviderRecordView {
		id = Objects.requireNonNull(id, "id");
		provider = requireText(provider, "provider");
		providerRecordId = requireText(providerRecordId, "providerRecordId");
		retrievedAt = Objects.requireNonNull(retrievedAt, "retrievedAt");
	}

	private static String requireText(String value, String name) {
		if (value == null || value.isBlank()) {
			throw new IllegalArgumentException(name + " must not be blank");
		}
		return value;
	}
}
