package com.openscholar.search.internal.persistence;

import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

import com.openscholar.paper.DocumentType;
import org.springframework.core.io.ClassPathResource;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

record LocalCatalogTopicEvaluationFixture(
		int schemaVersion,
		String fixtureId,
		String policyId,
		Split split,
		LabelUnit labelUnit,
		SourcePolicy sourcePolicy,
		Instant retrievedAt,
		List<Candidate> candidates,
		List<Query> queries) {

	static final String RESOURCE_PATH =
			"search/relevance/local-catalog-topic-development-v1.json";
	static final String FIXTURE_ID = "local-catalog-topic-development-v1";
	static final String POLICY_ID = "local-catalog-topic-policy-v1";
	static final String FIXTURE_SHA256 =
			"702f8e9b546977b80cc16192c105173f90a0fc0e66eea309f50703d8ba1fbd3f";
	private static final int MAXIMUM_INPUT_BYTES = 256 * 1024;
	private static final Pattern SAFE_KEY = Pattern.compile("[a-z0-9]+(?:-[a-z0-9]+)*");
	private static final Pattern LANGUAGE = Pattern.compile("[a-z]{2,8}");
	private static final Set<String> ROOT_FIELDS = Set.of(
			"schemaVersion", "fixtureId", "policyId", "split", "labelUnit", "sourcePolicy",
			"retrievedAt", "candidates", "queries");
	private static final Set<String> CANDIDATE_FIELDS = Set.of(
			"key", "visibility", "title", "abstractText", "venueName", "publicationYear",
			"documentType", "language", "citationCount", "reportedOpenAccess", "authors");
	private static final Set<String> QUERY_FIELDS = Set.of(
			"key", "text", "cutoff", "filters", "judgments", "adversaries");
	private static final Set<String> FILTER_FIELDS = Set.of(
			"yearFrom", "yearTo", "documentTypes", "openAccessOnly", "minimumCitations",
			"languages");
	private static final Set<String> ADVERSARY_FIELDS = Set.of(
			"candidateKey", "kind", "reason");

	LocalCatalogTopicEvaluationFixture {
		fixtureId = requireTextValue(fixtureId, "fixtureId", 3, 100);
		policyId = requireTextValue(policyId, "policyId", 3, 100);
		split = Objects.requireNonNull(split, "split");
		labelUnit = Objects.requireNonNull(labelUnit, "labelUnit");
		sourcePolicy = Objects.requireNonNull(sourcePolicy, "sourcePolicy");
		retrievedAt = Objects.requireNonNull(retrievedAt, "retrievedAt");
		candidates = List.copyOf(Objects.requireNonNull(candidates, "candidates"));
		queries = List.copyOf(Objects.requireNonNull(queries, "queries"));
	}

	static BoundFixture loadFrozen(ObjectMapper objectMapper) throws IOException {
		BoundFixture bound = loadBound(objectMapper, RESOURCE_PATH);
		bound.validateReference(FIXTURE_ID, FIXTURE_SHA256);
		return bound;
	}

	static BoundFixture loadBound(ObjectMapper objectMapper, String resourcePath) throws IOException {
		Objects.requireNonNull(objectMapper, "objectMapper");
		String path = requireTextValue(resourcePath, "resourcePath", 1, 240);
		try (InputStream input = new ClassPathResource(path).getInputStream()) {
			return parseBound(objectMapper, readBounded(input));
		}
	}

	static BoundFixture parseBound(ObjectMapper objectMapper, byte[] bytes) throws IOException {
		Objects.requireNonNull(objectMapper, "objectMapper");
		Objects.requireNonNull(bytes, "bytes");
		if (bytes.length < 1 || bytes.length > MAXIMUM_INPUT_BYTES) {
			throw new IllegalArgumentException("fixture must contain 1 through 262144 bytes");
		}
		JsonNode root = objectMapper.reader()
				.with(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
				.with(
						DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY,
						DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
				.readTree(bytes);
		return new BoundFixture(parse(root), sha256(bytes));
	}

	static LocalCatalogTopicEvaluationFixture parse(JsonNode root) {
		requireExactObject(root, "$", ROOT_FIELDS);
		int schemaVersion = requireInteger(root.required("schemaVersion"), "$.schemaVersion");
		String fixtureId = requireText(root.required("fixtureId"), "$.fixtureId", 3, 100);
		String policyId = requireText(root.required("policyId"), "$.policyId", 3, 100);
		Split split = requireEnum(root.required("split"), "$.split", Split.class);
		LabelUnit labelUnit = requireEnum(
				root.required("labelUnit"), "$.labelUnit", LabelUnit.class);
		SourcePolicy sourcePolicy = requireEnum(
				root.required("sourcePolicy"), "$.sourcePolicy", SourcePolicy.class);
		Instant retrievedAt = requireInstant(root.required("retrievedAt"), "$.retrievedAt");

		JsonNode candidateNodes = requireArray(root.required("candidates"), "$.candidates");
		List<Candidate> candidates = new ArrayList<>(candidateNodes.size());
		for (int index = 0; index < candidateNodes.size(); index++) {
			candidates.add(parseCandidate(candidateNodes.get(index), "$.candidates[" + index + "]"));
		}
		JsonNode queryNodes = requireArray(root.required("queries"), "$.queries");
		List<Query> queries = new ArrayList<>(queryNodes.size());
		for (int index = 0; index < queryNodes.size(); index++) {
			queries.add(parseQuery(queryNodes.get(index), "$.queries[" + index + "]"));
		}
		LocalCatalogTopicEvaluationFixture fixture = new LocalCatalogTopicEvaluationFixture(
				schemaVersion, fixtureId, policyId, split, labelUnit, sourcePolicy, retrievedAt,
				candidates, queries);
		validateFixture(fixture);
		return fixture;
	}

	Map<String, Candidate> candidatesByKey() {
		Map<String, Candidate> values = new LinkedHashMap<>();
		candidates.forEach(candidate -> values.put(candidate.key(), candidate));
		return Map.copyOf(values);
	}

	Set<String> targetVisibleKeys() {
		return candidates.stream()
				.filter(candidate -> candidate.visibility().targetVisible())
				.map(Candidate::key)
				.collect(java.util.stream.Collectors.toUnmodifiableSet());
	}

	private static Candidate parseCandidate(JsonNode node, String path) {
		requireExactObject(node, path, CANDIDATE_FIELDS);
		String key = requireKey(node.required("key"), path + ".key");
		Visibility visibility = requireEnum(
				node.required("visibility"), path + ".visibility", Visibility.class);
		String title = requireText(node.required("title"), path + ".title", 3, 300);
		String abstractText = requireNullableText(
				node.required("abstractText"), path + ".abstractText", 3, 4000);
		String venueName = requireNullableText(
				node.required("venueName"), path + ".venueName", 2, 300);
		Integer publicationYear = requireNullableInteger(
				node.required("publicationYear"), path + ".publicationYear");
		if (publicationYear != null && (publicationYear < 1000 || publicationYear > 9999)) {
			throw new IllegalArgumentException(path + ".publicationYear is outside 1000..9999");
		}
		DocumentType documentType = requireEnum(
				node.required("documentType"), path + ".documentType", DocumentType.class);
		String language = requireLanguage(node.required("language"), path + ".language");
		Integer citationCount = requireNullableInteger(
				node.required("citationCount"), path + ".citationCount");
		if (citationCount != null && citationCount < 0) {
			throw new IllegalArgumentException(path + ".citationCount must not be negative");
		}
		boolean reportedOpenAccess = requireBoolean(
				node.required("reportedOpenAccess"), path + ".reportedOpenAccess");
		List<String> authors = requireTextArray(
				node.required("authors"), path + ".authors", 0, 10, 2, 200);
		return new Candidate(
				key, visibility, title, abstractText, venueName, publicationYear, documentType,
				language, citationCount, reportedOpenAccess, authors);
	}

	private static Query parseQuery(JsonNode node, String path) {
		requireExactObject(node, path, QUERY_FIELDS);
		String key = requireKey(node.required("key"), path + ".key");
		String text = requireText(node.required("text"), path + ".text", 3, 500);
		int cutoff = requireInteger(node.required("cutoff"), path + ".cutoff");
		Filter filters = parseFilter(node.required("filters"), path + ".filters");
		JsonNode judgmentNode = requireObject(node.required("judgments"), path + ".judgments");
		Map<String, Integer> judgments = new LinkedHashMap<>();
		for (String propertyName : judgmentNode.propertyNames()) {
			String candidateKey = requireKeyValue(propertyName, path + ".judgments key");
			int grade = requireInteger(
					judgmentNode.required(propertyName), path + ".judgments." + candidateKey);
			if (grade < 0 || grade > 3) {
				throw new IllegalArgumentException(
						path + ".judgments." + candidateKey + " must be within 0..3");
			}
			judgments.put(candidateKey, grade);
		}
		JsonNode adversaryNodes = requireArray(node.required("adversaries"), path + ".adversaries");
		List<Adversary> adversaries = new ArrayList<>(adversaryNodes.size());
		for (int index = 0; index < adversaryNodes.size(); index++) {
			String adversaryPath = path + ".adversaries[" + index + "]";
			JsonNode adversary = adversaryNodes.get(index);
			requireExactObject(adversary, adversaryPath, ADVERSARY_FIELDS);
			adversaries.add(new Adversary(
					requireKey(adversary.required("candidateKey"), adversaryPath + ".candidateKey"),
					requireEnum(adversary.required("kind"), adversaryPath + ".kind", AdversaryKind.class),
					requireText(adversary.required("reason"), adversaryPath + ".reason", 10, 300)));
		}
		return new Query(key, text, cutoff, filters, judgments, adversaries);
	}

	private static Filter parseFilter(JsonNode node, String path) {
		requireExactObject(node, path, FILTER_FIELDS);
		Integer yearFrom = requireNullableInteger(node.required("yearFrom"), path + ".yearFrom");
		Integer yearTo = requireNullableInteger(node.required("yearTo"), path + ".yearTo");
		if (yearFrom != null && (yearFrom < 1000 || yearFrom > 9999)
				|| yearTo != null && (yearTo < 1000 || yearTo > 9999)
				|| yearFrom != null && yearTo != null && yearFrom > yearTo) {
			throw new IllegalArgumentException(path + " contains an invalid year range");
		}
		List<DocumentType> documentTypes = requireEnumArray(
				node.required("documentTypes"), path + ".documentTypes", DocumentType.class);
		boolean openAccessOnly = requireBoolean(
				node.required("openAccessOnly"), path + ".openAccessOnly");
		int minimumCitations = requireInteger(
				node.required("minimumCitations"), path + ".minimumCitations");
		if (minimumCitations < 0) {
			throw new IllegalArgumentException(path + ".minimumCitations must not be negative");
		}
		List<String> languages = requireLanguageArray(
				node.required("languages"), path + ".languages");
		return new Filter(
				yearFrom, yearTo, documentTypes, openAccessOnly, minimumCitations, languages);
	}

	private static void validateFixture(LocalCatalogTopicEvaluationFixture fixture) {
		if (fixture.schemaVersion() != 1
				|| !FIXTURE_ID.equals(fixture.fixtureId())
				|| !POLICY_ID.equals(fixture.policyId())
				|| fixture.split() != Split.DEVELOPMENT
				|| fixture.labelUnit() != LabelUnit.CANONICAL_PAPER_TOPIC_RELEVANCE
				|| fixture.sourcePolicy() != SourcePolicy.SYNTHETIC_METADATA_ONLY) {
			throw new IllegalArgumentException("Unexpected local topic fixture identity or policy boundary");
		}
		if (fixture.candidates().size() != 25 || fixture.queries().size() != 6) {
			throw new IllegalArgumentException("The v1 fixture must contain 25 candidates and 6 queries");
		}
		Set<String> candidateKeys = uniqueKeys(
				fixture.candidates().stream().map(Candidate::key).toList(), "candidate");
		Set<String> targetKeys = fixture.targetVisibleKeys();
		if (targetKeys.size() != 19) {
			throw new IllegalArgumentException("The v1 fixture must contain 19 target-visible candidates");
		}
		if (!fixture.candidates().stream().map(Candidate::visibility)
				.collect(java.util.stream.Collectors.toSet())
				.equals(EnumSet.allOf(Visibility.class))) {
			throw new IllegalArgumentException("Every candidate visibility must be represented");
		}
		uniqueKeys(fixture.queries().stream().map(Query::key).toList(), "query");
		Set<String> normalizedQueries = new LinkedHashSet<>();
		EnumSet<AdversaryKind> adversaryKinds = EnumSet.noneOf(AdversaryKind.class);
		boolean completeFilterQuery = false;
		Map<String, Candidate> byKey = fixture.candidatesByKey();
		for (Query query : fixture.queries()) {
			String normalized = query.text().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
			if (!normalizedQueries.add(normalized)) {
				throw new IllegalArgumentException("Query text must be unique after normalization");
			}
			if (query.cutoff() != 10) {
				throw new IllegalArgumentException("Every v1 query cutoff must be 10");
			}
			if (!query.judgments().keySet().equals(targetKeys)) {
				throw new IllegalArgumentException(
						"Query judgments must cover exactly every target-visible candidate: " + query.key());
			}
			if (query.judgments().values().stream().noneMatch(grade -> grade > 0)) {
				throw new IllegalArgumentException("Every query needs at least one relevant judgment");
			}
			Set<String> adversaryKeys = new LinkedHashSet<>();
			for (Adversary adversary : query.adversaries()) {
				if (!adversaryKeys.add(adversary.candidateKey())) {
					throw new IllegalArgumentException("Duplicate query adversary: " + adversary.candidateKey());
				}
				Candidate candidate = byKey.get(adversary.candidateKey());
				if (candidate == null || !candidateKeys.contains(candidate.key())) {
					throw new IllegalArgumentException("Adversary references an unknown candidate");
				}
				validateAdversary(query, adversary, candidate);
				adversaryKinds.add(adversary.kind());
			}
			Filter filter = query.filters();
			completeFilterQuery |= filter.yearFrom() != null
					&& filter.yearTo() != null
					&& !filter.documentTypes().isEmpty()
					&& filter.openAccessOnly()
					&& filter.minimumCitations() > 0
					&& !filter.languages().isEmpty();
		}
		if (!adversaryKinds.equals(EnumSet.allOf(AdversaryKind.class))) {
			throw new IllegalArgumentException("Every adversary kind must be represented");
		}
		if (!completeFilterQuery) {
			throw new IllegalArgumentException("At least one query must exercise every filter dimension");
		}
	}

	private static void validateAdversary(Query query, Adversary adversary, Candidate candidate) {
		switch (adversary.kind()) {
			case OTHER_OWNER_SEARCH_EXACT_MATCH -> requireVisibility(
					candidate, Visibility.OTHER_OWNER_SEARCH, adversary.kind());
			case OTHER_OWNER_COLLECTION_EXACT_MATCH -> requireVisibility(
					candidate, Visibility.OTHER_OWNER_COLLECTION, adversary.kind());
			case CATALOG_ONLY_EXACT_MATCH -> requireVisibility(
					candidate, Visibility.CATALOG_ONLY, adversary.kind());
			case HIGH_CITATION_LEXICAL_COLLISION, AUTHOR_SUBSTRING_COLLISION -> {
				if (!candidate.visibility().targetVisible()
						|| query.judgments().getOrDefault(candidate.key(), -1) != 0) {
					throw new IllegalArgumentException(
							adversary.kind() + " must reference a target-visible grade-zero candidate");
				}
			}
		}
	}

	private static void requireVisibility(
			Candidate candidate, Visibility expected, AdversaryKind kind) {
		if (candidate.visibility() != expected) {
			throw new IllegalArgumentException(kind + " must reference " + expected);
		}
	}

	private static Set<String> uniqueKeys(List<String> values, String kind) {
		Set<String> keys = new LinkedHashSet<>(values);
		if (keys.size() != values.size()) {
			throw new IllegalArgumentException(kind + " keys must be unique");
		}
		return Set.copyOf(keys);
	}

	private static byte[] readBounded(InputStream input) throws IOException {
		byte[] bytes = input.readNBytes(MAXIMUM_INPUT_BYTES + 1);
		if (bytes.length < 1 || bytes.length > MAXIMUM_INPUT_BYTES) {
			throw new IllegalArgumentException("fixture must contain 1 through 262144 bytes");
		}
		return bytes;
	}

	static String sha256(byte[] bytes) {
		try {
			return HexFormat.of().formatHex(
					MessageDigest.getInstance("SHA-256").digest(bytes));
		}
		catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException("SHA-256 is unavailable", exception);
		}
	}

	private static void requireExactObject(JsonNode node, String path, Set<String> fields) {
		requireObject(node, path);
		Set<String> actual = new LinkedHashSet<>(node.propertyNames());
		if (!actual.equals(fields)) {
			throw new IllegalArgumentException(path + " must contain exactly " + fields + "; found " + actual);
		}
	}

	private static JsonNode requireObject(JsonNode node, String path) {
		if (node == null || !node.isObject()) {
			throw new IllegalArgumentException(path + " must be an object");
		}
		return node;
	}

	private static JsonNode requireArray(JsonNode node, String path) {
		if (node == null || !node.isArray()) {
			throw new IllegalArgumentException(path + " must be an array");
		}
		return node;
	}

	private static int requireInteger(JsonNode node, String path) {
		if (node == null || !node.isIntegralNumber() || !node.canConvertToInt()) {
			throw new IllegalArgumentException(path + " must be an integer");
		}
		return node.intValue();
	}

	private static Integer requireNullableInteger(JsonNode node, String path) {
		return node != null && node.isNull() ? null : requireInteger(node, path);
	}

	private static boolean requireBoolean(JsonNode node, String path) {
		if (node == null || !node.isBoolean()) {
			throw new IllegalArgumentException(path + " must be a boolean");
		}
		return node.booleanValue();
	}

	private static String requireText(JsonNode node, String path, int minimum, int maximum) {
		if (node == null || !node.isTextual()) {
			throw new IllegalArgumentException(path + " must be text");
		}
		return requireTextValue(node.asString(), path, minimum, maximum);
	}

	private static String requireNullableText(
			JsonNode node, String path, int minimum, int maximum) {
		return node != null && node.isNull() ? null : requireText(node, path, minimum, maximum);
	}

	private static String requireTextValue(
			String value, String path, int minimum, int maximum) {
		if (value == null || !value.equals(value.strip())
				|| value.length() < minimum || value.length() > maximum
				|| !Normalizer.isNormalized(value, Normalizer.Form.NFC)
				|| value.codePoints().anyMatch(Character::isISOControl)) {
			throw new IllegalArgumentException(
					path + " must be bounded NFC text without surrounding whitespace or controls");
		}
		return value;
	}

	private static String requireKey(JsonNode node, String path) {
		return requireKeyValue(requireText(node, path, 3, 100), path);
	}

	private static String requireKeyValue(String value, String path) {
		if (!SAFE_KEY.matcher(value).matches()) {
			throw new IllegalArgumentException(path + " must be a lowercase safe slug");
		}
		return value;
	}

	private static String requireLanguage(JsonNode node, String path) {
		String value = requireText(node, path, 2, 8);
		if (!LANGUAGE.matcher(value).matches()) {
			throw new IllegalArgumentException(path + " must be a lowercase language code");
		}
		return value;
	}

	private static Instant requireInstant(JsonNode node, String path) {
		String value = requireText(node, path, 20, 40);
		try {
			Instant parsed = Instant.parse(value);
			if (!parsed.toString().equals(value)) {
				throw new IllegalArgumentException(path + " must use canonical Instant text");
			}
			return parsed;
		}
		catch (DateTimeParseException exception) {
			throw new IllegalArgumentException(path + " must be an ISO-8601 instant", exception);
		}
	}

	private static List<String> requireTextArray(
			JsonNode node, String path, int minimumSize, int maximumSize,
			int minimumLength, int maximumLength) {
		requireArray(node, path);
		if (node.size() < minimumSize || node.size() > maximumSize) {
			throw new IllegalArgumentException(path + " has an invalid item count");
		}
		List<String> values = new ArrayList<>(node.size());
		for (int index = 0; index < node.size(); index++) {
			values.add(requireText(
					node.get(index), path + "[" + index + "]", minimumLength, maximumLength));
		}
		if (values.stream().distinct().count() != values.size()) {
			throw new IllegalArgumentException(path + " must not contain duplicates");
		}
		return List.copyOf(values);
	}

	private static List<String> requireLanguageArray(JsonNode node, String path) {
		requireArray(node, path);
		List<String> values = new ArrayList<>(node.size());
		for (int index = 0; index < node.size(); index++) {
			values.add(requireLanguage(node.get(index), path + "[" + index + "]"));
		}
		if (values.stream().distinct().count() != values.size()) {
			throw new IllegalArgumentException(path + " must not contain duplicates");
		}
		return List.copyOf(values);
	}

	private static <E extends Enum<E>> E requireEnum(
			JsonNode node, String path, Class<E> enumType) {
		String value = requireText(node, path, 1, 100);
		try {
			return Enum.valueOf(enumType, value);
		}
		catch (IllegalArgumentException exception) {
			throw new IllegalArgumentException(path + " contains an unsupported value", exception);
		}
	}

	private static <E extends Enum<E>> List<E> requireEnumArray(
			JsonNode node, String path, Class<E> enumType) {
		requireArray(node, path);
		List<E> values = new ArrayList<>(node.size());
		for (int index = 0; index < node.size(); index++) {
			values.add(requireEnum(node.get(index), path + "[" + index + "]", enumType));
		}
		if (values.stream().distinct().count() != values.size()) {
			throw new IllegalArgumentException(path + " must not contain duplicates");
		}
		return List.copyOf(values);
	}

	enum Split {
		DEVELOPMENT
	}

	enum LabelUnit {
		CANONICAL_PAPER_TOPIC_RELEVANCE
	}

	enum SourcePolicy {
		SYNTHETIC_METADATA_ONLY
	}

	enum Visibility {
		TARGET_OWNER_SEARCH,
		TARGET_OWNER_COLLECTION,
		OTHER_OWNER_SEARCH,
		OTHER_OWNER_COLLECTION,
		CATALOG_ONLY;

		boolean targetVisible() {
			return this == TARGET_OWNER_SEARCH || this == TARGET_OWNER_COLLECTION;
		}
	}

	enum AdversaryKind {
		OTHER_OWNER_SEARCH_EXACT_MATCH,
		OTHER_OWNER_COLLECTION_EXACT_MATCH,
		CATALOG_ONLY_EXACT_MATCH,
		HIGH_CITATION_LEXICAL_COLLISION,
		AUTHOR_SUBSTRING_COLLISION
	}

	record Candidate(
			String key,
			Visibility visibility,
			String title,
			String abstractText,
			String venueName,
			Integer publicationYear,
			DocumentType documentType,
			String language,
			Integer citationCount,
			boolean reportedOpenAccess,
			List<String> authors) {

		Candidate {
			key = requireKeyValue(key, "candidate.key");
			visibility = Objects.requireNonNull(visibility, "visibility");
			title = requireTextValue(title, "candidate.title", 3, 300);
			authors = List.copyOf(Objects.requireNonNull(authors, "authors"));
		}
	}

	record Filter(
			Integer yearFrom,
			Integer yearTo,
			List<DocumentType> documentTypes,
			boolean openAccessOnly,
			int minimumCitations,
			List<String> languages) {

		Filter {
			documentTypes = List.copyOf(Objects.requireNonNull(documentTypes, "documentTypes"));
			languages = List.copyOf(Objects.requireNonNull(languages, "languages"));
		}
	}

	record Query(
			String key,
			String text,
			int cutoff,
			Filter filters,
			Map<String, Integer> judgments,
			List<Adversary> adversaries) {

		Query {
			key = requireKeyValue(key, "query.key");
			text = requireTextValue(text, "query.text", 3, 500);
			filters = Objects.requireNonNull(filters, "filters");
			judgments = Map.copyOf(Objects.requireNonNull(judgments, "judgments"));
			adversaries = List.copyOf(Objects.requireNonNull(adversaries, "adversaries"));
		}
	}

	record Adversary(String candidateKey, AdversaryKind kind, String reason) {

		Adversary {
			candidateKey = requireKeyValue(candidateKey, "adversary.candidateKey");
			kind = Objects.requireNonNull(kind, "kind");
			reason = requireTextValue(reason, "adversary.reason", 10, 300);
		}
	}

	record BoundFixture(LocalCatalogTopicEvaluationFixture fixture, String sha256) {

		BoundFixture {
			fixture = Objects.requireNonNull(fixture, "fixture");
			sha256 = requireTextValue(sha256, "sha256", 64, 64);
		}

		void validateReference(String expectedFixtureId, String expectedSha256) {
			if (!fixture.fixtureId().equals(expectedFixtureId) || !sha256.equals(expectedSha256)) {
				throw new IllegalArgumentException("Fixture identity or raw SHA-256 digest drifted");
			}
		}
	}
}
