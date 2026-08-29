package com.openscholar.search.internal.persistence;

import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.EnumMap;
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

record RelatedTopicReuseEvaluationFixture(
		int schemaVersion,
		String fixtureId,
		Split split,
		LabelUnit labelUnit,
		SourcePolicy sourcePolicy,
		Instant retrievedAt,
		List<Lineage> lineages,
		List<Candidate> candidates,
		List<Query> queries) {

	static final String RESOURCE_PATH =
			"search/relevance/related-topic-reuse-development-v1.json";
	static final String FIXTURE_ID = "related-topic-reuse-development-v1";
	static final String FIXTURE_SHA256 =
			"ff82f415c629f4e8b46606ece900de40c6ff05ddd77c0c7f8af319e191263f2e";
	static final int MAXIMUM_INPUT_BYTES = 256 * 1024;
	private static final Instant RETRIEVED_AT = Instant.parse("2026-08-29T00:00:00Z");
	private static final Pattern SAFE_KEY = Pattern.compile("[a-z0-9]+(?:-[a-z0-9]+)*");
	private static final Pattern LANGUAGE = Pattern.compile("[a-z]{2,8}");
	private static final Set<String> ROOT_FIELDS = Set.of(
			"schemaVersion", "fixtureId", "split", "labelUnit", "sourcePolicy", "retrievedAt",
			"lineages", "candidates", "queries");
	private static final Set<String> LINEAGE_FIELDS = Set.of("key", "kind");
	private static final Set<String> CANDIDATE_FIELDS = Set.of(
			"key", "lineageKey", "title", "abstractText", "venueName", "publicationYear",
			"documentType", "language", "citationCount", "reportedOpenAccess", "authors");
	private static final Set<String> QUERY_FIELDS = Set.of(
			"key", "text", "kind", "cutoff", "filters", "judgments", "adversaries");
	private static final Set<String> FILTER_FIELDS = Set.of(
			"yearFrom", "yearTo", "documentTypes", "openAccessOnly", "minimumCitations",
			"languages");
	private static final Set<String> ADVERSARY_FIELDS = Set.of(
			"candidateKey", "kind", "reason");
	private static final Map<LineageKind, Integer> EXPECTED_LINEAGE_COUNTS = Map.of(
			LineageKind.TARGET_OWNER_SEARCH, 4,
			LineageKind.TARGET_OWNER_COLLECTION, 4,
			LineageKind.OTHER_OWNER_SEARCH, 1,
			LineageKind.OTHER_OWNER_COLLECTION, 1,
			LineageKind.CATALOG_ONLY, 1);
	private static final Map<QueryKind, Integer> EXPECTED_QUERY_KIND_COUNTS = Map.of(
			QueryKind.LEXICAL_BRIDGE_OPPORTUNITY, 2,
			QueryKind.FILTERED_LEXICAL_BRIDGE_OPPORTUNITY, 1,
			QueryKind.AUTHOR_NO_RELATED_SIGNAL_CONTROL, 1,
			QueryKind.NO_SEED_FALLBACK_CONTROL, 1);

	RelatedTopicReuseEvaluationFixture {
		fixtureId = requireTextValue(fixtureId, "fixtureId", 3, 100);
		split = Objects.requireNonNull(split, "split");
		labelUnit = Objects.requireNonNull(labelUnit, "labelUnit");
		sourcePolicy = Objects.requireNonNull(sourcePolicy, "sourcePolicy");
		retrievedAt = Objects.requireNonNull(retrievedAt, "retrievedAt");
		lineages = List.copyOf(Objects.requireNonNull(lineages, "lineages"));
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

	static RelatedTopicReuseEvaluationFixture parse(JsonNode root) {
		requireExactObject(root, "$", ROOT_FIELDS);
		List<Lineage> lineages = parseLineages(root.required("lineages"), "$.lineages");
		List<Candidate> candidates = parseCandidates(root.required("candidates"), "$.candidates");
		List<Query> queries = parseQueries(root.required("queries"), "$.queries");
		RelatedTopicReuseEvaluationFixture fixture = new RelatedTopicReuseEvaluationFixture(
				requireInteger(root.required("schemaVersion"), "$.schemaVersion"),
				requireText(root.required("fixtureId"), "$.fixtureId", 3, 100),
				requireEnum(root.required("split"), "$.split", Split.class),
				requireEnum(root.required("labelUnit"), "$.labelUnit", LabelUnit.class),
				requireEnum(root.required("sourcePolicy"), "$.sourcePolicy", SourcePolicy.class),
				requireInstant(root.required("retrievedAt"), "$.retrievedAt"),
				lineages,
				candidates,
				queries);
		validateFixture(fixture);
		return fixture;
	}

	Map<String, Lineage> lineagesByKey() {
		Map<String, Lineage> values = new LinkedHashMap<>();
		lineages.forEach(lineage -> values.put(lineage.key(), lineage));
		return Map.copyOf(values);
	}

	Map<String, Candidate> candidatesByKey() {
		Map<String, Candidate> values = new LinkedHashMap<>();
		candidates.forEach(candidate -> values.put(candidate.key(), candidate));
		return Map.copyOf(values);
	}

	Set<String> targetVisibleKeys() {
		Map<String, Lineage> byKey = lineagesByKey();
		return candidates.stream()
				.filter(candidate -> byKey.get(candidate.lineageKey()).kind().targetVisible())
				.map(Candidate::key)
				.collect(java.util.stream.Collectors.toUnmodifiableSet());
	}

	private static List<Lineage> parseLineages(JsonNode node, String path) {
		JsonNode values = requireArray(node, path);
		List<Lineage> result = new ArrayList<>(values.size());
		for (int index = 0; index < values.size(); index++) {
			String itemPath = path + "[" + index + "]";
			JsonNode value = values.get(index);
			requireExactObject(value, itemPath, LINEAGE_FIELDS);
			result.add(new Lineage(
					requireKey(value.required("key"), itemPath + ".key"),
					requireEnum(value.required("kind"), itemPath + ".kind", LineageKind.class)));
		}
		return List.copyOf(result);
	}

	private static List<Candidate> parseCandidates(JsonNode node, String path) {
		JsonNode values = requireArray(node, path);
		List<Candidate> result = new ArrayList<>(values.size());
		for (int index = 0; index < values.size(); index++) {
			result.add(parseCandidate(values.get(index), path + "[" + index + "]"));
		}
		return List.copyOf(result);
	}

	private static Candidate parseCandidate(JsonNode node, String path) {
		requireExactObject(node, path, CANDIDATE_FIELDS);
		Integer publicationYear = requireNullableInteger(
				node.required("publicationYear"), path + ".publicationYear");
		if (publicationYear != null && (publicationYear < 1000 || publicationYear > 9999)) {
			throw new IllegalArgumentException(path + ".publicationYear is outside 1000..9999");
		}
		Integer citationCount = requireNullableInteger(
				node.required("citationCount"), path + ".citationCount");
		if (citationCount != null && citationCount < 0) {
			throw new IllegalArgumentException(path + ".citationCount must not be negative");
		}
		return new Candidate(
				requireKey(node.required("key"), path + ".key"),
				requireKey(node.required("lineageKey"), path + ".lineageKey"),
				requireText(node.required("title"), path + ".title", 3, 300),
				requireNullableText(node.required("abstractText"), path + ".abstractText", 3, 4000),
				requireNullableText(node.required("venueName"), path + ".venueName", 2, 300),
				publicationYear,
				requireEnum(node.required("documentType"), path + ".documentType", DocumentType.class),
				requireLanguage(node.required("language"), path + ".language"),
				citationCount,
				requireBoolean(node.required("reportedOpenAccess"), path + ".reportedOpenAccess"),
				requireTextArray(node.required("authors"), path + ".authors", 0, 10, 2, 200));
	}

	private static List<Query> parseQueries(JsonNode node, String path) {
		JsonNode values = requireArray(node, path);
		List<Query> result = new ArrayList<>(values.size());
		for (int index = 0; index < values.size(); index++) {
			result.add(parseQuery(values.get(index), path + "[" + index + "]"));
		}
		return List.copyOf(result);
	}

	private static Query parseQuery(JsonNode node, String path) {
		requireExactObject(node, path, QUERY_FIELDS);
		Map<String, Integer> judgments = new LinkedHashMap<>();
		JsonNode judgmentNode = requireObject(node.required("judgments"), path + ".judgments");
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
		List<Adversary> adversaries = new ArrayList<>();
		JsonNode adversaryNodes = requireArray(node.required("adversaries"), path + ".adversaries");
		for (int index = 0; index < adversaryNodes.size(); index++) {
			String adversaryPath = path + ".adversaries[" + index + "]";
			JsonNode adversaryNode = adversaryNodes.get(index);
			requireExactObject(adversaryNode, adversaryPath, ADVERSARY_FIELDS);
			adversaries.add(new Adversary(
					requireKey(
							adversaryNode.required("candidateKey"), adversaryPath + ".candidateKey"),
					requireEnum(
							adversaryNode.required("kind"), adversaryPath + ".kind", AdversaryKind.class),
					requireText(adversaryNode.required("reason"), adversaryPath + ".reason", 10, 300)));
		}
		return new Query(
				requireKey(node.required("key"), path + ".key"),
				requireText(node.required("text"), path + ".text", 3, 500),
				requireEnum(node.required("kind"), path + ".kind", QueryKind.class),
				requireInteger(node.required("cutoff"), path + ".cutoff"),
				parseFilter(node.required("filters"), path + ".filters"),
				judgments,
				adversaries);
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
		int minimumCitations = requireInteger(
				node.required("minimumCitations"), path + ".minimumCitations");
		if (minimumCitations < 0) {
			throw new IllegalArgumentException(path + ".minimumCitations must not be negative");
		}
		return new Filter(
				yearFrom,
				yearTo,
				requireEnumArray(
						node.required("documentTypes"), path + ".documentTypes", DocumentType.class),
				requireBoolean(node.required("openAccessOnly"), path + ".openAccessOnly"),
				minimumCitations,
				requireLanguageArray(node.required("languages"), path + ".languages"));
	}

	private static void validateFixture(RelatedTopicReuseEvaluationFixture fixture) {
		if (fixture.schemaVersion() != 1
				|| !FIXTURE_ID.equals(fixture.fixtureId())
				|| fixture.split() != Split.DEVELOPMENT
				|| fixture.labelUnit() != LabelUnit.CANONICAL_PAPER_TOPIC_RELEVANCE
				|| fixture.sourcePolicy() != SourcePolicy.SYNTHETIC_METADATA_ONLY
				|| !RETRIEVED_AT.equals(fixture.retrievedAt())) {
			throw new IllegalArgumentException("Unexpected related-topic fixture identity or boundary");
		}
		if (fixture.lineages().size() != 11
				|| fixture.candidates().size() != 25
				|| fixture.queries().size() != 5) {
			throw new IllegalArgumentException(
					"The v1 fixture must contain 11 lineages, 25 candidates, and 5 queries");
		}
		Set<String> lineageKeys = uniqueKeys(
				fixture.lineages().stream().map(Lineage::key).toList(), "lineage");
		Set<String> candidateKeys = uniqueKeys(
				fixture.candidates().stream().map(Candidate::key).toList(), "candidate");
		uniqueKeys(fixture.queries().stream().map(Query::key).toList(), "query");

		Map<LineageKind, Integer> lineageCounts = new EnumMap<>(LineageKind.class);
		fixture.lineages().forEach(lineage -> lineageCounts.merge(lineage.kind(), 1, Integer::sum));
		if (!lineageCounts.equals(EXPECTED_LINEAGE_COUNTS)) {
			throw new IllegalArgumentException("Distinct owner histories and collections drifted");
		}
		Set<String> usedLineages = fixture.candidates().stream()
				.map(Candidate::lineageKey)
				.collect(java.util.stream.Collectors.toUnmodifiableSet());
		if (!usedLineages.equals(lineageKeys)) {
			throw new IllegalArgumentException("Every candidate lineage must exist and be used");
		}
		Set<String> targetVisibleKeys = fixture.targetVisibleKeys();
		if (targetVisibleKeys.size() != 19 || candidateKeys.size() - targetVisibleKeys.size() != 6) {
			throw new IllegalArgumentException(
					"The v1 fixture must contain 19 target-visible and 6 ineligible candidates");
		}

		Map<QueryKind, Integer> queryKindCounts = new EnumMap<>(QueryKind.class);
		EnumSet<AdversaryKind> adversaryKinds = EnumSet.noneOf(AdversaryKind.class);
		Set<String> normalizedQueries = new LinkedHashSet<>();
		Map<String, Candidate> candidatesByKey = fixture.candidatesByKey();
		Map<String, Lineage> lineagesByKey = fixture.lineagesByKey();
		for (Query query : fixture.queries()) {
			queryKindCounts.merge(query.kind(), 1, Integer::sum);
			String normalized = query.text().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
			if (!normalizedQueries.add(normalized)) {
				throw new IllegalArgumentException("Query text must be unique after normalization");
			}
			if (query.cutoff() != 10) {
				throw new IllegalArgumentException("Every v1 query cutoff must be 10");
			}
			if (!query.judgments().keySet().equals(targetVisibleKeys)) {
				throw new IllegalArgumentException(
						"Query judgments must cover exactly every target-visible candidate: " + query.key());
			}
			validateQueryJudgments(query);
			validateFilterBoundary(query);
			validateAdversaries(
					query, candidatesByKey, lineagesByKey, candidateKeys, adversaryKinds);
			validateFilteredGoldAndCoverage(query, candidatesByKey);
		}
		if (!queryKindCounts.equals(EXPECTED_QUERY_KIND_COUNTS)) {
			throw new IllegalArgumentException("Opportunity and control episode counts drifted");
		}
		if (!adversaryKinds.equals(EnumSet.allOf(AdversaryKind.class))) {
			throw new IllegalArgumentException("Every frozen adversary kind must be represented");
		}
	}

	private static void validateQueryJudgments(Query query) {
		long relevantCount = query.judgments().values().stream().filter(grade -> grade > 0).count();
		switch (query.kind()) {
			case LEXICAL_BRIDGE_OPPORTUNITY, FILTERED_LEXICAL_BRIDGE_OPPORTUNITY -> {
				if (relevantCount < 2
						|| !query.judgments().containsValue(3)
						|| query.judgments().values().stream().noneMatch(grade -> grade == 1 || grade == 2)) {
					throw new IllegalArgumentException(
							"Every opportunity needs a grade-three anchor and a graded bridge");
				}
			}
			case AUTHOR_NO_RELATED_SIGNAL_CONTROL -> {
				if (relevantCount != 1 || !query.judgments().containsValue(3)) {
					throw new IllegalArgumentException(
							"The author control needs exactly one grade-three target");
				}
			}
			case NO_SEED_FALLBACK_CONTROL -> {
				if (relevantCount != 0) {
					throw new IllegalArgumentException(
							"The no-seed fallback control must have no eligible relevant target");
				}
			}
		}
	}

	private static void validateFilterBoundary(Query query) {
		boolean complete = query.filters().exercisesEveryDimension();
		if (query.kind() == QueryKind.FILTERED_LEXICAL_BRIDGE_OPPORTUNITY) {
			if (!complete) {
				throw new IllegalArgumentException(
						"The filtered opportunity must exercise every filter dimension");
			}
		}
		else if (!query.filters().isEmpty()) {
			throw new IllegalArgumentException("Only the filtered opportunity may declare filters");
		}
	}

	private static void validateFilteredGoldAndCoverage(
			Query query, Map<String, Candidate> candidatesByKey) {
		if (query.kind() != QueryKind.FILTERED_LEXICAL_BRIDGE_OPPORTUNITY) {
			return;
		}
		query.judgments().entrySet().stream()
				.filter(entry -> entry.getValue() > 0)
				.map(entry -> candidatesByKey.get(entry.getKey()))
				.forEach(candidate -> {
					if (!candidateSatisfies(candidate, query.filters())) {
						throw new IllegalArgumentException(
								"Filtered relevant judgments must satisfy every request filter");
					}
				});
		EnumSet<FilterDimension> violatedDimensions = EnumSet.noneOf(FilterDimension.class);
		query.adversaries().stream()
				.filter(adversary -> adversary.kind() == AdversaryKind.FILTER_VIOLATION)
				.map(adversary -> candidatesByKey.get(adversary.candidateKey()))
				.forEach(candidate -> collectViolatedDimensions(
						candidate, query.filters(), violatedDimensions));
		if (!violatedDimensions.equals(EnumSet.allOf(FilterDimension.class))) {
			throw new IllegalArgumentException(
					"Filtered adversaries must cover every request filter dimension");
		}
	}

	private static void collectViolatedDimensions(
			Candidate candidate, Filter filter, EnumSet<FilterDimension> result) {
		if (filter.yearFrom() != null && (candidate.publicationYear() == null
				|| candidate.publicationYear() < filter.yearFrom())) {
			result.add(FilterDimension.YEAR_FROM);
		}
		if (filter.yearTo() != null && (candidate.publicationYear() == null
				|| candidate.publicationYear() > filter.yearTo())) {
			result.add(FilterDimension.YEAR_TO);
		}
		if (!filter.documentTypes().isEmpty()
				&& !filter.documentTypes().contains(candidate.documentType())) {
			result.add(FilterDimension.DOCUMENT_TYPE);
		}
		if (filter.openAccessOnly() && !candidate.reportedOpenAccess()) {
			result.add(FilterDimension.OPEN_ACCESS);
		}
		if ((candidate.citationCount() == null ? 0 : candidate.citationCount())
				< filter.minimumCitations()) {
			result.add(FilterDimension.MINIMUM_CITATIONS);
		}
		if (!filter.languages().isEmpty() && !filter.languages().contains(candidate.language())) {
			result.add(FilterDimension.LANGUAGE);
		}
	}

	private static void validateAdversaries(
			Query query,
			Map<String, Candidate> candidatesByKey,
			Map<String, Lineage> lineagesByKey,
			Set<String> candidateKeys,
			EnumSet<AdversaryKind> representedKinds) {
		if (query.adversaries().isEmpty()) {
			throw new IllegalArgumentException("Every query needs at least one adversary");
		}
		Set<String> adversaryKeys = new LinkedHashSet<>();
		for (Adversary adversary : query.adversaries()) {
			if (!adversaryKeys.add(adversary.candidateKey())) {
				throw new IllegalArgumentException("Duplicate query adversary: " + adversary.candidateKey());
			}
			Candidate candidate = candidatesByKey.get(adversary.candidateKey());
			if (candidate == null || !candidateKeys.contains(candidate.key())) {
				throw new IllegalArgumentException("Adversary references an unknown candidate");
			}
			LineageKind lineageKind = lineagesByKey.get(candidate.lineageKey()).kind();
			switch (adversary.kind()) {
				case OWNER_VISIBLE_TOPIC_DRIFT -> {
					requireTargetGradeZero(query, candidate, lineageKind, adversary.kind());
					if (!query.kind().opportunity()) {
						throw new IllegalArgumentException(
								"Topic-drift adversaries belong only to opportunity queries");
					}
				}
				case FILTER_VIOLATION -> {
					requireTargetGradeZero(query, candidate, lineageKind, adversary.kind());
					if (query.kind() != QueryKind.FILTERED_LEXICAL_BRIDGE_OPPORTUNITY
							|| candidateSatisfies(candidate, query.filters())) {
						throw new IllegalArgumentException(
								"Filter adversaries must fail the filtered opportunity boundary");
					}
				}
				case AUTHOR_SUBSTRING_COLLISION -> {
					requireTargetGradeZero(query, candidate, lineageKind, adversary.kind());
					if (query.kind() != QueryKind.AUTHOR_NO_RELATED_SIGNAL_CONTROL) {
						throw new IllegalArgumentException(
								"Author substring collisions belong only to the author control");
					}
				}
				case OTHER_OWNER_HIGHER_RELATED_SCORE -> {
					if (lineageKind != LineageKind.OTHER_OWNER_SEARCH
							&& lineageKind != LineageKind.OTHER_OWNER_COLLECTION) {
						throw new IllegalArgumentException(
								"Other-owner adversaries must use another owner's lineage");
					}
				}
				case CATALOG_ONLY_HIGHER_RELATED_SCORE -> {
					if (lineageKind != LineageKind.CATALOG_ONLY) {
						throw new IllegalArgumentException(
								"Catalog-only adversaries must use the catalog-only lineage");
					}
				}
			}
			representedKinds.add(adversary.kind());
		}
	}

	private static void requireTargetGradeZero(
			Query query, Candidate candidate, LineageKind lineageKind, AdversaryKind adversaryKind) {
		if (!lineageKind.targetVisible()
				|| query.judgments().getOrDefault(candidate.key(), -1) != 0) {
			throw new IllegalArgumentException(
					adversaryKind + " must reference a target-visible grade-zero candidate");
		}
	}

	private static boolean candidateSatisfies(Candidate candidate, Filter filter) {
		return (filter.yearFrom() == null
					|| candidate.publicationYear() != null
					&& candidate.publicationYear() >= filter.yearFrom())
				&& (filter.yearTo() == null
						|| candidate.publicationYear() != null
						&& candidate.publicationYear() <= filter.yearTo())
				&& (filter.documentTypes().isEmpty()
						|| filter.documentTypes().contains(candidate.documentType()))
				&& (!filter.openAccessOnly() || candidate.reportedOpenAccess())
				&& (candidate.citationCount() == null ? 0 : candidate.citationCount())
						>= filter.minimumCitations()
				&& (filter.languages().isEmpty() || filter.languages().contains(candidate.language()));
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
			throw new IllegalArgumentException(
					path + " must contain exactly " + fields + "; found " + actual);
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
		String key = requireTextValue(value, path, 3, 100);
		if (!SAFE_KEY.matcher(key).matches()) {
			throw new IllegalArgumentException(path + " must be a lowercase safe slug");
		}
		return key;
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
		JsonNode values = requireArray(node, path);
		if (values.size() < minimumSize || values.size() > maximumSize) {
			throw new IllegalArgumentException(path + " has an invalid item count");
		}
		List<String> result = new ArrayList<>(values.size());
		for (int index = 0; index < values.size(); index++) {
			result.add(requireText(
					values.get(index), path + "[" + index + "]", minimumLength, maximumLength));
		}
		if (result.stream().distinct().count() != result.size()) {
			throw new IllegalArgumentException(path + " must not contain duplicates");
		}
		return List.copyOf(result);
	}

	private static List<String> requireLanguageArray(JsonNode node, String path) {
		JsonNode values = requireArray(node, path);
		List<String> result = new ArrayList<>(values.size());
		for (int index = 0; index < values.size(); index++) {
			result.add(requireLanguage(values.get(index), path + "[" + index + "]"));
		}
		if (result.stream().distinct().count() != result.size()) {
			throw new IllegalArgumentException(path + " must not contain duplicates");
		}
		return List.copyOf(result);
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
		JsonNode values = requireArray(node, path);
		List<E> result = new ArrayList<>(values.size());
		for (int index = 0; index < values.size(); index++) {
			result.add(requireEnum(values.get(index), path + "[" + index + "]", enumType));
		}
		if (result.stream().distinct().count() != result.size()) {
			throw new IllegalArgumentException(path + " must not contain duplicates");
		}
		return List.copyOf(result);
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

	enum LineageKind {
		TARGET_OWNER_SEARCH,
		TARGET_OWNER_COLLECTION,
		OTHER_OWNER_SEARCH,
		OTHER_OWNER_COLLECTION,
		CATALOG_ONLY;

		boolean targetVisible() {
			return this == TARGET_OWNER_SEARCH || this == TARGET_OWNER_COLLECTION;
		}
	}

	enum QueryKind {
		LEXICAL_BRIDGE_OPPORTUNITY,
		FILTERED_LEXICAL_BRIDGE_OPPORTUNITY,
		AUTHOR_NO_RELATED_SIGNAL_CONTROL,
		NO_SEED_FALLBACK_CONTROL;

		boolean opportunity() {
			return this == LEXICAL_BRIDGE_OPPORTUNITY
					|| this == FILTERED_LEXICAL_BRIDGE_OPPORTUNITY;
		}
	}

	enum AdversaryKind {
		OWNER_VISIBLE_TOPIC_DRIFT,
		OTHER_OWNER_HIGHER_RELATED_SCORE,
		CATALOG_ONLY_HIGHER_RELATED_SCORE,
		FILTER_VIOLATION,
		AUTHOR_SUBSTRING_COLLISION
	}

	private enum FilterDimension {
		YEAR_FROM,
		YEAR_TO,
		DOCUMENT_TYPE,
		OPEN_ACCESS,
		MINIMUM_CITATIONS,
		LANGUAGE
	}

	record Lineage(String key, LineageKind kind) {

		Lineage {
			key = requireKeyValue(key, "lineage.key");
			kind = Objects.requireNonNull(kind, "kind");
		}
	}

	record Candidate(
			String key,
			String lineageKey,
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
			lineageKey = requireKeyValue(lineageKey, "candidate.lineageKey");
			title = requireTextValue(title, "candidate.title", 3, 300);
			documentType = Objects.requireNonNull(documentType, "documentType");
			language = requireTextValue(language, "candidate.language", 2, 8);
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

		boolean exercisesEveryDimension() {
			return yearFrom != null
					&& yearTo != null
					&& !documentTypes.isEmpty()
					&& openAccessOnly
					&& minimumCitations > 0
					&& !languages.isEmpty();
		}

		boolean isEmpty() {
			return yearFrom == null
					&& yearTo == null
					&& documentTypes.isEmpty()
					&& !openAccessOnly
					&& minimumCitations == 0
					&& languages.isEmpty();
		}
	}

	record Query(
			String key,
			String text,
			QueryKind kind,
			int cutoff,
			Filter filters,
			Map<String, Integer> judgments,
			List<Adversary> adversaries) {

		Query {
			key = requireKeyValue(key, "query.key");
			text = requireTextValue(text, "query.text", 3, 500);
			kind = Objects.requireNonNull(kind, "kind");
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

	record BoundFixture(RelatedTopicReuseEvaluationFixture fixture, String sha256) {

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
