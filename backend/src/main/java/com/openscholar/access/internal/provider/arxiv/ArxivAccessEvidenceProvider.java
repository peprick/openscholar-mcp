package com.openscholar.access.internal.provider.arxiv;

import java.net.URI;
import java.net.URISyntaxException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

import com.openscholar.access.internal.provider.AccessCandidate;
import com.openscholar.access.internal.provider.AccessEvidenceLookup;
import com.openscholar.access.internal.provider.AccessEvidenceProvider;
import com.openscholar.access.internal.provider.AccessEvidenceResult;
import com.openscholar.access.internal.provider.AccessProviderException;
import com.openscholar.access.internal.provider.AccessProviderHttpSupport;
import com.openscholar.access.internal.provider.AccessResolutionStatus;
import com.openscholar.access.internal.provider.AccessSource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

final class ArxivAccessEvidenceProvider implements AccessEvidenceProvider {

	static final String RATE_LIMITED = "ARXIV_RATE_LIMITED";
	static final String UPSTREAM_ERROR = "ARXIV_UPSTREAM_ERROR";
	static final String REQUEST_REJECTED = "ARXIV_REQUEST_REJECTED";
	static final String TIMEOUT = "ARXIV_TIMEOUT";
	static final String UNAVAILABLE = "ARXIV_UNAVAILABLE";
	static final String RESPONSE_ERROR = "ARXIV_RESPONSE_ERROR";
	static final String INTERRUPTED = "ARXIV_INTERRUPTED";

	private final RestClient restClient;
	private final Clock clock;
	private final ArxivRateGate rateGate;
	private final ArxivFeedParser parser;

	ArxivAccessEvidenceProvider(RestClient restClient, ArxivProperties properties, Clock clock) {
		this(restClient, clock, new ArxivRateGate(properties.minimumRequestInterval()), new ArxivFeedParser());
	}

	// Package-private test seam: production properties reject intervals shorter than three seconds.
	ArxivAccessEvidenceProvider(RestClient restClient, Clock clock, Duration testRequestInterval) {
		this(restClient, clock, new ArxivRateGate(testRequestInterval), new ArxivFeedParser());
	}

	ArxivAccessEvidenceProvider(
			RestClient restClient,
			Clock clock,
			ArxivRateGate rateGate,
			ArxivFeedParser parser) {
		this.restClient = Objects.requireNonNull(restClient, "restClient");
		this.clock = Objects.requireNonNull(clock, "clock");
		this.rateGate = Objects.requireNonNull(rateGate, "rateGate");
		this.parser = Objects.requireNonNull(parser, "parser");
	}

	@Override
	public AccessSource source() {
		return AccessSource.ARXIV;
	}

	@Override
	public synchronized AccessEvidenceResult resolve(AccessEvidenceLookup lookup) {
		Objects.requireNonNull(lookup, "lookup");
		String requestedId = lookup.canonicalArxivId();
		if (requestedId == null) {
			return AccessEvidenceResult.unresolved(
					source(), AccessResolutionStatus.NOT_APPLICABLE, clock.instant(), "arxiv_id_missing");
		}

		acquirePermit();
		Instant retrievedAt = clock.instant();
		try {
			byte[] body = restClient.get()
					.uri(uriBuilder -> uriBuilder
							.pathSegment("query")
							.queryParam("id_list", requestedId)
							.queryParam("max_results", 1)
							.build())
					.accept(MediaType.APPLICATION_ATOM_XML, MediaType.APPLICATION_XML)
					.retrieve()
					.body(byte[].class);
			return mapFeed(parser.parse(body), requestedId, retrievedAt);
		}
		catch (RestClientResponseException exception) {
			if (exception.getStatusCode() == HttpStatus.NOT_FOUND) {
				return AccessEvidenceResult.unresolved(
						source(), AccessResolutionStatus.NO_RECORD, retrievedAt, "provider_404");
			}
			throw translateStatus(exception);
		}
		catch (ResourceAccessException exception) {
			throw translateAccessFailure(exception);
		}
		catch (ArxivFeedParseException exception) {
			throw exception(RESPONSE_ERROR, "arXiv returned an unreadable feed", true, null, exception);
		}
		catch (RestClientException exception) {
			throw exception(RESPONSE_ERROR, "arXiv returned an unreadable response", false, null, exception);
		}
	}

	private AccessEvidenceResult mapFeed(ArxivFeed feed, String requestedId, Instant retrievedAt) {
		if (feed.entries().isEmpty()) {
			return AccessEvidenceResult.unresolved(
					source(), AccessResolutionStatus.NO_RECORD, retrievedAt, "empty_feed");
		}
		if (feed.entries().size() != 1) {
			throw exception(RESPONSE_ERROR, "arXiv returned an unexpected number of entries", true, null, null);
		}

		ArxivFeedEntry entry = feed.entries().getFirst();
		if (isErrorEntry(entry)) {
			throw exception(REQUEST_REJECTED, "arXiv rejected the identifier lookup", false, null, null);
		}

		String returnedId;
		try {
			returnedId = AccessEvidenceLookup.normalizeArxivId(entry.id());
		}
		catch (IllegalArgumentException invalidId) {
			throw exception(RESPONSE_ERROR, "arXiv returned an invalid entry identifier", true, null, invalidId);
		}
		if (returnedId == null || !matchingId(requestedId, returnedId)) {
			throw exception(RESPONSE_ERROR, "arXiv returned a mismatched entry", true, null, null);
		}

		URI landingPage = safeEntryUri(entry.id(), returnedId, "/abs/");
		URI pdf = null;
		for (ArxivLink link : entry.links()) {
			if (isAlternateLink(link)) {
				URI candidate = safeEntryUri(link.href(), returnedId, "/abs/");
				if (candidate != null) {
					landingPage = candidate;
				}
			}
			if (isPdfLink(link)) {
				URI candidate = safeEntryUri(link.href(), returnedId, "/pdf/");
				if (candidate != null) {
					pdf = candidate;
				}
			}
		}
		if (landingPage == null && pdf == null) {
			throw exception(RESPONSE_ERROR, "arXiv returned no trusted access URL", true, null, null);
		}

		Map<String, String> candidateEvidence = new LinkedHashMap<>();
		candidateEvidence.put("arxivId", returnedId);
		put(candidateEvidence, "arxivVersion", versionSuffix(returnedId), 20);
		put(candidateEvidence, "publishedAt", entry.published(), 100);
		AccessCandidate candidate = new AccessCandidate(
				source(),
				"arxiv:" + returnedId,
				true,
				"preprint_server",
				"preprint",
				boundedText(entry.license(), 500),
				landingPage,
				pdf,
				parseInstant(entry.updated()),
				Map.copyOf(candidateEvidence));
		return new AccessEvidenceResult(
				source(),
				AccessResolutionStatus.RESOLVED,
				List.of(candidate),
				retrievedAt,
				Map.of("requestedArxivId", requestedId, "returnedArxivId", returnedId));
	}

	private void acquirePermit() {
		try {
			rateGate.acquire();
		}
		catch (InterruptedException interrupted) {
			Thread.currentThread().interrupt();
			throw exception(INTERRUPTED, "arXiv request was interrupted", true, null, interrupted);
		}
	}

	private AccessProviderException translateStatus(RestClientResponseException failure) {
		int status = failure.getStatusCode().value();
		Duration retryAfter = AccessProviderHttpSupport.retryAfter(failure.getResponseHeaders(), clock);
		if (status == 429) {
			return exception(RATE_LIMITED, "arXiv rate limit reached", true, retryAfter, failure);
		}
		if (status >= 500) {
			return exception(UPSTREAM_ERROR, "arXiv is temporarily unavailable", true, retryAfter, failure);
		}
		return exception(REQUEST_REJECTED, "arXiv rejected the identifier lookup", false, null, failure);
	}

	private AccessProviderException translateAccessFailure(ResourceAccessException failure) {
		boolean timeout = AccessProviderHttpSupport.hasTimeoutCause(failure);
		return exception(
				timeout ? TIMEOUT : UNAVAILABLE,
				timeout ? "arXiv request timed out" : "arXiv could not be reached",
				true,
				null,
				failure);
	}

	private AccessProviderException exception(
			String code,
			String message,
			boolean retryable,
			Duration retryAfter,
			Throwable cause) {
		return new AccessProviderException(source(), code, message, retryable, retryAfter, cause);
	}

	private static boolean isErrorEntry(ArxivFeedEntry entry) {
		String id = entry.id() == null ? "" : entry.id().toLowerCase(Locale.ROOT);
		String title = entry.title() == null ? "" : entry.title().strip();
		return id.contains("/api/errors") || title.equalsIgnoreCase("error");
	}

	private static boolean matchingId(String requestedId, String returnedId) {
		if (requestedId.matches(".*v\\d+$")) {
			return requestedId.equals(returnedId);
		}
		return AccessEvidenceLookup.arxivBaseId(requestedId)
				.equals(AccessEvidenceLookup.arxivBaseId(returnedId));
	}

	private static boolean isAlternateLink(ArxivLink link) {
		return "alternate".equalsIgnoreCase(link.rel())
				&& (link.type() == null || link.type().isBlank() || link.type().equalsIgnoreCase("text/html"));
	}

	private static boolean isPdfLink(ArxivLink link) {
		return "pdf".equalsIgnoreCase(link.title()) || "application/pdf".equalsIgnoreCase(link.type());
	}

	private static URI safeEntryUri(String value, String expectedId, String expectedPathPrefix) {
		URI raw = AccessProviderHttpSupport.safeHttpUri(value);
		if (raw == null) {
			return null;
		}
		String host = raw.getHost().toLowerCase(Locale.ROOT);
		if ((!host.equals("arxiv.org") && !host.equals("www.arxiv.org"))
				|| (raw.getPort() != -1 && raw.getPort() != 80 && raw.getPort() != 443)
				|| raw.getPath() == null
				|| !raw.getPath().startsWith(expectedPathPrefix)) {
			return null;
		}
		String idInPath = raw.getPath().substring(expectedPathPrefix.length()).replaceFirst("(?i)\\.pdf$", "");
		String normalizedPathId;
		try {
			normalizedPathId = AccessEvidenceLookup.normalizeArxivId(idInPath);
		}
		catch (IllegalArgumentException invalidPath) {
			return null;
		}
		if (!matchingId(expectedId, normalizedPathId)) {
			return null;
		}
		try {
			return new URI("https", null, "arxiv.org", -1, raw.getPath(), raw.getQuery(), null);
		}
		catch (URISyntaxException impossible) {
			return null;
		}
	}

	private static Instant parseInstant(String value) {
		String clean = boundedText(value, 100);
		if (clean == null) {
			return null;
		}
		try {
			return Instant.parse(clean);
		}
		catch (DateTimeParseException ignored) {
			try {
				return OffsetDateTime.parse(clean).toInstant();
			}
			catch (DateTimeParseException invalid) {
				return null;
			}
		}
	}

	private static String versionSuffix(String arxivId) {
		int marker = arxivId.lastIndexOf('v');
		return marker < 0 ? null : arxivId.substring(marker);
	}

	private static void put(Map<String, String> target, String key, String value, int maximumLength) {
		String clean = boundedText(value, maximumLength);
		if (clean != null) {
			target.put(key, clean);
		}
	}

	private static String boundedText(String value, int maximumLength) {
		if (value == null || value.isBlank()) {
			return null;
		}
		String clean = value.strip().replaceAll("[\\p{Cc}&&[^\\t]]", "");
		if (clean.isBlank()) {
			return null;
		}
		return clean.length() <= maximumLength ? clean : clean.substring(0, maximumLength);
	}
}
