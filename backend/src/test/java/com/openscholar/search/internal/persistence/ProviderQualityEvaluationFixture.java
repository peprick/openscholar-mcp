package com.openscholar.search.internal.persistence;

import java.io.InputStream;
import java.net.URI;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import com.openscholar.paper.DocumentType;
import com.openscholar.paper.PaperIdentifierNormalizer;
import com.openscholar.paper.PaperIdentifierType;
import com.openscholar.provider.ProviderId;
import org.springframework.core.io.ClassPathResource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

record ProviderQualityEvaluationFixture(
		int schemaVersion,
		String fixtureId,
		String policyId,
		String split,
		String sourcePolicy,
		List<EvaluationQuery> queries) {

	private static final int EXPECTED_QUERY_COUNT = 8;
	private static final int MAX_RECORDS_PER_PROVIDER_QUERY = 10;
	private static final Set<ProviderId> EXPECTED_PROVIDERS =
			Set.of(ProviderId.OPENALEX, ProviderId.EUROPE_PMC);
	private static final Set<PaperIdentifierType> ALLOWED_IDENTIFIERS =
			Set.of(PaperIdentifierType.DOI, PaperIdentifierType.PMID, PaperIdentifierType.PMCID);
	private static final Pattern DOI = Pattern.compile("(?i)^10\\.[^/\\s]+/[^\\s?#]+$");
	private static final Pattern PMID = Pattern.compile("^[1-9]\\d{0,11}$");
	private static final Pattern PMCID = Pattern.compile("(?i)^PMC[1-9]\\d{0,11}$");
	private static final Pattern SYNTHETIC_DOI = Pattern.compile(
			"^10\\.5555/pq\\.[a-z0-9.]+$");
	private static final Pattern SYNTHETIC_PMID = Pattern.compile(
			"^90000000[1-8]\\d{3}$");
	private static final Pattern SYNTHETIC_PMCID = Pattern.compile(
			"^PMC90000000[1-8]\\d{3}$");
	private static final String SYNTHETIC_IDENTIFIER_HOST = "fixtures.openscholar.test";

	private static final Set<String> ROOT_REQUIRED = Set.of(
			"schemaVersion", "fixtureId", "policyId", "split", "sourcePolicy", "queries");
	private static final Set<String> QUERY_REQUIRED = Set.of(
			"key", "text", "judgments", "providerResults", "criticalPairs");
	private static final Set<String> PROVIDER_RESULT_REQUIRED = Set.of("provider", "records");
	private static final Set<String> RECORD_REQUIRED = Set.of(
			"key", "goldPaperKey", "providerRecordId", "title", "documentType", "authors",
			"identifiers", "sourceUrl", "reportedOpenAccess");
	private static final Set<String> RECORD_OPTIONAL = Set.of(
			"abstractText", "publicationYear", "language", "venueName", "citationCount", "issn");
	private static final Set<String> AUTHOR_REQUIRED = Set.of("displayName");
	private static final Set<String> AUTHOR_OPTIONAL = Set.of("orcid");
	private static final Set<String> IDENTIFIER_REQUIRED = Set.of("type", "value");
	private static final Set<String> CRITICAL_PAIR_REQUIRED = Set.of(
			"leftRecordKey", "rightRecordKey", "relation", "signal", "reason");

	ProviderQualityEvaluationFixture {
		queries = List.copyOf(Objects.requireNonNull(queries, "queries"));
	}

	static ProviderQualityEvaluationFixture load(ObjectMapper objectMapper, String resourcePath)
			throws Exception {
		ClassPathResource resource = new ClassPathResource(resourcePath);
		try (InputStream input = resource.getInputStream()) {
			return parse(objectMapper, objectMapper.readTree(input));
		}
	}

	static ProviderQualityEvaluationFixture parse(ObjectMapper objectMapper, JsonNode root)
			throws Exception {
		validateSchema(root);
		ProviderQualityEvaluationFixture fixture = objectMapper.treeToValue(
				root, ProviderQualityEvaluationFixture.class);
		fixture.validateReferencesAndValues();
		return fixture;
	}

	Set<PaperIdentifierType> exactOverlapSignals() {
		return queries.stream()
				.flatMap(query -> query.criticalPairs().stream())
				.filter(pair -> pair.relation() == CriticalRelation.MUST_LINK)
				.map(CriticalPair::signal)
				.filter(signal -> signal != ExactSignal.NONE)
				.map(signal -> PaperIdentifierType.valueOf(signal.name()))
				.collect(Collectors.toUnmodifiableSet());
	}

	private static void validateSchema(JsonNode root) {
		requireObjectKeys(root, "$", ROOT_REQUIRED, Set.of());
		requireInteger(root.required("schemaVersion"), "$.schemaVersion");
		requireTextFields(root, "$", List.of("fixtureId", "policyId", "split", "sourcePolicy"));
		JsonNode queries = requireArray(root.required("queries"), "$.queries");
		for (int queryIndex = 0; queryIndex < queries.size(); queryIndex++) {
			JsonNode query = queries.get(queryIndex);
			String queryPath = "$.queries[" + queryIndex + "]";
			requireObjectKeys(query, queryPath, QUERY_REQUIRED, Set.of());
			requireTextFields(query, queryPath, List.of("key", "text"));
			JsonNode judgments = query.required("judgments");
			if (!judgments.isObject()) {
				throw new IllegalArgumentException(queryPath + ".judgments must be an object");
			}
			for (String key : judgments.propertyNames()) {
				requireInteger(judgments.required(key), queryPath + ".judgments." + key);
			}

			JsonNode providerResults = requireArray(
					query.required("providerResults"), queryPath + ".providerResults");
			for (int providerIndex = 0; providerIndex < providerResults.size(); providerIndex++) {
				JsonNode providerResult = providerResults.get(providerIndex);
				String providerPath = queryPath + ".providerResults[" + providerIndex + "]";
				requireObjectKeys(providerResult, providerPath, PROVIDER_RESULT_REQUIRED, Set.of());
				requireText(providerResult.required("provider"), providerPath + ".provider");
				JsonNode records = requireArray(
						providerResult.required("records"), providerPath + ".records");
				for (int recordIndex = 0; recordIndex < records.size(); recordIndex++) {
					validateRecordSchema(records.get(recordIndex), providerPath + ".records[" + recordIndex + "]");
				}
			}

			JsonNode criticalPairs = requireArray(
					query.required("criticalPairs"), queryPath + ".criticalPairs");
			for (int pairIndex = 0; pairIndex < criticalPairs.size(); pairIndex++) {
				JsonNode pair = criticalPairs.get(pairIndex);
				String pairPath = queryPath + ".criticalPairs[" + pairIndex + "]";
				requireObjectKeys(pair, pairPath, CRITICAL_PAIR_REQUIRED, Set.of());
				requireTextFields(pair, pairPath, List.of(
						"leftRecordKey", "rightRecordKey", "relation", "signal", "reason"));
			}
		}
	}

	private static void validateRecordSchema(JsonNode record, String path) {
		requireObjectKeys(record, path, RECORD_REQUIRED, RECORD_OPTIONAL);
		requireTextFields(record, path, List.of(
				"key", "goldPaperKey", "providerRecordId", "title", "documentType", "sourceUrl"));
		requireBoolean(record.required("reportedOpenAccess"), path + ".reportedOpenAccess");
		for (String optionalText : List.of("abstractText", "language", "venueName")) {
			if (record.has(optionalText)) {
				requireText(record.required(optionalText), path + "." + optionalText);
			}
		}
		for (String optionalInteger : List.of("publicationYear", "citationCount")) {
			if (record.has(optionalInteger)) {
				requireInteger(record.required(optionalInteger), path + "." + optionalInteger);
			}
		}
		if (record.has("issn")) {
			requireTextArray(record.required("issn"), path + ".issn");
		}
		JsonNode authors = requireArray(record.required("authors"), path + ".authors");
		for (int authorIndex = 0; authorIndex < authors.size(); authorIndex++) {
			JsonNode author = authors.get(authorIndex);
			String authorPath = path + ".authors[" + authorIndex + "]";
			requireObjectKeys(author, authorPath, AUTHOR_REQUIRED, AUTHOR_OPTIONAL);
			requireText(author.required("displayName"), authorPath + ".displayName");
			if (author.has("orcid")) {
				requireText(author.required("orcid"), authorPath + ".orcid");
			}
		}
		JsonNode identifiers = requireArray(record.required("identifiers"), path + ".identifiers");
		for (int identifierIndex = 0; identifierIndex < identifiers.size(); identifierIndex++) {
			JsonNode identifier = identifiers.get(identifierIndex);
			String identifierPath = path + ".identifiers[" + identifierIndex + "]";
			requireObjectKeys(identifier, identifierPath, IDENTIFIER_REQUIRED, Set.of());
			requireTextFields(identifier, identifierPath, List.of("type", "value"));
		}
	}

	private void validateReferencesAndValues() {
		if (schemaVersion != 1) {
			throw new IllegalArgumentException("schemaVersion must be 1");
		}
		requireNonBlank(fixtureId, "fixtureId");
		requireNonBlank(policyId, "policyId");
		if (!"DEVELOPMENT".equals(split)) {
			throw new IllegalArgumentException("split must be DEVELOPMENT");
		}
		if (!"SYNTHETIC_METADATA_ONLY".equals(sourcePolicy)) {
			throw new IllegalArgumentException("sourcePolicy must be SYNTHETIC_METADATA_ONLY");
		}
		if (queries.size() != EXPECTED_QUERY_COUNT) {
			throw new IllegalArgumentException("fixture must contain exactly eight query groups");
		}

		Set<String> queryKeys = new LinkedHashSet<>();
		Set<String> globalRecordKeys = new LinkedHashSet<>();
		Set<String> providerRecordKeys = new LinkedHashSet<>();
		Set<PaperIdentifierType> observedMustLinkSignals = EnumSet.noneOf(PaperIdentifierType.class);
		for (EvaluationQuery query : queries) {
			requireNonBlank(query.key(), "query key");
			requireNonBlank(query.text(), "query text");
			if (!queryKeys.add(query.key())) {
				throw new IllegalArgumentException("Duplicate query key: " + query.key());
			}
			validateQuery(query, globalRecordKeys, providerRecordKeys, observedMustLinkSignals);
		}
		if (!observedMustLinkSignals.containsAll(ALLOWED_IDENTIFIERS)) {
			throw new IllegalArgumentException(
					"fixture must include DOI, PMID, and PMCID exact-overlap cases");
		}
	}

	private static void validateQuery(
			EvaluationQuery query,
			Set<String> globalRecordKeys,
			Set<String> providerRecordKeys,
			Set<PaperIdentifierType> observedMustLinkSignals) {
		if (query.providerResults().size() != EXPECTED_PROVIDERS.size()) {
			throw new IllegalArgumentException("query must contain exactly two provider results: " + query.key());
		}
		Set<ProviderId> providers = query.providerResults().stream()
				.map(ProviderResult::provider)
				.collect(Collectors.toSet());
		if (!providers.equals(EXPECTED_PROVIDERS)) {
			throw new IllegalArgumentException(
					"query providers must be OPENALEX and EUROPE_PMC: " + query.key());
		}

		Map<String, FixtureRecord> recordsByKey = new LinkedHashMap<>();
		for (ProviderResult providerResult : query.providerResults()) {
			if (providerResult.records().isEmpty()
					|| providerResult.records().size() > MAX_RECORDS_PER_PROVIDER_QUERY) {
				throw new IllegalArgumentException(
						"provider records must contain 1..10 entries for " + query.key());
			}
			for (FixtureRecord record : providerResult.records()) {
				if (!globalRecordKeys.add(record.key())) {
					throw new IllegalArgumentException("Duplicate record key: " + record.key());
				}
				String providerRecordKey = providerResult.provider() + "\n" + record.providerRecordId();
				if (!providerRecordKeys.add(providerRecordKey)) {
					throw new IllegalArgumentException("Duplicate provider record: " + providerRecordKey);
				}
				validateRecord(providerResult.provider(), record);
				recordsByKey.put(record.key(), record);
			}
		}

		Set<String> returnedGoldKeys = recordsByKey.values().stream()
				.map(FixtureRecord::goldPaperKey)
				.collect(Collectors.toUnmodifiableSet());
		if (!query.judgments().keySet().containsAll(returnedGoldKeys)) {
			Set<String> missing = new LinkedHashSet<>(returnedGoldKeys);
			missing.removeAll(query.judgments().keySet());
			throw new IllegalArgumentException(
					"judgments must cover every returned query gold paper: " + query.key()
							+ " missing=" + missing);
		}
		if (query.judgments().keySet().stream().anyMatch(key -> key == null || key.isBlank())
				|| query.judgments().values().stream().anyMatch(grade -> grade == null || grade < 0 || grade > 3)
				|| query.judgments().values().stream().noneMatch(grade -> grade > 0)
				|| query.judgments().values().stream().noneMatch(grade -> grade == 0)) {
			throw new IllegalArgumentException(
					"each query must use non-blank judgment keys and grades 0..3 with relevant and hard-negative papers: "
							+ query.key());
		}

		validateIdentifierCollisions(recordsByKey.values());
		validateCriticalPairs(query, recordsByKey, observedMustLinkSignals);
	}

	private static void validateRecord(ProviderId provider, FixtureRecord record) {
		requireNonBlank(record.key(), "record key");
		requireNonBlank(record.goldPaperKey(), "goldPaperKey for " + record.key());
		requireNonBlank(record.providerRecordId(), "providerRecordId for " + record.key());
		requireNonBlank(record.title(), "title for " + record.key());
		if (record.documentType() != DocumentType.ARTICLE) {
			throw new IllegalArgumentException("provider-quality fixture records must be articles: " + record.key());
		}
		if (record.publicationYear() != null
				&& (record.publicationYear() < 1000 || record.publicationYear() > 9999)) {
			throw new IllegalArgumentException("invalid publicationYear for " + record.key());
		}
		if (record.citationCount() != null && record.citationCount() < 0) {
			throw new IllegalArgumentException("citationCount must not be negative for " + record.key());
		}
		validateSyntheticSource(record.sourceUrl(), record.key());
		for (FixtureAuthor author : record.authors()) {
			requireNonBlank(author.displayName(), "author displayName for " + record.key());
			if (author.orcid() != null) {
				validateSyntheticPlaceholder(author.orcid(), "orcid", record.key());
			}
		}
		if (!record.issn().isEmpty()) {
			if (record.issn().size() != 1) {
				throw new IllegalArgumentException(
						"synthetic ISSN metadata must contain one fixture placeholder for " + record.key());
			}
			validateSyntheticPlaceholder(record.issn().getFirst(), "issn", record.key());
		}
		if (record.identifiers().isEmpty()) {
			throw new IllegalArgumentException("identifiers must not be empty for " + record.key());
		}
		Set<String> normalizedIdentifiers = new HashSet<>();
		for (FixtureIdentifier identifier : record.identifiers()) {
			if (!ALLOWED_IDENTIFIERS.contains(identifier.type())) {
				throw new IllegalArgumentException("unsupported fixture identifier for " + record.key());
			}
			validateIdentifierValue(identifier, record.key());
			String normalized = normalizedIdentifierKey(identifier);
			if (!normalizedIdentifiers.add(normalized)) {
				throw new IllegalArgumentException("duplicate normalized identifier for " + record.key());
			}
		}
		if (provider == ProviderId.EUROPE_PMC
				&& (!record.hasIdentifier(PaperIdentifierType.PMID)
						|| !record.hasIdentifier(PaperIdentifierType.PMCID))) {
			throw new IllegalArgumentException(
					"Europe PMC fixture records must retain PMID and PMCID: " + record.key());
		}
		if (provider == ProviderId.EUROPE_PMC) {
			String pmid = identifierValue(record, PaperIdentifierType.PMID);
			String pmcid = identifierValue(record, PaperIdentifierType.PMCID);
			if (!("PMC" + pmid).equals(pmcid)
					|| !("MED:" + pmid).equals(record.providerRecordId())) {
				throw new IllegalArgumentException(
						"Europe PMC fixture PMID, PMCID, and provider record must agree: " + record.key());
			}
		}
	}

	private static void validateCriticalPairs(
			EvaluationQuery query,
			Map<String, FixtureRecord> recordsByKey,
			Set<PaperIdentifierType> observedMustLinkSignals) {
		if (query.criticalPairs().isEmpty()
				|| query.criticalPairs().stream().noneMatch(pair -> pair.relation() == CriticalRelation.MUST_LINK)
				|| query.criticalPairs().stream().noneMatch(pair -> pair.relation() == CriticalRelation.MUST_SEPARATE)) {
			throw new IllegalArgumentException(
					"each query must contain MUST_LINK and MUST_SEPARATE critical pairs: " + query.key());
		}
		Set<String> pairKeys = new HashSet<>();
		for (CriticalPair pair : query.criticalPairs()) {
			FixtureRecord left = recordsByKey.get(pair.leftRecordKey());
			FixtureRecord right = recordsByKey.get(pair.rightRecordKey());
			if (left == null || right == null) {
				throw new IllegalArgumentException(
						"critical pair references an unknown record: "
								+ pair.leftRecordKey() + "/" + pair.rightRecordKey());
			}
			if (left.key().equals(right.key())) {
				throw new IllegalArgumentException("critical pair must reference two records");
			}
			String pairKey = left.key().compareTo(right.key()) < 0
					? left.key() + '\n' + right.key()
					: right.key() + '\n' + left.key();
			if (!pairKeys.add(pairKey)) {
				throw new IllegalArgumentException("duplicate critical pair: " + pairKey);
			}
			boolean sameGold = left.goldPaperKey().equals(right.goldPaperKey());
			if (pair.relation() == CriticalRelation.MUST_LINK) {
				if (!sameGold || pair.signal() == ExactSignal.NONE) {
					throw new IllegalArgumentException("MUST_LINK pair must share gold and an exact signal: " + pairKey);
				}
				PaperIdentifierType signal = PaperIdentifierType.valueOf(pair.signal().name());
				if (!sharedIdentifier(left, right, signal)) {
					throw new IllegalArgumentException("MUST_LINK pair does not share " + signal + ": " + pairKey);
				}
				observedMustLinkSignals.add(signal);
			}
			else if (sameGold || pair.signal() != ExactSignal.NONE || hasAnySharedIdentifier(left, right)) {
				throw new IllegalArgumentException(
						"MUST_SEPARATE pair must cross gold without an exact collision: " + pairKey);
			}
		}
	}

	private static void validateIdentifierCollisions(Iterable<FixtureRecord> records) {
		Map<String, Set<String>> goldKeysByIdentifier = new HashMap<>();
		for (FixtureRecord record : records) {
			for (FixtureIdentifier identifier : record.identifiers()) {
				goldKeysByIdentifier.computeIfAbsent(normalizedIdentifierKey(identifier), ignored -> new HashSet<>())
						.add(record.goldPaperKey());
			}
		}
		goldKeysByIdentifier.forEach((identifier, goldKeys) -> {
			if (goldKeys.size() > 1) {
				throw new IllegalArgumentException(
						"exact identifier crosses gold papers: " + identifier + "=" + goldKeys);
			}
		});
	}

	private static boolean sharedIdentifier(
			FixtureRecord left, FixtureRecord right, PaperIdentifierType type) {
		Set<String> leftValues = left.identifiers().stream()
				.filter(identifier -> identifier.type() == type)
				.map(ProviderQualityEvaluationFixture::normalizedIdentifierKey)
				.collect(Collectors.toSet());
		return right.identifiers().stream()
				.filter(identifier -> identifier.type() == type)
				.map(ProviderQualityEvaluationFixture::normalizedIdentifierKey)
				.anyMatch(leftValues::contains);
	}

	private static boolean hasAnySharedIdentifier(FixtureRecord left, FixtureRecord right) {
		return ALLOWED_IDENTIFIERS.stream().anyMatch(type -> sharedIdentifier(left, right, type));
	}

	private static String normalizedIdentifierKey(FixtureIdentifier identifier) {
		return identifier.type() + "\n"
				+ PaperIdentifierNormalizer.normalize(identifier.type(), identifier.value());
	}

	private static void validateIdentifierValue(FixtureIdentifier identifier, String recordKey) {
		String value = identifier.value() == null ? "" : identifier.value().strip();
		boolean validShape = switch (identifier.type()) {
			case DOI -> DOI.matcher(value).matches();
			case PMID -> PMID.matcher(value).matches();
			case PMCID -> PMCID.matcher(value).matches();
			default -> false;
		};
		boolean syntheticValue = switch (identifier.type()) {
			case DOI -> SYNTHETIC_DOI.matcher(value).matches();
			case PMID -> SYNTHETIC_PMID.matcher(value).matches();
			case PMCID -> SYNTHETIC_PMCID.matcher(value).matches();
			default -> false;
		};
		if (!validShape || !syntheticValue) {
			throw new IllegalArgumentException(
					"invalid synthetic " + identifier.type() + " for " + recordKey + ": " + value);
		}
	}

	private static String identifierValue(FixtureRecord record, PaperIdentifierType type) {
		return record.identifiers().stream()
				.filter(identifier -> identifier.type() == type)
				.map(FixtureIdentifier::value)
				.findFirst()
				.orElseThrow();
	}

	private static void validateSyntheticSource(String value, String recordKey) {
		try {
			URI uri = URI.create(value);
			if (!"https".equalsIgnoreCase(uri.getScheme())
					|| !"fixtures.openscholar.test".equalsIgnoreCase(uri.getHost())
					|| uri.getUserInfo() != null
					|| uri.getRawQuery() != null
					|| uri.getRawFragment() != null) {
				throw new IllegalArgumentException("invalid synthetic source URL for " + recordKey);
			}
		}
		catch (IllegalArgumentException exception) {
			throw new IllegalArgumentException("invalid synthetic source URL for " + recordKey, exception);
		}
	}

	private static void validateSyntheticPlaceholder(
			String value, String kind, String recordKey) {
		try {
			URI uri = URI.create(value);
			if (!"https".equalsIgnoreCase(uri.getScheme())
					|| !SYNTHETIC_IDENTIFIER_HOST.equalsIgnoreCase(uri.getHost())
					|| !("/" + kind + "/" + recordKey).equals(uri.getRawPath())
					|| uri.getUserInfo() != null
					|| uri.getPort() != -1
					|| uri.getRawQuery() != null
					|| uri.getRawFragment() != null) {
				throw new IllegalArgumentException(
						"invalid synthetic " + kind + " placeholder for " + recordKey);
			}
		}
		catch (IllegalArgumentException exception) {
			throw new IllegalArgumentException(
					"invalid synthetic " + kind + " placeholder for " + recordKey,
					exception);
		}
	}

	private static void requireObjectKeys(
			JsonNode node, String path, Set<String> required, Set<String> optional) {
		if (!node.isObject()) {
			throw new IllegalArgumentException(path + " must be an object");
		}
		Set<String> actual = new LinkedHashSet<>(node.propertyNames());
		Set<String> allowed = new HashSet<>(required);
		allowed.addAll(optional);
		Set<String> unknown = new LinkedHashSet<>(actual);
		unknown.removeAll(allowed);
		if (!unknown.isEmpty()) {
			throw new IllegalArgumentException("Unknown keys at " + path + ": " + unknown);
		}
		Set<String> missing = new LinkedHashSet<>(required);
		missing.removeAll(actual);
		if (!missing.isEmpty()) {
			throw new IllegalArgumentException("Missing keys at " + path + ": " + missing);
		}
	}

	private static JsonNode requireArray(JsonNode node, String path) {
		if (!node.isArray()) {
			throw new IllegalArgumentException(path + " must be an array");
		}
		return node;
	}

	private static void requireTextArray(JsonNode node, String path) {
		JsonNode values = requireArray(node, path);
		for (int index = 0; index < values.size(); index++) {
			requireText(values.get(index), path + "[" + index + "]");
		}
	}

	private static void requireTextFields(JsonNode node, String path, List<String> fields) {
		for (String field : fields) {
			requireText(node.required(field), path + "." + field);
		}
	}

	private static void requireText(JsonNode node, String path) {
		if (!node.isString() || node.asString().isBlank()) {
			throw new IllegalArgumentException(path + " must be a non-blank string");
		}
	}

	private static void requireInteger(JsonNode node, String path) {
		if (!node.isInt()) {
			throw new IllegalArgumentException(path + " must be an integer");
		}
	}

	private static void requireBoolean(JsonNode node, String path) {
		if (!node.isBoolean()) {
			throw new IllegalArgumentException(path + " must be a boolean");
		}
	}

	private static String requireNonBlank(String value, String field) {
		if (value == null || value.isBlank()) {
			throw new IllegalArgumentException(field + " must not be blank");
		}
		return value.strip();
	}

	record EvaluationQuery(
			String key,
			String text,
			Map<String, Integer> judgments,
			List<ProviderResult> providerResults,
			List<CriticalPair> criticalPairs) {

		EvaluationQuery {
			judgments = Map.copyOf(Objects.requireNonNull(judgments, "judgments"));
			providerResults = List.copyOf(Objects.requireNonNull(providerResults, "providerResults"));
			criticalPairs = List.copyOf(Objects.requireNonNull(criticalPairs, "criticalPairs"));
		}

		Map<String, FixtureRecord> recordsByKey() {
			Map<String, FixtureRecord> records = new LinkedHashMap<>();
			providerResults.forEach(result -> result.records().forEach(record -> records.put(record.key(), record)));
			return Map.copyOf(records);
		}
	}

	record ProviderResult(ProviderId provider, List<FixtureRecord> records) {

		ProviderResult {
			provider = Objects.requireNonNull(provider, "provider");
			records = List.copyOf(Objects.requireNonNull(records, "records"));
		}
	}

	record FixtureRecord(
			String key,
			String goldPaperKey,
			String providerRecordId,
			String title,
			DocumentType documentType,
			String abstractText,
			Integer publicationYear,
			String language,
			String venueName,
			Integer citationCount,
			List<FixtureAuthor> authors,
			List<String> issn,
			List<FixtureIdentifier> identifiers,
			String sourceUrl,
			boolean reportedOpenAccess) {

		FixtureRecord {
			authors = authors == null ? List.of() : List.copyOf(authors);
			issn = issn == null ? List.of() : List.copyOf(issn);
			identifiers = List.copyOf(Objects.requireNonNull(identifiers, "identifiers"));
		}

		boolean hasIdentifier(PaperIdentifierType type) {
			return identifiers.stream().anyMatch(identifier -> identifier.type() == type);
		}

	}

	record FixtureAuthor(String displayName, String orcid) {
	}

	record FixtureIdentifier(PaperIdentifierType type, String value) {

		FixtureIdentifier {
			type = Objects.requireNonNull(type, "type");
		}
	}

	record CriticalPair(
			String leftRecordKey,
			String rightRecordKey,
			CriticalRelation relation,
			ExactSignal signal,
			String reason) {

		CriticalPair {
			relation = Objects.requireNonNull(relation, "relation");
			signal = Objects.requireNonNull(signal, "signal");
		}
	}

	enum CriticalRelation {
		MUST_LINK,
		MUST_SEPARATE
	}

	enum ExactSignal {
		DOI,
		PMID,
		PMCID,
		NONE
	}
}
