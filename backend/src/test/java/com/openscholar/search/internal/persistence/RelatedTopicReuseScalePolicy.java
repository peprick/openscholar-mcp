package com.openscholar.search.internal.persistence;

import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

import com.openscholar.TestcontainersConfiguration;
import com.openscholar.paper.DocumentType;
import org.springframework.core.io.ClassPathResource;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

record RelatedTopicReuseScalePolicy(
		int schemaVersion,
		String policyId,
		Status status,
		String subjectPolicyId,
		String subjectPolicySha256,
		String environmentGate,
		String databaseImage,
		int postgresMajorVersion,
		String sourcePolicy,
		Corpus corpus,
		List<Workload> workloads,
		Measurement measurement,
		StructuralGates structuralGates,
		Interpretation interpretation) {

	static final String RESOURCE_PATH =
			"search/relevance/related-topic-reuse-scale-policy-v1.json";
	static final String POLICY_ID = "related-topic-reuse-scale-policy-v1";
	static final String ENVIRONMENT_GATE = "RUN_RELATED_TOPIC_REUSE_SCALE_EVALUATION";
	static final String POLICY_SHA256 =
			"b0eb81d2ba174b70bd90e02c9df40b7babb82e818d681d5011f6c8a7301e66ed";

	private static final int MAXIMUM_INPUT_BYTES = 64 * 1024;
	private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");
	private static final Set<String> ROOT_FIELDS = Set.of(
			"schemaVersion", "policyId", "status", "subjectPolicyId",
			"subjectPolicySha256", "environmentGate", "databaseImage",
			"postgresMajorVersion", "sourcePolicy", "corpus", "workloads",
			"measurement", "structuralGates", "interpretation");
	private static final Set<String> CORPUS_FIELDS = Set.of(
			"generatorVersion", "generatorSeed", "generatorSqlSha256", "totalPaperCount",
			"targetSearchVisibleCount", "targetCollectionVisibleCount",
			"otherOwnerVisibleCount", "catalogOnlyCount");
	private static final Set<String> WORKLOAD_FIELDS = Set.of(
			"key", "kind", "query", "yearFrom", "yearTo", "documentTypes",
			"openAccessOnly", "minimumCitations", "languages", "expectedSeedCount");
	private static final Set<String> MEASUREMENT_FIELDS = Set.of(
			"baselinePoolSize", "cutoff", "warmupRuns", "measurementRuns",
			"concurrency", "cacheState", "clock", "stageOrder", "percentileMethod",
			"percentiles", "outputSchemaVersion", "outputMode");
	private static final Set<String> GATE_FIELDS = Set.of(
			"requireStableControl", "requireStableFeedback", "requireStableFusion",
			"requireExactNoSeedFallback", "requireNonemptySeededFeedback",
			"maximumOwnerScopeLeakCount", "maximumFilterViolationCount",
			"maximumProviderCallCount", "maximumExperimentalSnapshotWriteCount");
	private static final Set<String> INTERPRETATION_FIELDS = Set.of(
			"evidenceClassification", "latencyDecision",
			"qualifiedTargetEnvironmentRequired", "activationEvidence");
	private static final List<Workload> EXPECTED_WORKLOADS = List.of(
			new Workload(
					"no-seed-owner-scope-control",
					WorkloadKind.NO_SEED_SCOPE_CONTROL,
					"orbital lichen spectroscopy",
					null,
					null,
					List.of(),
					false,
					0,
					List.of(),
					0),
			new Workload(
					"one-seed-sparse-feedback",
					WorkloadKind.ONE_SEED_SPARSE,
					"coastal erosion drone mapping",
					null,
					null,
					List.of(),
					false,
					0,
					List.of(),
					1),
			new Workload(
					"two-seed-broad-feedback",
					WorkloadKind.TWO_SEED_BROAD,
					"river microplastic community sensors",
					null,
					null,
					List.of(),
					false,
					0,
					List.of(),
					2),
			new Workload(
					"fully-filtered-selective-feedback",
					WorkloadKind.FULLY_FILTERED_SELECTIVE,
					"community wildfire smoke sensors",
					2022,
					2026,
					List.of(DocumentType.THESIS),
					true,
					5,
					List.of("en"),
					1));

	RelatedTopicReuseScalePolicy {
		policyId = requireTextValue(policyId, "policyId", 3, 100);
		subjectPolicyId = requireTextValue(subjectPolicyId, "subjectPolicyId", 3, 100);
		subjectPolicySha256 = requireDigest(subjectPolicySha256, "subjectPolicySha256");
		environmentGate = requireTextValue(environmentGate, "environmentGate", 3, 100);
		databaseImage = requireTextValue(databaseImage, "databaseImage", 3, 300);
		sourcePolicy = requireTextValue(sourcePolicy, "sourcePolicy", 3, 100);
		status = Objects.requireNonNull(status, "status");
		corpus = Objects.requireNonNull(corpus, "corpus");
		workloads = List.copyOf(Objects.requireNonNull(workloads, "workloads"));
		measurement = Objects.requireNonNull(measurement, "measurement");
		structuralGates = Objects.requireNonNull(structuralGates, "structuralGates");
		interpretation = Objects.requireNonNull(interpretation, "interpretation");
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
			throw new IllegalArgumentException("scale policy must contain 1 through 65536 bytes");
		}
		JsonNode root = objectMapper.reader()
				.with(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
				.with(
						DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY,
						DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
				.readTree(bytes);
		return new BoundPolicy(parse(root), sha256(bytes));
	}

	static RelatedTopicReuseScalePolicy parse(JsonNode root) {
		requireExactObject(root, "$", ROOT_FIELDS);
		RelatedTopicReuseScalePolicy policy = new RelatedTopicReuseScalePolicy(
				requireInteger(root.required("schemaVersion"), "$.schemaVersion"),
				requireText(root.required("policyId"), "$.policyId", 3, 100),
				requireEnum(root.required("status"), "$.status", Status.class),
				requireText(root.required("subjectPolicyId"), "$.subjectPolicyId", 3, 100),
				requireText(root.required("subjectPolicySha256"), "$.subjectPolicySha256", 64, 64),
				requireText(root.required("environmentGate"), "$.environmentGate", 3, 100),
				requireText(root.required("databaseImage"), "$.databaseImage", 3, 300),
				requireInteger(root.required("postgresMajorVersion"), "$.postgresMajorVersion"),
				requireText(root.required("sourcePolicy"), "$.sourcePolicy", 3, 100),
				parseCorpus(root.required("corpus"), "$.corpus"),
				parseWorkloads(root.required("workloads"), "$.workloads"),
				parseMeasurement(root.required("measurement"), "$.measurement"),
				parseGates(root.required("structuralGates"), "$.structuralGates"),
				parseInterpretation(root.required("interpretation"), "$.interpretation"));
		validateFrozenValues(policy);
		return policy;
	}

	private static Corpus parseCorpus(JsonNode node, String path) {
		requireExactObject(node, path, CORPUS_FIELDS);
		return new Corpus(
				requireText(node.required("generatorVersion"), path + ".generatorVersion", 3, 100),
				requireInteger(node.required("generatorSeed"), path + ".generatorSeed"),
				requireText(
						node.required("generatorSqlSha256"),
						path + ".generatorSqlSha256",
						64,
						64),
				requireInteger(node.required("totalPaperCount"), path + ".totalPaperCount"),
				requireInteger(node.required("targetSearchVisibleCount"), path + ".targetSearchVisibleCount"),
				requireInteger(node.required("targetCollectionVisibleCount"), path + ".targetCollectionVisibleCount"),
				requireInteger(node.required("otherOwnerVisibleCount"), path + ".otherOwnerVisibleCount"),
				requireInteger(node.required("catalogOnlyCount"), path + ".catalogOnlyCount"));
	}

	private static List<Workload> parseWorkloads(JsonNode node, String path) {
		if (node == null || !node.isArray() || node.isEmpty()) {
			throw new IllegalArgumentException(path + " must be a non-empty array");
		}
		List<Workload> workloads = new ArrayList<>(node.size());
		for (int index = 0; index < node.size(); index++) {
			String itemPath = path + "[" + index + "]";
			JsonNode item = node.get(index);
			requireExactObject(item, itemPath, WORKLOAD_FIELDS);
			workloads.add(new Workload(
					requireText(item.required("key"), itemPath + ".key", 3, 100),
					requireEnum(item.required("kind"), itemPath + ".kind", WorkloadKind.class),
					requireText(item.required("query"), itemPath + ".query", 3, 500),
					optionalInteger(item.required("yearFrom"), itemPath + ".yearFrom"),
					optionalInteger(item.required("yearTo"), itemPath + ".yearTo"),
					requireEnumArray(
							item.required("documentTypes"), itemPath + ".documentTypes", DocumentType.class),
					requireBoolean(item.required("openAccessOnly"), itemPath + ".openAccessOnly"),
					requireInteger(item.required("minimumCitations"), itemPath + ".minimumCitations"),
					requireTextArray(item.required("languages"), itemPath + ".languages"),
					requireInteger(item.required("expectedSeedCount"), itemPath + ".expectedSeedCount")));
		}
		return List.copyOf(workloads);
	}

	private static Measurement parseMeasurement(JsonNode node, String path) {
		requireExactObject(node, path, MEASUREMENT_FIELDS);
		return new Measurement(
				requireInteger(node.required("baselinePoolSize"), path + ".baselinePoolSize"),
				requireInteger(node.required("cutoff"), path + ".cutoff"),
				requireInteger(node.required("warmupRuns"), path + ".warmupRuns"),
				requireInteger(node.required("measurementRuns"), path + ".measurementRuns"),
				requireInteger(node.required("concurrency"), path + ".concurrency"),
				requireText(node.required("cacheState"), path + ".cacheState", 3, 40),
				requireText(node.required("clock"), path + ".clock", 3, 40),
				requireText(node.required("stageOrder"), path + ".stageOrder", 3, 80),
				requireText(node.required("percentileMethod"), path + ".percentileMethod", 3, 40),
				requireNumberArray(node.required("percentiles"), path + ".percentiles"),
				requireInteger(node.required("outputSchemaVersion"), path + ".outputSchemaVersion"),
				requireText(node.required("outputMode"), path + ".outputMode", 3, 80));
	}

	private static StructuralGates parseGates(JsonNode node, String path) {
		requireExactObject(node, path, GATE_FIELDS);
		return new StructuralGates(
				requireBoolean(node.required("requireStableControl"), path + ".requireStableControl"),
				requireBoolean(node.required("requireStableFeedback"), path + ".requireStableFeedback"),
				requireBoolean(node.required("requireStableFusion"), path + ".requireStableFusion"),
				requireBoolean(node.required("requireExactNoSeedFallback"), path + ".requireExactNoSeedFallback"),
				requireBoolean(node.required("requireNonemptySeededFeedback"), path + ".requireNonemptySeededFeedback"),
				requireInteger(node.required("maximumOwnerScopeLeakCount"), path + ".maximumOwnerScopeLeakCount"),
				requireInteger(node.required("maximumFilterViolationCount"), path + ".maximumFilterViolationCount"),
				requireInteger(node.required("maximumProviderCallCount"), path + ".maximumProviderCallCount"),
				requireInteger(node.required("maximumExperimentalSnapshotWriteCount"), path + ".maximumExperimentalSnapshotWriteCount"));
	}

	private static Interpretation parseInterpretation(JsonNode node, String path) {
		requireExactObject(node, path, INTERPRETATION_FIELDS);
		return new Interpretation(
				requireText(node.required("evidenceClassification"), path + ".evidenceClassification", 3, 80),
				requireText(node.required("latencyDecision"), path + ".latencyDecision", 3, 40),
				requireBoolean(node.required("qualifiedTargetEnvironmentRequired"), path + ".qualifiedTargetEnvironmentRequired"),
				requireBoolean(node.required("activationEvidence"), path + ".activationEvidence"));
	}

	private static void validateFrozenValues(RelatedTopicReuseScalePolicy policy) {
		if (policy.schemaVersion() != 1
				|| !POLICY_ID.equals(policy.policyId())
				|| policy.status() != Status.DIAGNOSTIC_ONLY
				|| !RelatedTopicReuseEvaluationPolicy.POLICY_ID.equals(policy.subjectPolicyId())
				|| !RelatedTopicReuseEvaluationPolicy.POLICY_SHA256.equals(policy.subjectPolicySha256())
				|| !ENVIRONMENT_GATE.equals(policy.environmentGate())
				|| !TestcontainersConfiguration.POSTGRES_IMAGE.equals(policy.databaseImage())
				|| policy.postgresMajorVersion() != 17
				|| !"DETERMINISTIC_SYNTHETIC_METADATA_ONLY".equals(policy.sourcePolicy())) {
			throw new IllegalArgumentException("Unexpected related-topic scale policy identity");
		}
		Corpus corpus = policy.corpus();
		int partitionTotal = Math.addExact(
				Math.addExact(corpus.targetSearchVisibleCount(), corpus.targetCollectionVisibleCount()),
				Math.addExact(corpus.otherOwnerVisibleCount(), corpus.catalogOnlyCount()));
		if (!RelatedTopicReuseScaleFixture.GENERATOR_VERSION.equals(corpus.generatorVersion())
				|| corpus.generatorSeed() != RelatedTopicReuseScaleFixture.GENERATOR_SEED
				|| !RelatedTopicReuseScaleFixture.generatorSqlSha256()
						.equals(corpus.generatorSqlSha256())
				|| corpus.totalPaperCount() != 100_000
				|| corpus.targetSearchVisibleCount() != 40_000
				|| corpus.targetCollectionVisibleCount() != 10_000
				|| corpus.otherOwnerVisibleCount() != 25_000
				|| corpus.catalogOnlyCount() != 25_000
				|| partitionTotal != corpus.totalPaperCount()) {
			throw new IllegalArgumentException("Related-topic scale corpus drifted");
		}
		if (!policy.workloads().equals(EXPECTED_WORKLOADS)
				|| EnumSet.copyOf(policy.workloads().stream().map(Workload::kind).toList())
						.size() != WorkloadKind.values().length) {
			throw new IllegalArgumentException("Related-topic scale workloads drifted");
		}
		Measurement measurement = policy.measurement();
		if (measurement.baselinePoolSize() != RelatedTopicRankFusion.MAXIMUM_BASELINE_CANDIDATES
				|| measurement.cutoff() != 10
				|| measurement.warmupRuns() != 2
				|| measurement.measurementRuns() != 30
				|| measurement.concurrency() != 1
				|| !"WARM".equals(measurement.cacheState())
				|| !"SYSTEM_NANO_TIME".equals(measurement.clock())
				|| !"ROTATING_CONTROL_FEEDBACK_FUSION".equals(measurement.stageOrder())
				|| !"NEAREST_RANK".equals(measurement.percentileMethod())
				|| !measurement.percentiles().equals(List.of(0.5d, 0.95d, 0.99d))
				|| measurement.outputSchemaVersion() != 1
				|| !"STDOUT_JSON_DIAGNOSTIC".equals(measurement.outputMode())) {
			throw new IllegalArgumentException("Related-topic scale measurement contract drifted");
		}
		StructuralGates gates = policy.structuralGates();
		if (!gates.requireStableControl()
				|| !gates.requireStableFeedback()
				|| !gates.requireStableFusion()
				|| !gates.requireExactNoSeedFallback()
				|| !gates.requireNonemptySeededFeedback()
				|| gates.maximumOwnerScopeLeakCount() != 0
				|| gates.maximumFilterViolationCount() != 0
				|| gates.maximumProviderCallCount() != 0
				|| gates.maximumExperimentalSnapshotWriteCount() != 0) {
			throw new IllegalArgumentException("Related-topic scale gates must remain fail-closed");
		}
		Interpretation interpretation = policy.interpretation();
		if (!"REFERENCE_SHAPED_DIAGNOSTIC".equals(interpretation.evidenceClassification())
				|| !"RECORD_ONLY".equals(interpretation.latencyDecision())
				|| !interpretation.qualifiedTargetEnvironmentRequired()
				|| interpretation.activationEvidence()) {
			throw new IllegalArgumentException("Related-topic scale interpretation drifted");
		}
	}

	private static byte[] readBounded(InputStream input) throws IOException {
		byte[] bytes = input.readNBytes(MAXIMUM_INPUT_BYTES + 1);
		if (bytes.length < 1 || bytes.length > MAXIMUM_INPUT_BYTES) {
			throw new IllegalArgumentException("scale policy must contain 1 through 65536 bytes");
		}
		return bytes;
	}

	private static String sha256(byte[] bytes) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
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
			throw new IllegalArgumentException(
					path + " must contain exactly " + fields + "; found " + actual);
		}
	}

	private static int requireInteger(JsonNode node, String path) {
		if (node == null || !node.isIntegralNumber() || !node.canConvertToInt()) {
			throw new IllegalArgumentException(path + " must be an integer");
		}
		return node.intValue();
	}

	private static Integer optionalInteger(JsonNode node, String path) {
		return node == null || node.isNull() ? null : requireInteger(node, path);
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
		return requireTextValue(node.textValue(), path, minimum, maximum);
	}

	private static String requireTextValue(String value, String path, int minimum, int maximum) {
		if (value == null
				|| !value.equals(value.strip())
				|| !Normalizer.isNormalized(value, Normalizer.Form.NFC)
				|| value.codePoints().anyMatch(Character::isISOControl)
				|| value.length() < minimum
				|| value.length() > maximum) {
			throw new IllegalArgumentException(
					path + " must be stripped NFC text with length " + minimum + " through " + maximum);
		}
		return value;
	}

	private static String requireDigest(String value, String path) {
		String digest = requireTextValue(value, path, 64, 64);
		if (!SHA256.matcher(digest).matches()) {
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
		requireUnique(values, path);
		return List.copyOf(values);
	}

	private static List<Double> requireNumberArray(JsonNode node, String path) {
		if (node == null || !node.isArray() || node.isEmpty()) {
			throw new IllegalArgumentException(path + " must be a non-empty array");
		}
		List<Double> values = new ArrayList<>(node.size());
		for (int index = 0; index < node.size(); index++) {
			double value = requireNumber(node.get(index), path + "[" + index + "]");
			if (value <= 0.0d || value > 1.0d) {
				throw new IllegalArgumentException(path + " values must be in (0, 1]");
			}
			values.add(value);
		}
		requireUnique(values, path);
		return List.copyOf(values);
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
		requireUnique(values, path);
		return List.copyOf(values);
	}

	private static void requireUnique(List<?> values, String path) {
		if (new LinkedHashSet<>(values).size() != values.size()) {
			throw new IllegalArgumentException(path + " must not contain duplicates");
		}
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

	record BoundPolicy(RelatedTopicReuseScalePolicy policy, String sha256) {

		BoundPolicy {
			Objects.requireNonNull(policy, "policy");
			sha256 = requireDigest(sha256, "sha256");
		}

		void validateReference(String expectedId, String expectedSha256) {
			if (!policy.policyId().equals(expectedId) || !sha256.equals(expectedSha256)) {
				throw new IllegalArgumentException("Related-topic scale policy reference does not match");
			}
		}
	}

	enum Status {
		DIAGNOSTIC_ONLY
	}

	enum WorkloadKind {
		NO_SEED_SCOPE_CONTROL,
		ONE_SEED_SPARSE,
		TWO_SEED_BROAD,
		FULLY_FILTERED_SELECTIVE
	}

	record Corpus(
			String generatorVersion,
			int generatorSeed,
			String generatorSqlSha256,
			int totalPaperCount,
			int targetSearchVisibleCount,
			int targetCollectionVisibleCount,
			int otherOwnerVisibleCount,
			int catalogOnlyCount) {
	}

	record Workload(
			String key,
			WorkloadKind kind,
			String query,
			Integer yearFrom,
			Integer yearTo,
			List<DocumentType> documentTypes,
			boolean openAccessOnly,
			int minimumCitations,
			List<String> languages,
			int expectedSeedCount) {

		Workload {
			key = requireTextValue(key, "workload.key", 3, 100);
			kind = Objects.requireNonNull(kind, "workload.kind");
			query = requireTextValue(query, "workload.query", 3, 500);
			documentTypes = List.copyOf(Objects.requireNonNull(documentTypes, "documentTypes"));
			languages = List.copyOf(Objects.requireNonNull(languages, "languages"));
			if (yearFrom != null && (yearFrom < 1000 || yearFrom > 9999)
					|| yearTo != null && (yearTo < 1000 || yearTo > 9999)
					|| yearFrom != null && yearTo != null && yearFrom > yearTo
					|| minimumCitations < 0
					|| expectedSeedCount < 0
					|| expectedSeedCount > OwnerScopedRelatedTopicComparator.MAXIMUM_SEEDS) {
				throw new IllegalArgumentException("Invalid related-topic scale workload bounds");
			}
		}
	}

	record Measurement(
			int baselinePoolSize,
			int cutoff,
			int warmupRuns,
			int measurementRuns,
			int concurrency,
			String cacheState,
			String clock,
			String stageOrder,
			String percentileMethod,
			List<Double> percentiles,
			int outputSchemaVersion,
			String outputMode) {

		Measurement {
			percentiles = List.copyOf(Objects.requireNonNull(percentiles, "percentiles"));
		}
	}

	record StructuralGates(
			boolean requireStableControl,
			boolean requireStableFeedback,
			boolean requireStableFusion,
			boolean requireExactNoSeedFallback,
			boolean requireNonemptySeededFeedback,
			int maximumOwnerScopeLeakCount,
			int maximumFilterViolationCount,
			int maximumProviderCallCount,
			int maximumExperimentalSnapshotWriteCount) {
	}

	record Interpretation(
			String evidenceClassification,
			String latencyDecision,
			boolean qualifiedTargetEnvironmentRequired,
			boolean activationEvidence) {
	}
}
