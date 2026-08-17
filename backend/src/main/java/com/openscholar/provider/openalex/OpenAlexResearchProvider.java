package com.openscholar.provider.openalex;

import java.io.InterruptedIOException;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.http.HttpTimeoutException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.openscholar.paper.DocumentType;
import com.openscholar.provider.ProviderAuthor;
import com.openscholar.provider.ProviderException;
import com.openscholar.provider.ProviderId;
import com.openscholar.provider.ProviderPaperRecord;
import com.openscholar.provider.ProviderSearchQuery;
import com.openscholar.provider.ProviderSearchResult;
import com.openscholar.provider.ResearchProvider;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

final class OpenAlexResearchProvider implements ResearchProvider {

	static final String RATE_LIMITED = "OPENALEX_RATE_LIMITED";
	static final String UPSTREAM_ERROR = "OPENALEX_UPSTREAM_ERROR";
	static final String REQUEST_REJECTED = "OPENALEX_REQUEST_REJECTED";
	static final String TIMEOUT = "OPENALEX_TIMEOUT";
	static final String UNAVAILABLE = "OPENALEX_UNAVAILABLE";
	static final String RESPONSE_ERROR = "OPENALEX_RESPONSE_ERROR";

	private static final String SELECT_FIELDS = String.join(",",
			"id",
			"ids",
			"doi",
			"title",
			"display_name",
			"abstract_inverted_index",
			"publication_date",
			"publication_year",
			"type",
			"language",
			"primary_location",
			"best_oa_location",
			"open_access",
			"cited_by_count",
			"authorships",
			"relevance_score",
			"updated_date",
			"is_retracted");

	private static final Pattern OPENALEX_ID = Pattern.compile("(?i)^([A-Z]\\d+)$");
	private static final Pattern MODERN_ARXIV_ID = Pattern.compile(
			"(?i)^(\\d{2}(?:0[1-9]|1[0-2])\\.\\d{4,5})(?:v[1-9]\\d*)?$");
	private static final Pattern LEGACY_ARXIV_ID = Pattern.compile(
			"(?i)^([a-z][a-z0-9]*(?:[.-][a-z0-9]+)*/\\d{2}(?:0[1-9]|1[0-2])\\d{3})(?:v[1-9]\\d*)?$");
	private static final Map<DocumentType, String> OPENALEX_TYPES = Map.ofEntries(
			Map.entry(DocumentType.ARTICLE, "article"),
			Map.entry(DocumentType.PREPRINT, "preprint"),
			Map.entry(DocumentType.CONFERENCE_PAPER, "proceedings-article"),
			Map.entry(DocumentType.THESIS, "dissertation"),
			Map.entry(DocumentType.DISSERTATION, "dissertation"),
			Map.entry(DocumentType.BOOK, "book"),
			Map.entry(DocumentType.BOOK_CHAPTER, "book-chapter"),
			Map.entry(DocumentType.REPORT, "report"),
			Map.entry(DocumentType.DATASET, "dataset"),
			Map.entry(DocumentType.OTHER, "other"));

	private final RestClient restClient;
	private final OpenAlexProperties properties;
	private final Clock clock;

	OpenAlexResearchProvider(RestClient restClient, OpenAlexProperties properties, Clock clock) {
		this.restClient = Objects.requireNonNull(restClient, "restClient");
		this.properties = Objects.requireNonNull(properties, "properties");
		this.clock = Objects.requireNonNull(clock, "clock");
	}

	@Override
	public ProviderId id() {
		return ProviderId.OPENALEX;
	}

	@Override
	public ProviderSearchResult search(ProviderSearchQuery query) {
		Objects.requireNonNull(query, "query");
		Instant retrievedAt = clock.instant();

		try {
			RestClient.RequestHeadersSpec<?> request = restClient.get()
					.uri(uriBuilder -> {
						var builder = uriBuilder.pathSegment("works")
								.queryParam("search", query.query())
								.queryParam("cursor", query.cursor())
								.queryParam("corpus", "core")
								.queryParam("per_page", boundedPageSize(query.pageSize()))
								.queryParam("select", SELECT_FIELDS);
						List<String> filters = filters(query);
						if (!filters.isEmpty()) {
							builder.queryParam("filter", String.join(",", filters));
						}
						return builder.build();
					})
					.accept(MediaType.APPLICATION_JSON);

			if (properties.hasApiKey()) {
				request.header(HttpHeaders.AUTHORIZATION, "Bearer " + properties.apiKey());
			}

			OpenAlexResponse response = request.retrieve().body(OpenAlexResponse.class);
			return mapResponse(response, retrievedAt);
		}
		catch (RestClientResponseException exception) {
			throw translateStatus(exception);
		}
		catch (ResourceAccessException exception) {
			throw translateAccessFailure(exception);
		}
		catch (RestClientException exception) {
			throw providerException(
					RESPONSE_ERROR,
					"OpenAlex returned a response that could not be processed",
					false,
					null,
					exception);
		}
	}

	private ProviderSearchResult mapResponse(OpenAlexResponse response, Instant retrievedAt) {
		if (response == null || response.results() == null) {
			throw providerException(
					RESPONSE_ERROR,
					"OpenAlex returned an incomplete response",
					true,
					null,
					null);
		}

		List<ProviderPaperRecord> records = response.results().stream()
				.filter(Objects::nonNull)
				.filter(work -> !Boolean.TRUE.equals(work.retracted()))
				.map(this::mapWork)
				.flatMap(Optional::stream)
				.toList();
		long totalMatches = response.meta() == null || response.meta().count() == null
				? records.size()
				: Math.max(0, response.meta().count());
		String nextCursor = response.meta() == null ? null : blankToNull(response.meta().nextCursor());
		return new ProviderSearchResult(id(), records, totalMatches, nextCursor, retrievedAt);
	}

	private Optional<ProviderPaperRecord> mapWork(OpenAlexWork work) {
		String recordId = normalizeOpenAlexId(firstNonBlank(work.id(), idValue(work.ids(), "openalex")), 'W');
		String title = firstNonBlank(work.title(), work.displayName());
		if (recordId == null || !usableTitle(title)) {
			return Optional.empty();
		}

		LocalDate publicationDate = parseDate(work.publicationDate());
		Integer publicationYear = work.publicationYear();
		if (publicationYear == null && publicationDate != null) {
			publicationYear = publicationDate.getYear();
		}

		OpenAlexLocation primaryLocation = work.primaryLocation();
		OpenAlexLocation bestOpenAccessLocation = work.bestOpenAccessLocation();
		URI openAccessUrl = safeUri(work.openAccess() == null ? null : work.openAccess().url());
		URI landingPageUrl = firstUri(
				primaryLocation == null ? null : primaryLocation.landingPageUrl(),
				bestOpenAccessLocation == null ? null : bestOpenAccessLocation.landingPageUrl());
		if (landingPageUrl == null) {
			landingPageUrl = openAccessUrl;
		}
		URI pdfUrl = bestOpenAccessLocation != null && Boolean.TRUE.equals(bestOpenAccessLocation.openAccess())
				? safeUri(bestOpenAccessLocation.pdfUrl())
				: null;
		boolean reportedOpenAccess = work.openAccess() != null && Boolean.TRUE.equals(work.openAccess().openAccess());
		if (pdfUrl == null && reportedOpenAccess && looksLikePdf(openAccessUrl)) {
			pdfUrl = openAccessUrl;
		}

		String doi = normalizeDoi(firstNonBlank(work.doi(), idValue(work.ids(), "doi")));
		String arxivId = firstArxivId(
				idValue(work.ids(), "arxiv"),
				primaryLocation == null ? null : primaryLocation.landingPageUrl(),
				primaryLocation == null ? null : primaryLocation.pdfUrl(),
				bestOpenAccessLocation == null ? null : bestOpenAccessLocation.landingPageUrl(),
				bestOpenAccessLocation == null ? null : bestOpenAccessLocation.pdfUrl(),
				work.openAccess() == null ? null : work.openAccess().url());
		ProviderPaperRecord record = new ProviderPaperRecord(
				id(),
				recordId,
				doi,
				arxivId,
				title,
				reconstructAbstract(work.abstractInvertedIndex()),
				publicationDate,
				publicationYear,
				mapDocumentType(work.type()),
				normalizeLanguage(work.language()),
				venueName(primaryLocation, bestOpenAccessLocation),
				work.citedByCount() == null ? null : Math.max(0, work.citedByCount()),
				mapAuthors(work.authorships()),
				reportedOpenAccess,
				landingPageUrl,
				pdfUrl,
				work.relevanceScore(),
				parseInstant(work.updatedDate()),
				metadataFragment(work));
		return Optional.of(record);
	}

	private static List<String> filters(ProviderSearchQuery query) {
		List<String> filters = new ArrayList<>();
		if (query.yearFrom() != null) {
			filters.add("from_publication_date:" + query.yearFrom() + "-01-01");
		}
		if (query.yearTo() != null) {
			filters.add("to_publication_date:" + query.yearTo() + "-12-31");
		}

		String types = query.documentTypes().stream()
				.map(OPENALEX_TYPES::get)
				.filter(Objects::nonNull)
				.distinct()
				.sorted()
				.reduce((left, right) -> left + "|" + right)
				.orElse(null);
		if (types != null) {
			filters.add("type:" + types);
		}
		if (query.openAccessOnly()) {
			filters.add("open_access.is_oa:true");
		}
		if (query.minimumCitations() > 0) {
			filters.add("cited_by_count:>" + (query.minimumCitations() - 1));
		}

		String languages = query.languages().stream()
				.map(OpenAlexResearchProvider::normalizeLanguage)
				.filter(Objects::nonNull)
				.distinct()
				.sorted()
				.reduce((left, right) -> left + "|" + right)
				.orElse(null);
		if (languages != null) {
			filters.add("language:" + languages);
		}
		return filters;
	}

	private static int boundedPageSize(int requestedPageSize) {
		return Math.max(1, Math.min(100, requestedPageSize));
	}

	private static String reconstructAbstract(Map<String, List<Integer>> invertedIndex) {
		if (invertedIndex == null || invertedIndex.isEmpty()) {
			return null;
		}
		record PositionedWord(int position, String word) {
		}

		List<PositionedWord> words = new ArrayList<>();
		invertedIndex.forEach((word, positions) -> {
			if (word == null || word.isBlank() || positions == null) {
				return;
			}
			positions.stream()
					.filter(Objects::nonNull)
					.filter(position -> position >= 0)
					.forEach(position -> words.add(new PositionedWord(position, word)));
		});
		words.sort(Comparator.comparingInt(PositionedWord::position).thenComparing(PositionedWord::word));
		if (words.isEmpty()) {
			return null;
		}
		return words.stream().map(PositionedWord::word).reduce((left, right) -> left + " " + right).orElse(null);
	}

	private static List<ProviderAuthor> mapAuthors(List<OpenAlexAuthorship> authorships) {
		if (authorships == null || authorships.isEmpty()) {
			return List.of();
		}
		List<ProviderAuthor> authors = new ArrayList<>();
		for (int index = 0; index < authorships.size(); index++) {
			OpenAlexAuthorship authorship = authorships.get(index);
			if (authorship == null || authorship.author() == null) {
				continue;
			}
			OpenAlexAuthor author = authorship.author();
			String displayName = blankToNull(author.displayName());
			if (displayName == null) {
				continue;
			}
			authors.add(new ProviderAuthor(
					normalizeOpenAlexId(author.id(), 'A'),
					displayName,
					normalizeOrcid(author.orcid()),
					index + 1,
					Boolean.TRUE.equals(authorship.corresponding())));
		}
		return List.copyOf(authors);
	}

	private static DocumentType mapDocumentType(String type) {
		if (type == null) {
			return DocumentType.OTHER;
		}
		return switch (type.strip().toLowerCase(Locale.ROOT)) {
			case "article", "review", "editorial", "letter" -> DocumentType.ARTICLE;
			case "preprint" -> DocumentType.PREPRINT;
			case "proceedings-article", "conference-paper" -> DocumentType.CONFERENCE_PAPER;
			case "thesis" -> DocumentType.THESIS;
			case "dissertation" -> DocumentType.DISSERTATION;
			case "book" -> DocumentType.BOOK;
			case "book-chapter" -> DocumentType.BOOK_CHAPTER;
			case "report" -> DocumentType.REPORT;
			case "dataset" -> DocumentType.DATASET;
			default -> DocumentType.OTHER;
		};
	}

	private static String venueName(OpenAlexLocation primary, OpenAlexLocation bestOpenAccess) {
		String primaryName = primary == null || primary.source() == null ? null : primary.source().displayName();
		String openAccessName = bestOpenAccess == null || bestOpenAccess.source() == null
				? null
				: bestOpenAccess.source().displayName();
		return firstNonBlank(primaryName, openAccessName);
	}

	private static Map<String, Object> metadataFragment(OpenAlexWork work) {
		Map<String, Object> metadata = new LinkedHashMap<>();
		Map<String, String> externalIds = sanitizedIds(work.ids());
		if (!externalIds.isEmpty()) {
			metadata.put("externalIds", externalIds);
		}
		String providerType = blankToNull(work.type());
		if (providerType != null) {
			metadata.put("providerType", providerType);
		}
		if (work.openAccess() != null) {
			String openAccessStatus = blankToNull(work.openAccess().status());
			if (openAccessStatus != null) {
				metadata.put("openAccessStatus", openAccessStatus);
			}
		}
		return Map.copyOf(metadata);
	}

	private static Map<String, String> sanitizedIds(Map<String, Object> ids) {
		if (ids == null || ids.isEmpty()) {
			return Map.of();
		}
		Map<String, String> sanitized = new LinkedHashMap<>();
		ids.forEach((key, value) -> {
			String cleanKey = blankToNull(key);
			String cleanValue = scalarValue(value);
			if (cleanKey != null && cleanValue != null) {
				sanitized.put(cleanKey, cleanValue);
			}
		});
		return Map.copyOf(sanitized);
	}

	private ProviderException translateStatus(RestClientResponseException exception) {
		int status = exception.getStatusCode().value();
		Duration retryAfter = parseRetryAfter(exception.getResponseHeaders());
		if (status == 429) {
			return providerException(
					RATE_LIMITED,
					"OpenAlex rate limit reached",
					true,
					retryAfter,
					exception);
		}
		if (status >= 500) {
			return providerException(
					UPSTREAM_ERROR,
					"OpenAlex is temporarily unavailable",
					true,
					retryAfter,
					exception);
		}
		return providerException(
				REQUEST_REJECTED,
				"OpenAlex rejected the request",
				false,
				null,
				exception);
	}

	private ProviderException translateAccessFailure(ResourceAccessException exception) {
		boolean timeout = hasTimeoutCause(exception);
		return providerException(
				timeout ? TIMEOUT : UNAVAILABLE,
				timeout ? "OpenAlex request timed out" : "OpenAlex could not be reached",
				true,
				null,
				exception);
	}

	private ProviderException providerException(
			String errorCode,
			String message,
			boolean retryable,
			Duration retryAfter,
			Throwable cause) {
		return new ProviderException(id(), errorCode, message, retryable, retryAfter, cause);
	}

	private Duration parseRetryAfter(HttpHeaders headers) {
		if (headers == null) {
			return null;
		}
		String value = headers.getFirst(HttpHeaders.RETRY_AFTER);
		if (value == null || value.isBlank()) {
			return null;
		}
		try {
			return Duration.ofSeconds(Math.max(0, Long.parseLong(value.strip())));
		}
		catch (NumberFormatException ignored) {
			try {
				Instant retryAt = ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
				Duration duration = Duration.between(clock.instant(), retryAt);
				return duration.isNegative() ? Duration.ZERO : duration;
			}
			catch (DateTimeParseException invalidDate) {
				return null;
			}
		}
	}

	private static boolean hasTimeoutCause(Throwable throwable) {
		for (Throwable current = throwable; current != null; current = current.getCause()) {
			if (current instanceof SocketTimeoutException
					|| current instanceof HttpTimeoutException
					|| current instanceof InterruptedIOException
					|| Optional.ofNullable(current.getMessage())
							.map(message -> message.toLowerCase(Locale.ROOT).contains("timed out"))
							.orElse(false)) {
				return true;
			}
		}
		return false;
	}

	private static LocalDate parseDate(String value) {
		String clean = blankToNull(value);
		if (clean == null) {
			return null;
		}
		try {
			return LocalDate.parse(clean);
		}
		catch (DateTimeParseException ignored) {
			return null;
		}
	}

	private static Instant parseInstant(String value) {
		String clean = blankToNull(value);
		if (clean == null) {
			return null;
		}
		try {
			return Instant.parse(clean);
		}
		catch (DateTimeParseException ignored) {
			try {
				return LocalDate.parse(clean).atStartOfDay().toInstant(ZoneOffset.UTC);
			}
			catch (DateTimeParseException invalidDate) {
				return null;
			}
		}
	}

	private static URI firstUri(String... values) {
		for (String value : values) {
			URI uri = safeUri(value);
			if (uri != null) {
				return uri;
			}
		}
		return null;
	}

	private static URI safeUri(String value) {
		String clean = blankToNull(value);
		if (clean == null) {
			return null;
		}
		try {
			URI uri = URI.create(clean);
			String scheme = uri.getScheme();
			if (!uri.isAbsolute()
					|| scheme == null
					|| (!scheme.equalsIgnoreCase("http") && !scheme.equalsIgnoreCase("https"))
					|| uri.getHost() == null
					|| uri.getUserInfo() != null) {
				return null;
			}
			return uri;
		}
		catch (IllegalArgumentException ignored) {
			return null;
		}
	}

	private static boolean looksLikePdf(URI uri) {
		if (uri == null || uri.getPath() == null) {
			return false;
		}
		String path = uri.getPath().toLowerCase(Locale.ROOT);
		return path.endsWith(".pdf") || path.endsWith("/pdf");
	}

	private static String normalizeOpenAlexId(String value, char expectedPrefix) {
		String clean = blankToNull(value);
		if (clean == null) {
			return null;
		}
		if (clean.regionMatches(true, 0, "https://openalex.org/", 0, "https://openalex.org/".length())
				|| clean.regionMatches(true, 0, "http://openalex.org/", 0, "http://openalex.org/".length())) {
			int slash = clean.lastIndexOf('/');
			clean = slash >= 0 ? clean.substring(slash + 1) : clean;
		}
		Matcher matcher = OPENALEX_ID.matcher(clean);
		if (!matcher.matches()) {
			return null;
		}
		String id = matcher.group(1).toUpperCase(Locale.ROOT);
		return id.charAt(0) == Character.toUpperCase(expectedPrefix) ? id : null;
	}

	private static String normalizeDoi(String value) {
		String clean = blankToNull(value);
		if (clean == null) {
			return null;
		}
		clean = clean.replaceFirst("(?i)^https?://(?:dx\\.)?doi\\.org/", "")
				.replaceFirst("(?i)^doi:\\s*", "")
				.strip();
		return clean.isEmpty() ? null : clean.toLowerCase(Locale.ROOT);
	}

	private static String firstArxivId(String... values) {
		for (String value : values) {
			String arxivId = normalizeArxivId(value);
			if (arxivId != null) {
				return arxivId;
			}
		}
		return null;
	}

	private static String normalizeArxivId(String value) {
		String clean = blankToNull(value);
		if (clean == null) {
			return null;
		}

		String directCandidate = clean.replaceFirst("(?i)^arxiv:\\s*", "");
		String normalized = matchArxivId(directCandidate);
		if (normalized != null) {
			return normalized;
		}

		try {
			URI uri = URI.create(clean);
			String scheme = uri.getScheme();
			String host = uri.getHost();
			if (!uri.isAbsolute()
					|| scheme == null
					|| (!scheme.equalsIgnoreCase("http") && !scheme.equalsIgnoreCase("https"))
					|| host == null
					|| uri.getUserInfo() != null
					|| !(host.equalsIgnoreCase("arxiv.org")
							|| host.toLowerCase(Locale.ROOT).endsWith(".arxiv.org"))) {
				return null;
			}

			String rawPath = uri.getRawPath();
			if (rawPath == null) {
				return null;
			}
			String candidate;
			if (rawPath.startsWith("/abs/")) {
				candidate = rawPath.substring("/abs/".length());
			}
			else if (rawPath.startsWith("/pdf/")) {
				candidate = rawPath.substring("/pdf/".length()).replaceFirst("(?i)\\.pdf$", "");
			}
			else {
				return null;
			}
			return matchArxivId(candidate);
		}
		catch (IllegalArgumentException ignored) {
			return null;
		}
	}

	private static String matchArxivId(String candidate) {
		Matcher modern = MODERN_ARXIV_ID.matcher(candidate);
		if (modern.matches()) {
			return modern.group(1).toLowerCase(Locale.ROOT);
		}
		Matcher legacy = LEGACY_ARXIV_ID.matcher(candidate);
		return legacy.matches() ? legacy.group(1).toLowerCase(Locale.ROOT) : null;
	}

	private static String normalizeOrcid(String value) {
		String clean = blankToNull(value);
		if (clean == null) {
			return null;
		}
		clean = clean.replaceFirst("(?i)^https?://orcid\\.org/", "").strip();
		return clean.isEmpty() ? null : clean;
	}

	private static String normalizeLanguage(String value) {
		String clean = blankToNull(value);
		return clean == null ? null : clean.toLowerCase(Locale.ROOT);
	}

	private static String idValue(Map<String, Object> ids, String key) {
		return ids == null ? null : scalarValue(ids.get(key));
	}

	private static String scalarValue(Object value) {
		if (!(value instanceof CharSequence) && !(value instanceof Number)) {
			return null;
		}
		return blankToNull(value.toString());
	}

	private static boolean usableTitle(String value) {
		String clean = blankToNull(value);
		if (clean == null) {
			return false;
		}
		return switch (clean.toLowerCase(Locale.ROOT)) {
			case "untitled", "[untitled]", "no title", "[no title]", "unknown title" -> false;
			default -> true;
		};
	}

	private static String firstNonBlank(String... values) {
		for (String value : values) {
			String clean = blankToNull(value);
			if (clean != null) {
				return clean;
			}
		}
		return null;
	}

	private static String blankToNull(String value) {
		return value == null || value.isBlank() ? null : value.strip();
	}
}
