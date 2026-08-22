package com.openscholar.provider.core;

import java.io.InterruptedIOException;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
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
import org.springframework.web.util.UriComponentsBuilder;
import tools.jackson.databind.JsonNode;

final class CoreResearchProvider implements ResearchProvider {

	static final String RATE_LIMITED = "CORE_RATE_LIMITED";
	static final String UPSTREAM_ERROR = "CORE_UPSTREAM_ERROR";
	static final String REQUEST_REJECTED = "CORE_REQUEST_REJECTED";
	static final String TIMEOUT = "CORE_TIMEOUT";
	static final String UNAVAILABLE = "CORE_UNAVAILABLE";
	static final String RESPONSE_ERROR = "CORE_RESPONSE_ERROR";
	static final String RESPONSE_TOO_LARGE = "CORE_RESPONSE_TOO_LARGE";

	private static final int MAX_PAGE_SIZE = 50;
	private static final int MAX_RESULT_WINDOW = 10_000;
	private static final String CURSOR_PREFIX = "core1.";
	private static final Pattern CURSOR_PAYLOAD = Pattern.compile("^v1:(0|[1-9]\\d*)$");
	private static final Pattern CORE_ID = Pattern.compile("^[1-9]\\d*$");
	private static final Pattern ISSN = Pattern.compile("(?i)^(\\d{4})-?(\\d{3}[\\dX])$");
	private static final DateTimeFormatter CORE_RATE_LIMIT_DATE =
			DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssZ");
	private static final String CORE_RETRY_AFTER = "X-RateLimit-Retry-After";

	private final RestClient restClient;
	private final CoreProperties properties;
	private final Clock clock;

	CoreResearchProvider(RestClient restClient, CoreProperties properties, Clock clock) {
		this.restClient = Objects.requireNonNull(restClient, "restClient");
		this.properties = Objects.requireNonNull(properties, "properties");
		this.clock = Objects.requireNonNull(clock, "clock");
	}

	@Override
	public ProviderId id() {
		return ProviderId.CORE;
	}

	@Override
	public ProviderSearchResult search(ProviderSearchQuery query) {
		Objects.requireNonNull(query, "query");
		Instant retrievedAt = clock.instant();
		int pageSize = boundedPageSize(query.pageSize());
		int offset = offset(query.cursor(), pageSize);
		try {
			CoreResponse response = restClient.get()
					.uri(uriBuilder -> uriBuilder
							.path("/v3/search/works/")
							.queryParam("q", coreQuery(query))
							.queryParam("offset", offset)
							.queryParam("limit", pageSize)
							.queryParam("stats", false)
							.build())
					.accept(MediaType.APPLICATION_JSON)
					.retrieve()
					.body(CoreResponse.class);
			return mapResponse(response, query, offset, pageSize, retrievedAt);
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
					"CORE returned a response that could not be processed",
					false,
					null,
					exception);
		}
	}

	private ProviderSearchResult mapResponse(
			CoreResponse response,
			ProviderSearchQuery query,
			int requestedOffset,
			int requestedPageSize,
			Instant retrievedAt) {
		if (response == null
				|| response.totalHits() == null
				|| response.limit() == null
				|| response.offset() == null
				|| response.results() == null
				|| response.totalHits() < 0
				|| response.limit() < 1
				|| response.limit() > requestedPageSize
				|| response.offset() != requestedOffset
				|| response.results().size() > response.limit()) {
			throw providerException(
					RESPONSE_ERROR,
					"CORE returned an incomplete or inconsistent response",
					false,
					null,
					null);
		}

		List<ProviderPaperRecord> records = response.results().stream()
				.filter(Objects::nonNull)
				.map(work -> mapWork(work, query.openAccessOnly()))
				.flatMap(Optional::stream)
				.filter(record -> matchesFilters(query, record))
				.toList();
		String nextCursor = nextCursor(requestedOffset, response.limit(), response.totalHits());
		return new ProviderSearchResult(id(), records, response.totalHits(), nextCursor, retrievedAt);
	}

	private Optional<ProviderPaperRecord> mapWork(CoreWork work, boolean fullTextRequired) {
		String providerRecordId = coreId(work.id());
		String title = cleanText(work.title());
		DocumentType documentType = documentType(work.documentType());
		if (providerRecordId == null || !usableTitle(title)) {
			return Optional.empty();
		}

		String doi = normalizeDoi(firstNonBlank(work.doi(), identifierValue(work.identifiers(), "doi")));
		String arxivId = normalizeArxivId(firstNonBlank(
				work.arxivId(), identifierValue(work.identifiers(), "arxiv", "arxivid", "arxiv_id")));
		LocalDate publicationDate = parseDate(work.publishedDate());
		Integer publicationYear = validYear(work.yearPublished());
		if (publicationYear == null && publicationDate != null) {
			publicationYear = publicationDate.getYear();
		}
		List<String> dataProviderNames = dataProviderNames(work.dataProviders());
		List<String> issn = journalIssn(work.journals());
		URI sourceUrl = sourceUrl(providerRecordId);
		boolean reportedOpenAccess = fullTextRequired || hasReportedFullText(work);

		ProviderPaperRecord record = new ProviderPaperRecord(
				id(),
				providerRecordId,
				doi,
				arxivId,
				title,
				cleanText(work.abstractText()),
				publicationDate,
				publicationYear,
				documentType,
				languageCode(work.language()),
				journalTitle(work.journals()),
				work.citationCount() == null ? null : Math.max(0, work.citationCount()),
				mapAuthors(work.authors()),
				reportedOpenAccess,
				landingPage(work.links(), doi, sourceUrl),
				null,
				null,
				parseInstant(work.updatedDate()),
				metadataFragment(work, dataProviderNames, reportedOpenAccess),
				identifiers(work, doi, arxivId),
				sourceUrl,
				publisherName(work.publisher()),
				dataProviderNames.isEmpty() ? null : dataProviderNames.getFirst(),
				null,
				null,
				null,
				null,
				null,
				List.of(),
				issn,
				degree(work.documentType()));
		return Optional.of(record);
	}

	private static String coreQuery(ProviderSearchQuery query) {
		StringBuilder value = new StringBuilder("(")
				.append(literalTerms(query.query()))
				.append(')');
		if (query.yearFrom() != null) {
			value.append(" AND yearPublished>=").append(query.yearFrom());
		}
		if (query.yearTo() != null) {
			value.append(" AND yearPublished<=").append(query.yearTo());
		}
		List<String> providerTypes = query.documentTypes().stream()
				.flatMap(type -> coreDocumentTypes(type).stream())
				.distinct()
				.sorted()
				.map(type -> "documentType:" + quotedLiteral(type))
				.toList();
		if (!providerTypes.isEmpty()) {
			value.append(" AND (").append(String.join(" OR ", providerTypes)).append(')');
		}
		if (query.openAccessOnly()) {
			value.append(" AND _exists_:fullText");
		}
		return value.toString();
	}

	private static List<String> coreDocumentTypes(DocumentType type) {
		return switch (type) {
			case ARTICLE -> List.of("journal article", "research", "research article", "review", "review article");
			case PREPRINT -> List.of("preprint", "working paper");
			case CONFERENCE_PAPER -> List.of("conference object", "conference paper", "conference proceedings");
			case THESIS -> List.of("bachelor thesis", "master thesis", "thesis");
			case DISSERTATION -> List.of("dissertation", "doctoral thesis");
			case BOOK -> List.of("book");
			case BOOK_CHAPTER -> List.of("book part");
			case REPORT -> List.of("report", "technical report");
			case DATASET -> List.of("dataset");
			case OTHER -> List.of("other", "unknown");
		};
	}

	private static String literalTerms(String value) {
		String clean = cleanText(value);
		if (clean == null) {
			return "*";
		}
		return java.util.Arrays.stream(clean.split("\\s+"))
				.map(CoreResearchProvider::quotedLiteral)
				.reduce((left, right) -> left + " " + right)
				.orElse("*");
	}

	private static String quotedLiteral(String value) {
		return '"' + value.replace("\\", "\\\\").replace("\"", "\\\"") + '"';
	}

	private static int boundedPageSize(int requestedPageSize) {
		return Math.max(1, Math.min(MAX_PAGE_SIZE, requestedPageSize));
	}

	private int offset(String cursor, int pageSize) {
		if (cursor == null || cursor.isBlank() || cursor.equals("*")) {
			return 0;
		}
		if (!cursor.startsWith(CURSOR_PREFIX)) {
			throw invalidCursor();
		}
		String encoded = cursor.substring(CURSOR_PREFIX.length());
		try {
			byte[] decoded = Base64.getUrlDecoder().decode(encoded);
			if (!Base64.getUrlEncoder().withoutPadding().encodeToString(decoded).equals(encoded)) {
				throw invalidCursor();
			}
			Matcher matcher = CURSOR_PAYLOAD.matcher(new String(decoded, StandardCharsets.US_ASCII));
			if (!matcher.matches()) {
				throw invalidCursor();
			}
			long value = Long.parseLong(matcher.group(1));
			if (value < 0 || value > MAX_RESULT_WINDOW - pageSize || value > Integer.MAX_VALUE) {
				throw invalidCursor();
			}
			return (int) value;
		}
		catch (IllegalArgumentException exception) {
			throw invalidCursor();
		}
	}

	private ProviderException invalidCursor() {
		return providerException(
				REQUEST_REJECTED,
				"CORE page cursor is invalid",
				false,
				null,
				null);
	}

	private static String nextCursor(int offset, int limit, long totalHits) {
		long nextOffset = (long) offset + limit;
		if (nextOffset >= totalHits || nextOffset >= MAX_RESULT_WINDOW) {
			return null;
		}
		String payload = "v1:" + nextOffset;
		return CURSOR_PREFIX + Base64.getUrlEncoder().withoutPadding()
				.encodeToString(payload.getBytes(StandardCharsets.US_ASCII));
	}

	private static boolean matchesFilters(ProviderSearchQuery query, ProviderPaperRecord record) {
		if (!query.documentTypes().isEmpty() && !query.documentTypes().contains(record.documentType())) {
			return false;
		}
		if (query.openAccessOnly() && !record.reportedOpenAccess()) {
			return false;
		}
		if (query.minimumCitations() > 0
				&& (record.citationCount() == null || record.citationCount() < query.minimumCitations())) {
			return false;
		}
		if (!query.languages().isEmpty()
				&& (record.language() == null
						|| !query.languages().contains(record.language().toLowerCase(Locale.ROOT)))) {
			return false;
		}
		if (query.yearFrom() != null
				&& (record.publicationYear() == null || record.publicationYear() < query.yearFrom())) {
			return false;
		}
		return query.yearTo() == null
				|| (record.publicationYear() != null && record.publicationYear() <= query.yearTo());
	}

	private static DocumentType documentType(JsonNode value) {
		List<String> types = scalarValues(value).stream()
				.map(CoreResearchProvider::normalizedText)
				.filter(Objects::nonNull)
				.toList();
		if (containsAny(types, "doctoral", "doctorate", "phd", "ph.d", "dissertation")) {
			return DocumentType.DISSERTATION;
		}
		if (containsAny(types, "thesis", "bachelor", "master")) {
			return DocumentType.THESIS;
		}
		if (containsAny(types, "preprint", "working paper")) {
			return DocumentType.PREPRINT;
		}
		if (containsAny(types, "conference")) {
			return DocumentType.CONFERENCE_PAPER;
		}
		if (containsAny(types, "book part", "book chapter")) {
			return DocumentType.BOOK_CHAPTER;
		}
		if (containsAny(types, "book")) {
			return DocumentType.BOOK;
		}
		if (containsAny(types, "technical report", "report")) {
			return DocumentType.REPORT;
		}
		if (containsAny(types, "dataset", "data set")) {
			return DocumentType.DATASET;
		}
		if (containsAny(types, "research", "article", "review", "journal")) {
			return DocumentType.ARTICLE;
		}
		return DocumentType.OTHER;
	}

	private static boolean containsAny(List<String> values, String... candidates) {
		for (String value : values) {
			for (String candidate : candidates) {
				if (value.contains(candidate)) {
					return true;
				}
			}
		}
		return false;
	}

	private static String degree(JsonNode documentType) {
		List<String> values = scalarValues(documentType).stream()
				.map(CoreResearchProvider::normalizedText)
				.filter(Objects::nonNull)
				.toList();
		if (containsAny(values, "phd", "ph.d", "doctor of philosophy")) {
			return "PhD";
		}
		if (containsAny(values, "doctorate", "doctoral", "dissertation")) {
			return "Doctoral";
		}
		if (containsAny(values, "master")) {
			return "Master's";
		}
		if (containsAny(values, "bachelor", "undergraduate")) {
			return "Bachelor's";
		}
		return null;
	}

	private static List<ProviderAuthor> mapAuthors(List<JsonNode> authors) {
		if (authors == null || authors.isEmpty()) {
			return List.of();
		}
		LinkedHashSet<String> names = new LinkedHashSet<>();
		for (JsonNode author : authors) {
			String name = author == null
					? null
					: author.isTextual() ? cleanText(author.textValue()) : cleanText(fieldText(author, "name"));
			if (name != null) {
				names.add(name);
			}
		}
		List<ProviderAuthor> mapped = new ArrayList<>(names.size());
		int position = 1;
		for (String name : names) {
			mapped.add(new ProviderAuthor(null, name, null, position++, false));
		}
		return List.copyOf(mapped);
	}

	private static List<PaperIdentifier> identifiers(CoreWork work, String doi, String arxivId) {
		Map<String, PaperIdentifier> values = new LinkedHashMap<>();
		addIdentifier(values, PaperIdentifierType.DOI, "", doi);
		addIdentifier(values, PaperIdentifierType.ARXIV, "", arxivId);
		if (work.identifiers() != null) {
			for (CoreIdentifier identifier : work.identifiers()) {
				if (identifier == null) {
					continue;
				}
				String type = normalizedIdentifierType(identifier.type());
				String value = cleanText(identifier.identifier());
				if (type == null || value == null) {
					continue;
				}
				switch (type) {
					case "doi" -> addIdentifier(values, PaperIdentifierType.DOI, "", normalizeDoi(value));
					case "arxiv", "arxivid" ->
						addIdentifier(values, PaperIdentifierType.ARXIV, "", normalizeArxivId(value));
					case "pubmed", "pubmedid", "pmid" ->
						addIdentifier(values, PaperIdentifierType.PMID, "", normalizePubmedId(value));
					case "oai", "oaiid", "oaipmh" ->
						addIdentifier(values, PaperIdentifierType.REPOSITORY, "oai", value);
					default -> {
						// Unsupported exact identifiers remain in the bounded provider fragment only.
					}
				}
			}
		}
		if (work.oaiIds() != null) {
			work.oaiIds().forEach(value -> addIdentifier(values, PaperIdentifierType.REPOSITORY, "oai", value));
		}
		addIdentifier(values, PaperIdentifierType.PMID, "", normalizePubmedId(scalarText(work.pubmedId())));
		return List.copyOf(values.values());
	}

	private static void addIdentifier(
			Map<String, PaperIdentifier> values, PaperIdentifierType type, String namespace, String value) {
		String clean = cleanText(value);
		if (clean == null) {
			return;
		}
		PaperIdentifier identifier = new PaperIdentifier(type, namespace, clean);
		String key = type.name() + '\n' + namespace + '\n' + clean.toLowerCase(Locale.ROOT);
		values.putIfAbsent(key, identifier);
	}

	private static String identifierValue(List<CoreIdentifier> identifiers, String... acceptedTypes) {
		if (identifiers == null) {
			return null;
		}
		Set<String> types = java.util.Arrays.stream(acceptedTypes)
				.map(CoreResearchProvider::normalizedIdentifierType)
				.filter(Objects::nonNull)
				.collect(java.util.stream.Collectors.toUnmodifiableSet());
		return identifiers.stream()
				.filter(Objects::nonNull)
				.filter(identifier -> types.contains(normalizedIdentifierType(identifier.type())))
				.map(CoreIdentifier::identifier)
				.map(CoreResearchProvider::cleanText)
				.filter(Objects::nonNull)
				.findFirst()
				.orElse(null);
	}

	private static String normalizedIdentifierType(String value) {
		String clean = normalizedText(value);
		return clean == null ? null : clean.replaceAll("[^a-z0-9]", "");
	}

	private static String coreId(JsonNode id) {
		String value = scalarText(id);
		return value != null && CORE_ID.matcher(value).matches() ? value : null;
	}

	private URI sourceUrl(String providerRecordId) {
		return UriComponentsBuilder.fromUri(properties.baseUrl())
				.pathSegment("v3", "works", providerRecordId)
				.build(true)
				.toUri();
	}

	private static URI landingPage(List<JsonNode> links, String doi, URI sourceUrl) {
		if (links != null) {
			Optional<URI> display = links.stream()
					.filter(Objects::nonNull)
					.filter(JsonNode::isObject)
					.filter(link -> "display".equals(normalizedText(fieldText(link, "type"))))
					.map(link -> fieldText(link, "url"))
					.map(CoreResearchProvider::httpUri)
					.flatMap(Optional::stream)
					.findFirst();
			if (display.isPresent()) {
				return display.orElseThrow();
			}
		}
		return doi == null
				? sourceUrl
				: UriComponentsBuilder.fromUriString("https://doi.org/")
						.path(doi)
						.build()
						.encode()
						.toUri();
	}

	private static Optional<URI> httpUri(String value) {
		String clean = cleanText(value);
		if (clean == null) {
			return Optional.empty();
		}
		try {
			URI uri = URI.create(clean);
			String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
			return uri.isAbsolute()
					&& uri.getHost() != null
					&& uri.getUserInfo() == null
					&& (scheme.equals("http") || scheme.equals("https"))
					? Optional.of(uri)
					: Optional.empty();
		}
		catch (IllegalArgumentException exception) {
			return Optional.empty();
		}
	}

	private static String languageCode(JsonNode language) {
		if (language == null || language.isNull()) {
			return null;
		}
		String value = language.isTextual() ? language.textValue() : fieldText(language, "code");
		String clean = cleanText(value);
		return clean == null ? null : clean.toLowerCase(Locale.ROOT);
	}

	private static String publisherName(JsonNode publisher) {
		if (publisher == null || publisher.isNull()) {
			return null;
		}
		return publisher.isTextual()
				? cleanText(publisher.textValue())
				: firstNonBlank(fieldText(publisher, "name"), fieldText(publisher, "displayName"));
	}

	private static String journalTitle(List<CoreJournal> journals) {
		if (journals == null) {
			return null;
		}
		return journals.stream()
				.filter(Objects::nonNull)
				.map(CoreJournal::title)
				.map(CoreResearchProvider::cleanText)
				.filter(Objects::nonNull)
				.findFirst()
				.orElse(null);
	}

	private static List<String> journalIssn(List<CoreJournal> journals) {
		if (journals == null) {
			return List.of();
		}
		return journals.stream()
				.filter(Objects::nonNull)
				.map(CoreJournal::identifiers)
				.filter(Objects::nonNull)
				.flatMap(List::stream)
				.map(CoreResearchProvider::scalarText)
				.map(CoreResearchProvider::normalizeIssn)
				.filter(Objects::nonNull)
				.distinct()
				.sorted()
				.toList();
	}

	private static String normalizeIssn(String value) {
		String clean = cleanText(value);
		if (clean == null) {
			return null;
		}
		Matcher matcher = ISSN.matcher(clean);
		return matcher.matches()
				? matcher.group(1) + '-' + matcher.group(2).toUpperCase(Locale.ROOT)
				: null;
	}

	private static List<String> dataProviderNames(List<JsonNode> providers) {
		if (providers == null) {
			return List.of();
		}
		return providers.stream()
				.map(provider -> provider == null
						? null
						: provider.isTextual() ? provider.textValue() : fieldText(provider, "name"))
				.map(CoreResearchProvider::cleanText)
				.filter(Objects::nonNull)
				.distinct()
				.sorted()
				.limit(20)
				.toList();
	}

	private static boolean hasReportedFullText(CoreWork work) {
		if (cleanText(work.downloadUrl()) != null) {
			return true;
		}
		return work.sourceFulltextUrls() != null
				&& work.sourceFulltextUrls().stream().map(CoreResearchProvider::scalarText)
						.anyMatch(value -> cleanText(value) != null);
	}

	private static Map<String, Object> metadataFragment(
			CoreWork work, List<String> dataProviderNames, boolean reportedOpenAccess) {
		Map<String, Object> metadata = new LinkedHashMap<>();
		List<String> providerTypes = scalarValues(work.documentType()).stream()
				.map(CoreResearchProvider::cleanText)
				.filter(Objects::nonNull)
				.distinct()
				.sorted()
				.toList();
		if (!providerTypes.isEmpty()) {
			metadata.put("providerTypes", providerTypes);
		}
		if (!dataProviderNames.isEmpty()) {
			metadata.put("dataProviders", dataProviderNames);
		}
		metadata.put("reportedFullText", reportedOpenAccess);
		return Map.copyOf(metadata);
	}

	private static List<String> scalarValues(JsonNode value) {
		if (value == null || value.isNull()) {
			return List.of();
		}
		if (value.isArray()) {
			List<String> values = new ArrayList<>();
			value.forEach(item -> {
				String scalar = scalarText(item);
				if (scalar != null) {
					values.add(scalar);
				}
			});
			return List.copyOf(values);
		}
		String scalar = scalarText(value);
		return scalar == null ? List.of() : List.of(scalar);
	}

	private static String scalarText(JsonNode value) {
		return value != null && value.isValueNode() && !value.isNull() ? cleanText(value.asText()) : null;
	}

	private static String fieldText(JsonNode object, String field) {
		return object != null && object.isObject() ? scalarText(object.get(field)) : null;
	}

	private static Integer validYear(Integer value) {
		return value != null && value >= 1000 && value <= 9999 ? value : null;
	}

	private static LocalDate parseDate(String value) {
		String clean = cleanText(value);
		if (clean == null) {
			return null;
		}
		try {
			return LocalDate.parse(clean.length() >= 10 ? clean.substring(0, 10) : clean);
		}
		catch (DateTimeParseException exception) {
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
			try {
				return OffsetDateTime.parse(clean).toInstant();
			}
			catch (DateTimeParseException ignoredOffset) {
				try {
					return LocalDateTime.parse(clean).toInstant(ZoneOffset.UTC);
				}
				catch (DateTimeParseException ignoredLocal) {
					LocalDate date = parseDate(clean);
					return date == null ? null : date.atStartOfDay().toInstant(ZoneOffset.UTC);
				}
			}
		}
	}

	private static String normalizeDoi(String value) {
		String clean = cleanText(value);
		if (clean == null) {
			return null;
		}
		clean = clean.toLowerCase(Locale.ROOT)
				.replaceFirst("^https?://(?:dx\\.)?doi\\.org/", "")
				.replaceFirst("^doi:\\s*", "");
		int slash = clean.indexOf('/');
		if (!clean.startsWith("10.")
				|| slash <= 3
				|| slash == clean.length() - 1
				|| clean.indexOf('?') >= 0
				|| clean.indexOf('#') >= 0
				|| clean.codePoints().anyMatch(character -> Character.isWhitespace(character)
						|| Character.isISOControl(character))) {
			return null;
		}
		return clean;
	}

	private static String normalizeArxivId(String value) {
		String clean = cleanText(value);
		if (clean == null) {
			return null;
		}
		return clean.replaceFirst("(?i)^https?://arxiv\\.org/(?:abs|pdf)/", "")
				.replaceFirst("(?i)^arxiv:", "")
				.replaceFirst("(?i)\\.pdf$", "");
	}

	private static String normalizePubmedId(String value) {
		String clean = cleanText(value);
		if (clean == null) {
			return null;
		}
		clean = clean.replaceFirst("(?i)^(?:pubmed|pmid):", "");
		return clean.codePoints().allMatch(Character::isDigit) ? clean : null;
	}

	private static String normalizedText(String value) {
		String clean = cleanText(value);
		return clean == null ? null : clean.toLowerCase(Locale.ROOT);
	}

	private static String cleanText(String value) {
		if (value == null) {
			return null;
		}
		String clean = value.strip();
		return clean.isEmpty() ? null : clean;
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
		return value != null && value.codePoints().anyMatch(Character::isLetterOrDigit);
	}

	private ProviderException responseTooLarge(RestClientException exception) {
		return providerException(
				RESPONSE_TOO_LARGE,
				"CORE response exceeded the configured byte limit",
				false,
				null,
				exception);
	}

	private ProviderException translateStatus(RestClientResponseException exception) {
		int status = exception.getStatusCode().value();
		Duration retryAfter = parseRetryAfter(exception.getResponseHeaders());
		if (status == 429) {
			return providerException(RATE_LIMITED, "CORE rate limit reached", true, retryAfter, exception);
		}
		if (status == 408) {
			return providerException(TIMEOUT, "CORE request timed out", true, retryAfter, exception);
		}
		if (status >= 500) {
			return providerException(
					UPSTREAM_ERROR, "CORE is temporarily unavailable", true, retryAfter, exception);
		}
		return providerException(
				REQUEST_REJECTED, "CORE rejected the request", false, null, exception);
	}

	private ProviderException translateAccessFailure(ResourceAccessException exception) {
		return hasTimeoutCause(exception)
				? requestTimedOut(exception)
				: providerException(UNAVAILABLE, "CORE could not be reached", true, null, exception);
	}

	private ProviderException requestTimedOut(RestClientException exception) {
		return providerException(TIMEOUT, "CORE request timed out", true, null, exception);
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
		String coreValue = headers.getFirst(CORE_RETRY_AFTER);
		Duration coreDuration = durationUntil(coreValue, true);
		if (coreDuration != null) {
			return coreDuration;
		}
		String standard = headers.getFirst(HttpHeaders.RETRY_AFTER);
		if (standard == null || standard.isBlank()) {
			return null;
		}
		try {
			return Duration.ofSeconds(Math.max(0, Long.parseLong(standard.strip())));
		}
		catch (NumberFormatException ignored) {
			return durationUntil(standard, false);
		}
	}

	private Duration durationUntil(String value, boolean coreFormat) {
		String clean = cleanText(value);
		if (clean == null) {
			return null;
		}
		try {
			Instant retryAt = coreFormat
					? OffsetDateTime.parse(clean, CORE_RATE_LIMIT_DATE).toInstant()
					: ZonedDateTime.parse(clean, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
			Duration duration = Duration.between(clock.instant(), retryAt);
			return duration.isNegative() ? Duration.ZERO : duration;
		}
		catch (DateTimeParseException invalidDate) {
			if (coreFormat) {
				try {
					Instant retryAt = OffsetDateTime.parse(clean, DateTimeFormatter.ISO_OFFSET_DATE_TIME).toInstant();
					Duration duration = Duration.between(clock.instant(), retryAt);
					return duration.isNegative() ? Duration.ZERO : duration;
				}
				catch (DateTimeParseException ignored) {
					return null;
				}
			}
			return null;
		}
	}

	private static boolean hasTimeoutCause(Throwable throwable) {
		if (CoreRequestDeadline.wasExceeded(throwable)) {
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
}
