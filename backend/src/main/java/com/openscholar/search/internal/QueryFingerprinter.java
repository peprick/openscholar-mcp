package com.openscholar.search.internal;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.HexFormat;
import java.util.stream.Collectors;

import com.openscholar.search.SearchCommand;
import org.springframework.stereotype.Component;

@Component
class QueryFingerprinter {

	static final int FINGERPRINT_VERSION = 1;
	static final String PIPELINE_VERSION = "openalex-v1";

	private final QueryNormalizer normalizer;

	QueryFingerprinter(QueryNormalizer normalizer) {
		this.normalizer = normalizer;
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
		String canonical = String.join("\n",
				"fingerprintVersion=" + FINGERPRINT_VERSION,
				"pipelineVersion=" + PIPELINE_VERSION,
				"query=" + normalizedQuery(command),
				"yearFrom=" + nullable(command.yearFrom()),
				"yearTo=" + nullable(command.yearTo()),
				"documentTypes=" + types,
				"openAccessOnly=" + command.openAccessOnly(),
				"minimumCitations=" + command.minimumCitations(),
				"languages=" + languages,
				"pageSize=" + command.pageSize(),
				"cursor=" + cursor,
				"providers=OPENALEX");
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			return HexFormat.of().formatHex(digest.digest(canonical.getBytes(StandardCharsets.UTF_8)));
		}
		catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException("SHA-256 is not available", exception);
		}
	}

	private static String nullable(Object value) {
		return value == null ? "" : value.toString();
	}
}
