package com.openscholar.search.internal.persistence;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

import com.openscholar.paper.DocumentType;
import com.openscholar.provider.ProviderSearchQuery;
import org.springframework.core.io.ClassPathResource;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

record ProviderQualityLiveQuerySet(
		int schemaVersion,
		String querySetId,
		String sourcePolicy,
		int pageSize,
		List<Query> queries) {

	static final String RESOURCE_PATH =
			"search/provider-quality/europe-pmc-live-queries-v1.json";
	private static final String EXPECTED_QUERY_SET_ID = "europe-pmc-live-queries-v1";
	private static final String EXPECTED_SOURCE_POLICY =
			"AUTHOR_WRITTEN_TOPICS_WITHOUT_RELEVANCE_LABELS";
	private static final int EXPECTED_QUERY_COUNT = 8;
	private static final int MAX_RESOURCE_BYTES = 64 * 1_024;
	private static final Set<String> ROOT_FIELDS = Set.of(
			"schemaVersion", "querySetId", "sourcePolicy", "pageSize", "queries");
	private static final Set<String> QUERY_FIELDS = Set.of("key", "query");
	private static final Pattern QUERY_KEY = Pattern.compile("^[a-z0-9][a-z0-9-]{1,78}[a-z0-9]$");

	ProviderQualityLiveQuerySet {
		queries = List.copyOf(Objects.requireNonNull(queries, "queries"));
	}

	static ProviderQualityLiveQuerySet load(ObjectMapper objectMapper, String resourcePath)
			throws IOException {
		Objects.requireNonNull(objectMapper, "objectMapper");
		ClassPathResource resource = new ClassPathResource(
				requireNonBlank(resourcePath, "resourcePath", 1, 500));
		try (InputStream input = resource.getInputStream()) {
			byte[] bytes = input.readNBytes(MAX_RESOURCE_BYTES + 1);
			return parse(objectMapper, bytes);
		}
	}

	static ProviderQualityLiveQuerySet parse(ObjectMapper objectMapper, byte[] bytes)
			throws IOException {
		Objects.requireNonNull(objectMapper, "objectMapper");
		Objects.requireNonNull(bytes, "bytes");
		if (bytes.length == 0 || bytes.length > MAX_RESOURCE_BYTES) {
			throw new IllegalArgumentException(
					"query-set resource must contain 1 through " + MAX_RESOURCE_BYTES + " bytes");
		}
		JsonNode root = objectMapper.reader()
				.with(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
				.with(
						DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY,
						DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
				.readTree(bytes);
		return parse(root);
	}

	static ProviderQualityLiveQuerySet parse(JsonNode root) {
		requireExactObject(root, "$", ROOT_FIELDS);
		int schemaVersion = requireInteger(root.required("schemaVersion"), "$.schemaVersion");
		String querySetId = requireText(root.required("querySetId"), "$.querySetId", 3, 100);
		String sourcePolicy = requireText(root.required("sourcePolicy"), "$.sourcePolicy", 3, 100);
		int pageSize = requireInteger(root.required("pageSize"), "$.pageSize");
		JsonNode queryNodes = root.required("queries");
		if (!queryNodes.isArray()) {
			throw new IllegalArgumentException("$.queries must be an array");
		}

		if (schemaVersion != 1) {
			throw new IllegalArgumentException("schemaVersion must be 1");
		}
		if (!EXPECTED_QUERY_SET_ID.equals(querySetId)) {
			throw new IllegalArgumentException(
					"querySetId must be " + EXPECTED_QUERY_SET_ID);
		}
		if (!EXPECTED_SOURCE_POLICY.equals(sourcePolicy)) {
			throw new IllegalArgumentException(
					"sourcePolicy must be " + EXPECTED_SOURCE_POLICY);
		}
		if (pageSize < 1 || pageSize > 20) {
			throw new IllegalArgumentException("pageSize must be an integer from 1 through 20");
		}
		if (queryNodes.size() != EXPECTED_QUERY_COUNT) {
			throw new IllegalArgumentException("query set must contain exactly eight queries");
		}

		List<Query> queries = java.util.stream.IntStream.range(0, queryNodes.size())
				.mapToObj(index -> parseQuery(queryNodes.get(index), index))
				.toList();
		Set<String> keys = new HashSet<>();
		Set<String> normalizedQueries = new HashSet<>();
		for (Query query : queries) {
			if (!keys.add(query.key())) {
				throw new IllegalArgumentException("duplicate query key: " + query.key());
			}
			String normalized = query.text().toLowerCase(Locale.ROOT)
					.replaceAll("\\s+", " ");
			if (!normalizedQueries.add(normalized)) {
				throw new IllegalArgumentException("duplicate normalized query text");
			}
		}
		return new ProviderQualityLiveQuerySet(
				schemaVersion, querySetId, sourcePolicy, pageSize, queries);
	}

	List<QueryCommand> commands() {
		return queries.stream()
				.map(query -> new QueryCommand(query.key(), new ProviderSearchQuery(
						query.text(),
						null,
						null,
						Set.of(DocumentType.ARTICLE),
						false,
						0,
						Set.of(),
						pageSize,
						"*")))
				.toList();
	}

	private static Query parseQuery(JsonNode node, int index) {
		String path = "$.queries[" + index + "]";
		requireExactObject(node, path, QUERY_FIELDS);
		String key = requireText(node.required("key"), path + ".key", 3, 80);
		if (!QUERY_KEY.matcher(key).matches()) {
			throw new IllegalArgumentException(path + ".key must be a canonical lowercase slug");
		}
		String query = requireText(node.required("query"), path + ".query", 3, 500);
		return new Query(key, query);
	}

	private static void requireExactObject(JsonNode node, String path, Set<String> expectedFields) {
		if (node == null || !node.isObject()) {
			throw new IllegalArgumentException(path + " must be an object");
		}
		Set<String> actual = new LinkedHashSet<>(node.propertyNames());
		Set<String> unknown = new LinkedHashSet<>(actual);
		unknown.removeAll(expectedFields);
		if (!unknown.isEmpty()) {
			throw new IllegalArgumentException("Unknown keys at " + path + ": " + unknown);
		}
		Set<String> missing = new LinkedHashSet<>(expectedFields);
		missing.removeAll(actual);
		if (!missing.isEmpty()) {
			throw new IllegalArgumentException("Missing keys at " + path + ": " + missing);
		}
	}

	private static int requireInteger(JsonNode node, String path) {
		if (!node.isInt()) {
			throw new IllegalArgumentException(path + " must be an integer");
		}
		return node.asInt();
	}

	private static String requireText(JsonNode node, String path, int minimum, int maximum) {
		if (!node.isString()) {
			throw new IllegalArgumentException(path + " must be a string");
		}
		String value = node.asString();
		if (!value.equals(value.strip()) || value.length() < minimum || value.length() > maximum) {
			throw new IllegalArgumentException(
					path + " must contain " + minimum + " through " + maximum
							+ " characters without surrounding whitespace");
		}
		return value;
	}

	private static String requireNonBlank(String value, String field, int minimum, int maximum) {
		if (value == null || !value.equals(value.strip())
				|| value.length() < minimum || value.length() > maximum) {
			throw new IllegalArgumentException(field + " must be a bounded non-blank string");
		}
		return value;
	}

	record Query(String key, String text) {
	}

	record QueryCommand(String key, ProviderSearchQuery command) {

		QueryCommand {
			Objects.requireNonNull(key, "key");
			Objects.requireNonNull(command, "command");
		}
	}
}
