package com.openscholar.access.internal.provider.unpaywall;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
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

final class UnpaywallAccessEvidenceProvider implements AccessEvidenceProvider {

	static final String RATE_LIMITED = "UNPAYWALL_RATE_LIMITED";
	static final String UPSTREAM_ERROR = "UNPAYWALL_UPSTREAM_ERROR";
	static final String REQUEST_REJECTED = "UNPAYWALL_REQUEST_REJECTED";
	static final String TIMEOUT = "UNPAYWALL_TIMEOUT";
	static final String UNAVAILABLE = "UNPAYWALL_UNAVAILABLE";
	static final String RESPONSE_ERROR = "UNPAYWALL_RESPONSE_ERROR";

	private static final int MAX_CANDIDATES = 20;

	private final RestClient restClient;
	private final UnpaywallProperties properties;
	private final Clock clock;

	UnpaywallAccessEvidenceProvider(RestClient restClient, UnpaywallProperties properties, Clock clock) {
		this.restClient = Objects.requireNonNull(restClient, "restClient");
		this.properties = Objects.requireNonNull(properties, "properties");
		this.clock = Objects.requireNonNull(clock, "clock");
	}

	@Override
	public AccessSource source() {
		return AccessSource.UNPAYWALL;
	}

	@Override
	public AccessEvidenceResult resolve(AccessEvidenceLookup lookup) {
		Objects.requireNonNull(lookup, "lookup");
		Instant retrievedAt = clock.instant();
		if (lookup.normalizedDoi() == null) {
			return AccessEvidenceResult.unresolved(
					source(), AccessResolutionStatus.NOT_APPLICABLE, retrievedAt, "doi_missing");
		}
		if (!properties.configured()) {
			return AccessEvidenceResult.unresolved(
					source(), AccessResolutionStatus.NOT_CONFIGURED, retrievedAt, "backend_email_missing");
		}

		try {
			UnpaywallResponse response = restClient.get()
					.uri(uriBuilder -> uriBuilder
							.path("/v2/")
							.path(lookup.normalizedDoi())
							.queryParam("email", properties.email())
							.build())
					.accept(MediaType.APPLICATION_JSON)
					.retrieve()
					.body(UnpaywallResponse.class);
			return mapResponse(response, lookup.normalizedDoi(), retrievedAt);
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
		catch (RestClientException exception) {
			throw exception(RESPONSE_ERROR, "Unpaywall returned an unreadable response", false, null, exception);
		}
	}

	private AccessEvidenceResult mapResponse(UnpaywallResponse response, String requestedDoi, Instant retrievedAt) {
		if (response == null || response.openAccess() == null) {
			throw exception(RESPONSE_ERROR, "Unpaywall returned an incomplete response", true, null, null);
		}
		String responseDoi;
		try {
			responseDoi = AccessEvidenceLookup.normalizeDoi(response.doi());
		}
		catch (IllegalArgumentException invalidDoi) {
			throw exception(RESPONSE_ERROR, "Unpaywall returned an invalid DOI", true, null, invalidDoi);
		}
		if (responseDoi == null || !responseDoi.equals(requestedDoi)) {
			throw exception(RESPONSE_ERROR, "Unpaywall returned a mismatched DOI", true, null, null);
		}

		Map<String, String> resultEvidence = resultEvidence(response);
		if (!response.openAccess()) {
			return new AccessEvidenceResult(
					source(), AccessResolutionStatus.CLOSED, List.of(), retrievedAt, resultEvidence);
		}

		UnpaywallLocation bestLocation = response.bestOpenAccessLocation();
		List<AccessCandidate> candidates = new ArrayList<>();
		for (UnpaywallLocation location : nullSafe(response.openAccessLocations())) {
			AccessCandidate candidate = mapLocation(requestedDoi, location, bestLocation);
			if (candidate != null) {
				candidates.add(candidate);
			}
		}
		candidates.sort(Comparator.comparing(AccessCandidate::best).reversed());

		Map<String, AccessCandidate> unique = new LinkedHashMap<>();
		for (AccessCandidate candidate : candidates) {
			unique.putIfAbsent(candidate.sourceKey(), candidate);
			if (unique.size() == MAX_CANDIDATES) {
				break;
			}
		}
		List<AccessCandidate> bounded = List.copyOf(unique.values());
		if (bounded.isEmpty()) {
			throw exception(
					RESPONSE_ERROR,
					"Unpaywall reported open access without a usable location",
					true,
					null,
					null);
		}
		return new AccessEvidenceResult(
				source(), AccessResolutionStatus.RESOLVED, bounded, retrievedAt, resultEvidence);
	}

	private AccessCandidate mapLocation(String doi, UnpaywallLocation location, UnpaywallLocation bestLocation) {
		if (location == null) {
			return null;
		}
		URI landingPage = firstUri(location.landingPageUrl(), location.url());
		// Unpaywall's generic `url` is intentionally treated as a landing page, never as proof of a PDF.
		URI pdf = AccessProviderHttpSupport.safeHttpUri(location.pdfUrl());
		if (landingPage == null && pdf == null) {
			return null;
		}
		String fingerprint = locationFingerprint(location);
		boolean best = Boolean.TRUE.equals(location.best())
				|| matchingLocation(location, bestLocation);
		return new AccessCandidate(
				source(),
				sourceKey(doi, fingerprint, landingPage, pdf),
				best,
				boundedText(location.hostType(), 100),
				boundedText(location.version(), 100),
				boundedText(location.license(), 500),
				landingPage,
				pdf,
				parseInstant(location.updated()),
				locationEvidence(location));
	}

	private static Map<String, String> resultEvidence(UnpaywallResponse response) {
		Map<String, String> evidence = new LinkedHashMap<>();
		put(evidence, "doi", response.doi(), 500);
		put(evidence, "oaStatus", response.openAccessStatus(), 100);
		put(evidence, "providerUpdatedAt", response.updated(), 100);
		if (response.dataStandard() != null) {
			evidence.put("dataStandard", response.dataStandard().toString());
		}
		return Map.copyOf(evidence);
	}

	private static Map<String, String> locationEvidence(UnpaywallLocation location) {
		Map<String, String> evidence = new LinkedHashMap<>();
		put(evidence, "endpointId", location.endpointId(), 500);
		put(evidence, "pmhId", location.pmhId(), 500);
		put(evidence, "evidence", location.evidence(), 1_000);
		put(evidence, "repositoryInstitution", location.repositoryInstitution(), 500);
		put(evidence, "oaDate", location.openAccessDate(), 100);
		return Map.copyOf(evidence);
	}

	private static String locationFingerprint(UnpaywallLocation location) {
		if (location == null) {
			return null;
		}
		String identity = firstNonBlank(
				location.pmhId(),
				location.endpointId() == null ? null : location.endpointId() + "|" + firstNonBlank(
						location.pdfUrl(), location.landingPageUrl(), location.url()),
				location.pdfUrl(),
				location.landingPageUrl(),
				location.url());
		return identity == null ? null : identity.strip();
	}

	private static boolean matchingLocation(UnpaywallLocation candidate, UnpaywallLocation best) {
		if (candidate == null || best == null) {
			return false;
		}
		return sameNonBlank(candidate.pdfUrl(), best.pdfUrl())
				|| sameNonBlank(candidate.landingPageUrl(), best.landingPageUrl())
				|| sameNonBlank(candidate.url(), best.url())
				|| (sameNonBlank(candidate.endpointId(), best.endpointId())
						&& sameNonBlank(candidate.pmhId(), best.pmhId()));
	}

	private static boolean sameNonBlank(String left, String right) {
		return left != null && !left.isBlank() && right != null && left.strip().equals(right.strip());
	}

	private static String sourceKey(String doi, String fingerprint, URI landingPage, URI pdf) {
		String identity = firstNonBlank(fingerprint, uriText(pdf), uriText(landingPage));
		String input = doi + "|" + identity;
		try {
			byte[] digest = MessageDigest.getInstance("SHA-256").digest(input.getBytes(StandardCharsets.UTF_8));
			return "unpaywall:" + HexFormat.of().formatHex(digest);
		}
		catch (NoSuchAlgorithmException impossible) {
			throw new IllegalStateException("SHA-256 is required by the Java platform", impossible);
		}
	}

	private AccessProviderException translateStatus(RestClientResponseException exception) {
		int status = exception.getStatusCode().value();
		Duration retryAfter = AccessProviderHttpSupport.retryAfter(exception.getResponseHeaders(), clock);
		if (status == 429) {
			return exception(RATE_LIMITED, "Unpaywall rate limit reached", true, retryAfter, exception);
		}
		if (status >= 500) {
			return exception(UPSTREAM_ERROR, "Unpaywall is temporarily unavailable", true, retryAfter, exception);
		}
		return exception(REQUEST_REJECTED, "Unpaywall rejected the DOI lookup", false, null, exception);
	}

	private AccessProviderException translateAccessFailure(ResourceAccessException failure) {
		boolean timeout = AccessProviderHttpSupport.hasTimeoutCause(failure);
		return exception(
				timeout ? TIMEOUT : UNAVAILABLE,
				timeout ? "Unpaywall request timed out" : "Unpaywall could not be reached",
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

	private static List<UnpaywallLocation> nullSafe(List<UnpaywallLocation> values) {
		return values == null ? List.of() : values;
	}

	private static URI firstUri(String... values) {
		for (String value : values) {
			URI uri = AccessProviderHttpSupport.safeHttpUri(value);
			if (uri != null) {
				return uri;
			}
		}
		return null;
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
			catch (DateTimeParseException invalidOffset) {
				try {
					return LocalDate.parse(clean).atStartOfDay(java.time.ZoneOffset.UTC).toInstant();
				}
				catch (DateTimeParseException invalidDate) {
					return null;
				}
			}
		}
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

	private static String firstNonBlank(String... values) {
		for (String value : values) {
			if (value != null && !value.isBlank()) {
				return value.strip();
			}
		}
		return null;
	}

	private static String uriText(URI value) {
		return value == null ? null : value.toString().toLowerCase(Locale.ROOT);
	}
}
