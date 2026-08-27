package com.openscholar.search.internal.persistence;

import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.Channels;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

import com.openscholar.search.internal.persistence.ProviderQualityMetrics.MetadataField;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Strict, evaluation-only contract for judgments authored independently of a
 * comparative capture's provenance and scenario output. This loader validates
 * packet structure and internal references; binding the declared evidence and
 * scoring-policy digests to files is deliberately performed by the later
 * offline scorer.
 */
record ProviderQualityComparativeJudgments(
		int schemaVersion,
		String protocolId,
		String evidenceId,
		String evidenceManifestSha256,
		String querySetId,
		String querySetSha256,
		String scoringPolicyId,
		String scoringPolicySha256,
		String reviewPacketSha256,
		String independenceAttestation,
		List<QueryJudgments> queries) {

	static final int MAX_INPUT_BYTES = 1024 * 1024;
	static final int MAX_QUERIES = 50;
	static final int MAX_CANDIDATES_PER_QUERY = 40;
	static final String PROTOCOL_ID = "provider-quality-independent-judgments-v2";
	static final String INDEPENDENCE_ATTESTATION =
			"AUTHORED_WITHOUT_PROVENANCE_OR_SCENARIO_OUTPUT";

	private static final Pattern SAFE_SLUG =
			Pattern.compile("^[a-z0-9][a-z0-9-]{1,126}[a-z0-9]$");
	private static final Pattern QUERY_LOCAL_SLUG =
			Pattern.compile("^[a-z0-9][a-z0-9-]{1,78}[a-z0-9]$");
	private static final Pattern SHA256 = Pattern.compile("^[0-9a-f]{64}$");
	private static final Pattern REASON_CODE =
			Pattern.compile("^[A-Z][A-Z0-9_]{1,78}[A-Z0-9]$");

	private static final Set<String> ROOT_FIELDS = Set.of(
			"schemaVersion",
			"protocolId",
			"evidenceId",
			"evidenceManifestSha256",
			"querySetId",
			"querySetSha256",
			"scoringPolicyId",
			"scoringPolicySha256",
			"reviewPacketSha256",
			"independenceAttestation",
			"queries");
	private static final Set<String> QUERY_FIELDS = Set.of(
			"queryKey", "goldPapers", "mustSeparatePairs");
	private static final Set<String> GOLD_FIELDS = Set.of(
			"goldPaperKey", "reviewKeys", "relevanceGrade", "expectedFields");
	private static final Set<String> MUST_SEPARATE_FIELDS = Set.of(
			"leftReviewKey", "rightReviewKey", "reasonCode");

	ProviderQualityComparativeJudgments {
		queries = List.copyOf(Objects.requireNonNull(queries, "queries"));
		validateRoot(
				schemaVersion,
				protocolId,
				evidenceId,
				evidenceManifestSha256,
				querySetId,
				querySetSha256,
				scoringPolicyId,
				scoringPolicySha256,
				reviewPacketSha256,
				independenceAttestation,
				queries);
	}

	static ProviderQualityComparativeJudgments load(ObjectMapper objectMapper, Path path)
			throws IOException {
		return loadBound(objectMapper, path).judgments();
	}

	static BoundJudgments loadBound(ObjectMapper objectMapper, Path path)
			throws IOException {
		Objects.requireNonNull(path, "path");
		if (Files.isSymbolicLink(path)
				|| !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
			throw new IOException("judgment packet must be a real regular file");
		}
		try (SeekableByteChannel channel = Files.newByteChannel(
				path, Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS));
				InputStream input = Channels.newInputStream(channel)) {
			byte[] bytes = input.readNBytes(MAX_INPUT_BYTES + 1);
			return parseBound(objectMapper, bytes);
		}
	}

	static ProviderQualityComparativeJudgments parse(ObjectMapper objectMapper, byte[] bytes)
			throws IOException {
		return parseBound(objectMapper, bytes).judgments();
	}

	static BoundJudgments parseBound(ObjectMapper objectMapper, byte[] bytes)
			throws IOException {
		Objects.requireNonNull(objectMapper, "objectMapper");
		Objects.requireNonNull(bytes, "bytes");
		if (bytes.length == 0 || bytes.length > MAX_INPUT_BYTES) {
			throw new IllegalArgumentException(
					"judgment packet must contain 1 through " + MAX_INPUT_BYTES + " bytes");
		}
		JsonNode root = objectMapper.reader()
				.with(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
				.with(
						DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY,
						DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
				.readTree(bytes);
		return new BoundJudgments(parse(root), sha256(bytes));
	}

	static ProviderQualityComparativeJudgments parse(JsonNode root) {
		requireExactObject(root, "$", ROOT_FIELDS);
		JsonNode queryNodes = requireArray(root.required("queries"), "$.queries");
		if (queryNodes.isEmpty() || queryNodes.size() > MAX_QUERIES) {
			throw new IllegalArgumentException(
					"$.queries must contain 1 through " + MAX_QUERIES + " entries");
		}

		List<QueryJudgments> queries = new ArrayList<>(queryNodes.size());
		for (int index = 0; index < queryNodes.size(); index++) {
			queries.add(parseQuery(queryNodes.get(index), index));
		}
		return new ProviderQualityComparativeJudgments(
				requireInteger(root.required("schemaVersion"), "$.schemaVersion"),
				requireString(root.required("protocolId"), "$.protocolId"),
				requireString(root.required("evidenceId"), "$.evidenceId"),
				requireString(
						root.required("evidenceManifestSha256"), "$.evidenceManifestSha256"),
				requireString(root.required("querySetId"), "$.querySetId"),
				requireString(root.required("querySetSha256"), "$.querySetSha256"),
				requireString(root.required("scoringPolicyId"), "$.scoringPolicyId"),
				requireString(root.required("scoringPolicySha256"), "$.scoringPolicySha256"),
				requireString(root.required("reviewPacketSha256"), "$.reviewPacketSha256"),
				requireString(
						root.required("independenceAttestation"), "$.independenceAttestation"),
				queries);
	}

	Map<String, QueryJudgments> queriesByKey() {
		Map<String, QueryJudgments> result = new LinkedHashMap<>();
		queries.forEach(query -> result.put(query.queryKey(), query));
		return Collections.unmodifiableMap(result);
	}

	private static QueryJudgments parseQuery(JsonNode node, int queryIndex) {
		String path = "$.queries[" + queryIndex + "]";
		requireExactObject(node, path, QUERY_FIELDS);
		JsonNode goldNodes = requireArray(node.required("goldPapers"), path + ".goldPapers");
		JsonNode pairNodes = requireArray(
				node.required("mustSeparatePairs"), path + ".mustSeparatePairs");
		List<GoldPaper> goldPapers = new ArrayList<>(goldNodes.size());
		for (int index = 0; index < goldNodes.size(); index++) {
			goldPapers.add(parseGoldPaper(
					goldNodes.get(index), path + ".goldPapers[" + index + "]"));
		}
		List<MustSeparatePair> pairs = new ArrayList<>(pairNodes.size());
		for (int index = 0; index < pairNodes.size(); index++) {
			pairs.add(parseMustSeparatePair(
					pairNodes.get(index), path + ".mustSeparatePairs[" + index + "]"));
		}
		return new QueryJudgments(
				requireString(node.required("queryKey"), path + ".queryKey"),
				goldPapers,
				pairs);
	}

	private static GoldPaper parseGoldPaper(JsonNode node, String path) {
		requireExactObject(node, path, GOLD_FIELDS);
		JsonNode reviewKeyNodes = requireArray(node.required("reviewKeys"), path + ".reviewKeys");
		List<String> reviewKeys = new ArrayList<>(reviewKeyNodes.size());
		for (int index = 0; index < reviewKeyNodes.size(); index++) {
			reviewKeys.add(requireString(
					reviewKeyNodes.get(index), path + ".reviewKeys[" + index + "]"));
		}
		JsonNode expectedFieldNodes = requireArray(
				node.required("expectedFields"), path + ".expectedFields");
		List<MetadataField> expectedFields = new ArrayList<>(expectedFieldNodes.size());
		for (int index = 0; index < expectedFieldNodes.size(); index++) {
			String field = requireString(
					expectedFieldNodes.get(index), path + ".expectedFields[" + index + "]");
			try {
				expectedFields.add(MetadataField.valueOf(field));
			}
			catch (IllegalArgumentException exception) {
				throw new IllegalArgumentException(
						path + ".expectedFields contains unsupported field " + field, exception);
			}
		}
		return new GoldPaper(
				requireString(node.required("goldPaperKey"), path + ".goldPaperKey"),
				reviewKeys,
				requireInteger(node.required("relevanceGrade"), path + ".relevanceGrade"),
				expectedFields);
	}

	private static MustSeparatePair parseMustSeparatePair(JsonNode node, String path) {
		requireExactObject(node, path, MUST_SEPARATE_FIELDS);
		return new MustSeparatePair(
				requireString(node.required("leftReviewKey"), path + ".leftReviewKey"),
				requireString(node.required("rightReviewKey"), path + ".rightReviewKey"),
				requireString(node.required("reasonCode"), path + ".reasonCode"));
	}

	private static void validateRoot(
			int schemaVersion,
			String protocolId,
			String evidenceId,
			String evidenceManifestSha256,
			String querySetId,
			String querySetSha256,
			String scoringPolicyId,
			String scoringPolicySha256,
			String reviewPacketSha256,
			String independenceAttestation,
			List<QueryJudgments> queries) {
		if (schemaVersion != 2) {
			throw new IllegalArgumentException("schemaVersion must be 2");
		}
		if (!PROTOCOL_ID.equals(protocolId)) {
			throw new IllegalArgumentException("protocolId must be " + PROTOCOL_ID);
		}
		requireSlug(evidenceId, "evidenceId", SAFE_SLUG);
		requireSha256(evidenceManifestSha256, "evidenceManifestSha256");
		requireSlug(querySetId, "querySetId", SAFE_SLUG);
		requireSha256(querySetSha256, "querySetSha256");
		requireSlug(scoringPolicyId, "scoringPolicyId", SAFE_SLUG);
		requireSha256(scoringPolicySha256, "scoringPolicySha256");
		requireSha256(reviewPacketSha256, "reviewPacketSha256");
		if (!INDEPENDENCE_ATTESTATION.equals(independenceAttestation)) {
			throw new IllegalArgumentException(
					"independenceAttestation must be " + INDEPENDENCE_ATTESTATION);
		}
		if (queries.isEmpty() || queries.size() > MAX_QUERIES) {
			throw new IllegalArgumentException(
					"queries must contain 1 through " + MAX_QUERIES + " entries");
		}
		Set<String> queryKeys = new HashSet<>();
		Set<String> reviewKeys = new HashSet<>();
		for (QueryJudgments query : queries) {
			Objects.requireNonNull(query, "queries must not contain null");
			if (!queryKeys.add(query.queryKey())) {
				throw new IllegalArgumentException("duplicate queryKey: " + query.queryKey());
			}
			for (String reviewKey : query.goldPaperKeyByReviewKey().keySet()) {
				if (!reviewKeys.add(reviewKey)) {
					throw new IllegalArgumentException("reviewKeys must be unique across queries");
				}
			}
		}
	}

	private static void requireExactObject(JsonNode node, String path, Set<String> expected) {
		if (node == null || !node.isObject()) {
			throw new IllegalArgumentException(path + " must be an object");
		}
		Set<String> actual = new LinkedHashSet<>(node.propertyNames());
		Set<String> unknown = new LinkedHashSet<>(actual);
		unknown.removeAll(expected);
		if (!unknown.isEmpty()) {
			throw new IllegalArgumentException("Unknown keys at " + path + ": " + unknown);
		}
		Set<String> missing = new LinkedHashSet<>(expected);
		missing.removeAll(actual);
		if (!missing.isEmpty()) {
			throw new IllegalArgumentException("Missing keys at " + path + ": " + missing);
		}
	}

	private static JsonNode requireArray(JsonNode node, String path) {
		if (node == null || !node.isArray()) {
			throw new IllegalArgumentException(path + " must be an array");
		}
		return node;
	}

	private static String requireString(JsonNode node, String path) {
		if (node == null || !node.isString()) {
			throw new IllegalArgumentException(path + " must be a string");
		}
		return node.asString();
	}

	private static int requireInteger(JsonNode node, String path) {
		if (node == null || !node.isInt()) {
			throw new IllegalArgumentException(path + " must be an integer");
		}
		return node.asInt();
	}

	private static void requireSlug(String value, String field, Pattern pattern) {
		if (value == null || !pattern.matcher(value).matches()) {
			throw new IllegalArgumentException(field + " must be a safe lowercase slug");
		}
	}

	private static void requireSha256(String value, String field) {
		if (value == null || !SHA256.matcher(value).matches()) {
			throw new IllegalArgumentException(field + " must be a lowercase SHA-256 value");
		}
	}

	private static String sha256(byte[] bytes) {
		try {
			return HexFormat.of().formatHex(
					MessageDigest.getInstance("SHA-256").digest(bytes));
		}
		catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException("SHA-256 is unavailable", exception);
		}
	}

	record BoundJudgments(
			ProviderQualityComparativeJudgments judgments,
			String sha256) {

		BoundJudgments {
			Objects.requireNonNull(judgments, "judgments");
			requireSha256(sha256, "sha256");
		}
	}

	record QueryJudgments(
			String queryKey,
			List<GoldPaper> goldPapers,
			List<MustSeparatePair> mustSeparatePairs) {

		QueryJudgments {
			requireSlug(queryKey, "queryKey", QUERY_LOCAL_SLUG);
			goldPapers = List.copyOf(Objects.requireNonNull(goldPapers, "goldPapers"));
			mustSeparatePairs = List.copyOf(
					Objects.requireNonNull(mustSeparatePairs, "mustSeparatePairs"));
			Set<String> goldKeys = new HashSet<>();
			Map<String, String> goldByReviewKey = new LinkedHashMap<>();
			for (GoldPaper goldPaper : goldPapers) {
				Objects.requireNonNull(goldPaper, "goldPapers must not contain null");
				if (!goldKeys.add(goldPaper.goldPaperKey())) {
					throw new IllegalArgumentException(
							"duplicate goldPaperKey in " + queryKey + ": " + goldPaper.goldPaperKey());
				}
				for (String reviewKey : goldPaper.reviewKeys()) {
					if (goldByReviewKey.putIfAbsent(reviewKey, goldPaper.goldPaperKey()) != null) {
						throw new IllegalArgumentException(
								"reviewKeys must be assigned to exactly one gold paper in " + queryKey);
					}
				}
			}
			if (goldByReviewKey.size() > MAX_CANDIDATES_PER_QUERY) {
				throw new IllegalArgumentException(
						"query " + queryKey + " exceeds " + MAX_CANDIDATES_PER_QUERY + " candidates");
			}
			Set<String> pairKeys = new HashSet<>();
			for (MustSeparatePair pair : mustSeparatePairs) {
				Objects.requireNonNull(pair, "mustSeparatePairs must not contain null");
				if (!pairKeys.add(pair.canonicalKey())) {
					throw new IllegalArgumentException(
							"duplicate must-separate pair in " + queryKey);
				}
				String leftGold = goldByReviewKey.get(pair.leftReviewKey());
				String rightGold = goldByReviewKey.get(pair.rightReviewKey());
				if (leftGold == null || rightGold == null) {
					throw new IllegalArgumentException(
							"must-separate keys must both belong to query " + queryKey);
				}
				if (leftGold.equals(rightGold)) {
					throw new IllegalArgumentException(
							"must-separate keys must belong to different gold papers");
				}
			}
		}

		Map<String, GoldPaper> goldPapersByKey() {
			Map<String, GoldPaper> result = new LinkedHashMap<>();
			goldPapers.forEach(gold -> result.put(gold.goldPaperKey(), gold));
			return Collections.unmodifiableMap(result);
		}

		Map<String, String> goldPaperKeyByReviewKey() {
			Map<String, String> result = new LinkedHashMap<>();
			for (GoldPaper goldPaper : goldPapers) {
				goldPaper.reviewKeys().forEach(reviewKey ->
						result.put(reviewKey, goldPaper.goldPaperKey()));
			}
			return Collections.unmodifiableMap(result);
		}

		Map<String, Integer> relevanceByGoldPaperKey() {
			Map<String, Integer> result = new LinkedHashMap<>();
			goldPapers.forEach(gold -> result.put(gold.goldPaperKey(), gold.relevanceGrade()));
			return Collections.unmodifiableMap(result);
		}
	}

	record GoldPaper(
			String goldPaperKey,
			List<String> reviewKeys,
			int relevanceGrade,
			List<MetadataField> expectedFields) {

		GoldPaper {
			requireSlug(goldPaperKey, "goldPaperKey", QUERY_LOCAL_SLUG);
			reviewKeys = List.copyOf(Objects.requireNonNull(reviewKeys, "reviewKeys"));
			expectedFields = List.copyOf(
					Objects.requireNonNull(expectedFields, "expectedFields"));
			if (reviewKeys.isEmpty()) {
				throw new IllegalArgumentException("reviewKeys must not be empty");
			}
			Set<String> uniqueReviewKeys = new HashSet<>();
			for (String reviewKey : reviewKeys) {
				requireSha256(reviewKey, "reviewKey");
				if (!uniqueReviewKeys.add(reviewKey)) {
					throw new IllegalArgumentException("reviewKeys must be unique within a gold paper");
				}
			}
			if (relevanceGrade < 0 || relevanceGrade > 3) {
				throw new IllegalArgumentException("relevanceGrade must be from 0 through 3");
			}
			List<String> expectedNames = expectedFields.stream()
					.map(field -> Objects.requireNonNull(
							field, "expectedFields must not contain null").name())
					.toList();
			List<String> canonicalNames = expectedNames.stream().distinct().sorted().toList();
			if (!expectedNames.equals(canonicalNames)) {
				throw new IllegalArgumentException(
						"expectedFields must contain sorted unique MetadataField names");
			}
		}

		Set<MetadataField> expectedFieldSet() {
			return Collections.unmodifiableSet(new LinkedHashSet<>(expectedFields));
		}
	}

	record MustSeparatePair(
			String leftReviewKey,
			String rightReviewKey,
			String reasonCode) {

		MustSeparatePair {
			requireSha256(leftReviewKey, "leftReviewKey");
			requireSha256(rightReviewKey, "rightReviewKey");
			if (leftReviewKey.compareTo(rightReviewKey) >= 0) {
				throw new IllegalArgumentException(
						"must-separate pair review keys must use canonical ascending order");
			}
			if (reasonCode == null || !REASON_CODE.matcher(reasonCode).matches()) {
				throw new IllegalArgumentException("reasonCode must be a safe uppercase code");
			}
		}

		String canonicalKey() {
			return leftReviewKey + '\n' + rightReviewKey;
		}
	}
}
