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
import java.util.regex.Pattern;

import org.springframework.core.io.ClassPathResource;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

record RelatedTopicReuseEvaluationPolicy(
		int schemaVersion,
		String policyId,
		String developmentFixtureId,
		String developmentFixtureSha256,
		Status status,
		String labelUnit,
		String sourcePolicy,
		Baseline baseline,
		Candidate candidate,
		Constraints constraints,
		List<String> metrics,
		Gates gates) {

	static final String RESOURCE_PATH =
			"search/relevance/related-topic-reuse-policy-v1.json";
	static final String POLICY_ID = "related-topic-reuse-policy-v1";
	static final String FIXTURE_ID = "related-topic-reuse-development-v1";
	static final String POLICY_SHA256 =
			"5538dc68135e98a77093b51ba70259a410b9ad56c87ddcd5262941fd80b84fa3";
	private static final int MAXIMUM_INPUT_BYTES = 64 * 1024;
	private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");
	private static final Set<String> ROOT_FIELDS = Set.of(
			"schemaVersion", "policyId", "developmentFixtureId", "developmentFixtureSha256",
			"status", "labelUnit", "sourcePolicy", "baseline", "candidate", "constraints",
			"metrics", "gates");
	private static final Set<String> BASELINE_FIELDS = Set.of(
			"pipelineVersion", "mode", "poolSize", "cutoff");
	private static final Set<String> CANDIDATE_FIELDS = Set.of(
			"seedSelection", "seedEligibilityFeatures", "maximumSeeds", "feedbackSignal", "textSearchConfiguration",
			"maximumSeedLexemes", "queryConstruction", "textRankNormalization",
			"maximumRelatedCandidatesPerSeed", "scopeOrder", "fusion", "tieBreak", "fallback");
	private static final Set<String> FUSION_FIELDS = Set.of(
			"method", "k", "baselineWeight", "totalFeedbackWeight", "normalizeBy");
	private static final Set<String> CONSTRAINT_FIELDS = Set.of(
			"queryCount", "candidateCount", "targetVisibleCandidateCount",
			"opportunityQueryCount", "controlQueryCount", "metadataOnly",
			"allTargetVisibleCandidatesJudged", "requiredLineageKinds",
			"requiredAdversaryKinds");
	private static final Set<String> GATE_FIELDS = Set.of(
			"minimumStrictOpportunityRecallImprovements", "minimumNovelRelevantAt10",
			"requireNoPerQueryRecallRegression", "requireNoPerQueryNdcgRegression",
			"requireNoMacroNdcgRegression",
			"requireNoControlRegression", "requireFilteredOpportunityStrictRecallImprovement",
			"requireAuthorControlRelevantBaselineHit", "maximumOwnerScopeLeakCount",
			"maximumFilterViolationCount", "maximumBaselineAdversaryAt10Count",
			"maximumCandidateAdversaryAt10Count", "maximumRankOneAdversaryCount",
			"maximumProviderCallCount", "maximumExperimentalSnapshotWriteCount",
			"requireRepeatedOrderAndScores", "requireHiddenCandidateNoninterference",
			"requireExactFallbackWithoutFeedback");
	private static final List<String> EXPECTED_SCOPE_ORDER = List.of(
			"OWNER_ELIGIBILITY", "COMMAND_FILTERS", "RELATED_MATCH", "RANK", "LIMIT");
	private static final List<String> EXPECTED_SEED_ELIGIBILITY_FEATURES = List.of(
			"TITLE_EXACT", "TITLE_PREFIX", "TITLE_CONTAINS", "POSTGRES_FULL_TEXT");
	private static final List<String> EXPECTED_TIE_BREAK = List.of(
			"FUSED_SCORE_DESC", "BASELINE_RANK_ASC_MISSING_LAST",
			"BEST_FEEDBACK_RANK_ASC_MISSING_LAST", "PAPER_UUID_ASC");
	private static final Set<String> EXPECTED_LINEAGE_KINDS = Set.of(
			"TARGET_OWNER_SEARCH", "TARGET_OWNER_COLLECTION", "OTHER_OWNER_SEARCH",
			"OTHER_OWNER_COLLECTION", "CATALOG_ONLY");
	private static final Set<String> EXPECTED_ADVERSARY_KINDS = Set.of(
			"OWNER_VISIBLE_TOPIC_DRIFT", "OTHER_OWNER_HIGHER_RELATED_SCORE",
			"CATALOG_ONLY_HIGHER_RELATED_SCORE", "FILTER_VIOLATION",
			"AUTHOR_SUBSTRING_COLLISION");
	private static final List<String> EXPECTED_METRICS = List.of(
			"RECALL_AT_10", "NDCG_AT_10", "PRECISION_AT_1",
			"MEAN_RECIPROCAL_RANK_AT_10", "NOVEL_RELEVANT_AT_10",
			"OWNER_SCOPE_LEAK_COUNT", "FILTER_VIOLATION_COUNT",
			"BASELINE_EXPLICIT_ADVERSARY_AT_10_COUNT",
			"CANDIDATE_EXPLICIT_ADVERSARY_AT_10_COUNT",
			"RANK_ONE_ADVERSARY_COUNT", "PROVIDER_CALL_COUNT",
			"EXPERIMENTAL_SNAPSHOT_WRITE_COUNT");

	RelatedTopicReuseEvaluationPolicy {
		policyId = requireTextValue(policyId, "policyId", 3, 100);
		developmentFixtureId = requireTextValue(
				developmentFixtureId, "developmentFixtureId", 3, 100);
		developmentFixtureSha256 = requireDigest(
				developmentFixtureSha256, "developmentFixtureSha256");
		status = Objects.requireNonNull(status, "status");
		labelUnit = requireTextValue(labelUnit, "labelUnit", 3, 100);
		sourcePolicy = requireTextValue(sourcePolicy, "sourcePolicy", 3, 100);
		baseline = Objects.requireNonNull(baseline, "baseline");
		candidate = Objects.requireNonNull(candidate, "candidate");
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

	static RelatedTopicReuseEvaluationPolicy parse(JsonNode root) {
		requireExactObject(root, "$", ROOT_FIELDS);
		RelatedTopicReuseEvaluationPolicy policy = new RelatedTopicReuseEvaluationPolicy(
				requireInteger(root.required("schemaVersion"), "$.schemaVersion"),
				requireText(root.required("policyId"), "$.policyId", 3, 100),
				requireText(root.required("developmentFixtureId"), "$.developmentFixtureId", 3, 100),
				requireText(root.required("developmentFixtureSha256"), "$.developmentFixtureSha256", 64, 64),
				requireEnum(root.required("status"), "$.status", Status.class),
				requireText(root.required("labelUnit"), "$.labelUnit", 3, 100),
				requireText(root.required("sourcePolicy"), "$.sourcePolicy", 3, 100),
				parseBaseline(root.required("baseline"), "$.baseline"),
				parseCandidate(root.required("candidate"), "$.candidate"),
				parseConstraints(root.required("constraints"), "$.constraints"),
				requireTextArray(root.required("metrics"), "$.metrics"),
				parseGates(root.required("gates"), "$.gates"));
		validateFrozenValues(policy);
		return policy;
	}

	void validateFixture(
			String fixtureId,
			String fixtureSha256,
			int queryCount,
			int candidateCount,
			int targetVisibleCount,
			int opportunityCount,
			int controlCount) {
		if (!developmentFixtureId.equals(fixtureId)
				|| !developmentFixtureSha256.equals(fixtureSha256)
				|| constraints.queryCount() != queryCount
				|| constraints.candidateCount() != candidateCount
				|| constraints.targetVisibleCandidateCount() != targetVisibleCount
				|| constraints.opportunityQueryCount() != opportunityCount
				|| constraints.controlQueryCount() != controlCount) {
			throw new IllegalArgumentException("Policy and related-topic fixture are not digest/count bound");
		}
	}

	private static Baseline parseBaseline(JsonNode node, String path) {
		requireExactObject(node, path, BASELINE_FIELDS);
		return new Baseline(
				requireText(node.required("pipelineVersion"), path + ".pipelineVersion", 3, 64),
				requireText(node.required("mode"), path + ".mode", 3, 32),
				requireInteger(node.required("poolSize"), path + ".poolSize"),
				requireInteger(node.required("cutoff"), path + ".cutoff"));
	}

	private static Candidate parseCandidate(JsonNode node, String path) {
		requireExactObject(node, path, CANDIDATE_FIELDS);
		return new Candidate(
				requireText(node.required("seedSelection"), path + ".seedSelection", 3, 64),
				requireTextArray(node.required("seedEligibilityFeatures"), path + ".seedEligibilityFeatures"),
				requireInteger(node.required("maximumSeeds"), path + ".maximumSeeds"),
				requireText(node.required("feedbackSignal"), path + ".feedbackSignal", 3, 64),
				requireText(node.required("textSearchConfiguration"), path + ".textSearchConfiguration", 3, 32),
				requireInteger(node.required("maximumSeedLexemes"), path + ".maximumSeedLexemes"),
				requireText(node.required("queryConstruction"), path + ".queryConstruction", 3, 64),
				requireInteger(node.required("textRankNormalization"), path + ".textRankNormalization"),
				requireInteger(node.required("maximumRelatedCandidatesPerSeed"), path + ".maximumRelatedCandidatesPerSeed"),
				requireTextArray(node.required("scopeOrder"), path + ".scopeOrder"),
				parseFusion(node.required("fusion"), path + ".fusion"),
				requireTextArray(node.required("tieBreak"), path + ".tieBreak"),
				requireText(node.required("fallback"), path + ".fallback", 3, 100));
	}

	private static Fusion parseFusion(JsonNode node, String path) {
		requireExactObject(node, path, FUSION_FIELDS);
		return new Fusion(
				requireText(node.required("method"), path + ".method", 3, 64),
				requireInteger(node.required("k"), path + ".k"),
				requireNumber(node.required("baselineWeight"), path + ".baselineWeight"),
				requireNumber(node.required("totalFeedbackWeight"), path + ".totalFeedbackWeight"),
				requireText(node.required("normalizeBy"), path + ".normalizeBy", 3, 64));
	}

	private static Constraints parseConstraints(JsonNode node, String path) {
		requireExactObject(node, path, CONSTRAINT_FIELDS);
		return new Constraints(
				requireInteger(node.required("queryCount"), path + ".queryCount"),
				requireInteger(node.required("candidateCount"), path + ".candidateCount"),
				requireInteger(node.required("targetVisibleCandidateCount"), path + ".targetVisibleCandidateCount"),
				requireInteger(node.required("opportunityQueryCount"), path + ".opportunityQueryCount"),
				requireInteger(node.required("controlQueryCount"), path + ".controlQueryCount"),
				requireBoolean(node.required("metadataOnly"), path + ".metadataOnly"),
				requireBoolean(node.required("allTargetVisibleCandidatesJudged"), path + ".allTargetVisibleCandidatesJudged"),
				requireTextArray(node.required("requiredLineageKinds"), path + ".requiredLineageKinds"),
				requireTextArray(node.required("requiredAdversaryKinds"), path + ".requiredAdversaryKinds"));
	}

	private static Gates parseGates(JsonNode node, String path) {
		requireExactObject(node, path, GATE_FIELDS);
		return new Gates(
				requireInteger(node.required("minimumStrictOpportunityRecallImprovements"), path + ".minimumStrictOpportunityRecallImprovements"),
				requireInteger(node.required("minimumNovelRelevantAt10"), path + ".minimumNovelRelevantAt10"),
				requireBoolean(node.required("requireNoPerQueryRecallRegression"), path + ".requireNoPerQueryRecallRegression"),
				requireBoolean(node.required("requireNoPerQueryNdcgRegression"), path + ".requireNoPerQueryNdcgRegression"),
				requireBoolean(node.required("requireNoMacroNdcgRegression"), path + ".requireNoMacroNdcgRegression"),
				requireBoolean(node.required("requireNoControlRegression"), path + ".requireNoControlRegression"),
				requireBoolean(node.required("requireFilteredOpportunityStrictRecallImprovement"), path + ".requireFilteredOpportunityStrictRecallImprovement"),
				requireBoolean(node.required("requireAuthorControlRelevantBaselineHit"), path + ".requireAuthorControlRelevantBaselineHit"),
				requireInteger(node.required("maximumOwnerScopeLeakCount"), path + ".maximumOwnerScopeLeakCount"),
				requireInteger(node.required("maximumFilterViolationCount"), path + ".maximumFilterViolationCount"),
				requireInteger(node.required("maximumBaselineAdversaryAt10Count"), path + ".maximumBaselineAdversaryAt10Count"),
				requireInteger(node.required("maximumCandidateAdversaryAt10Count"), path + ".maximumCandidateAdversaryAt10Count"),
				requireInteger(node.required("maximumRankOneAdversaryCount"), path + ".maximumRankOneAdversaryCount"),
				requireInteger(node.required("maximumProviderCallCount"), path + ".maximumProviderCallCount"),
				requireInteger(node.required("maximumExperimentalSnapshotWriteCount"), path + ".maximumExperimentalSnapshotWriteCount"),
				requireBoolean(node.required("requireRepeatedOrderAndScores"), path + ".requireRepeatedOrderAndScores"),
				requireBoolean(node.required("requireHiddenCandidateNoninterference"), path + ".requireHiddenCandidateNoninterference"),
				requireBoolean(node.required("requireExactFallbackWithoutFeedback"), path + ".requireExactFallbackWithoutFeedback"));
	}

	private static void validateFrozenValues(RelatedTopicReuseEvaluationPolicy policy) {
		if (policy.schemaVersion() != 1
				|| !POLICY_ID.equals(policy.policyId())
				|| !FIXTURE_ID.equals(policy.developmentFixtureId())
				|| policy.status() != Status.EVALUATION_ONLY
				|| !"CANONICAL_PAPER_TOPIC_RELEVANCE".equals(policy.labelUnit())
				|| !"SYNTHETIC_METADATA_ONLY".equals(policy.sourcePolicy())) {
			throw new IllegalArgumentException("Unexpected related-topic evaluation policy identity");
		}
		Baseline baseline = policy.baseline();
		if (!"local-catalog-v1".equals(baseline.pipelineVersion())
				|| !"LOCAL".equals(baseline.mode())
				|| baseline.poolSize() != 50 || baseline.cutoff() != 10) {
			throw new IllegalArgumentException("Production LOCAL control semantics drifted");
		}
		Candidate candidate = policy.candidate();
		if (!"PRODUCTION_LOCAL_TOPIC_SIGNAL_RANK".equals(candidate.seedSelection())
				|| !candidate.seedEligibilityFeatures().equals(EXPECTED_SEED_ELIGIBILITY_FEATURES)
				|| candidate.maximumSeeds() != 2
				|| !"SOURCE_TITLE_POSTGRES_FULL_TEXT".equals(candidate.feedbackSignal())
				|| !"english".equals(candidate.textSearchConfiguration())
				|| candidate.maximumSeedLexemes() != 16
				|| !"ORDERED_LEXEME_OR".equals(candidate.queryConstruction())
				|| candidate.textRankNormalization() != 32
				|| candidate.maximumRelatedCandidatesPerSeed() != 25
				|| !candidate.scopeOrder().equals(EXPECTED_SCOPE_ORDER)
				|| !candidate.tieBreak().equals(EXPECTED_TIE_BREAK)
				|| !"EXACT_BASELINE_WHEN_NO_NONEMPTY_FEEDBACK".equals(candidate.fallback())) {
			throw new IllegalArgumentException("Related-topic candidate semantics drifted");
		}
		Fusion fusion = candidate.fusion();
		if (!"WEIGHTED_RRF".equals(fusion.method()) || fusion.k() != 60
				|| Double.compare(fusion.baselineWeight(), 1.0d) != 0
				|| Double.compare(fusion.totalFeedbackWeight(), 1.0d) != 0
				|| !"NONEMPTY_FEEDBACK_LIST_COUNT".equals(fusion.normalizeBy())) {
			throw new IllegalArgumentException("Related-topic rank-fusion semantics drifted");
		}
		Constraints constraints = policy.constraints();
		if (constraints.queryCount() != 5 || constraints.candidateCount() != 25
				|| constraints.targetVisibleCandidateCount() != 19
				|| constraints.opportunityQueryCount() != 3 || constraints.controlQueryCount() != 2
				|| !constraints.metadataOnly() || !constraints.allTargetVisibleCandidatesJudged()
				|| !Set.copyOf(constraints.requiredLineageKinds()).equals(EXPECTED_LINEAGE_KINDS)
				|| !Set.copyOf(constraints.requiredAdversaryKinds()).equals(EXPECTED_ADVERSARY_KINDS)) {
			throw new IllegalArgumentException("Related-topic corpus constraints drifted");
		}
		if (!policy.metrics().equals(EXPECTED_METRICS)) {
			throw new IllegalArgumentException("Related-topic metrics drifted");
		}
		Gates gates = policy.gates();
		if (gates.minimumStrictOpportunityRecallImprovements() != 2
				|| gates.minimumNovelRelevantAt10() != 2
				|| !gates.requireNoPerQueryRecallRegression()
				|| !gates.requireNoPerQueryNdcgRegression()
				|| !gates.requireNoMacroNdcgRegression()
				|| !gates.requireNoControlRegression()
				|| !gates.requireFilteredOpportunityStrictRecallImprovement()
				|| !gates.requireAuthorControlRelevantBaselineHit()
				|| gates.maximumOwnerScopeLeakCount() != 0
				|| gates.maximumFilterViolationCount() != 0
				|| gates.maximumBaselineAdversaryAt10Count() != 1
				|| gates.maximumCandidateAdversaryAt10Count() != 3
				|| gates.maximumRankOneAdversaryCount() != 0
				|| gates.maximumProviderCallCount() != 0
				|| gates.maximumExperimentalSnapshotWriteCount() != 0
				|| !gates.requireRepeatedOrderAndScores()
				|| !gates.requireHiddenCandidateNoninterference()
				|| !gates.requireExactFallbackWithoutFeedback()) {
			throw new IllegalArgumentException("Related-topic structural gates must remain fail-closed");
		}
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
		if (value == null || !value.equals(value.strip())
				|| value.length() < minimum || value.length() > maximum) {
			throw new IllegalArgumentException(
					path + " must be stripped text with length " + minimum + " through " + maximum);
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
		if (node == null || !node.isArray() || node.isEmpty()) {
			throw new IllegalArgumentException(path + " must be a non-empty array");
		}
		List<String> values = new ArrayList<>(node.size());
		for (int index = 0; index < node.size(); index++) {
			values.add(requireText(node.get(index), path + "[" + index + "]", 1, 100));
		}
		if (new LinkedHashSet<>(values).size() != values.size()) {
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

	record BoundPolicy(RelatedTopicReuseEvaluationPolicy policy, String sha256) {

		BoundPolicy {
			Objects.requireNonNull(policy, "policy");
			sha256 = requireDigest(sha256, "sha256");
		}

		void validateReference(String expectedId, String expectedSha256) {
			if (!policy.policyId().equals(expectedId) || !sha256.equals(expectedSha256)) {
				throw new IllegalArgumentException("Related-topic policy reference does not match");
			}
		}
	}

	enum Status {
		EVALUATION_ONLY
	}

	record Baseline(String pipelineVersion, String mode, int poolSize, int cutoff) {
	}

	record Candidate(
			String seedSelection,
			List<String> seedEligibilityFeatures,
			int maximumSeeds,
			String feedbackSignal,
			String textSearchConfiguration,
			int maximumSeedLexemes,
			String queryConstruction,
			int textRankNormalization,
			int maximumRelatedCandidatesPerSeed,
			List<String> scopeOrder,
			Fusion fusion,
			List<String> tieBreak,
			String fallback) {

		Candidate {
			seedEligibilityFeatures = List.copyOf(seedEligibilityFeatures);
			scopeOrder = List.copyOf(scopeOrder);
			tieBreak = List.copyOf(tieBreak);
			Objects.requireNonNull(fusion, "fusion");
		}
	}

	record Fusion(
			String method,
			int k,
			double baselineWeight,
			double totalFeedbackWeight,
			String normalizeBy) {
	}

	record Constraints(
			int queryCount,
			int candidateCount,
			int targetVisibleCandidateCount,
			int opportunityQueryCount,
			int controlQueryCount,
			boolean metadataOnly,
			boolean allTargetVisibleCandidatesJudged,
			List<String> requiredLineageKinds,
			List<String> requiredAdversaryKinds) {

		Constraints {
			requiredLineageKinds = List.copyOf(requiredLineageKinds);
			requiredAdversaryKinds = List.copyOf(requiredAdversaryKinds);
		}
	}

	record Gates(
			int minimumStrictOpportunityRecallImprovements,
			int minimumNovelRelevantAt10,
			boolean requireNoPerQueryRecallRegression,
			boolean requireNoPerQueryNdcgRegression,
			boolean requireNoMacroNdcgRegression,
			boolean requireNoControlRegression,
			boolean requireFilteredOpportunityStrictRecallImprovement,
			boolean requireAuthorControlRelevantBaselineHit,
			int maximumOwnerScopeLeakCount,
			int maximumFilterViolationCount,
			int maximumBaselineAdversaryAt10Count,
			int maximumCandidateAdversaryAt10Count,
			int maximumRankOneAdversaryCount,
			int maximumProviderCallCount,
			int maximumExperimentalSnapshotWriteCount,
			boolean requireRepeatedOrderAndScores,
			boolean requireHiddenCandidateNoninterference,
			boolean requireExactFallbackWithoutFeedback) {
	}
}
