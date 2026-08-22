package com.openscholar.provider.doaj;

import java.io.InterruptedIOException;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpTimeoutException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.openscholar.common.ProviderResponseBodyLimit;
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
import org.springframework.web.util.UriComponentsBuilder;

final class DoajResearchProvider implements ResearchProvider {

	static final String RATE_LIMITED = "DOAJ_RATE_LIMITED";
	static final String UPSTREAM_ERROR = "DOAJ_UPSTREAM_ERROR";
	static final String REQUEST_REJECTED = "DOAJ_REQUEST_REJECTED";
	static final String TIMEOUT = "DOAJ_TIMEOUT";
	static final String UNAVAILABLE = "DOAJ_UNAVAILABLE";
	static final String RESPONSE_ERROR = "DOAJ_RESPONSE_ERROR";
	static final String RESPONSE_TOO_LARGE = "DOAJ_RESPONSE_TOO_LARGE";

	private static final int MAX_PAGE_SIZE = 100;
	private static final long MAX_PAGE_WINDOW = 1_000;
	private static final String CURSOR_PREFIX = "doajpage1:";
	private static final Pattern CURSOR = Pattern.compile("^doajpage1:([1-9]\\d*)$");
	private static final Pattern DOI = Pattern.compile("(?i)^10\\.[^/\\s]+/[^\\s?#]+$");
	private static final Pattern ISSN = Pattern.compile("(?i)^[0-9]{7}[0-9X]$");
	private static final Pattern ORCID = Pattern.compile(
			"(?i)^(?:https?://orcid\\.org/)?(\\d{4}-\\d{4}-\\d{4}-[\\dX]{4})$");
	private static final Set<String> SERIAL_IDENTIFIER_TYPES = Set.of("issn", "pissn", "eissn");
	private static final MediaType JSON = MediaType.APPLICATION_JSON;

	private final RestClient restClient;
	private final Clock clock;

	DoajResearchProvider(RestClient restClient, Clock clock) {
		this.restClient = Objects.requireNonNull(restClient, "restClient");
		this.clock = Objects.requireNonNull(clock, "clock");
	}

	@Override
	public ProviderId id() {
		return ProviderId.DOAJ;
	}

	@Override
	public ProviderSearchResult search(ProviderSearchQuery query) {
		Objects.requireNonNull(query, "query");
		Instant retrievedAt = clock.instant();
		if (mustSkip(query)) {
			return new ProviderSearchResult(id(), List.of(), 0, null, retrievedAt);
		}

		int pageSize = boundedPageSize(query.pageSize());
		int page = pageNumber(query.cursor(), pageSize);
		try {
			DoajResponse response = restClient.get()
					.uri(uriBuilder -> uriBuilder
							.pathSegment("search", "articles", doajQuery(query))
							.queryParam("page", page)
							.queryParam("pageSize", pageSize)
							.build())
					.accept(JSON)
					.retrieve()
					.body(DoajResponse.class);
			return mapResponse(response, page, pageSize, retrievedAt);
		}
		catch (RestClientResponseException exception) {
			if (ProviderResponseBodyLimit.wasExceeded(exception)) {
				throw responseTooLarge(exception);
			}
			throw translateStatus(exception);
		}
		catch (ResourceAccessException exception) {
			if (ProviderResponseBodyLimit.wasExceeded(exception)) {
				throw responseTooLarge(exception);
			}
			throw translateAccessFailure(exception);
		}
		catch (RestClientException exception) {
			if (ProviderResponseBodyLimit.wasExceeded(exception)) {
				throw responseTooLarge(exception);
			}
			if (hasTimeoutCause(exception)) {
				throw requestTimedOut(exception);
			}
			throw providerException(
					RESPONSE_ERROR,
					"DOAJ returned a response that could not be processed",
					false,
					null,
					exception);
		}
	}

	private static boolean mustSkip(ProviderSearchQuery query) {
		return query.minimumCitations() > 0
				|| !query.languages().isEmpty()
				|| (!query.documentTypes().isEmpty()
						&& !query.documentTypes().contains(DocumentType.ARTICLE));
	}

	private int pageNumber(String cursor, int pageSize) {
		if (cursor == null || cursor.isBlank() || cursor.equals("*")) {
			return 1;
		}
		Matcher matcher = CURSOR.matcher(cursor);
		if (!matcher.matches()) {
			throw invalidCursor();
		}
		try {
			long page = Long.parseLong(matcher.group(1));
			if ((page - 1) * pageSize >= MAX_PAGE_WINDOW || page > Integer.MAX_VALUE) {
				throw invalidCursor();
			}
			return (int) page;
		}
		catch (NumberFormatException exception) {
			throw invalidCursor();
		}
	}

	private ProviderException invalidCursor() {
		return providerException(
				REQUEST_REJECTED,
				"DOAJ page cursor is invalid",
				false,
				null,
				null);
	}

	private ProviderSearchResult mapResponse(
			DoajResponse response,
			int requestedPage,
			int pageSize,
			Instant retrievedAt) {
		if (response == null || response.results() == null) {
			throw providerException(
					RESPONSE_ERROR,
					"DOAJ returned an incomplete response",
					true,
					null,
					null);
		}
		List<ProviderPaperRecord> records = response.results().stream()
				.filter(Objects::nonNull)
				.map(this::mapArticle)
				.flatMap(Optional::stream)
				.toList();
		long totalMatches = response.total() == null
				? records.size()
				: Math.max(0, response.total());
		return new ProviderSearchResult(
				id(),
				records,
				totalMatches,
				nextCursor(requestedPage, pageSize, totalMatches),
				retrievedAt);
	}

	private static String nextCursor(int page, int pageSize, long totalMatches) {
		long consumed = (long) page * pageSize;
		return consumed >= MAX_PAGE_WINDOW || consumed >= totalMatches
				? null
				: CURSOR_PREFIX + (page + 1);
	}

	private Optional<ProviderPaperRecord> mapArticle(DoajArticle article) {
		DoajBibjson metadata = article.bibjson();
		String recordId = cleanText(article.id());
		String title = metadata == null ? null : cleanText(metadata.title());
		if (recordId == null || title == null) {
			return Optional.empty();
		}

		String doi = doi(metadata.identifier());
		DoajJournal journal = metadata.journal();
		URI sourceUrl = doajSource(recordId);
		URI pdfUrl = fullTextLink(metadata.link(), "pdf");
		URI landingPageUrl = doi == null
				? firstNonNull(fullTextLink(metadata.link(), "html"), firstFullTextLink(metadata.link()), sourceUrl)
				: encodedUri("https://doi.org/", doi);

		return Optional.of(new ProviderPaperRecord(
				id(),
				recordId,
				doi,
				null,
				title,
				cleanText(metadata.abstractText()),
				null,
				publicationYear(metadata.year()),
				DocumentType.ARTICLE,
				null,
				journal == null ? null : cleanText(journal.title()),
				null,
				mapAuthors(metadata.author()),
				true,
				landingPageUrl,
				pdfUrl,
				null,
				parseInstant(article.lastUpdated()),
				metadataFragment(article, metadata),
				List.of(),
				sourceUrl,
				journal == null ? null : cleanText(journal.publisher()),
				null,
				journal == null ? null : cleanText(journal.volume()),
				journal == null ? null : cleanText(journal.number()),
				pages(metadata, journal),
				null,
				null,
				List.of(),
				issn(metadata.identifier()),
				null));
	}

	private static String doajQuery(ProviderSearchQuery query) {
		StringBuilder value = new StringBuilder(quotedLiteral(query.query()));
		if (query.yearFrom() != null || query.yearTo() != null) {
			value.append(" AND bibjson.year:[")
					.append(query.yearFrom() == null ? 0 : query.yearFrom())
					.append(" TO ")
					.append(query.yearTo() == null ? 9999 : query.yearTo())
					.append(']');
		}
		return value.toString();
	}

	private static String quotedLiteral(String value) {
		String clean = cleanText(value);
		if (clean == null) {
			return "\\*";
		}
		StringBuilder escaped = new StringBuilder(clean.length() + 2).append('"');
		for (int index = 0; index < clean.length(); index++) {
			char character = clean.charAt(index);
			if (character == '\\' || character == '"' || character == '?' || character == '*'
					|| character == '~' || character == ':' || character == '/') {
				escaped.append('\\');
			}
			escaped.append(character);
		}
		return escaped.append('"').toString();
	}

	private static int boundedPageSize(int requestedPageSize) {
		return Math.max(1, Math.min(MAX_PAGE_SIZE, requestedPageSize));
	}

	private static String doi(List<DoajIdentifier> identifiers) {
		if (identifiers == null) {
			return null;
		}
		return identifiers.stream()
				.filter(Objects::nonNull)
				.filter(identifier -> "doi".equals(normalizedText(identifier.type())))
				.map(DoajIdentifier::id)
				.map(DoajResearchProvider::normalizeDoi)
				.filter(Objects::nonNull)
				.findFirst()
				.orElse(null);
	}

	private static List<String> issn(List<DoajIdentifier> identifiers) {
		if (identifiers == null) {
			return List.of();
		}
		return identifiers.stream()
				.filter(Objects::nonNull)
				.filter(identifier -> SERIAL_IDENTIFIER_TYPES.contains(normalizedText(identifier.type())))
				.map(DoajIdentifier::id)
				.map(DoajResearchProvider::normalizeIssn)
				.filter(Objects::nonNull)
				.distinct()
				.sorted()
				.toList();
	}

	private static List<ProviderAuthor> mapAuthors(List<DoajAuthor> authors) {
		if (authors == null || authors.isEmpty()) {
			return List.of();
		}
		List<ProviderAuthor> values = new ArrayList<>();
		for (int index = 0; index < authors.size(); index++) {
			DoajAuthor author = authors.get(index);
			String name = author == null ? null : cleanText(author.name());
			if (name != null) {
				values.add(new ProviderAuthor(null, name, normalizeOrcid(author.orcidId()), index + 1, false));
			}
		}
		return List.copyOf(values);
	}

	private static String pages(DoajBibjson metadata, DoajJournal journal) {
		String start = firstNonBlank(metadata.startPage(), journal == null ? null : journal.startPage());
		String end = firstNonBlank(metadata.endPage(), journal == null ? null : journal.endPage());
		if (start == null) {
			return end;
		}
		return end == null || start.equals(end) ? start : start + "-" + end;
	}

	private static Map<String, Object> metadataFragment(DoajArticle article, DoajBibjson metadata) {
		Map<String, Object> values = new LinkedHashMap<>();
		values.put("metadataLicense", "CC0-1.0");
		values.put("accessClaim", "DOAJ_INDEXED_OPEN_ACCESS");
		putClean(values, "createdDate", article.createdDate());
		putClean(values, "month", metadata.month());
		DoajJournal journal = metadata.journal();
		if (journal != null) {
			putClean(values, "journalCountry", journal.country());
			putList(values, "journalLanguages", journal.language());
		}
		putList(values, "keywords", metadata.keywords());
		List<String> subjects = metadata.subject() == null
				? List.of()
				: metadata.subject().stream()
						.filter(Objects::nonNull)
						.map(DoajSubject::term)
						.toList();
		putList(values, "subjects", subjects);
		return Map.copyOf(values);
	}

	private static void putClean(Map<String, Object> target, String key, String value) {
		String clean = boundedText(value, 256);
		if (clean != null) {
			target.put(key, clean);
		}
	}

	private static void putList(Map<String, Object> target, String key, List<String> values) {
		if (values == null) {
			return;
		}
		List<String> normalized = values.stream()
				.map(value -> boundedText(value, 128))
				.filter(Objects::nonNull)
				.distinct()
				.sorted()
				.limit(10)
				.toList();
		if (!normalized.isEmpty()) {
			target.put(key, normalized);
		}
	}

	private static URI fullTextLink(List<DoajLink> links, String contentType) {
		if (links == null) {
			return null;
		}
		return links.stream()
				.filter(Objects::nonNull)
				.filter(DoajResearchProvider::isFullText)
				.filter(link -> contentType.equals(normalizedText(link.contentType())))
				.map(DoajLink::url)
				.map(DoajResearchProvider::safeHttpUri)
				.filter(Objects::nonNull)
				.findFirst()
				.orElse(null);
	}

	private static URI firstFullTextLink(List<DoajLink> links) {
		if (links == null) {
			return null;
		}
		return links.stream()
				.filter(Objects::nonNull)
				.filter(DoajResearchProvider::isFullText)
				.map(DoajLink::url)
				.map(DoajResearchProvider::safeHttpUri)
				.filter(Objects::nonNull)
				.findFirst()
				.orElse(null);
	}

	private static boolean isFullText(DoajLink link) {
		return "fulltext".equals(normalizedText(link.type()));
	}

	private static URI safeHttpUri(String value) {
		String clean = cleanText(value);
		if (clean == null) {
			return null;
		}
		try {
			URI uri = new URI(clean);
			String scheme = uri.getScheme();
			if (!uri.isAbsolute() || uri.getHost() == null || uri.getUserInfo() != null || scheme == null
					|| (!scheme.equalsIgnoreCase("http") && !scheme.equalsIgnoreCase("https"))) {
				return null;
			}
			return uri;
		}
		catch (URISyntaxException ignored) {
			return null;
		}
	}

	private static URI encodedUri(String prefix, String value) {
		return UriComponentsBuilder.fromUriString(prefix)
				.path(value)
				.build()
				.encode()
				.toUri();
	}

	private static URI doajSource(String recordId) {
		return UriComponentsBuilder.fromUriString("https://doaj.org")
				.pathSegment("article", recordId)
				.build()
				.encode()
				.toUri();
	}

	private ProviderException responseTooLarge(RestClientException exception) {
		return providerException(
				RESPONSE_TOO_LARGE,
				"DOAJ response exceeded the configured byte limit",
				false,
				null,
				exception);
	}

	private ProviderException translateStatus(RestClientResponseException exception) {
		int status = exception.getStatusCode().value();
		Duration retryAfter = parseRetryAfter(exception.getResponseHeaders());
		if (status == 429) {
			return providerException(RATE_LIMITED, "DOAJ rate limit reached", true, retryAfter, exception);
		}
		if (status >= 500) {
			return providerException(UPSTREAM_ERROR, "DOAJ is temporarily unavailable", true, retryAfter, exception);
		}
		return providerException(REQUEST_REJECTED, "DOAJ rejected the request", false, null, exception);
	}

	private ProviderException translateAccessFailure(ResourceAccessException exception) {
		return hasTimeoutCause(exception)
				? requestTimedOut(exception)
				: providerException(UNAVAILABLE, "DOAJ could not be reached", true, null, exception);
	}

	private ProviderException requestTimedOut(RestClientException exception) {
		return providerException(TIMEOUT, "DOAJ request timed out", true, null, exception);
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
		if (DoajRequestDeadline.wasExceeded(throwable)) {
			return true;
		}
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

	private static Integer publicationYear(String value) {
		String clean = cleanText(value);
		if (clean == null || !clean.matches("\\d{4}")) {
			return null;
		}
		int year = Integer.parseInt(clean);
		return year >= 1000 && year <= 9999 ? year : null;
	}

	private static Instant parseInstant(String value) {
		String clean = cleanText(value);
		if (clean == null) {
			return null;
		}
		try {
			return Instant.parse(clean);
		}
		catch (DateTimeParseException ignored) {
			return null;
		}
	}

	private static String normalizeDoi(String value) {
		String clean = cleanText(value);
		if (clean == null) {
			return null;
		}
		clean = clean.replaceFirst("(?i)^https?://(?:dx\\.)?doi\\.org/", "")
				.replaceFirst("(?i)^doi:\\s*", "")
				.strip();
		return DOI.matcher(clean).matches() ? clean.toLowerCase(Locale.ROOT) : null;
	}

	private static String normalizeIssn(String value) {
		String clean = cleanText(value);
		if (clean == null) {
			return null;
		}
		String compact = clean.replace("-", "").replace(" ", "").toUpperCase(Locale.ROOT);
		return ISSN.matcher(compact).matches()
				? compact.substring(0, 4) + '-' + compact.substring(4)
				: null;
	}

	private static String normalizeOrcid(String value) {
		String clean = cleanText(value);
		if (clean == null) {
			return null;
		}
		Matcher matcher = ORCID.matcher(clean);
		return matcher.matches() ? matcher.group(1).toUpperCase(Locale.ROOT) : null;
	}

	private static String cleanText(String value) {
		if (value == null) {
			return null;
		}
		String clean = value.replaceAll("\\s+", " ").strip();
		return clean.isEmpty() ? null : clean;
	}

	private static String normalizedText(String value) {
		String clean = cleanText(value);
		return clean == null ? null : clean.toLowerCase(Locale.ROOT);
	}

	private static String boundedText(String value, int maximumCodePoints) {
		String clean = cleanText(value);
		if (clean == null || clean.codePointCount(0, clean.length()) <= maximumCodePoints) {
			return clean;
		}
		return clean.substring(0, clean.offsetByCodePoints(0, maximumCodePoints));
	}

	private static String firstNonBlank(String... values) {
		for (String value : values) {
			String clean = cleanText(value);
			if (clean != null) {
				return clean;
			}
		}
		return null;
	}

	@SafeVarargs
	private static <T> T firstNonNull(T... values) {
		for (T value : values) {
			if (value != null) {
				return value;
			}
		}
		return null;
	}
}
