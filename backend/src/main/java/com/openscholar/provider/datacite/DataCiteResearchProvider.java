package com.openscholar.provider.datacite;

import java.io.InterruptedIOException;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.http.HttpTimeoutException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
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

final class DataCiteResearchProvider implements ResearchProvider {

	static final String RATE_LIMITED = "DATACITE_RATE_LIMITED";
	static final String UPSTREAM_ERROR = "DATACITE_UPSTREAM_ERROR";
	static final String REQUEST_REJECTED = "DATACITE_REQUEST_REJECTED";
	static final String TIMEOUT = "DATACITE_TIMEOUT";
	static final String UNAVAILABLE = "DATACITE_UNAVAILABLE";
	static final String RESPONSE_ERROR = "DATACITE_RESPONSE_ERROR";
	static final String RESPONSE_TOO_LARGE = "DATACITE_RESPONSE_TOO_LARGE";

	private static final int MAX_PAGE_SIZE = 50;
	private static final long MAX_PAGE_WINDOW = 10_000;
	private static final String CURSOR_PREFIX = "dcpage1:";
	private static final Pattern CURSOR = Pattern.compile("^dcpage1:([1-9]\\d*)$");
	private static final Pattern ORCID = Pattern.compile(
			"(?i)^(?:https?://orcid\\.org/)?(\\d{4}-\\d{4}-\\d{4}-[\\dX]{4})$");
	private static final Set<DocumentType> SUPPORTED_TYPES = Set.of(
			DocumentType.THESIS, DocumentType.DISSERTATION);
	private static final Set<String> UNKNOWN_VALUE_CODES = Set.of(
			":unac", ":unal", ":unap", ":unas", ":unav", ":unkn", ":none", ":null", ":tba", ":etal");
	private static final MediaType JSON_API = MediaType.parseMediaType("application/vnd.api+json");
	private static final String SELECT_FIELDS = String.join(",",
			"doi",
			"titles",
			"creators",
			"publisher",
			"publicationYear",
			"contributors",
			"dates",
			"language",
			"types",
			"relatedIdentifiers",
			"relatedItems",
			"rightsList",
			"descriptions",
			"updated",
			"citationCount",
			"schemaVersion",
			"version",
			"clientId");

	private final RestClient restClient;
	private final DataCiteProperties properties;
	private final Clock clock;

	DataCiteResearchProvider(RestClient restClient, DataCiteProperties properties, Clock clock) {
		this.restClient = Objects.requireNonNull(restClient, "restClient");
		this.properties = Objects.requireNonNull(properties, "properties");
		this.clock = Objects.requireNonNull(clock, "clock");
	}

	@Override
	public ProviderId id() {
		return ProviderId.DATACITE;
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
			RestClient.RequestHeadersSpec<?> request = restClient.get()
					.uri(uriBuilder -> {
						var builder = uriBuilder.pathSegment("dois")
								.queryParam("query", dataCiteQuery(query))
								.queryParam("sort", "relevance")
								.queryParam("page[number]", page)
								.queryParam("page[size]", pageSize)
								.queryParam("disable-facets", true)
								.queryParam("fields[dois]", SELECT_FIELDS);
						if (query.minimumCitations() > 0) {
							builder.queryParam("has-citations", query.minimumCitations());
						}
						return builder.build();
					})
					.accept(JSON_API);

			DataCiteResponse response = request.retrieve().body(DataCiteResponse.class);
			return mapResponse(response, query, page, pageSize, retrievedAt);
		}
		catch (RestClientResponseException exception) {
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
					"DataCite returned a response that could not be processed",
					false,
					null,
					exception);
		}
	}

	private static boolean mustSkip(ProviderSearchQuery query) {
		return query.openAccessOnly()
				|| (!query.documentTypes().isEmpty()
						&& query.documentTypes().stream().noneMatch(SUPPORTED_TYPES::contains));
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
				"DataCite page cursor is invalid",
				false,
				null,
				null);
	}

	private ProviderSearchResult mapResponse(
			DataCiteResponse response,
			ProviderSearchQuery query,
			int requestedPage,
			int pageSize,
			Instant retrievedAt) {
		if (response == null || response.data() == null) {
			throw providerException(
					RESPONSE_ERROR,
					"DataCite returned an incomplete response",
					true,
					null,
					null);
		}

		List<ProviderPaperRecord> records = response.data().stream()
				.filter(Objects::nonNull)
				.map(this::mapResource)
				.flatMap(Optional::stream)
				.filter(record -> requestedType(query, record.documentType()))
				.toList();
		long totalMatches = response.meta() == null || response.meta().total() == null
				? records.size()
				: Math.max(0, response.meta().total());
		String nextCursor = nextCursor(response.meta(), requestedPage, pageSize, totalMatches);
		return new ProviderSearchResult(id(), records, totalMatches, nextCursor, retrievedAt);
	}

	private static boolean requestedType(ProviderSearchQuery query, DocumentType type) {
		return query.documentTypes().isEmpty() || query.documentTypes().contains(type);
	}

	private static String nextCursor(DataCiteMeta meta, int page, int pageSize, long totalMatches) {
		long consumed = (long) page * pageSize;
		if (consumed >= MAX_PAGE_WINDOW || consumed >= totalMatches) {
			return null;
		}
		if (meta != null && meta.totalPages() != null && page >= Math.max(0, meta.totalPages())) {
			return null;
		}
		return CURSOR_PREFIX + (page + 1);
	}

	private Optional<ProviderPaperRecord> mapResource(DataCiteResource resource) {
		DataCiteAttributes attributes = resource.attributes();
		if (attributes == null) {
			return Optional.empty();
		}
		String doi = normalizeDoi(firstNonBlank(attributes.doi(), resource.id()));
		String title = primaryTitle(attributes.titles());
		DocumentType documentType = documentType(attributes.types());
		if (doi == null || !usableTitle(title) || documentType == null) {
			return Optional.empty();
		}

		LocalDate publicationDate = issuedDate(attributes.dates());
		Integer publicationYear = attributes.publicationYear();
		if (publicationYear == null && publicationDate != null) {
			publicationYear = publicationDate.getYear();
		}
		DataCiteRelatedItem publishedIn = publishedIn(attributes.relatedItems());
		RelatedMetadata related = relatedMetadata(attributes.relatedIdentifiers(), publishedIn);

		ProviderPaperRecord record = new ProviderPaperRecord(
				id(),
				doi,
				doi,
				null,
				title,
				abstractText(attributes.descriptions()),
				publicationDate,
				publicationYear,
				documentType,
				normalizeLanguage(attributes.language()),
				related.venueName(),
				attributes.citationCount() == null ? null : Math.max(0, attributes.citationCount()),
				mapAuthors(attributes.creators()),
				false,
				doiLandingPage(doi),
				null,
				null,
				parseInstant(attributes.updated()),
				metadataFragment(attributes),
				List.of(),
				dataCiteSource(doi),
				publisherName(attributes.publisher()),
				hostingInstitution(attributes.contributors()),
				related.volume(),
				related.issue(),
				related.pages(),
				related.articleNumber(),
				related.edition(),
				related.isbn(),
				related.issn(),
				degree(attributes.types()));
		return Optional.of(record);
	}

	private static String dataCiteQuery(ProviderSearchQuery query) {
		StringBuilder value = new StringBuilder()
				.append('(')
				.append(literalTerms(query.query()))
				.append(") AND (types.resourceTypeGeneral:Dissertation")
				.append(" OR types.resourceType:*thesis*")
				.append(" OR types.resourceType:*dissertation*)");
		if (query.yearFrom() != null || query.yearTo() != null) {
			value.append(" AND publicationYear:[")
					.append(query.yearFrom() == null ? "*" : query.yearFrom())
					.append(" TO ")
					.append(query.yearTo() == null ? "*" : query.yearTo())
					.append(']');
		}
		List<String> languages = query.languages().stream()
				.map(DataCiteResearchProvider::cleanText)
				.filter(Objects::nonNull)
				.distinct()
				.sorted()
				.map(DataCiteResearchProvider::quotedLiteral)
				.toList();
		if (!languages.isEmpty()) {
			value.append(" AND language:(").append(String.join(" OR ", languages)).append(')');
		}
		return value.toString();
	}

	private static String literalTerms(String value) {
		String clean = value == null ? null : value.strip();
		if (clean == null || clean.isEmpty()) {
			return "*";
		}
		return java.util.Arrays.stream(clean.split("\\s+"))
				.map(DataCiteResearchProvider::quotedLiteral)
				.reduce((left, right) -> left + " " + right)
				.orElse("*");
	}

	private static String quotedLiteral(String value) {
		return '"' + value.replace("\\", "\\\\").replace("\"", "\\\"") + '"';
	}

	private static int boundedPageSize(int requestedPageSize) {
		return Math.max(1, Math.min(MAX_PAGE_SIZE, requestedPageSize));
	}

	private static DocumentType documentType(DataCiteTypes types) {
		if (types == null) {
			return null;
		}
		String detail = normalizedText(types.resourceType());
		if (detail != null) {
			if (containsAny(detail, "master", "bachelor", "undergraduate")) {
				return DocumentType.THESIS;
			}
			if (containsAny(detail, "phd", "ph.d", "doctor", "dissertation")) {
				return DocumentType.DISSERTATION;
			}
			if (detail.contains("thesis")) {
				return DocumentType.THESIS;
			}
		}
		String general = normalizedText(types.resourceTypeGeneral());
		return "dissertation".equals(general) ? DocumentType.DISSERTATION : null;
	}

	private static String degree(DataCiteTypes types) {
		String detail = types == null ? null : normalizedText(types.resourceType());
		if (detail == null) {
			return null;
		}
		if (containsAny(detail, "phd", "ph.d", "doctor of philosophy")) {
			return "PhD";
		}
		if (containsAny(detail, "doctorate", "doctoral")) {
			return "Doctoral";
		}
		if (detail.contains("master")) {
			return "Master's";
		}
		if (containsAny(detail, "bachelor", "undergraduate")) {
			return "Bachelor's";
		}
		return null;
	}

	private static boolean containsAny(String value, String... candidates) {
		for (String candidate : candidates) {
			if (value.contains(candidate)) {
				return true;
			}
		}
		return false;
	}

	private static String primaryTitle(List<DataCiteTitle> titles) {
		if (titles == null || titles.isEmpty()) {
			return null;
		}
		for (DataCiteTitle title : titles) {
			if (title != null && cleanText(title.titleType()) == null && usableTitle(title.title())) {
				return cleanText(title.title());
			}
		}
		return titles.stream()
				.filter(Objects::nonNull)
				.map(DataCiteTitle::title)
				.filter(DataCiteResearchProvider::usableTitle)
				.map(DataCiteResearchProvider::cleanText)
				.findFirst()
				.orElse(null);
	}

	private static String abstractText(List<DataCiteDescription> descriptions) {
		if (descriptions == null) {
			return null;
		}
		return descriptions.stream()
				.filter(Objects::nonNull)
				.filter(description -> "abstract".equals(normalizedText(description.descriptionType())))
				.map(DataCiteDescription::description)
				.map(DataCiteResearchProvider::cleanText)
				.filter(Objects::nonNull)
				.findFirst()
				.orElse(null);
	}

	private static LocalDate issuedDate(List<DataCiteDate> dates) {
		if (dates == null) {
			return null;
		}
		return dates.stream()
				.filter(Objects::nonNull)
				.filter(date -> "issued".equals(normalizedText(date.dateType())))
				.map(DataCiteDate::date)
				.map(DataCiteResearchProvider::parseDate)
				.filter(Objects::nonNull)
				.findFirst()
				.orElse(null);
	}

	private static List<ProviderAuthor> mapAuthors(List<DataCiteCreator> creators) {
		if (creators == null || creators.isEmpty()) {
			return List.of();
		}
		List<ProviderAuthor> authors = new ArrayList<>();
		for (int index = 0; index < creators.size(); index++) {
			DataCiteCreator creator = creators.get(index);
			String displayName = creator == null ? null : cleanText(creator.name());
			if (displayName == null) {
				continue;
			}
			authors.add(new ProviderAuthor(
					null,
					displayName,
					orcid(creator.nameIdentifiers()),
					index + 1,
					false));
		}
		return List.copyOf(authors);
	}

	private static String orcid(List<DataCiteNameIdentifier> identifiers) {
		if (identifiers == null) {
			return null;
		}
		for (DataCiteNameIdentifier identifier : identifiers) {
			if (identifier == null) {
				continue;
			}
			String scheme = normalizedText(identifier.nameIdentifierScheme());
			String schemeUri = normalizedText(identifier.schemeUri());
			if (!"orcid".equals(scheme) && (schemeUri == null || !schemeUri.contains("orcid.org"))) {
				continue;
			}
			String value = cleanText(identifier.nameIdentifier());
			if (value == null) {
				continue;
			}
			Matcher matcher = ORCID.matcher(value);
			if (matcher.matches()) {
				return matcher.group(1).toUpperCase(Locale.ROOT);
			}
		}
		return null;
	}

	private static String hostingInstitution(List<DataCiteContributor> contributors) {
		if (contributors == null) {
			return null;
		}
		return contributors.stream()
				.filter(Objects::nonNull)
				.filter(contributor -> "hostinginstitution".equals(normalizedText(contributor.contributorType())))
				.map(DataCiteContributor::name)
				.map(DataCiteResearchProvider::cleanText)
				.filter(Objects::nonNull)
				.findFirst()
				.orElse(null);
	}

	private static DataCiteRelatedItem publishedIn(List<DataCiteRelatedItem> relatedItems) {
		if (relatedItems == null) {
			return null;
		}
		return relatedItems.stream()
				.filter(Objects::nonNull)
				.filter(item -> "ispublishedin".equals(normalizedText(item.relationType())))
				.findFirst()
				.orElse(null);
	}

	private static RelatedMetadata relatedMetadata(
			List<DataCiteRelatedIdentifier> relatedIdentifiers,
			DataCiteRelatedItem publishedIn) {
		List<String> isbn = new ArrayList<>();
		List<String> issn = new ArrayList<>();
		if (relatedIdentifiers != null) {
			for (DataCiteRelatedIdentifier identifier : relatedIdentifiers) {
				if (identifier != null && "ispublishedin".equals(normalizedText(identifier.relationType()))) {
					addSerialIdentifier(
							isbn, issn, identifier.relatedIdentifierType(), identifier.relatedIdentifier());
				}
			}
		}
		if (publishedIn == null) {
			return new RelatedMetadata(null, null, null, null, null, null, isbn, issn);
		}
		DataCiteRelatedItemIdentifier identifier = publishedIn.relatedItemIdentifier();
		if (identifier != null) {
			addSerialIdentifier(
					isbn, issn, identifier.relatedItemIdentifierType(), identifier.relatedItemIdentifier());
		}
		String firstPage = cleanText(publishedIn.firstPage());
		String lastPage = cleanText(publishedIn.lastPage());
		String articleNumber = "article".equals(normalizedText(publishedIn.numberType()))
				? cleanText(publishedIn.number())
				: null;
		return new RelatedMetadata(
				primaryTitle(publishedIn.titles()),
				cleanText(publishedIn.volume()),
				cleanText(publishedIn.issue()),
				pageRange(firstPage, lastPage),
				articleNumber,
				cleanText(publishedIn.edition()),
				isbn,
				issn);
	}

	private static void addSerialIdentifier(
			List<String> isbn, List<String> issn, String identifierType, String identifierValue) {
		String type = normalizedText(identifierType);
		String value = cleanText(identifierValue);
		if (value == null || type == null) {
			return;
		}
		if ("isbn".equals(type)) {
			isbn.add(value);
		}
		else if (Set.of("issn", "eissn", "lissn").contains(type)) {
			issn.add(value);
		}
	}

	private static String pageRange(String firstPage, String lastPage) {
		if (firstPage == null) {
			return lastPage;
		}
		if (lastPage == null || firstPage.equals(lastPage)) {
			return firstPage;
		}
		return firstPage + "-" + lastPage;
	}

	private static String publisherName(Object publisher) {
		if (publisher instanceof String string) {
			return cleanText(string);
		}
		if (publisher instanceof Map<?, ?> values) {
			Object name = values.get("name");
			return name instanceof String string ? cleanText(string) : null;
		}
		return null;
	}

	private static Map<String, Object> metadataFragment(DataCiteAttributes attributes) {
		Map<String, Object> metadata = new LinkedHashMap<>();
		if (attributes.types() != null) {
			putClean(metadata, "providerType", attributes.types().resourceType());
			putClean(metadata, "resourceTypeGeneral", attributes.types().resourceTypeGeneral());
		}
		putClean(metadata, "schemaVersion", attributes.schemaVersion());
		putClean(metadata, "version", attributes.version());
		putClean(metadata, "clientId", attributes.clientId());
		List<String> rightsIdentifiers = attributes.rightsList() == null
				? List.of()
				: attributes.rightsList().stream()
						.filter(Objects::nonNull)
						.map(DataCiteRight::rightsIdentifier)
						.map(DataCiteResearchProvider::cleanText)
						.filter(Objects::nonNull)
						.distinct()
						.sorted()
						.toList();
		if (!rightsIdentifiers.isEmpty()) {
			metadata.put("rightsIdentifiers", rightsIdentifiers);
		}
		return Map.copyOf(metadata);
	}

	private static void putClean(Map<String, Object> target, String key, String value) {
		String clean = cleanText(value);
		if (clean != null) {
			target.put(key, clean);
		}
	}

	private ProviderException responseTooLarge(RestClientException exception) {
		return providerException(
				RESPONSE_TOO_LARGE,
				"DataCite response exceeded the configured byte limit",
				false,
				null,
				exception);
	}

	private ProviderException translateStatus(RestClientResponseException exception) {
		int status = exception.getStatusCode().value();
		Duration retryAfter = parseRetryAfter(exception.getResponseHeaders());
		if (status == 429) {
			return providerException(
					RATE_LIMITED,
					"DataCite rate limit reached",
					true,
					retryAfter,
					exception);
		}
		if (status >= 500) {
			return providerException(
					UPSTREAM_ERROR,
					"DataCite is temporarily unavailable",
					true,
					retryAfter,
					exception);
		}
		return providerException(
				REQUEST_REJECTED,
				"DataCite rejected the request",
				false,
				null,
				exception);
	}

	private ProviderException translateAccessFailure(ResourceAccessException exception) {
		return hasTimeoutCause(exception)
				? requestTimedOut(exception)
				: providerException(UNAVAILABLE, "DataCite could not be reached", true, null, exception);
	}

	private ProviderException requestTimedOut(RestClientException exception) {
		return providerException(TIMEOUT, "DataCite request timed out", true, null, exception);
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
		if (DataCiteRequestDeadline.wasExceeded(throwable)) {
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

	private static LocalDate parseDate(String value) {
		String clean = cleanText(value);
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

	private static String normalizeLanguage(String value) {
		String clean = cleanText(value);
		return clean == null ? null : clean.toLowerCase(Locale.ROOT);
	}

	private static String normalizeDoi(String value) {
		String clean = cleanText(value);
		if (clean == null) {
			return null;
		}
		clean = clean.replaceFirst("(?i)^https?://(?:dx\\.)?doi\\.org/", "")
				.replaceFirst("(?i)^doi:\\s*", "")
				.strip();
		int slash = clean.indexOf('/');
		if (!clean.regionMatches(true, 0, "10.", 0, 3)
				|| slash <= 3
				|| slash == clean.length() - 1
				|| clean.indexOf('?') >= 0
				|| clean.indexOf('#') >= 0
				|| clean.codePoints().anyMatch(character -> Character.isWhitespace(character)
						|| Character.isISOControl(character))) {
			return null;
		}
		return clean.toLowerCase(Locale.ROOT);
	}

	private static URI doiLandingPage(String doi) {
		return encodedUri("https://doi.org/", doi);
	}

	private static URI dataCiteSource(String doi) {
		return encodedUri("https://api.datacite.org/dois/", doi);
	}

	private static URI encodedUri(String prefix, String value) {
		return UriComponentsBuilder.fromUriString(prefix)
				.path(value)
				.build()
				.encode()
				.toUri();
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

	private static boolean usableTitle(String value) {
		return cleanText(value) != null;
	}

	private static String normalizedText(String value) {
		String clean = cleanText(value);
		return clean == null ? null : clean.toLowerCase(Locale.ROOT);
	}

	private static String cleanText(String value) {
		if (value == null || value.isBlank()) {
			return null;
		}
		String clean = value.strip();
		return UNKNOWN_VALUE_CODES.contains(clean.toLowerCase(Locale.ROOT)) ? null : clean;
	}

	private record RelatedMetadata(
			String venueName,
			String volume,
			String issue,
			String pages,
			String articleNumber,
			String edition,
			List<String> isbn,
			List<String> issn) {
	}
}
