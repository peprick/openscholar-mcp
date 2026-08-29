package com.openscholar.search.internal.persistence;

import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import com.openscholar.search.internal.persistence.LocalCatalogTopicEvaluationFixture.AdversaryKind;
import com.openscholar.search.internal.persistence.LocalCatalogTopicEvaluationFixture.BoundFixture;
import com.openscholar.search.internal.persistence.LocalCatalogTopicEvaluationFixture.Visibility;
import org.springframework.core.io.ClassPathResource;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

record LocalCatalogTopicEvaluationPolicy(
		int schemaVersion,
		String policyId,
		String developmentFixtureId,
		String developmentFixtureSha256,
		Status status,
		String labelUnit,
		String sourcePolicy,
		Baseline baseline,
		Constraints constraints,
		List<String> metrics,
		Gates gates) {

	static final String RESOURCE_PATH =
			"search/relevance/local-catalog-topic-policy-v1.json";
	static final String POLICY_ID = "local-catalog-topic-policy-v1";
	static final String POLICY_SHA256 =
			"420ce83c66aa92b62f9c8ffd82914bd12fdbeae8fd3225cfe1c137b6cb28cc6e";
	private static final int MAXIMUM_INPUT_BYTES = 64 * 1024;
	private static final double EPSILON = 1.0e-12d;
	private static final Set<String> ROOT_FIELDS = Set.of(
			"schemaVersion", "policyId", "developmentFixtureId", "developmentFixtureSha256",
			"status", "labelUnit", "sourcePolicy", "baseline", "constraints", "metrics", "gates");
	private static final Set<String> BASELINE_FIELDS = Set.of(
			"pipelineVersion", "mode", "textSearchConfiguration", "queryFunction",
			"textRankNormalization", "searchVectorFields", "weights", "tieBreak");
	private static final Set<String> WEIGHT_FIELDS = Set.of(
			"titleExact", "titlePrefix", "titleContains", "textRank", "authorSubstring",
			"logCitation");
	private static final Set<String> CONSTRAINT_FIELDS = Set.of(
			"queryCount", "candidateCount", "targetVisibleCandidateCount", "pageSize",
			"metadataOnly", "allTargetVisibleCandidatesJudged", "requiredVisibilities",
			"requiredAdversaryKinds", "requiredFilterDimensions");
	private static final Set<String> GATE_FIELDS = Set.of(
			"minimumPerQueryRecallAt10", "minimumPerQueryNdcgAt10", "minimumMacroRecallAt10",
			"minimumMacroNdcgAt10", "minimumMacroPrecisionAt1",
			"minimumMeanReciprocalRankAt10", "maximumOwnerScopeLeakCount",
			"maximumTopRankedAdversaryCount", "requireRepeatedOrder", "requireNoProviderCalls");
	private static final List<String> EXPECTED_METRICS = List.of(
			"RECALL_AT_10", "NDCG_AT_10", "PRECISION_AT_1",
			"MEAN_RECIPROCAL_RANK_AT_10", "OWNER_SCOPE_LEAK_COUNT",
			"TOP_RANKED_ADVERSARY_COUNT");

	LocalCatalogTopicEvaluationPolicy {
		policyId = requireTextValue(policyId, "policyId", 3, 100);
		developmentFixtureId = requireTextValue(
				developmentFixtureId, "developmentFixtureId", 3, 100);
		developmentFixtureSha256 = requireDigest(
				developmentFixtureSha256, "developmentFixtureSha256");
		status = Objects.requireNonNull(status, "status");
		labelUnit = requireTextValue(labelUnit, "labelUnit", 3, 100);
		sourcePolicy = requireTextValue(sourcePolicy, "sourcePolicy", 3, 100);
		baseline = Objects.requireNonNull(baseline, "baseline");
		constraints = Objects.requireNonNull(constraints, "constraints");
		metrics = List.copyOf(Objects.requireNonNull(metrics, "metrics"));
		gates = Objects.requireNonNull(gates, "gates");
	}

	static BoundPolicy loadFrozen(ObjectMapper objectMapper) throws IOException {
		BoundPolicy bound = loadBound(objectMapper, RESOURCE_PATH);
		bound.validateReference(POLICY_ID, POLICY_SHA256);
		return bound;
	}

	static BoundPolicy loadBound(ObjectMapper objectMapper, String resourcePath) throws IOException {
		Objects.requireNonNull(objectMapper, "objectMapper");
		String path = requireTextValue(resourcePath, "resourcePath", 1, 240);
		try (InputStream input = new ClassPathResource(path).getInputStream()) {
			return parseBound(objectMapper, readBounded(input));
		}
	}

	static BoundPolicy parseBound(ObjectMapper objectMapper, byte[] bytes) throws IOException {
		Objects.requireNonNull(objectMapper, "objectMapper");
		Objects.requireNonNull(bytes, "bytes");
		if (bytes.length < 1 || bytes.length > MAXIMUM_INPUT_BYTES) {
			throw new IllegalArgumentException("policy must contain 1 through 65536 bytes");
		}
		JsonNode root = objectMapper.reader()
				.with(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
				.with(
						DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY,
						DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
				.readTree(bytes);
		return new BoundPolicy(parse(root), sha256(bytes));
	}

	static LocalCatalogTopicEvaluationPolicy parse(JsonNode root) {
		requireExactObject(root, "$", ROOT_FIELDS);
		LocalCatalogTopicEvaluationPolicy policy = new LocalCatalogTopicEvaluationPolicy(
				requireInteger(root.required("schemaVersion"), "$.schemaVersion"),
				requireText(root.required("policyId"), "$.policyId", 3, 100),
				requireText(root.required("developmentFixtureId"), "$.developmentFixtureId", 3, 100),
				requireText(root.required("developmentFixtureSha256"), "$.developmentFixtureSha256", 64, 64),
				requireEnum(root.required("status"), "$.status", Status.class),
				requireText(root.required("labelUnit"), "$.labelUnit", 3, 100),
				requireText(root.required("sourcePolicy"), "$.sourcePolicy", 3, 100),
				parseBaseline(root.required("baseline"), "$.baseline"),
				parseConstraints(root.required("constraints"), "$.constraints"),
				requireTextArray(root.required("metrics"), "$.metrics"),
				parseGates(root.required("gates"), "$.gates"));
		validateValues(policy);
		return policy;
	}

	void validateFixture(BoundFixture fixture) {
		Objects.requireNonNull(fixture, "fixture");
		if (!developmentFixtureId.equals(fixture.fixture().fixtureId())
				|| !developmentFixtureSha256.equals(fixture.sha256())
				|| !policyId.equals(fixture.fixture().policyId())
				|| constraints.queryCount() != fixture.fixture().queries().size()
				|| constraints.candidateCount() != fixture.fixture().candidates().size()
				|| constraints.targetVisibleCandidateCount()
						!= fixture.fixture().targetVisibleKeys().size()) {
			throw new IllegalArgumentException("Policy and development fixture are not digest/count bound");
		}
	}

	private static Baseline parseBaseline(JsonNode node, String path) {
		requireExactObject(node, path, BASELINE_FIELDS);
		JsonNode weights = node.required("weights");
		requireExactObject(weights, path + ".weights", WEIGHT_FIELDS);
		return new Baseline(
				requireText(node.required("pipelineVersion"), path + ".pipelineVersion", 3, 64),
				requireText(node.required("mode"), path + ".mode", 3, 32),
				requireText(node.required("textSearchConfiguration"), path + ".textSearchConfiguration", 3, 32),
				requireText(node.required("queryFunction"), path + ".queryFunction", 3, 64),
				requireInteger(node.required("textRankNormalization"), path + ".textRankNormalization"),
				requireTextArray(node.required("searchVectorFields"), path + ".searchVectorFields"),
				new Weights(
						requireNumber(weights.required("titleExact"), path + ".weights.titleExact"),
						requireNumber(weights.required("titlePrefix"), path + ".weights.titlePrefix"),
						requireNumber(weights.required("titleContains"), path + ".weights.titleContains"),
						requireNumber(weights.required("textRank"), path + ".weights.textRank"),
						requireNumber(weights.required("authorSubstring"), path + ".weights.authorSubstring"),
						requireNumber(weights.required("logCitation"), path + ".weights.logCitation")),
				requireTextArray(node.required("tieBreak"), path + ".tieBreak"));
	}

	private static Constraints parseConstraints(JsonNode node, String path) {
		requireExactObject(node, path, CONSTRAINT_FIELDS);
		return new Constraints(
				requireInteger(node.required("queryCount"), path + ".queryCount"),
				requireInteger(node.required("candidateCount"), path + ".candidateCount"),
				requireInteger(node.required("targetVisibleCandidateCount"), path + ".targetVisibleCandidateCount"),
				requireInteger(node.required("pageSize"), path + ".pageSize"),
				requireBoolean(node.required("metadataOnly"), path + ".metadataOnly"),
				requireBoolean(node.required("allTargetVisibleCandidatesJudged"), path + ".allTargetVisibleCandidatesJudged"),
				requireEnumArray(node.required("requiredVisibilities"), path + ".requiredVisibilities", Visibility.class),
				requireEnumArray(node.required("requiredAdversaryKinds"), path + ".requiredAdversaryKinds", AdversaryKind.class),
				requireEnumArray(node.required("requiredFilterDimensions"), path + ".requiredFilterDimensions", FilterDimension.class));
	}

	private static Gates parseGates(JsonNode node, String path) {
		requireExactObject(node, path, GATE_FIELDS);
		return new Gates(
				requireNumber(node.required("minimumPerQueryRecallAt10"), path + ".minimumPerQueryRecallAt10"),
				requireNumber(node.required("minimumPerQueryNdcgAt10"), path + ".minimumPerQueryNdcgAt10"),
				requireNumber(node.required("minimumMacroRecallAt10"), path + ".minimumMacroRecallAt10"),
				requireNumber(node.required("minimumMacroNdcgAt10"), path + ".minimumMacroNdcgAt10"),
				requireNumber(node.required("minimumMacroPrecisionAt1"), path + ".minimumMacroPrecisionAt1"),
				requireNumber(node.required("minimumMeanReciprocalRankAt10"), path + ".minimumMeanReciprocalRankAt10"),
				requireInteger(node.required("maximumOwnerScopeLeakCount"), path + ".maximumOwnerScopeLeakCount"),
				requireInteger(node.required("maximumTopRankedAdversaryCount"), path + ".maximumTopRankedAdversaryCount"),
				requireBoolean(node.required("requireRepeatedOrder"), path + ".requireRepeatedOrder"),
				requireBoolean(node.required("requireNoProviderCalls"), path + ".requireNoProviderCalls"));
	}

	private static void validateValues(LocalCatalogTopicEvaluationPolicy policy) {
		if (policy.schemaVersion() != 1
				|| !POLICY_ID.equals(policy.policyId())
				|| !LocalCatalogTopicEvaluationFixture.FIXTURE_ID.equals(policy.developmentFixtureId())
				|| policy.status() != Status.EVALUATION_ONLY
				|| !"CANONICAL_PAPER_TOPIC_RELEVANCE".equals(policy.labelUnit())
				|| !"SYNTHETIC_METADATA_ONLY".equals(policy.sourcePolicy())) {
			throw new IllegalArgumentException("Unexpected local topic evaluation policy identity");
		}
		Baseline baseline = policy.baseline();
		if (!"local-catalog-v1".equals(baseline.pipelineVersion())
				|| !"LOCAL".equals(baseline.mode())
				|| !"english".equals(baseline.textSearchConfiguration())
				|| !"WEBSEARCH_TO_TSQUERY".equals(baseline.queryFunction())
				|| baseline.textRankNormalization() != 32
				|| !baseline.searchVectorFields().equals(List.of("TITLE_A", "ABSTRACT_B", "VENUE_C"))
				|| !baseline.tieBreak().equals(List.of(
						"TOTAL_SCORE_DESC", "CITATION_COUNT_DESC_NULLS_LAST", "PAPER_UUID_ASC"))) {
			throw new IllegalArgumentException("Production local-catalog baseline semantics drifted");
		}
		Weights weights = baseline.weights();
		if (!close(weights.titleExact(), 8.0d)
				|| !close(weights.titlePrefix(), 5.0d)
				|| !close(weights.titleContains(), 2.5d)
				|| !close(weights.textRank(), 3.0d)
				|| !close(weights.authorSubstring(), 1.5d)
				|| !close(weights.logCitation(), 0.01d)) {
			throw new IllegalArgumentException("Production local-catalog weights drifted");
		}
		Constraints constraints = policy.constraints();
		if (constraints.queryCount() != 6 || constraints.candidateCount() != 25
				|| constraints.targetVisibleCandidateCount() != 19 || constraints.pageSize() != 10
				|| !constraints.metadataOnly() || !constraints.allTargetVisibleCandidatesJudged()
				|| !Set.copyOf(constraints.requiredVisibilities()).equals(Set.of(Visibility.values()))
				|| !Set.copyOf(constraints.requiredAdversaryKinds()).equals(Set.of(AdversaryKind.values()))
				|| !Set.copyOf(constraints.requiredFilterDimensions()).equals(Set.of(FilterDimension.values()))) {
			throw new IllegalArgumentException("Local topic corpus constraints drifted");
		}
		if (!policy.metrics().equals(EXPECTED_METRICS)) {
			throw new IllegalArgumentException("Local topic metrics drifted");
		}
		Gates gates = policy.gates();
		for (double rate : List.of(
				gates.minimumPerQueryRecallAt10(), gates.minimumPerQueryNdcgAt10(),
				gates.minimumMacroRecallAt10(), gates.minimumMacroNdcgAt10(),
				gates.minimumMacroPrecisionAt1(), gates.minimumMeanReciprocalRankAt10())) {
			if (!Double.isFinite(rate) || rate < 0.0d || rate > 1.0d) {
				throw new IllegalArgumentException("Metric gates must be finite values within 0..1");
			}
		}
		if (gates.maximumOwnerScopeLeakCount() != 0
				|| gates.maximumTopRankedAdversaryCount() != 0
				|| !gates.requireRepeatedOrder() || !gates.requireNoProviderCalls()) {
			throw new IllegalArgumentException("Structural safety gates must remain fail-closed");
		}
	}

	private static boolean close(double left, double right) {
		return Math.abs(left - right) <= EPSILON;
	}

	private static byte[] readBounded(InputStream input) throws IOException {
		byte[] bytes = input.readNBytes(MAXIMUM_INPUT_BYTES + 1);
		if (bytes.length < 1 || bytes.length > MAXIMUM_INPUT_BYTES) {
			throw new IllegalArgumentException("policy must contain 1 through 65536 bytes");
		}
		return bytes;
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

	private static void requireExactObject(JsonNode node, String path, Set<String> fields) {
		if (node == null || !node.isObject()) {
			throw new IllegalArgumentException(path + " must be an object");
		}
		Set<String> actual = new LinkedHashSet<>(node.propertyNames());
		if (!actual.equals(fields)) {
			throw new IllegalArgumentException(path + " must contain exactly " + fields + "; found " + actual);
		}
	}

	private static int requireInteger(JsonNode node, String path) {
		if (node == null || !node.isIntegralNumber() || !node.canConvertToInt()) {
			throw new IllegalArgumentException(path + " must be an integer");
		}
		return node.intValue();
	}

	private static double requireNumber(JsonNode node, String path) {
		if (node == null || !node.isNumber()) {
			throw new IllegalArgumentException(path + " must be numeric");
		}
		double value = node.doubleValue();
		if (!Double.isFinite(value)) {
			throw new IllegalArgumentException(path + " must be finite");
		}
		return value;
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

	private static String requireTextValue(String value, String path, int minimum, int maximum) {
		if (value == null || !value.equals(value.strip())
				|| value.length() < minimum || value.length() > maximum
				|| value.codePoints().anyMatch(Character::isISOControl)) {
			throw new IllegalArgumentException(path + " must be bounded text without whitespace or controls");
		}
		return value;
	}

	private static String requireDigest(String value, String path) {
		String digest = requireTextValue(value, path, 64, 64);
		if (!digest.matches("[0-9a-f]{64}")) {
			throw new IllegalArgumentException(path + " must be a lowercase SHA-256 digest");
		}
		return digest;
	}

	private static List<String> requireTextArray(JsonNode node, String path) {
		if (node == null || !node.isArray()) {
			throw new IllegalArgumentException(path + " must be an array");
		}
		List<String> values = new ArrayList<>(node.size());
		for (int index = 0; index < node.size(); index++) {
			values.add(requireText(node.get(index), path + "[" + index + "]", 1, 100));
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
		if (node == null || !node.isArray()) {
			throw new IllegalArgumentException(path + " must be an array");
		}
		List<E> values = new ArrayList<>(node.size());
		for (int index = 0; index < node.size(); index++) {
			values.add(requireEnum(node.get(index), path + "[" + index + "]", enumType));
		}
		if (values.stream().distinct().count() != values.size()) {
			throw new IllegalArgumentException(path + " must not contain duplicates");
		}
		return List.copyOf(values);
	}

	enum Status {
		EVALUATION_ONLY
	}

	enum FilterDimension {
		YEAR_FROM,
		YEAR_TO,
		DOCUMENT_TYPE,
		OPEN_ACCESS,
		MINIMUM_CITATIONS,
		LANGUAGE
	}

	record Baseline(
			String pipelineVersion,
			String mode,
			String textSearchConfiguration,
			String queryFunction,
			int textRankNormalization,
			List<String> searchVectorFields,
			Weights weights,
			List<String> tieBreak) {

		Baseline {
			searchVectorFields = List.copyOf(searchVectorFields);
			weights = Objects.requireNonNull(weights, "weights");
			tieBreak = List.copyOf(tieBreak);
		}
	}

	record Weights(
			double titleExact,
			double titlePrefix,
			double titleContains,
			double textRank,
			double authorSubstring,
			double logCitation) {
	}

	record Constraints(
			int queryCount,
			int candidateCount,
			int targetVisibleCandidateCount,
			int pageSize,
			boolean metadataOnly,
			boolean allTargetVisibleCandidatesJudged,
			List<Visibility> requiredVisibilities,
			List<AdversaryKind> requiredAdversaryKinds,
			List<FilterDimension> requiredFilterDimensions) {

		Constraints {
			requiredVisibilities = List.copyOf(requiredVisibilities);
			requiredAdversaryKinds = List.copyOf(requiredAdversaryKinds);
			requiredFilterDimensions = List.copyOf(requiredFilterDimensions);
		}
	}

	record Gates(
			double minimumPerQueryRecallAt10,
			double minimumPerQueryNdcgAt10,
			double minimumMacroRecallAt10,
			double minimumMacroNdcgAt10,
			double minimumMacroPrecisionAt1,
			double minimumMeanReciprocalRankAt10,
			int maximumOwnerScopeLeakCount,
			int maximumTopRankedAdversaryCount,
			boolean requireRepeatedOrder,
			boolean requireNoProviderCalls) {
	}

	record BoundPolicy(LocalCatalogTopicEvaluationPolicy policy, String sha256) {

		BoundPolicy {
			policy = Objects.requireNonNull(policy, "policy");
			sha256 = requireDigest(sha256, "sha256");
		}

		void validateReference(String expectedPolicyId, String expectedSha256) {
			if (!policy.policyId().equals(expectedPolicyId) || !sha256.equals(expectedSha256)) {
				throw new IllegalArgumentException("Policy identity or raw SHA-256 digest drifted");
			}
		}
	}
}
