package com.openscholar.search.internal.persistence;

import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.Channels;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

import org.springframework.core.io.ClassPathResource;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Frozen, evaluation-only rules for scoring independently judged comparative
 * provider evidence. This policy deliberately defines measurements, not
 * provider-enablement gates.
 */
record ProviderQualityComparativeScoringPolicy(
		int schemaVersion,
		String policyId,
		Status status,
		List<Scenario> scenarios,
		Ranking ranking,
		Deduplication deduplication,
		Metadata metadata,
		Limits limits,
		List<String> defaultEnablementGates) {

	static final String RESOURCE_PATH =
			"search/provider-quality/provider-comparative-scoring-policy-v1.json";
	static final String POLICY_ID = "provider-comparative-scoring-policy-v1";
	static final String POLICY_SHA256 =
			"a0755e826399af1c02721df0e506e9d14d4460bb83b956c5fee79684d4e42bdf";
	static final int MAXIMUM_INPUT_BYTES = 1_048_576;
	static final int MAXIMUM_QUERIES = 50;
	static final int MAXIMUM_CANDIDATES_PER_QUERY = 40;

	private static final Pattern SHA_256 = Pattern.compile("^[0-9a-f]{64}$");
	private static final int MAXIMUM_RESOURCE_PATH_CHARACTERS = 500;
	private static final int MAXIMUM_FILE_PATH_CHARACTERS = 4_096;
	private static final List<Scenario> EXPECTED_SCENARIOS = List.of(
			Scenario.OPENALEX_ONLY,
			Scenario.EUROPE_PMC_ONLY,
			Scenario.FUSED);
	private static final Set<String> ROOT_FIELDS = Set.of(
			"schemaVersion", "policyId", "status", "scenarios", "ranking",
			"deduplication", "metadata", "limits", "defaultEnablementGates");
	private static final Set<String> RANKING_FIELDS = Set.of(
			"recallAt", "ndcgAt", "precisionAt", "reciprocalRankAt", "clusterCredit",
			"creditTieBreak", "noRelevantJudgments");
	private static final Set<String> NO_RELEVANT_JUDGMENT_FIELDS = Set.of(
			"recall", "ndcg", "precision", "reciprocalRank");
	private static final Set<String> DEDUPLICATION_FIELDS = Set.of(
			"method", "undefinedHandling");
	private static final Set<String> DEDUPLICATION_UNDEFINED_HANDLING_FIELDS = Set.of(
			"precisionWhen", "recallWhen", "f1When", "counts");
	private static final Set<String> METADATA_FIELDS = Set.of("method");
	private static final Set<String> LIMIT_FIELDS = Set.of(
			"maximumInputBytes", "maximumQueries", "maximumCandidatesPerQuery");

	ProviderQualityComparativeScoringPolicy {
		policyId = requireBoundedText(policyId, "policyId", 3, 100);
		status = Objects.requireNonNull(status, "status");
		scenarios = List.copyOf(Objects.requireNonNull(scenarios, "scenarios"));
		ranking = Objects.requireNonNull(ranking, "ranking");
		deduplication = Objects.requireNonNull(deduplication, "deduplication");
		metadata = Objects.requireNonNull(metadata, "metadata");
		limits = Objects.requireNonNull(limits, "limits");
		defaultEnablementGates = List.copyOf(
				Objects.requireNonNull(defaultEnablementGates, "defaultEnablementGates"));

		if (schemaVersion != 1) {
			throw new IllegalArgumentException("schemaVersion must be 1");
		}
		if (!POLICY_ID.equals(policyId)) {
			throw new IllegalArgumentException("policyId must be " + POLICY_ID);
		}
		if (status != Status.EVALUATION_ONLY) {
			throw new IllegalArgumentException("status must be EVALUATION_ONLY");
		}
		if (!scenarios.equals(EXPECTED_SCENARIOS)) {
			throw new IllegalArgumentException(
					"scenarios must be ordered OPENALEX_ONLY, EUROPE_PMC_ONLY, FUSED");
		}
		if (defaultEnablementGates.stream().anyMatch(Objects::isNull)
				|| !defaultEnablementGates.isEmpty()) {
			throw new IllegalArgumentException(
					"defaultEnablementGates must be empty; this policy defines no gates");
		}
	}

	static ProviderQualityComparativeScoringPolicy loadFrozen(ObjectMapper objectMapper)
			throws IOException {
		BoundPolicy bound = loadBound(objectMapper, RESOURCE_PATH);
		bound.validateReference(POLICY_ID, POLICY_SHA256);
		return bound.policy();
	}

	static ProviderQualityComparativeScoringPolicy load(
			ObjectMapper objectMapper, String resourcePath) throws IOException {
		return loadBound(objectMapper, resourcePath).policy();
	}

	static ProviderQualityComparativeScoringPolicy load(
			ObjectMapper objectMapper, Path path) throws IOException {
		return loadBound(objectMapper, path).policy();
	}

	static BoundPolicy loadBound(ObjectMapper objectMapper, String resourcePath)
			throws IOException {
		Objects.requireNonNull(objectMapper, "objectMapper");
		String canonicalPath = requireBoundedText(
				resourcePath, "resourcePath", 1, MAXIMUM_RESOURCE_PATH_CHARACTERS);
		ClassPathResource resource = new ClassPathResource(canonicalPath);
		try (InputStream input = resource.getInputStream()) {
			return parseBound(objectMapper, readBounded(input));
		}
	}

	static BoundPolicy loadBound(ObjectMapper objectMapper, Path path) throws IOException {
		Objects.requireNonNull(objectMapper, "objectMapper");
		Path canonicalPath = Objects.requireNonNull(path, "path").toAbsolutePath().normalize();
		if (canonicalPath.toString().length() > MAXIMUM_FILE_PATH_CHARACTERS) {
			throw new IllegalArgumentException("policy path is too long");
		}
		if (Files.isSymbolicLink(canonicalPath)
				|| !Files.isRegularFile(canonicalPath, LinkOption.NOFOLLOW_LINKS)) {
			throw new IllegalArgumentException("policy path must be a regular non-symbolic-link file");
		}
		long size = Files.size(canonicalPath);
		if (size < 1 || size > MAXIMUM_INPUT_BYTES) {
			throw invalidByteCount();
		}
		try (SeekableByteChannel channel = Files.newByteChannel(
				canonicalPath, Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS));
				InputStream input = Channels.newInputStream(channel)) {
			return parseBound(objectMapper, readBounded(input));
		}
	}

	static ProviderQualityComparativeScoringPolicy parse(
			ObjectMapper objectMapper, byte[] bytes) throws IOException {
		return parseBound(objectMapper, bytes).policy();
	}

	static BoundPolicy parseBound(ObjectMapper objectMapper, byte[] bytes) throws IOException {
		Objects.requireNonNull(objectMapper, "objectMapper");
		Objects.requireNonNull(bytes, "bytes");
		if (bytes.length < 1 || bytes.length > MAXIMUM_INPUT_BYTES) {
			throw invalidByteCount();
		}
		JsonNode root = objectMapper.reader()
				.with(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
				.with(
						DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY,
						DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
				.readTree(bytes);
		ProviderQualityComparativeScoringPolicy policy = parse(root);
		return new BoundPolicy(policy, sha256(bytes));
	}

	static ProviderQualityComparativeScoringPolicy parse(JsonNode root) {
		requireExactObject(root, "$", ROOT_FIELDS);
		int schemaVersion = requireInteger(root.required("schemaVersion"), "$.schemaVersion");
		String policyId = requireText(root.required("policyId"), "$.policyId", 3, 100);
		Status status = requireEnum(root.required("status"), "$.status", Status.class);
		List<Scenario> scenarios = requireEnumArray(
				root.required("scenarios"), "$.scenarios", Scenario.class);

		JsonNode rankingNode = root.required("ranking");
		requireExactObject(rankingNode, "$.ranking", RANKING_FIELDS);
		JsonNode noRelevantJudgmentsNode = rankingNode.required("noRelevantJudgments");
		requireExactObject(
				noRelevantJudgmentsNode,
				"$.ranking.noRelevantJudgments",
				NO_RELEVANT_JUDGMENT_FIELDS);
		NoRelevantJudgments noRelevantJudgments = new NoRelevantJudgments(
				requireEnum(
						noRelevantJudgmentsNode.required("recall"),
						"$.ranking.noRelevantJudgments.recall",
						NoRelevantRelevanceMetric.class),
				requireEnum(
						noRelevantJudgmentsNode.required("ndcg"),
						"$.ranking.noRelevantJudgments.ndcg",
						NoRelevantRelevanceMetric.class),
				requireEnum(
						noRelevantJudgmentsNode.required("precision"),
						"$.ranking.noRelevantJudgments.precision",
						NoRelevantPrecision.class),
				requireEnum(
						noRelevantJudgmentsNode.required("reciprocalRank"),
						"$.ranking.noRelevantJudgments.reciprocalRank",
						NoRelevantRelevanceMetric.class));

		Ranking ranking = new Ranking(
				requireInteger(rankingNode.required("recallAt"), "$.ranking.recallAt"),
				requireInteger(rankingNode.required("ndcgAt"), "$.ranking.ndcgAt"),
				requireInteger(rankingNode.required("precisionAt"), "$.ranking.precisionAt"),
				requireInteger(
						rankingNode.required("reciprocalRankAt"), "$.ranking.reciprocalRankAt"),
				requireEnum(
						rankingNode.required("clusterCredit"),
						"$.ranking.clusterCredit",
						ClusterCredit.class),
				requireEnum(
						rankingNode.required("creditTieBreak"),
						"$.ranking.creditTieBreak",
						CreditTieBreak.class),
				noRelevantJudgments);

		JsonNode deduplicationNode = root.required("deduplication");
		requireExactObject(deduplicationNode, "$.deduplication", DEDUPLICATION_FIELDS);
		JsonNode undefinedHandlingNode = deduplicationNode.required("undefinedHandling");
		requireExactObject(
				undefinedHandlingNode,
				"$.deduplication.undefinedHandling",
				DEDUPLICATION_UNDEFINED_HANDLING_FIELDS);
		Deduplication deduplication = new Deduplication(
				requireEnum(
						deduplicationNode.required("method"),
						"$.deduplication.method",
						DeduplicationMethod.class),
				new DeduplicationUndefinedHandling(
						requireEnum(
								undefinedHandlingNode.required("precisionWhen"),
								"$.deduplication.undefinedHandling.precisionWhen",
								UndefinedPrecisionWhen.class),
						requireEnum(
								undefinedHandlingNode.required("recallWhen"),
								"$.deduplication.undefinedHandling.recallWhen",
								UndefinedRecallWhen.class),
						requireEnum(
								undefinedHandlingNode.required("f1When"),
								"$.deduplication.undefinedHandling.f1When",
								UndefinedF1When.class),
						requireEnum(
								undefinedHandlingNode.required("counts"),
								"$.deduplication.undefinedHandling.counts",
								UndefinedCounts.class)));

		JsonNode metadataNode = root.required("metadata");
		requireExactObject(metadataNode, "$.metadata", METADATA_FIELDS);
		Metadata metadata = new Metadata(requireEnum(
				metadataNode.required("method"), "$.metadata.method", MetadataMethod.class));

		JsonNode limitsNode = root.required("limits");
		requireExactObject(limitsNode, "$.limits", LIMIT_FIELDS);
		Limits limits = new Limits(
				requireInteger(
						limitsNode.required("maximumInputBytes"), "$.limits.maximumInputBytes"),
				requireInteger(limitsNode.required("maximumQueries"), "$.limits.maximumQueries"),
				requireInteger(
						limitsNode.required("maximumCandidatesPerQuery"),
						"$.limits.maximumCandidatesPerQuery"));

		List<String> defaultEnablementGates = requireTextArray(
				root.required("defaultEnablementGates"), "$.defaultEnablementGates", 1, 100);
		return new ProviderQualityComparativeScoringPolicy(
				schemaVersion,
				policyId,
				status,
				scenarios,
				ranking,
				deduplication,
				metadata,
				limits,
				defaultEnablementGates);
	}

	static String sha256(byte[] bytes) {
		Objects.requireNonNull(bytes, "bytes");
		try {
			return HexFormat.of().formatHex(
					MessageDigest.getInstance("SHA-256").digest(bytes));
		}
		catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException("SHA-256 is unavailable", exception);
		}
	}

	private static byte[] readBounded(InputStream input) throws IOException {
		byte[] bytes = input.readNBytes(MAXIMUM_INPUT_BYTES + 1);
		if (bytes.length < 1 || bytes.length > MAXIMUM_INPUT_BYTES) {
			throw invalidByteCount();
		}
		return bytes;
	}

	private static IllegalArgumentException invalidByteCount() {
		return new IllegalArgumentException(
				"scoring-policy input must contain 1 through "
						+ MAXIMUM_INPUT_BYTES + " bytes");
	}

	private static void requireExactObject(
			JsonNode node, String path, Set<String> expectedFields) {
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
		return requireBoundedText(node.asString(), path, minimum, maximum);
	}

	private static List<String> requireTextArray(
			JsonNode node, String path, int minimum, int maximum) {
		if (!node.isArray()) {
			throw new IllegalArgumentException(path + " must be an array");
		}
		return java.util.stream.IntStream.range(0, node.size())
				.mapToObj(index -> requireText(
						node.get(index), path + "[" + index + "]", minimum, maximum))
				.toList();
	}

	private static <E extends Enum<E>> E requireEnum(
			JsonNode node, String path, Class<E> enumType) {
		String value = requireText(node, path, 1, 100);
		try {
			return Enum.valueOf(enumType, value);
		}
		catch (IllegalArgumentException exception) {
			throw new IllegalArgumentException(path + " has an unsupported value: " + value);
		}
	}

	private static <E extends Enum<E>> List<E> requireEnumArray(
			JsonNode node, String path, Class<E> enumType) {
		if (!node.isArray()) {
			throw new IllegalArgumentException(path + " must be an array");
		}
		return java.util.stream.IntStream.range(0, node.size())
				.mapToObj(index -> requireEnum(
						node.get(index), path + "[" + index + "]", enumType))
				.toList();
	}

	private static String requireBoundedText(
			String value, String field, int minimum, int maximum) {
		if (value == null || !value.equals(value.strip())
				|| value.length() < minimum || value.length() > maximum) {
			throw new IllegalArgumentException(
					field + " must contain " + minimum + " through " + maximum
							+ " characters without surrounding whitespace");
		}
		return value;
	}

	enum Status {
		EVALUATION_ONLY
	}

	enum Scenario {
		OPENALEX_ONLY,
		EUROPE_PMC_ONLY,
		FUSED
	}

	enum ClusterCredit {
		ONE_HIGHEST_GRADE_UNCREDITED_GOLD_WORK_PER_RESULT
	}

	enum CreditTieBreak {
		GOLD_PAPER_KEY_ASCENDING
	}

	enum NoRelevantRelevanceMetric {
		UNDEFINED_EXCLUDE_FROM_MACRO
	}

	enum NoRelevantPrecision {
		ZERO_INCLUDE_IN_MACRO
	}

	enum DeduplicationMethod {
		PAIRWISE_PRECISION_RECALL_F1
	}

	enum UndefinedPrecisionWhen {
		TRUE_POSITIVE_PLUS_FALSE_POSITIVE_IS_ZERO
	}

	enum UndefinedRecallWhen {
		TRUE_POSITIVE_PLUS_FALSE_NEGATIVE_IS_ZERO
	}

	enum UndefinedF1When {
		PRECISION_OR_RECALL_IS_UNDEFINED
	}

	enum UndefinedCounts {
		ALWAYS_PRESERVED
	}

	enum MetadataMethod {
		EXPECTED_FIELD_RECOVERY
	}

	record Ranking(
			int recallAt,
			int ndcgAt,
			int precisionAt,
			int reciprocalRankAt,
			ClusterCredit clusterCredit,
			CreditTieBreak creditTieBreak,
			NoRelevantJudgments noRelevantJudgments) {

		Ranking {
			clusterCredit = Objects.requireNonNull(clusterCredit, "clusterCredit");
			creditTieBreak = Objects.requireNonNull(creditTieBreak, "creditTieBreak");
			noRelevantJudgments = Objects.requireNonNull(
					noRelevantJudgments, "noRelevantJudgments");
			if (recallAt != 20 || ndcgAt != 10 || precisionAt != 5 || reciprocalRankAt != 20
					|| clusterCredit
							!= ClusterCredit.ONE_HIGHEST_GRADE_UNCREDITED_GOLD_WORK_PER_RESULT
					|| creditTieBreak != CreditTieBreak.GOLD_PAPER_KEY_ASCENDING) {
				throw new IllegalArgumentException(
						"ranking must freeze cutoffs, cluster credit, and tie-break");
			}
		}
	}

	record NoRelevantJudgments(
			NoRelevantRelevanceMetric recall,
			NoRelevantRelevanceMetric ndcg,
			NoRelevantPrecision precision,
			NoRelevantRelevanceMetric reciprocalRank) {

		NoRelevantJudgments {
			if (recall != NoRelevantRelevanceMetric.UNDEFINED_EXCLUDE_FROM_MACRO
					|| ndcg != NoRelevantRelevanceMetric.UNDEFINED_EXCLUDE_FROM_MACRO
					|| precision != NoRelevantPrecision.ZERO_INCLUDE_IN_MACRO
					|| reciprocalRank
							!= NoRelevantRelevanceMetric.UNDEFINED_EXCLUDE_FROM_MACRO) {
				throw new IllegalArgumentException(
						"no-relevant ranking behavior must freeze undefined recall/nDCG/MRR exclusion and zero Precision@5 inclusion");
			}
		}
	}

	record Deduplication(
			DeduplicationMethod method,
			DeduplicationUndefinedHandling undefinedHandling) {

		Deduplication {
			if (method != DeduplicationMethod.PAIRWISE_PRECISION_RECALL_F1) {
				throw new IllegalArgumentException(
						"deduplication method must be PAIRWISE_PRECISION_RECALL_F1");
			}
			undefinedHandling = Objects.requireNonNull(undefinedHandling, "undefinedHandling");
		}
	}

	record DeduplicationUndefinedHandling(
			UndefinedPrecisionWhen precisionWhen,
			UndefinedRecallWhen recallWhen,
			UndefinedF1When f1When,
			UndefinedCounts counts) {

		DeduplicationUndefinedHandling {
			if (precisionWhen
						!= UndefinedPrecisionWhen.TRUE_POSITIVE_PLUS_FALSE_POSITIVE_IS_ZERO
					|| recallWhen
							!= UndefinedRecallWhen.TRUE_POSITIVE_PLUS_FALSE_NEGATIVE_IS_ZERO
					|| f1When != UndefinedF1When.PRECISION_OR_RECALL_IS_UNDEFINED
					|| counts != UndefinedCounts.ALWAYS_PRESERVED) {
				throw new IllegalArgumentException(
						"deduplication undefined handling must freeze denominator rules, F1 propagation, and count preservation");
			}
		}
	}

	record Metadata(MetadataMethod method) {

		Metadata {
			if (method != MetadataMethod.EXPECTED_FIELD_RECOVERY) {
				throw new IllegalArgumentException(
						"metadata method must be EXPECTED_FIELD_RECOVERY");
			}
		}
	}

	record Limits(
			int maximumInputBytes,
			int maximumQueries,
			int maximumCandidatesPerQuery) {

		Limits {
			if (maximumInputBytes != MAXIMUM_INPUT_BYTES
					|| maximumQueries != MAXIMUM_QUERIES
					|| maximumCandidatesPerQuery != MAXIMUM_CANDIDATES_PER_QUERY) {
				throw new IllegalArgumentException(
						"limits must freeze 1048576 bytes, 50 queries, and 40 candidates per query");
			}
		}
	}

	record BoundPolicy(ProviderQualityComparativeScoringPolicy policy, String sha256) {

		BoundPolicy {
			policy = Objects.requireNonNull(policy, "policy");
			if (sha256 == null || !SHA_256.matcher(sha256).matches()) {
				throw new IllegalArgumentException("sha256 must be a lowercase SHA-256 digest");
			}
		}

		void validateReference(String referencedPolicyId, String referencedSha256) {
			String canonicalPolicyId = requireBoundedText(
					referencedPolicyId, "referencedPolicyId", 3, 100);
			if (!policy.policyId().equals(canonicalPolicyId)) {
				throw new IllegalArgumentException("referenced scoring policy ID does not match");
			}
			if (referencedSha256 == null || !SHA_256.matcher(referencedSha256).matches()) {
				throw new IllegalArgumentException(
						"referenced scoring policy SHA-256 must be lowercase hexadecimal");
			}
			if (!MessageDigest.isEqual(
					sha256.getBytes(StandardCharsets.US_ASCII),
					referencedSha256.getBytes(StandardCharsets.US_ASCII))) {
				throw new IllegalArgumentException("referenced scoring policy SHA-256 does not match");
			}
		}
	}
}
