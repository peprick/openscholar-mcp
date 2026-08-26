package com.openscholar.provider.europepmc;

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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import com.openscholar.common.ProviderResponseBodyLimit;
import com.openscholar.paper.DocumentType;
import com.openscholar.paper.PaperIdentifier;
import com.openscholar.paper.PaperIdentifierType;
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
import org.springframework.web.util.HtmlUtils;
import org.springframework.web.util.UriComponentsBuilder;

final class EuropePmcResearchProvider implements ResearchProvider {

	static final String RATE_LIMITED = "EUROPE_PMC_RATE_LIMITED";
	static final String UPSTREAM_ERROR = "EUROPE_PMC_UPSTREAM_ERROR";
	static final String REQUEST_REJECTED = "EUROPE_PMC_REQUEST_REJECTED";
	static final String TIMEOUT = "EUROPE_PMC_TIMEOUT";
	static final String UNAVAILABLE = "EUROPE_PMC_UNAVAILABLE";
	static final String RESPONSE_ERROR = "EUROPE_PMC_RESPONSE_ERROR";
	static final String RESPONSE_TOO_LARGE = "EUROPE_PMC_RESPONSE_TOO_LARGE";

	private static final int MAX_PAGE_SIZE = 50;
	private static final int MAX_CURSOR_LENGTH = 1024;
	private static final int MAX_ABSTRACT_CODE_POINTS = 100_000;
	private static final String MED_SOURCE = "MED";
	private static final Pattern CURSOR = Pattern.compile("^[A-Za-z0-9+/_=-]{1," + MAX_CURSOR_LENGTH + "}$");
	private static final Pattern PMID = Pattern.compile("^[1-9]\\d{0,11}$");
	private static final Pattern PMCID = Pattern.compile("(?i)^PMC[1-9]\\d{0,11}$");
	private static final Pattern DOI = Pattern.compile("(?i)^10\\.[^/\\s]+/[^\\s?#]+$");
	private static final Pattern ORCID = Pattern.compile(
			"(?i)^(?:https?://orcid\\.org/)?(\\d{4}-\\d{4}-\\d{4}-[\\dX]{4})$");
	private static final Pattern ISSN = Pattern.compile("(?i)^[0-9]{7}[0-9X]$");

	private final RestClient restClient;
	private final Clock clock;

	EuropePmcResearchProvider(RestClient restClient, Clock clock) {
		this.restClient = Objects.requireNonNull(restClient, "restClient");
		this.clock = Objects.requireNonNull(clock, "clock");
	}

	@Override
	public ProviderId id() {
		return ProviderId.EUROPE_PMC;
	}

	@Override
	public ProviderSearchResult search(ProviderSearchQuery query) {
		Objects.requireNonNull(query, "query");
		Instant retrievedAt = clock.instant();
		if (mustSkip(query)) {
			return new ProviderSearchResult(id(), List.of(), 0, null, retrievedAt);
		}

		int pageSize = boundedPageSize(query.pageSize());
		String cursor = validatedCursor(query.cursor());
		try {
			EuropePmcResponse response = restClient.get()
					.uri(uriBuilder -> uriBuilder
							.pathSegment("search")
							.queryParam("query", "{query}")
							.queryParam("format", "json")
							.queryParam("resultType", "core")
							.queryParam("synonym", false)
							.queryParam("cursorMark", "{cursorMark}")
							.queryParam("pageSize", pageSize)
							.build(Map.of("query", europePmcQuery(query), "cursorMark", cursor)))
					.accept(MediaType.APPLICATION_JSON)
					.retrieve()
					.body(EuropePmcResponse.class);
			return mapResponse(response, query, cursor, pageSize, retrievedAt);
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
					"Europe PMC returned a response that could not be processed",
					false,
					null,
					exception);
		}
	}

	private static boolean mustSkip(ProviderSearchQuery query) {
		return !query.languages().isEmpty()
				|| (!query.documentTypes().isEmpty()
						&& !query.documentTypes().contains(DocumentType.ARTICLE));
	}

	private ProviderSearchResult mapResponse(
			EuropePmcResponse response,
			ProviderSearchQuery query,
			String requestedCursor,
			int requestedPageSize,
			Instant retrievedAt) {
		if (response == null
				|| response.hitCount() == null
				|| response.hitCount() < 0
				|| response.resultList() == null
				|| response.resultList().result() == null
				|| response.resultList().result().size() > requestedPageSize) {
			throw responseError("Europe PMC returned an incomplete or inconsistent response", false);
		}

		List<ProviderPaperRecord> records = response.resultList().result().stream()
				.filter(Objects::nonNull)
				.map(work -> mapWork(work, query))
				.flatMap(Optional::stream)
				.toList();
		String nextCursor = validatedNextCursor(response.nextCursorMark(), requestedCursor);
		return new ProviderSearchResult(id(), records, response.hitCount(), nextCursor, retrievedAt);
	}

	private Optional<ProviderPaperRecord> mapWork(EuropePmcWork work, ProviderSearchQuery query) {
		String source = normalizedText(work.source());
		if (!MED_SOURCE.toLowerCase(Locale.ROOT).equals(source)
				|| !isJournalArticle(work.pubTypeList())
				|| !"y".equals(normalizedText(work.inPMC()))) {
			return Optional.empty();
		}

		String id = normalizePmid(work.id());
		String suppliedPmid = normalizePmid(work.pmid());
		if (id == null || (work.pmid() != null && (suppliedPmid == null || !id.equals(suppliedPmid)))) {
			return Optional.empty();
		}
		String title = cleanText(work.title());
		if (title == null) {
			return Optional.empty();
		}

		Integer publicationYear = publicationYear(
				work.pubYear(), work.journalInfo() == null ? null : work.journalInfo().yearOfPublication());
		LocalDate publicationDate = firstDate(
				work.firstPublicationDate(),
				work.journalInfo() == null ? null : work.journalInfo().printPublicationDate(),
				work.electronicPublicationDate());
		if (publicationDate != null && publicationYear != null
				&& publicationDate.getYear() != publicationYear) {
			publicationDate = null;
		}
		if (publicationYear == null && publicationDate != null) {
			publicationYear = publicationDate.getYear();
		}

		boolean reportedOpenAccess = "y".equals(normalizedText(work.isOpenAccess()));
		Integer citationCount = work.citedByCount() == null ? null : Math.max(0, work.citedByCount());
		if (!matchesFilters(query, publicationYear, reportedOpenAccess, citationCount)) {
			return Optional.empty();
		}

		String doi = normalizeDoi(work.doi());
		String pmcid = normalizePmcid(work.pmcid());
		if (pmcid == null) {
			return Optional.empty();
		}
		URI sourceUrl = sourceUrl(id);
		EuropePmcJournalInfo journalInfo = work.journalInfo();
		EuropePmcJournal journal = journalInfo == null ? null : journalInfo.journal();

		return Optional.of(new ProviderPaperRecord(
				id(),
				MED_SOURCE + ":" + id,
				doi,
				null,
				title,
				plainTextAbstract(work.abstractText()),
				publicationDate,
				publicationYear,
				DocumentType.ARTICLE,
				normalizedText(work.language()),
				journal == null ? null : cleanText(journal.title()),
				citationCount,
				mapAuthors(work.authorList()),
				reportedOpenAccess,
				sourceUrl,
				null,
				null,
				parseDateInstant(work.dateOfRevision()),
				metadataFragment(work),
				identifiers(doi, id, pmcid),
				sourceUrl,
				null,
				null,
				journalInfo == null ? null : cleanText(journalInfo.volume()),
				journalInfo == null ? null : cleanText(journalInfo.issue()),
				cleanText(work.pageInfo()),
				null,
				null,
				List.of(),
				issn(journal),
				null));
	}

	private static boolean matchesFilters(
			ProviderSearchQuery query,
			Integer publicationYear,
			boolean reportedOpenAccess,
			Integer citationCount) {
		if (query.yearFrom() != null
				&& (publicationYear == null || publicationYear < query.yearFrom())) {
			return false;
		}
		if (query.yearTo() != null
				&& (publicationYear == null || publicationYear > query.yearTo())) {
			return false;
		}
		if (query.openAccessOnly() && !reportedOpenAccess) {
			return false;
		}
		return query.minimumCitations() == 0
				|| (citationCount != null && citationCount >= query.minimumCitations());
	}

	private static String europePmcQuery(ProviderSearchQuery query) {
		StringBuilder value = new StringBuilder("(")
				.append(literalTerms(query.query()))
				.append(") AND SRC:MED AND PUB_TYPE:\"Journal Article\" AND IN_PMC:Y");
		if (query.yearFrom() != null || query.yearTo() != null) {
			value.append(" AND PUB_YEAR:[")
					.append(query.yearFrom() == null ? "*" : query.yearFrom())
					.append(" TO ")
					.append(query.yearTo() == null ? "*" : query.yearTo())
					.append(']');
		}
		if (query.openAccessOnly()) {
			value.append(" AND OPEN_ACCESS:Y");
		}
		if (query.minimumCitations() > 0) {
			value.append(" AND CITED:[").append(query.minimumCitations()).append(" TO *]");
		}
		return value.toString();
	}

	private static String literalTerms(String value) {
		String clean = cleanText(value);
		if (clean == null) {
			return "\"\"";
		}
		return java.util.Arrays.stream(clean.split("\\s+"))
				.map(EuropePmcResearchProvider::quotedLiteral)
				.collect(Collectors.joining(" "));
	}

	private static String quotedLiteral(String value) {
		return '"' + value.replace("\\", "\\\\").replace("\"", "\\\"") + '"';
	}

	private static int boundedPageSize(int requestedPageSize) {
		return Math.max(1, Math.min(MAX_PAGE_SIZE, requestedPageSize));
	}

	private String validatedCursor(String value) {
		if (value == null || value.isBlank() || value.equals("*")) {
			return "*";
		}
		if (value.length() > MAX_CURSOR_LENGTH || !CURSOR.matcher(value).matches()) {
			throw providerException(
					REQUEST_REJECTED,
					"Europe PMC page cursor is invalid",
					false,
					null,
					null);
		}
		return value;
	}

	private String validatedNextCursor(String value, String requestedCursor) {
		String clean = cleanText(value);
		if (clean == null) {
			return null;
		}
		if (clean.equals(requestedCursor)) {
			return null;
		}
		if (clean.length() > MAX_CURSOR_LENGTH
				|| !CURSOR.matcher(clean).matches()) {
			throw responseError("Europe PMC returned an invalid page cursor", false);
		}
		return clean;
	}

	private static boolean isJournalArticle(EuropePmcPubTypeList types) {
		if (types == null || types.pubType() == null) {
			return false;
		}
		return types.pubType().stream()
				.map(EuropePmcResearchProvider::normalizedText)
				.filter(Objects::nonNull)
				.anyMatch(type -> type.equals("journal article") || type.equals("research-article"));
	}

	private static List<ProviderAuthor> mapAuthors(EuropePmcAuthorList authorList) {
		if (authorList == null || authorList.author() == null) {
			return List.of();
		}
		List<ProviderAuthor> authors = new ArrayList<>();
		for (EuropePmcAuthor author : authorList.author()) {
			String name = author == null ? null : cleanText(author.fullName());
			if (name == null) {
				continue;
			}
			String orcid = author.authorId() == null
					|| !"orcid".equals(normalizedText(author.authorId().type()))
					? null
					: normalizeOrcid(author.authorId().value());
			authors.add(new ProviderAuthor(null, name, orcid, authors.size() + 1, false));
		}
		return List.copyOf(authors);
	}

	private static List<PaperIdentifier> identifiers(String doi, String pmid, String pmcid) {
		List<PaperIdentifier> identifiers = new ArrayList<>();
		addIdentifier(identifiers, PaperIdentifierType.DOI, doi);
		addIdentifier(identifiers, PaperIdentifierType.PMID, pmid);
		addIdentifier(identifiers, PaperIdentifierType.PMCID, pmcid);
		return List.copyOf(identifiers);
	}

	private static void addIdentifier(
			List<PaperIdentifier> identifiers, PaperIdentifierType type, String value) {
		if (value != null) {
			identifiers.add(new PaperIdentifier(type, "", value));
		}
	}

	private static List<String> issn(EuropePmcJournal journal) {
		if (journal == null) {
			return List.of();
		}
		return java.util.stream.Stream.of(journal.issn(), journal.essn())
				.map(EuropePmcResearchProvider::normalizeIssn)
				.filter(Objects::nonNull)
				.distinct()
				.sorted()
				.toList();
	}

	private static Map<String, Object> metadataFragment(EuropePmcWork work) {
		Map<String, Object> values = new LinkedHashMap<>();
		values.put("metadataScope", "PUBLICATION_METADATA_ONLY");
		values.put("source", MED_SOURCE);
		putClean(values, "publicationStatus", work.publicationStatus());
		putClean(values, "publicationModel", work.pubModel());
		putClean(values, "reportedLicense", work.license());
		putClean(values, "firstPublicationDate", work.firstPublicationDate());
		putClean(values, "electronicPublicationDate", work.electronicPublicationDate());
		List<String> publicationTypes = work.pubTypeList() == null
				? List.of()
				: boundedValues(work.pubTypeList().pubType(), 10, 128);
		if (!publicationTypes.isEmpty()) {
			values.put("publicationTypes", publicationTypes);
		}
		return Map.copyOf(values);
	}

	private static void putClean(Map<String, Object> target, String key, String value) {
		String clean = boundedText(value, 256);
		if (clean != null) {
			target.put(key, clean);
		}
	}

	private static List<String> boundedValues(List<String> values, int maximumValues, int maximumCodePoints) {
		if (values == null) {
			return List.of();
		}
		return values.stream()
				.map(value -> boundedText(value, maximumCodePoints))
				.filter(Objects::nonNull)
				.distinct()
				.limit(maximumValues)
				.toList();
	}

	private static String plainTextAbstract(String value) {
		if (value == null) {
			return null;
		}
		StringBuilder plain = new StringBuilder(Math.min(value.length(), MAX_ABSTRACT_CODE_POINTS));
		boolean insideTag = false;
		for (int index = 0; index < value.length(); index++) {
			char character = value.charAt(index);
			if (character == '<') {
				insideTag = true;
				plain.append(' ');
			}
			else if (character == '>' && insideTag) {
				insideTag = false;
			}
			else if (!insideTag) {
				plain.append(character);
			}
		}
		return boundedText(HtmlUtils.htmlUnescape(plain.toString()), MAX_ABSTRACT_CODE_POINTS);
	}

	private static URI sourceUrl(String pmid) {
		return UriComponentsBuilder.fromUriString("https://europepmc.org")
				.pathSegment("article", MED_SOURCE, pmid)
				.build()
				.encode()
				.toUri();
	}

	private ProviderException responseTooLarge(RestClientException exception) {
		return providerException(
				RESPONSE_TOO_LARGE,
				"Europe PMC response exceeded the configured byte limit",
				false,
				null,
				exception);
	}

	private ProviderException responseError(String message, boolean retryable) {
		return providerException(RESPONSE_ERROR, message, retryable, null, null);
	}

	private ProviderException translateStatus(RestClientResponseException exception) {
		int status = exception.getStatusCode().value();
		Duration retryAfter = parseRetryAfter(exception.getResponseHeaders());
		if (status == 429) {
			return providerException(
					RATE_LIMITED, "Europe PMC rate limit reached", true, retryAfter, exception);
		}
		if (status >= 500) {
			return providerException(
					UPSTREAM_ERROR, "Europe PMC is temporarily unavailable", true, retryAfter, exception);
		}
		return providerException(
				REQUEST_REJECTED, "Europe PMC rejected the request", false, null, exception);
	}

	private ProviderException translateAccessFailure(ResourceAccessException exception) {
		return hasTimeoutCause(exception)
				? requestTimedOut(exception)
				: providerException(UNAVAILABLE, "Europe PMC could not be reached", true, null, exception);
	}

	private ProviderException requestTimedOut(RestClientException exception) {
		return providerException(TIMEOUT, "Europe PMC request timed out", true, null, exception);
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
		if (EuropePmcRequestDeadline.wasExceeded(throwable)) {
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

	private static String normalizePmid(String value) {
		String clean = cleanText(value);
		return clean != null && PMID.matcher(clean).matches() ? clean : null;
	}

	private static String normalizePmcid(String value) {
		String clean = cleanText(value);
		if (clean == null) {
			return null;
		}
		Matcher matcher = PMCID.matcher(clean);
		return matcher.matches() ? clean.toUpperCase(Locale.ROOT) : null;
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

	private static String normalizeOrcid(String value) {
		String clean = cleanText(value);
		if (clean == null) {
			return null;
		}
		Matcher matcher = ORCID.matcher(clean);
		return matcher.matches() ? matcher.group(1).toUpperCase(Locale.ROOT) : null;
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

	private static Integer publicationYear(String value, Integer fallback) {
		String clean = cleanText(value);
		if (clean != null && clean.matches("\\d{4}")) {
			int year = Integer.parseInt(clean);
			if (year >= 1000 && year <= 9999) {
				return year;
			}
		}
		return fallback != null && fallback >= 1000 && fallback <= 9999 ? fallback : null;
	}

	private static LocalDate firstDate(String... values) {
		for (String value : values) {
			LocalDate parsed = parseDate(value);
			if (parsed != null) {
				return parsed;
			}
		}
		return null;
	}

	private static LocalDate parseDate(String value) {
		String clean = cleanText(value);
		if (clean == null) {
			return null;
		}
		try {
			LocalDate date = LocalDate.parse(clean);
			return date.getYear() >= 1000 && date.getYear() <= 9999 ? date : null;
		}
		catch (DateTimeParseException ignored) {
			return null;
		}
	}

	private static Instant parseDateInstant(String value) {
		LocalDate date = parseDate(value);
		return date == null ? null : date.atStartOfDay(ZoneOffset.UTC).toInstant();
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
}
