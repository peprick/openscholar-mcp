package com.openscholar.paper.internal.persistence;

import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.springframework.core.io.ClassPathResource;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

record MultilingualLexicalEvaluationFixture(
		int schemaVersion,
		String fixtureId,
		String policyId,
		Split split,
		SourcePolicy sourcePolicy,
		List<Candidate> candidates,
		List<Query> queries) {

	static final String RESOURCE_PATH =
			"search/relevance/multilingual-lexical-development-v1.json";
	static final String FIXTURE_ID = "multilingual-lexical-development-v1";
	static final String POLICY_ID = "multilingual-lexical-policy-v1";
	static final String FIXTURE_SHA256 =
			"366f49cd7278fc0d015adffc173632ae3384c863da0aff7270656317bccf1471";
	static final int MAXIMUM_INPUT_BYTES = 128 * 1024;
	private static final Pattern SAFE_KEY = Pattern.compile("[a-z0-9]+(?:-[a-z0-9]+)*");
	private static final Pattern LANGUAGE = Pattern.compile("[a-z]{2,8}");
	private static final Set<String> EXPECTED_LANGUAGES = Set.of("en", "de", "fr", "es", "ja");
	private static final Set<String> ROOT_FIELDS = Set.of(
			"schemaVersion", "fixtureId", "policyId", "split", "sourcePolicy",
			"candidates", "queries");
	private static final Set<String> CANDIDATE_FIELDS = Set.of(
			"key", "language", "title", "abstractText", "venueName");
	private static final Set<String> QUERY_FIELDS = Set.of(
			"key", "language", "text", "cutoff", "judgments");

	MultilingualLexicalEvaluationFixture {
		fixtureId = requireTextValue(fixtureId, "fixtureId", 3, 100);
		policyId = requireTextValue(policyId, "policyId", 3, 100);
		split = Objects.requireNonNull(split, "split");
		sourcePolicy = Objects.requireNonNull(sourcePolicy, "sourcePolicy");
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
			return parseBound(objectMapper, readBounded(input, MAXIMUM_INPUT_BYTES, "fixture"));
		}
	}

	static BoundFixture parseBound(ObjectMapper objectMapper, byte[] bytes) throws IOException {
		Objects.requireNonNull(objectMapper, "objectMapper");
		Objects.requireNonNull(bytes, "bytes");
		if (bytes.length < 1 || bytes.length > MAXIMUM_INPUT_BYTES) {
			throw new IllegalArgumentException("fixture must contain 1 through 131072 bytes");
		}
		JsonNode root = objectMapper.reader()
				.with(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
				.with(
						DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY,
						DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
				.readTree(bytes);
		return new BoundFixture(parse(root), sha256(bytes));
	}

	static MultilingualLexicalEvaluationFixture parse(JsonNode root) {
		requireExactObject(root, "$", ROOT_FIELDS);
		List<Candidate> candidates = new ArrayList<>();
		JsonNode candidateNodes = requireArray(root.required("candidates"), "$.candidates");
		for (int index = 0; index < candidateNodes.size(); index++) {
			candidates.add(parseCandidate(candidateNodes.get(index), "$.candidates[" + index + "]"));
		}
		List<Query> queries = new ArrayList<>();
		JsonNode queryNodes = requireArray(root.required("queries"), "$.queries");
		for (int index = 0; index < queryNodes.size(); index++) {
			queries.add(parseQuery(queryNodes.get(index), "$.queries[" + index + "]"));
		}
		MultilingualLexicalEvaluationFixture fixture = new MultilingualLexicalEvaluationFixture(
				requireInteger(root.required("schemaVersion"), "$.schemaVersion"),
				requireText(root.required("fixtureId"), "$.fixtureId", 3, 100),
				requireText(root.required("policyId"), "$.policyId", 3, 100),
				requireEnum(root.required("split"), "$.split", Split.class),
				requireEnum(root.required("sourcePolicy"), "$.sourcePolicy", SourcePolicy.class),
				candidates,
				queries);
		validateFixture(fixture);
		return fixture;
	}

	Map<String, Candidate> candidatesByKey() {
		Map<String, Candidate> result = new LinkedHashMap<>();
		candidates.forEach(candidate -> result.put(candidate.key(), candidate));
		return Map.copyOf(result);
	}

	List<Candidate> candidatesFor(String language) {
		return candidates.stream().filter(candidate -> candidate.language().equals(language)).toList();
	}

	private static Candidate parseCandidate(JsonNode node, String path) {
		requireExactObject(node, path, CANDIDATE_FIELDS);
		return new Candidate(
				requireKey(node.required("key"), path + ".key"),
				requireLanguage(node.required("language"), path + ".language"),
				requireText(node.required("title"), path + ".title", 3, 300),
				requireNullableText(node.required("abstractText"), path + ".abstractText", 3, 4000),
				requireNullableText(node.required("venueName"), path + ".venueName", 2, 300));
	}

	private static Query parseQuery(JsonNode node, String path) {
		requireExactObject(node, path, QUERY_FIELDS);
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
			if (judgments.put(candidateKey, grade) != null) {
				throw new IllegalArgumentException(path + ".judgments contains duplicate keys");
			}
		}
		return new Query(
				requireKey(node.required("key"), path + ".key"),
				requireLanguage(node.required("language"), path + ".language"),
				requireText(node.required("text"), path + ".text", 2, 500),
				requireInteger(node.required("cutoff"), path + ".cutoff"),
				judgments);
	}

	private static void validateFixture(MultilingualLexicalEvaluationFixture fixture) {
		if (fixture.schemaVersion() != 1
				|| !FIXTURE_ID.equals(fixture.fixtureId())
				|| !POLICY_ID.equals(fixture.policyId())
				|| fixture.split() != Split.DEVELOPMENT
				|| fixture.sourcePolicy() != SourcePolicy.SYNTHETIC_METADATA_ONLY) {
			throw new IllegalArgumentException("Unexpected multilingual lexical fixture identity");
		}
		if (fixture.candidates().size() != 15 || fixture.queries().size() != 5) {
			throw new IllegalArgumentException("The v1 fixture must contain 15 candidates and 5 queries");
		}
		Set<String> candidateKeys = unique(
				fixture.candidates().stream().map(Candidate::key).toList(), "candidate keys");
		Set<String> queryKeys = unique(
				fixture.queries().stream().map(Query::key).toList(), "query keys");
		if (candidateKeys.size() != 15 || queryKeys.size() != 5) {
			throw new IllegalArgumentException("Fixture keys drifted");
		}
		Map<String, List<Candidate>> candidatesByLanguage = fixture.candidates().stream()
				.collect(Collectors.groupingBy(Candidate::language));
		if (!candidatesByLanguage.keySet().equals(EXPECTED_LANGUAGES)
				|| candidatesByLanguage.values().stream().anyMatch(values -> values.size() != 3)) {
			throw new IllegalArgumentException("Each required language must have exactly three candidates");
		}
		Set<String> queryLanguages = unique(
				fixture.queries().stream().map(Query::language).toList(), "query languages");
		if (!queryLanguages.equals(EXPECTED_LANGUAGES)) {
			throw new IllegalArgumentException("Every required language must have exactly one query");
		}
		for (Query query : fixture.queries()) {
			Set<String> languageCandidateKeys = candidatesByLanguage.get(query.language()).stream()
					.map(Candidate::key)
					.collect(Collectors.toUnmodifiableSet());
			if (query.cutoff() != 3 || !query.judgments().keySet().equals(languageCandidateKeys)) {
				throw new IllegalArgumentException(
						"Each query must judge exactly its three language-scoped candidates");
			}
			long relevant = query.judgments().values().stream().filter(grade -> grade > 0).count();
			long negatives = query.judgments().values().stream().filter(grade -> grade == 0).count();
			if (relevant != 2 || negatives != 1 || !query.judgments().containsValue(3)) {
				throw new IllegalArgumentException(
						"Each query needs two relevant candidates, one negative, and a grade-three target");
			}
		}
	}

	static byte[] readBounded(InputStream input, int maximumBytes, String kind) throws IOException {
		byte[] bytes = input.readNBytes(maximumBytes + 1);
		if (bytes.length < 1 || bytes.length > maximumBytes) {
			throw new IllegalArgumentException(
					kind + " must contain 1 through " + maximumBytes + " bytes");
		}
		return bytes;
	}

	static String sha256(byte[] bytes) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
		}
		catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException("SHA-256 is unavailable", exception);
		}
	}

	static void requireExactObject(JsonNode node, String path, Set<String> fields) {
		requireObject(node, path);
		Set<String> actual = new LinkedHashSet<>(node.propertyNames());
		if (!actual.equals(fields)) {
			throw new IllegalArgumentException(
					path + " must contain exactly " + fields + "; found " + actual);
		}
	}

	static JsonNode requireObject(JsonNode node, String path) {
		if (node == null || !node.isObject()) {
			throw new IllegalArgumentException(path + " must be an object");
		}
		return node;
	}

	static JsonNode requireArray(JsonNode node, String path) {
		if (node == null || !node.isArray()) {
			throw new IllegalArgumentException(path + " must be an array");
		}
		return node;
	}

	static int requireInteger(JsonNode node, String path) {
		if (node == null || !node.isIntegralNumber() || !node.canConvertToInt()) {
			throw new IllegalArgumentException(path + " must be an integer");
		}
		return node.intValue();
	}

	static double requireNumber(JsonNode node, String path) {
		if (node == null || !node.isNumber()) {
			throw new IllegalArgumentException(path + " must be numeric");
		}
		double value = node.doubleValue();
		if (!Double.isFinite(value)) {
			throw new IllegalArgumentException(path + " must be finite");
		}
		return value;
	}

	static boolean requireBoolean(JsonNode node, String path) {
		if (node == null || !node.isBoolean()) {
			throw new IllegalArgumentException(path + " must be a boolean");
		}
		return node.booleanValue();
	}

	static String requireText(JsonNode node, String path, int minimum, int maximum) {
		if (node == null || !node.isTextual()) {
			throw new IllegalArgumentException(path + " must be text");
		}
		return requireTextValue(node.asString(), path, minimum, maximum);
	}

	static String requireNullableText(JsonNode node, String path, int minimum, int maximum) {
		return node != null && node.isNull() ? null : requireText(node, path, minimum, maximum);
	}

	static String requireTextValue(String value, String path, int minimum, int maximum) {
		if (value == null || !value.equals(value.strip())
				|| value.length() < minimum || value.length() > maximum
				|| !Normalizer.isNormalized(value, Normalizer.Form.NFC)
				|| value.codePoints().anyMatch(Character::isISOControl)) {
			throw new IllegalArgumentException(
					path + " must be bounded NFC text without surrounding whitespace or controls");
		}
		return value;
	}

	static String requireKey(JsonNode node, String path) {
		return requireKeyValue(requireText(node, path, 3, 100), path);
	}

	static String requireKeyValue(String value, String path) {
		String key = requireTextValue(value, path, 3, 100);
		if (!SAFE_KEY.matcher(key).matches()) {
			throw new IllegalArgumentException(path + " must be a lowercase hyphenated key");
		}
		return key;
	}

	static String requireLanguage(JsonNode node, String path) {
		String language = requireText(node, path, 2, 8);
		if (!LANGUAGE.matcher(language).matches()) {
			throw new IllegalArgumentException(path + " must be a lowercase language code");
		}
		return language;
	}

	static <E extends Enum<E>> E requireEnum(JsonNode node, String path, Class<E> type) {
		String value = requireText(node, path, 1, 100);
		try {
			return Enum.valueOf(type, value);
		}
		catch (IllegalArgumentException exception) {
			throw new IllegalArgumentException(path + " contains an unsupported value", exception);
		}
	}

	static List<String> requireTextArray(
			JsonNode node, String path, int minimumSize, int maximumSize) {
		JsonNode array = requireArray(node, path);
		if (array.size() < minimumSize || array.size() > maximumSize) {
			throw new IllegalArgumentException(path + " has an invalid size");
		}
		List<String> result = new ArrayList<>();
		for (int index = 0; index < array.size(); index++) {
			result.add(requireText(array.get(index), path + "[" + index + "]", 1, 200));
		}
		unique(result, path);
		return List.copyOf(result);
	}

	static Set<String> unique(List<String> values, String path) {
		Set<String> result = new LinkedHashSet<>(values);
		if (result.size() != values.size()) {
			throw new IllegalArgumentException(path + " must not contain duplicates");
		}
		return Set.copyOf(result);
	}

	enum Split {
		DEVELOPMENT
	}

	enum SourcePolicy {
		SYNTHETIC_METADATA_ONLY
	}

	record Candidate(
			String key,
			String language,
			String title,
			String abstractText,
			String venueName) {

		Candidate {
			key = requireKeyValue(key, "candidate.key");
			language = requireTextValue(language, "candidate.language", 2, 8);
			title = requireTextValue(title, "candidate.title", 3, 300);
		}
	}

	record Query(
			String key,
			String language,
			String text,
			int cutoff,
			Map<String, Integer> judgments) {

		Query {
			key = requireKeyValue(key, "query.key");
			language = requireTextValue(language, "query.language", 2, 8);
			text = requireTextValue(text, "query.text", 2, 500);
			judgments = Map.copyOf(Objects.requireNonNull(judgments, "judgments"));
		}
	}

	record BoundFixture(MultilingualLexicalEvaluationFixture fixture, String sha256) {

		BoundFixture {
			fixture = Objects.requireNonNull(fixture, "fixture");
			sha256 = Objects.requireNonNull(sha256, "sha256");
		}

		void validateReference(String expectedFixtureId, String expectedSha256) {
			if (!fixture.fixtureId().equals(expectedFixtureId) || !sha256.equals(expectedSha256)) {
				throw new IllegalArgumentException("Fixture identity or digest does not match its reference");
			}
		}
	}
}
