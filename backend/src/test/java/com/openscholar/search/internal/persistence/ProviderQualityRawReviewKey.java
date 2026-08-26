package com.openscholar.search.internal.persistence;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

import com.openscholar.provider.ProviderId;

final class ProviderQualityRawReviewKey {

	private static final byte[] DOMAIN =
			"openscholar-provider-quality-raw-review-key-v1".getBytes(StandardCharsets.UTF_8);

	private ProviderQualityRawReviewKey() {
	}

	static String create(
			String querySetId,
			String queryKey,
			ProviderId provider,
			String providerRecordId) {
		String boundedQuerySetId = bounded(querySetId, "querySetId", 3, 100);
		String boundedQueryKey = bounded(queryKey, "queryKey", 3, 80);
		ProviderId boundedProvider = Objects.requireNonNull(provider, "provider");
		if (boundedProvider != ProviderId.OPENALEX && boundedProvider != ProviderId.EUROPE_PMC) {
			throw new IllegalArgumentException("provider must be OPENALEX or EUROPE_PMC");
		}
		String boundedProviderRecordId = bounded(
				providerRecordId, "providerRecordId", 1, 1_024);

		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			update(digest, DOMAIN);
			update(digest, boundedQuerySetId.getBytes(StandardCharsets.UTF_8));
			update(digest, boundedQueryKey.getBytes(StandardCharsets.UTF_8));
			update(digest, boundedProvider.name().getBytes(StandardCharsets.UTF_8));
			update(digest, boundedProviderRecordId.getBytes(StandardCharsets.UTF_8));
			return HexFormat.of().formatHex(digest.digest());
		}
		catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException("SHA-256 is unavailable", exception);
		}
	}

	private static void update(MessageDigest digest, byte[] value) {
		digest.update((byte) (value.length >>> 24));
		digest.update((byte) (value.length >>> 16));
		digest.update((byte) (value.length >>> 8));
		digest.update((byte) value.length);
		digest.update(value);
	}

	private static String bounded(String value, String field, int minimum, int maximum) {
		if (value == null || !value.equals(value.strip())
				|| value.length() < minimum || value.length() > maximum) {
			throw new IllegalArgumentException(
					field + " must contain " + minimum + " through " + maximum
							+ " characters without surrounding whitespace");
		}
		return value;
	}
}
