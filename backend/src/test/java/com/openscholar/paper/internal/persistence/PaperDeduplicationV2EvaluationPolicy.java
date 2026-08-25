package com.openscholar.paper.internal.persistence;

import java.io.InputStream;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import org.springframework.core.io.ClassPathResource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

record PaperDeduplicationV2EvaluationPolicy(
		int schemaVersion,
		String policyId,
		String developmentFixtureId,
		String developmentFixtureSha256,
		String status,
		String labelUnit,
		String sourcePolicy,
		ProductionBaseline productionBaseline,
		List<String> allowedFeatures,
		List<String> forbiddenFeatures,
		List<CaseFamily> caseFamilies,
		List<String> metrics,
		List<String> ingestOrderKeys,
		Activation activation) {

	private static final Set<String> ROOT_REQUIRED = Set.of(
			"schemaVersion", "policyId", "developmentFixtureId", "developmentFixtureSha256",
			"status", "labelUnit", "sourcePolicy", "productionBaseline", "allowedFeatures",
			"forbiddenFeatures", "caseFamilies", "metrics", "ingestOrderKeys", "activation");
	private static final Set<String> BASELINE_REQUIRED = Set.of(
			"algorithm", "matchPrecedence", "normalization", "metadataFallback");
	private static final Set<String> CASE_FAMILY_REQUIRED = Set.of(
			"key", "signalClass", "minimumGoldPositivePairs", "minimumCriticalMustSeparatePairs");
	private static final Set<String> ACTIVATION_REQUIRED = Set.of(
			"mode", "allGatesRequired", "holdoutRequired", "gates", "expectedExactBaseline");
	private static final Set<String> GATES_REQUIRED = Set.of(
			"minimumPairwisePrecision", "minimumBCubedPrecision", "maximumCriticalFalseMerges",
			"maximumCriticalMissedLinks",
			"minimumExactSignalRecall", "minimumOverallPairwiseRecall",
			"minimumMetadataOnlyRecall", "minimumPairwiseRecallGainOverExactBaseline",
			"minimumBCubedF1GainOverExactBaseline", "minimumPerCaseFamilyRecall",
			"minimumPositivePairsForFamilyGate", "requireOrderInvariantPartitions");
	private static final Set<String> EXPECTED_BASELINE_REQUIRED = Set.of(
			"recordCount", "goldClusterCount", "goldPositivePairCount", "truePositives",
			"falsePositives", "falseNegatives", "trueNegatives", "pairwisePrecision",
			"pairwiseRecall", "pairwiseF1", "bCubedPrecision", "bCubedRecall", "bCubedF1",
			"exactClusterMatches", "exactClusterMatchRate", "exactSignalRecall",
			"metadataOnlyRecall", "criticalFalseMerges", "criticalMissedLinks");
	private static final List<String> EXPECTED_MATCH_PRECEDENCE = List.of(
			"DOI", "OPENALEX", "PROVIDER_RECORD", "ANY_EXACT_IDENTIFIER");
	private static final List<String> EXPECTED_NORMALIZATION = List.of(
			"DOI_PREFIX_URL_AND_CASE",
			"OPENALEX_URL_AND_CASE",
			"ARXIV_URL_PDF_VERSION_AND_CASE",
			"OTHER_IDENTIFIER_TRIM_AND_CASE",
			"REPOSITORY_NAMESPACE_TRIM_AND_CASE");
	private static final double EPSILON = 1.0e-12d;

	PaperDeduplicationV2EvaluationPolicy {
		allowedFeatures = List.copyOf(Objects.requireNonNull(allowedFeatures, "allowedFeatures"));
		forbiddenFeatures = List.copyOf(Objects.requireNonNull(forbiddenFeatures, "forbiddenFeatures"));
		caseFamilies = List.copyOf(Objects.requireNonNull(caseFamilies, "caseFamilies"));
		metrics = List.copyOf(Objects.requireNonNull(metrics, "metrics"));
		ingestOrderKeys = List.copyOf(Objects.requireNonNull(ingestOrderKeys, "ingestOrderKeys"));
	}

	static PaperDeduplicationV2EvaluationPolicy load(
			ObjectMapper objectMapper, String resourcePath) throws Exception {
		ClassPathResource resource = new ClassPathResource(resourcePath);
		try (InputStream input = resource.getInputStream()) {
			return parse(objectMapper, objectMapper.readTree(input));
		}
	}

	static PaperDeduplicationV2EvaluationPolicy parse(
			ObjectMapper objectMapper, JsonNode root) throws Exception {
		validateSchema(root);
		PaperDeduplicationV2EvaluationPolicy policy = objectMapper.treeToValue(
				root, PaperDeduplicationV2EvaluationPolicy.class);
		policy.validateValues();
		return policy;
	}

	private static void validateSchema(JsonNode root) {
		requireObjectKeys(root, "$", ROOT_REQUIRED);
		requireInteger(root.required("schemaVersion"), "$.schemaVersion");
		requireTextFields(root, "$", List.of(
				"policyId", "developmentFixtureId", "developmentFixtureSha256", "status",
				"labelUnit", "sourcePolicy"));

		JsonNode baseline = root.required("productionBaseline");
		requireObjectKeys(baseline, "$.productionBaseline", BASELINE_REQUIRED);
		requireTextFields(baseline, "$.productionBaseline", List.of("algorithm", "metadataFallback"));
		requireTextArray(baseline.required("matchPrecedence"), "$.productionBaseline.matchPrecedence");
		requireTextArray(baseline.required("normalization"), "$.productionBaseline.normalization");

		requireTextArray(root.required("allowedFeatures"), "$.allowedFeatures");
		requireTextArray(root.required("forbiddenFeatures"), "$.forbiddenFeatures");
		requireTextArray(root.required("metrics"), "$.metrics");
		requireTextArray(root.required("ingestOrderKeys"), "$.ingestOrderKeys");

		JsonNode caseFamilies = requireArray(root.required("caseFamilies"), "$.caseFamilies");
		for (int index = 0; index < caseFamilies.size(); index++) {
			JsonNode family = caseFamilies.get(index);
			String path = "$.caseFamilies[" + index + "]";
			requireObjectKeys(family, path, CASE_FAMILY_REQUIRED);
			requireTextFields(family, path, List.of("key", "signalClass"));
			requireInteger(
					family.required("minimumGoldPositivePairs"), path + ".minimumGoldPositivePairs");
			requireInteger(
					family.required("minimumCriticalMustSeparatePairs"),
					path + ".minimumCriticalMustSeparatePairs");
		}

		JsonNode activation = root.required("activation");
		requireObjectKeys(activation, "$.activation", ACTIVATION_REQUIRED);
		requireText(activation.required("mode"), "$.activation.mode");
		requireBoolean(activation.required("allGatesRequired"), "$.activation.allGatesRequired");
		requireBoolean(activation.required("holdoutRequired"), "$.activation.holdoutRequired");

		JsonNode gates = activation.required("gates");
		requireObjectKeys(gates, "$.activation.gates", GATES_REQUIRED);
		for (String field : List.of(
				"minimumPairwisePrecision", "minimumBCubedPrecision", "minimumExactSignalRecall",
				"minimumOverallPairwiseRecall", "minimumMetadataOnlyRecall",
				"minimumPairwiseRecallGainOverExactBaseline",
				"minimumBCubedF1GainOverExactBaseline", "minimumPerCaseFamilyRecall")) {
			requireNumber(gates.required(field), "$.activation.gates." + field);
		}
		requireInteger(
				gates.required("maximumCriticalFalseMerges"),
				"$.activation.gates.maximumCriticalFalseMerges");
		requireInteger(
				gates.required("maximumCriticalMissedLinks"),
				"$.activation.gates.maximumCriticalMissedLinks");
		requireInteger(
				gates.required("minimumPositivePairsForFamilyGate"),
				"$.activation.gates.minimumPositivePairsForFamilyGate");
		requireBoolean(
				gates.required("requireOrderInvariantPartitions"),
				"$.activation.gates.requireOrderInvariantPartitions");

		JsonNode expected = activation.required("expectedExactBaseline");
		requireObjectKeys(expected, "$.activation.expectedExactBaseline", EXPECTED_BASELINE_REQUIRED);
		for (String field : List.of(
				"recordCount", "goldClusterCount", "goldPositivePairCount", "truePositives",
				"falsePositives", "falseNegatives", "trueNegatives", "exactClusterMatches",
				"criticalFalseMerges", "criticalMissedLinks")) {
			requireInteger(expected.required(field), "$.activation.expectedExactBaseline." + field);
		}
		for (String field : List.of(
				"pairwisePrecision", "pairwiseRecall", "pairwiseF1", "bCubedPrecision",
				"bCubedRecall", "bCubedF1", "exactClusterMatchRate", "exactSignalRecall",
				"metadataOnlyRecall")) {
			requireNumber(expected.required(field), "$.activation.expectedExactBaseline." + field);
		}
	}

	private void validateValues() {
		if (schemaVersion != 2) {
			throw new IllegalArgumentException("schemaVersion must be 2");
		}
		if (!"paper-deduplication-policy-v2".equals(policyId)
				|| !"paper-deduplication-development-v2".equals(developmentFixtureId)) {
			throw new IllegalArgumentException("Unexpected v2 policy or development-fixture identity");
		}
		if (!developmentFixtureSha256.matches("[0-9a-f]{64}")) {
			throw new IllegalArgumentException("developmentFixtureSha256 must be lowercase SHA-256");
		}
		if (!"EVALUATION_ONLY".equals(status)) {
			throw new IllegalArgumentException("status must be EVALUATION_ONLY");
		}
		if (!"BIBLIOGRAPHIC_MANIFESTATION".equals(labelUnit)
				|| !"SYNTHETIC_METADATA_ONLY".equals(sourcePolicy)) {
			throw new IllegalArgumentException("Unexpected v2 label or source policy");
		}
		if (!"EXACT_IDENTIFIERS_AND_PROVIDER_RECORDS_ONLY".equals(productionBaseline.algorithm())) {
			throw new IllegalArgumentException("Unexpected production baseline algorithm");
		}
		if (!productionBaseline.matchPrecedence().equals(EXPECTED_MATCH_PRECEDENCE)
				|| !productionBaseline.normalization().equals(EXPECTED_NORMALIZATION)) {
			throw new IllegalArgumentException("Production-baseline precedence or normalization drifted");
		}
		if (!"DISABLED".equals(productionBaseline.metadataFallback())) {
			throw new IllegalArgumentException("Production metadataFallback must stay DISABLED");
		}
		if (!"KEEP_EXACT_PRODUCTION_BASELINE_UNLESS_EVERY_GATE_PASSES"
				.equals(activation.mode())) {
			throw new IllegalArgumentException("Unexpected activation mode");
		}
		if (!activation.allGatesRequired() || !activation.holdoutRequired()) {
			throw new IllegalArgumentException("Activation must require all gates and a future holdout");
		}
		Set<String> familyKeys = new LinkedHashSet<>();
		for (CaseFamily family : caseFamilies) {
			if (!familyKeys.add(family.key())) {
				throw new IllegalArgumentException("Duplicate case-family key: " + family.key());
			}
			if (family.minimumGoldPositivePairs() < 0
					|| family.minimumCriticalMustSeparatePairs() < 0) {
				throw new IllegalArgumentException("Case-family minima must be non-negative");
			}
		}
		requireUniqueNonEmpty(allowedFeatures, "allowedFeatures");
		requireUniqueNonEmpty(forbiddenFeatures, "forbiddenFeatures");
		Set<String> forbiddenOverlap = new HashSet<>(allowedFeatures);
		forbiddenOverlap.retainAll(forbiddenFeatures);
		if (!forbiddenOverlap.isEmpty()) {
			throw new IllegalArgumentException(
					"Features cannot be both allowed and forbidden: " + forbiddenOverlap);
		}
		requireUniqueNonEmpty(metrics, "metrics");
		requireUniqueNonEmpty(ingestOrderKeys, "ingestOrderKeys");
		Gates gates = activation.gates();
		for (double value : List.of(
				gates.minimumPairwisePrecision(), gates.minimumBCubedPrecision(),
				gates.minimumExactSignalRecall(), gates.minimumOverallPairwiseRecall(),
				gates.minimumMetadataOnlyRecall(), gates.minimumPairwiseRecallGainOverExactBaseline(),
				gates.minimumBCubedF1GainOverExactBaseline(), gates.minimumPerCaseFamilyRecall())) {
			if (value < 0.0d || value > 1.0d) {
				throw new IllegalArgumentException("Activation rates must be within [0, 1]");
			}
		}
		if (gates.maximumCriticalFalseMerges() < 0 || gates.maximumCriticalMissedLinks() < 0
				|| gates.minimumPositivePairsForFamilyGate() < 1) {
			throw new IllegalArgumentException("Activation count gates are invalid");
		}
		validateExpectedBaseline(activation.expectedExactBaseline(), gates);
	}

	private static void validateExpectedBaseline(ExpectedExactBaseline expected, Gates gates) {
		if (expected.recordCount() < 2 || expected.goldClusterCount() < 1
				|| expected.exactClusterMatches() < 0
				|| expected.exactClusterMatches() > expected.goldClusterCount()) {
			throw new IllegalArgumentException("Expected exact-baseline corpus counts are invalid");
		}
		int totalPairs = expected.recordCount() * (expected.recordCount() - 1) / 2;
		if (expected.goldPositivePairCount() != expected.truePositives() + expected.falseNegatives()
				|| totalPairs != expected.truePositives() + expected.falsePositives()
						+ expected.falseNegatives() + expected.trueNegatives()) {
			throw new IllegalArgumentException("Expected exact-baseline pair counts are inconsistent");
		}
		double pairwisePrecision = ratioOrOne(
				expected.truePositives(), expected.truePositives() + expected.falsePositives());
		double pairwiseRecall = ratioOrOne(
				expected.truePositives(), expected.truePositives() + expected.falseNegatives());
		double pairwiseF1 = harmonicMean(pairwisePrecision, pairwiseRecall);
		double bCubedF1 = harmonicMean(expected.bCubedPrecision(), expected.bCubedRecall());
		double exactClusterRate = ratioOrOne(
				expected.exactClusterMatches(), expected.goldClusterCount());
		if (!close(expected.pairwisePrecision(), pairwisePrecision)
				|| !close(expected.pairwiseRecall(), pairwiseRecall)
				|| !close(expected.pairwiseF1(), pairwiseF1)
				|| !close(expected.bCubedF1(), bCubedF1)
				|| !close(expected.exactClusterMatchRate(), exactClusterRate)) {
			throw new IllegalArgumentException("Expected exact-baseline rates are inconsistent");
		}
		for (double rate : List.of(
				expected.bCubedPrecision(), expected.bCubedRecall(), expected.exactSignalRecall(),
				expected.metadataOnlyRecall())) {
			if (rate < 0.0d || rate > 1.0d) {
				throw new IllegalArgumentException("Expected exact-baseline rates must be within [0, 1]");
			}
		}
		if (expected.criticalFalseMerges() < 0
				|| expected.criticalFalseMerges() > gates.maximumCriticalFalseMerges()
				|| expected.criticalMissedLinks() < 0
				|| expected.criticalMissedLinks() > gates.maximumCriticalMissedLinks()) {
			throw new IllegalArgumentException("Expected baseline violates critical-pair safety");
		}
		if (expected.pairwisePrecision() < gates.minimumPairwisePrecision()
				|| expected.bCubedPrecision() < gates.minimumBCubedPrecision()
				|| expected.exactSignalRecall() < gates.minimumExactSignalRecall()
				|| expected.metadataOnlyRecall() >= gates.minimumMetadataOnlyRecall()) {
			throw new IllegalArgumentException("Expected baseline safety/activation boundary drifted");
		}
	}

	private static double ratioOrOne(int numerator, int denominator) {
		return denominator == 0 ? 1.0d : (double) numerator / denominator;
	}

	private static double harmonicMean(double left, double right) {
		return left + right == 0.0d ? 0.0d : 2.0d * left * right / (left + right);
	}

	private static boolean close(double left, double right) {
		return Math.abs(left - right) <= EPSILON;
	}

	private static void requireUniqueNonEmpty(List<String> values, String field) {
		if (values.isEmpty() || new HashSet<>(values).size() != values.size()
				|| values.stream().anyMatch(value -> value == null || value.isBlank())) {
			throw new IllegalArgumentException(field + " must contain unique non-blank values");
		}
	}

	private static void requireObjectKeys(JsonNode node, String path, Set<String> required) {
		if (!node.isObject()) {
			throw new IllegalArgumentException(path + " must be an object");
		}
		Set<String> actual = new LinkedHashSet<>(node.propertyNames());
		Set<String> unknown = new LinkedHashSet<>(actual);
		unknown.removeAll(required);
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

	private static void requireNumber(JsonNode node, String path) {
		if (!node.isNumber()) {
			throw new IllegalArgumentException(path + " must be a number");
		}
	}

	private static void requireBoolean(JsonNode node, String path) {
		if (!node.isBoolean()) {
			throw new IllegalArgumentException(path + " must be a boolean");
		}
	}

	record ProductionBaseline(
			String algorithm,
			List<String> matchPrecedence,
			List<String> normalization,
			String metadataFallback) {

		ProductionBaseline {
			matchPrecedence = List.copyOf(Objects.requireNonNull(matchPrecedence, "matchPrecedence"));
			normalization = List.copyOf(Objects.requireNonNull(normalization, "normalization"));
		}
	}

	record CaseFamily(
			String key,
			SignalClass signalClass,
			int minimumGoldPositivePairs,
			int minimumCriticalMustSeparatePairs) {
	}

	enum SignalClass {
		EXACT_SIGNAL,
		METADATA_ONLY,
		MUST_SEPARATE
	}

	record Activation(
			String mode,
			boolean allGatesRequired,
			boolean holdoutRequired,
			Gates gates,
			ExpectedExactBaseline expectedExactBaseline) {
	}

	record Gates(
			double minimumPairwisePrecision,
			double minimumBCubedPrecision,
			int maximumCriticalFalseMerges,
			int maximumCriticalMissedLinks,
			double minimumExactSignalRecall,
			double minimumOverallPairwiseRecall,
			double minimumMetadataOnlyRecall,
			double minimumPairwiseRecallGainOverExactBaseline,
			double minimumBCubedF1GainOverExactBaseline,
			double minimumPerCaseFamilyRecall,
			int minimumPositivePairsForFamilyGate,
			boolean requireOrderInvariantPartitions) {
	}

	record ExpectedExactBaseline(
			int recordCount,
			int goldClusterCount,
			int goldPositivePairCount,
			int truePositives,
			int falsePositives,
			int falseNegatives,
			int trueNegatives,
			double pairwisePrecision,
			double pairwiseRecall,
			double pairwiseF1,
			double bCubedPrecision,
			double bCubedRecall,
			double bCubedF1,
			int exactClusterMatches,
			double exactClusterMatchRate,
			double exactSignalRecall,
			double metadataOnlyRecall,
			int criticalFalseMerges,
			int criticalMissedLinks) {
	}
}
