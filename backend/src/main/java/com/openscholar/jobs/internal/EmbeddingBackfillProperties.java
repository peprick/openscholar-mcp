package com.openscholar.jobs.internal;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

import com.openscholar.embedding.EmbeddingGenerator;
import com.openscholar.jobs.EmbeddingBackfillCommand;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(EmbeddingBackfillProperties.PREFIX)
record EmbeddingBackfillProperties(
		@DefaultValue("false") boolean enabled,
		String profileKey,
		String afterExclusive,
		@DefaultValue("100") int limit,
		@DefaultValue("2") int maxAttempts) {

	static final String PREFIX = "openscholar.embedding.backfill";

	EmbeddingBackfillProperties {
		profileKey = cleanOptional(profileKey);
		afterExclusive = cleanOptional(afterExclusive);
		new EmbeddingBackfillCommand(
				profileKey == null ? "placeholder-v1" : profileKey,
				parseCursor(afterExclusive),
				limit,
				maxAttempts);
	}

	EmbeddingBackfillCommand command(List<EmbeddingGenerator> generators) {
		return new EmbeddingBackfillCommand(
				resolveProfileKey(generators),
				parseCursor(afterExclusive),
				limit,
				maxAttempts);
	}

	private String resolveProfileKey(List<EmbeddingGenerator> generators) {
		if (profileKey != null) {
			return profileKey;
		}
		List<EmbeddingGenerator> configured = List.copyOf(
				Objects.requireNonNull(generators, "generators"));
		if (configured.size() != 1) {
			throw new IllegalStateException(
					"Embedding backfill requires an explicit profile key unless exactly one generator is configured");
		}
		return Objects.requireNonNull(
				Objects.requireNonNull(configured.getFirst(), "embedding generator").profile(),
				"embedding generator profile")
				.profileKey();
	}

	private static String cleanOptional(String value) {
		if (value == null || value.isBlank()) {
			return null;
		}
		return value.strip();
	}

	private static UUID parseCursor(String value) {
		if (value == null) {
			return null;
		}
		try {
			return UUID.fromString(value);
		}
		catch (IllegalArgumentException exception) {
			throw new IllegalArgumentException(
					"Embedding backfill afterExclusive must be a UUID", exception);
		}
	}
}
