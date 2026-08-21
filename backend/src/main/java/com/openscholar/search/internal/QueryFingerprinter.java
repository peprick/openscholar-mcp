package com.openscholar.search.internal;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.stream.Collectors;

import com.openscholar.provider.ProviderId;
import com.openscholar.search.SearchCommand;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
class QueryFingerprinter {

	static final int FINGERPRINT_VERSION = 1;
	static final String OPENALEX_PIPELINE_VERSION = "openalex-v1";
	static final String FANOUT_PIPELINE_VERSION = "provider-fanout-v1";

	private final QueryNormalizer normalizer;
	private final List<ProviderId> providers;

	@Autowired
	QueryFingerprinter(QueryNormalizer normalizer, ResearchProviderFanout providerFanout) {
		this(normalizer, providerFanout.providerIds());
	}

	QueryFingerprinter(QueryNormalizer normalizer) {
		this(normalizer, List.of(ProviderId.OPENALEX));
	}

	QueryFingerprinter(QueryNormalizer normalizer, List<ProviderId> providers) {
		this.normalizer = normalizer;
		this.providers = providers.stream()
				.distinct()
				.sorted(Comparator.comparing(ProviderId::name))
				.toList();
		if (this.providers.isEmpty()) {
			throw new IllegalArgumentException("At least one provider is required for search fingerprinting");
		}
	}

	String normalizedQuery(SearchCommand command) {
		return normalizer.normalize(command.query());
	}

	String fingerprint(SearchCommand command) {
		String types = command.documentTypes().stream()
				.map(Enum::name)
				.sorted()
				.collect(Collectors.joining(","));
		String languages = command.languages().stream()
				.sorted()
				.collect(Collectors.joining(","));
		String cursor = Base64.getUrlEncoder().withoutPadding()
				.encodeToString(command.cursor().getBytes(StandardCharsets.UTF_8));
		String providerSet = providers.stream().map(ProviderId::name).collect(Collectors.joining(","));
		String canonical = String.join("\n",
				"fingerprintVersion=" + FINGERPRINT_VERSION,
				"pipelineVersion=" + pipelineVersion(),
				"query=" + normalizedQuery(command),
				"yearFrom=" + nullable(command.yearFrom()),
				"yearTo=" + nullable(command.yearTo()),
				"documentTypes=" + types,
				"openAccessOnly=" + command.openAccessOnly(),
				"minimumCitations=" + command.minimumCitations(),
				"languages=" + languages,
				"pageSize=" + command.pageSize(),
				"cursor=" + cursor,
				"providers=" + providerSet);
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			return HexFormat.of().formatHex(digest.digest(canonical.getBytes(StandardCharsets.UTF_8)));
		}
		catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException("SHA-256 is not available", exception);
		}
	}

	int fingerprintVersion() {
		return FINGERPRINT_VERSION;
	}

	String pipelineVersion() {
		return providers.equals(List.of(ProviderId.OPENALEX))
				? OPENALEX_PIPELINE_VERSION
				: FANOUT_PIPELINE_VERSION;
	}

	private static String nullable(Object value) {
		return value == null ? "" : value.toString();
	}
}
