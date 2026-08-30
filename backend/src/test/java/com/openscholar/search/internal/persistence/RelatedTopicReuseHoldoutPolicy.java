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
import tools.jackson.core.json.JsonReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Frozen preregistration for a future externally authored related-topic holdout.
 *
 * <p>This policy contains no holdout corpus, judgments, evaluator, or result. It
 * freezes the input boundary and decision rules before any real holdout is
 * supplied.</p>
 */
record RelatedTopicReuseHoldoutPolicy(
		int schemaVersion,
		String policyId,
		Status status,
		String candidateFreezeRevision,
		String candidatePolicyId,
		String candidatePolicySha256,
		String developmentFixtureId,
		String developmentFixtureSha256,
		String labelUnit,
		String sourcePolicy,
		BundleContract bundle,
		CorpusContract corpus,
		JudgmentsContract judgments,
		EvaluationContract evaluation,
		List<String> metrics,
		Gates gates,
		RequiredDeclarations requiredDeclarations,
		Interpretation interpretation) {

	static final String RESOURCE_PATH =
			"search/relevance/related-topic-reuse-holdout-policy-v1.json";
	static final String POLICY_ID = "related-topic-reuse-holdout-policy-v1";
	static final String EVALUATION_PROTOCOL_ID =
			"related-topic-reuse-holdout-evaluation-v1";
	static final String CANDIDATE_FREEZE_REVISION =
			"6b22f6185d9c14a3dd0bf0a80a4b08c045396bff";
	static final String POLICY_SHA256 =
			"4c020b90aae6365f8a245d200a4758734fd3c74d831156e92572a36eada73582";
	static final int MAXIMUM_INPUT_BYTES = 64 * 1024;

	private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");
	private static final Pattern GIT_REVISION = Pattern.compile("[0-9a-f]{40}");
	private static final Set<String> ROOT_FIELDS = Set.of(
			"schemaVersion", "policyId", "status", "candidateFreezeRevision",
			"candidatePolicyId", "candidatePolicySha256", "developmentFixtureId",
			"developmentFixtureSha256", "labelUnit", "sourcePolicy", "bundle",
			"corpus", "judgments", "evaluation", "metrics", "gates", "requiredDeclarations",
			"interpretation");
	private static final Set<String> BUNDLE_FIELDS = Set.of(
			"protocolId", "requiredFiles", "maximumTotalBytes", "maximumManifestBytes",
			"maximumCorpusBytes", "maximumJudgmentsBytes", "requireExactFileSet",
			"requireAbsoluteExternalPath", "rejectRepositoryContainment", "rejectSymlinks",
			"requireSha256Manifest", "requireStrictJson");
	private static final Set<String> CORPUS_FIELDS = Set.of(
			"split", "minimumQueryCount", "maximumQueryCount", "minimumCandidateCount",
			"maximumCandidateCount", "minimumTargetVisibleCandidateCount",
			"minimumOtherOwnerCandidateCount", "minimumCatalogOnlyCandidateCount",
			"minimumOpportunityQueryCount", "minimumControlQueryCount",
			"minimumFullyFilteredQueryCount", "minimumNoSeedControlCount", "metadataOnly",
			"prohibitOracleFields",
			"requireCandidateKeyDisjointFromDevelopment",
			"requireQueryKeyDisjointFromDevelopment",
			"requireNormalizedQueryTextDisjointFromDevelopment",
			"requireNormalizedTitleDisjointFromDevelopment", "requiredLineageKinds",
			"requiredQueryKinds");
	private static final Set<String> JUDGMENT_FIELDS = Set.of(
			"minimumRelevanceGrade", "maximumRelevanceGrade",
			"allTargetVisibleCandidatesJudged", "requireAdversaryAnnotations",
			"requireAdversaryReasons", "prohibitCandidateOutputFields",
			"requiredAdversaryKinds");
	private static final Set<String> EVALUATION_FIELDS = Set.of(
			"protocolId", "cutoff", "relevanceThreshold", "rankingInputRule",
			"gradedGain", "rankDiscount", "idealDcg", "recallDenominator",
			"precisionAt1", "reciprocalRank", "noRelevantRecall", "noRelevantNdcg",
			"noRelevantPrecisionAt1", "noRelevantReciprocalRank", "macroAggregation",
			"deltaDirection", "comparisonEpsilon", "comparisonRule", "floatingThresholdRule",
			"integerThresholdRule", "metricArithmetic", "controlRegressionScope",
			"novelRelevantAt10",
			"adversaryInspectionScope", "rankOneIrrelevantScope",
			"scopeViolationInspectionScope", "stabilityRule",
			"hiddenCandidatePerturbation", "labelIsolation", "implementationFreezeRule");
	private static final Set<String> GATE_FIELDS = Set.of(
			"cutoff", "minimumMacroNdcgDelta", "minimumMacroRecallDelta",
			"minimumMacroPrecisionAt1Delta", "minimumMacroMeanReciprocalRankAt10Delta",
			"minimumStrictOpportunityRecallImprovements", "minimumNovelRelevantAt10",
			"maximumPerQueryNdcgRegressionCount", "maximumPerQueryNdcgRegressionMagnitude",
			"requireNoPerQueryRecallRegression", "requireNoControlRegression",
			"requireFilteredOpportunityStrictRecallImprovement",
			"requireAuthorControlRelevantBaselineHit",
			"requireAuthorControlZeroEligibleSeedsAndFeedback",
			"requireNoSeedZeroEligibleSeedsAndFeedback",
			"maximumRankOneIrrelevantCount", "maximumOwnerScopeLeakCount",
			"maximumFilterViolationCount", "maximumProviderCallCount",
			"maximumExperimentalSnapshotWriteCount", "requireRepeatedOrderAndScores",
			"requireHiddenCandidateNoninterference", "requireExactFallbackWithoutFeedback");
	private static final Set<String> DECLARATION_FIELDS = Set.of(
			"corpusAuthorship", "judgmentAuthorship", "firstRunRule", "noRetuningRule",
			"externalCustodyRule", "evaluatorFreezeRule", "requiredLimitations");
	private static final Set<String> INTERPRETATION_FIELDS = Set.of(
			"evidenceClassification", "realHoldoutIncluded", "evaluatorIncluded",
			"externalBundleAcceptanceAuthorized", "custodyReleaseAuthorized",
			"productActivationAuthorized", "targetDeploymentEvidenceStillRequired",
			"readerFacingMetrics");

	private static final List<String> EXPECTED_FILES = List.of(
			"manifest.json", "holdout-corpus.json", "judgments.json");
	private static final Set<String> EXPECTED_LINEAGE_KINDS = Set.of(
			"TARGET_OWNER_SEARCH", "TARGET_OWNER_COLLECTION", "OTHER_OWNER_SEARCH",
			"OTHER_OWNER_COLLECTION", "CATALOG_ONLY");
	private static final Set<String> EXPECTED_QUERY_KINDS = Set.of(
			"LEXICAL_BRIDGE_OPPORTUNITY", "FILTERED_LEXICAL_BRIDGE_OPPORTUNITY",
			"AUTHOR_NO_RELATED_SIGNAL_CONTROL", "NO_SEED_FALLBACK_CONTROL");
	private static final Set<String> EXPECTED_ADVERSARY_KINDS = Set.of(
			"OWNER_VISIBLE_TOPIC_DRIFT", "OTHER_OWNER_TOPIC_MATCH",
			"CATALOG_ONLY_TOPIC_MATCH", "FILTER_VIOLATION",
			"AUTHOR_SUBSTRING_COLLISION");
	private static final List<String> EXPECTED_METRICS = List.of(
			"RECALL_AT_10",
			"NDCG_AT_10",
			"PRECISION_AT_1",
			"MEAN_RECIPROCAL_RANK_AT_10",
			"MACRO_RECALL_AT_10_DELTA",
			"MACRO_NDCG_AT_10_DELTA",
			"MACRO_PRECISION_AT_1_DELTA",
			"MACRO_MEAN_RECIPROCAL_RANK_AT_10_DELTA",
			"STRICT_OPPORTUNITY_RECALL_IMPROVEMENT_COUNT",
			"NOVEL_RELEVANT_AT_10",
			"PER_QUERY_NDCG_REGRESSION_COUNT",
			"MAXIMUM_PER_QUERY_NDCG_REGRESSION",
			"CONTROL_EXPLICIT_ADVERSARY_AT_10_COUNT",
			"CANDIDATE_EXPLICIT_ADVERSARY_AT_10_COUNT",
			"RANK_ONE_IRRELEVANT_COUNT",
			"OWNER_SCOPE_LEAK_COUNT",
			"FILTER_VIOLATION_COUNT",
			"PROVIDER_CALL_COUNT",
			"EXPERIMENTAL_SNAPSHOT_WRITE_COUNT");
	private static final List<String> EXPECTED_LIMITATIONS = List.of(
			"DECLARATIONS_ARE_PROCEDURAL_NOT_CRYPTOGRAPHIC_PROOF",
			"SHA256_PROVES_INTEGRITY_NOT_AUTHORSHIP_OR_AUTHENTICITY",
			"LITERAL_DISJOINTNESS_DOES_NOT_PROVE_SEMANTIC_DISJOINTNESS");

	RelatedTopicReuseHoldoutPolicy {
		policyId = requireTextValue(policyId, "policyId", 3, 100);
		status = Objects.requireNonNull(status, "status");
		candidateFreezeRevision = requireRevision(
				candidateFreezeRevision, "candidateFreezeRevision");
		candidatePolicyId = requireTextValue(
				candidatePolicyId, "candidatePolicyId", 3, 100);
		candidatePolicySha256 = requireDigest(
				candidatePolicySha256, "candidatePolicySha256");
		developmentFixtureId = requireTextValue(
				developmentFixtureId, "developmentFixtureId", 3, 100);
		developmentFixtureSha256 = requireDigest(
				developmentFixtureSha256, "developmentFixtureSha256");
		labelUnit = requireTextValue(labelUnit, "labelUnit", 3, 100);
		sourcePolicy = requireTextValue(sourcePolicy, "sourcePolicy", 3, 100);
		bundle = Objects.requireNonNull(bundle, "bundle");
		corpus = Objects.requireNonNull(corpus, "corpus");
		judgments = Objects.requireNonNull(judgments, "judgments");
		evaluation = Objects.requireNonNull(evaluation, "evaluation");
		metrics = List.copyOf(Objects.requireNonNull(metrics, "metrics"));
		gates = Objects.requireNonNull(gates, "gates");
		requiredDeclarations = Objects.requireNonNull(
				requiredDeclarations, "requiredDeclarations");
		interpretation = Objects.requireNonNull(interpretation, "interpretation");
	}

	static BoundPolicy loadFrozen(ObjectMapper objectMapper) throws IOException {
		BoundPolicy bound = loadBound(objectMapper, RESOURCE_PATH);
		bound.validateReference(POLICY_ID, POLICY_SHA256);
		return bound;
	}

	static BoundPolicy loadBound(ObjectMapper objectMapper, String resourcePath)
			throws IOException {
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
				.withoutFeatures(JsonReadFeature.values())
				.without(StreamReadFeature.IGNORE_UNDEFINED)
				.with(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
				.with(
						DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY,
						DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
				.readTree(bytes);
		return new BoundPolicy(parse(root), sha256(bytes));
	}

	static RelatedTopicReuseHoldoutPolicy parse(JsonNode root) {
		requireExactObject(root, "$", ROOT_FIELDS);
		RelatedTopicReuseHoldoutPolicy policy = new RelatedTopicReuseHoldoutPolicy(
				requireInteger(root.required("schemaVersion"), "$.schemaVersion"),
				requireText(root.required("policyId"), "$.policyId", 3, 100),
				requireEnum(root.required("status"), "$.status", Status.class),
				requireText(
						root.required("candidateFreezeRevision"),
						"$.candidateFreezeRevision",
						40,
						40),
				requireText(root.required("candidatePolicyId"), "$.candidatePolicyId", 3, 100),
				requireText(
						root.required("candidatePolicySha256"),
						"$.candidatePolicySha256",
						64,
						64),
				requireText(
						root.required("developmentFixtureId"),
						"$.developmentFixtureId",
						3,
						100),
				requireText(
						root.required("developmentFixtureSha256"),
						"$.developmentFixtureSha256",
						64,
						64),
				requireText(root.required("labelUnit"), "$.labelUnit", 3, 100),
				requireText(root.required("sourcePolicy"), "$.sourcePolicy", 3, 100),
				parseBundle(root.required("bundle"), "$.bundle"),
				parseCorpus(root.required("corpus"), "$.corpus"),
				parseJudgments(root.required("judgments"), "$.judgments"),
				parseEvaluation(root.required("evaluation"), "$.evaluation"),
				requireTextArray(root.required("metrics"), "$.metrics"),
				parseGates(root.required("gates"), "$.gates"),
				parseRequiredDeclarations(
						root.required("requiredDeclarations"), "$.requiredDeclarations"),
				parseInterpretation(root.required("interpretation"), "$.interpretation"));
		validateFrozenValues(policy);
		return policy;
	}

	void validateFrozenInputs(
			RelatedTopicReuseEvaluationPolicy.BoundPolicy candidatePolicy,
			RelatedTopicReuseEvaluationFixture.BoundFixture developmentFixture) {
		Objects.requireNonNull(candidatePolicy, "candidatePolicy");
		Objects.requireNonNull(developmentFixture, "developmentFixture");
		if (!candidatePolicyId.equals(candidatePolicy.policy().policyId())
				|| !candidatePolicySha256.equals(candidatePolicy.sha256())
				|| !developmentFixtureId.equals(developmentFixture.fixture().fixtureId())
				|| !developmentFixtureSha256.equals(developmentFixture.sha256())) {
			throw new IllegalArgumentException(
					"Holdout preregistration is not bound to the frozen candidate inputs");
		}
	}

	private static BundleContract parseBundle(JsonNode node, String path) {
		requireExactObject(node, path, BUNDLE_FIELDS);
		return new BundleContract(
				requireText(node.required("protocolId"), path + ".protocolId", 3, 100),
				requireTextArray(node.required("requiredFiles"), path + ".requiredFiles"),
				requireInteger(node.required("maximumTotalBytes"), path + ".maximumTotalBytes"),
				requireInteger(
						node.required("maximumManifestBytes"), path + ".maximumManifestBytes"),
				requireInteger(node.required("maximumCorpusBytes"), path + ".maximumCorpusBytes"),
				requireInteger(
						node.required("maximumJudgmentsBytes"), path + ".maximumJudgmentsBytes"),
				requireBoolean(node.required("requireExactFileSet"), path + ".requireExactFileSet"),
				requireBoolean(
						node.required("requireAbsoluteExternalPath"),
						path + ".requireAbsoluteExternalPath"),
				requireBoolean(
						node.required("rejectRepositoryContainment"),
						path + ".rejectRepositoryContainment"),
				requireBoolean(node.required("rejectSymlinks"), path + ".rejectSymlinks"),
				requireBoolean(
						node.required("requireSha256Manifest"), path + ".requireSha256Manifest"),
				requireBoolean(node.required("requireStrictJson"), path + ".requireStrictJson"));
	}

	private static CorpusContract parseCorpus(JsonNode node, String path) {
		requireExactObject(node, path, CORPUS_FIELDS);
		return new CorpusContract(
				requireEnum(node.required("split"), path + ".split", Split.class),
				requireInteger(node.required("minimumQueryCount"), path + ".minimumQueryCount"),
				requireInteger(node.required("maximumQueryCount"), path + ".maximumQueryCount"),
				requireInteger(
						node.required("minimumCandidateCount"), path + ".minimumCandidateCount"),
				requireInteger(
						node.required("maximumCandidateCount"), path + ".maximumCandidateCount"),
				requireInteger(
						node.required("minimumTargetVisibleCandidateCount"),
						path + ".minimumTargetVisibleCandidateCount"),
				requireInteger(
						node.required("minimumOtherOwnerCandidateCount"),
						path + ".minimumOtherOwnerCandidateCount"),
				requireInteger(
						node.required("minimumCatalogOnlyCandidateCount"),
						path + ".minimumCatalogOnlyCandidateCount"),
				requireInteger(
						node.required("minimumOpportunityQueryCount"),
						path + ".minimumOpportunityQueryCount"),
				requireInteger(
						node.required("minimumControlQueryCount"),
						path + ".minimumControlQueryCount"),
				requireInteger(
						node.required("minimumFullyFilteredQueryCount"),
						path + ".minimumFullyFilteredQueryCount"),
				requireInteger(
						node.required("minimumNoSeedControlCount"),
						path + ".minimumNoSeedControlCount"),
				requireBoolean(node.required("metadataOnly"), path + ".metadataOnly"),
				requireBoolean(
						node.required("prohibitOracleFields"), path + ".prohibitOracleFields"),
				requireBoolean(
						node.required("requireCandidateKeyDisjointFromDevelopment"),
						path + ".requireCandidateKeyDisjointFromDevelopment"),
				requireBoolean(
						node.required("requireQueryKeyDisjointFromDevelopment"),
						path + ".requireQueryKeyDisjointFromDevelopment"),
				requireBoolean(
						node.required("requireNormalizedQueryTextDisjointFromDevelopment"),
						path + ".requireNormalizedQueryTextDisjointFromDevelopment"),
				requireBoolean(
						node.required("requireNormalizedTitleDisjointFromDevelopment"),
						path + ".requireNormalizedTitleDisjointFromDevelopment"),
				requireTextArray(
						node.required("requiredLineageKinds"), path + ".requiredLineageKinds"),
				requireTextArray(
						node.required("requiredQueryKinds"), path + ".requiredQueryKinds"));
	}

	private static JudgmentsContract parseJudgments(JsonNode node, String path) {
		requireExactObject(node, path, JUDGMENT_FIELDS);
		return new JudgmentsContract(
				requireInteger(
						node.required("minimumRelevanceGrade"), path + ".minimumRelevanceGrade"),
				requireInteger(
						node.required("maximumRelevanceGrade"), path + ".maximumRelevanceGrade"),
				requireBoolean(
						node.required("allTargetVisibleCandidatesJudged"),
						path + ".allTargetVisibleCandidatesJudged"),
				requireBoolean(
						node.required("requireAdversaryAnnotations"),
						path + ".requireAdversaryAnnotations"),
				requireBoolean(
						node.required("requireAdversaryReasons"),
						path + ".requireAdversaryReasons"),
				requireBoolean(
						node.required("prohibitCandidateOutputFields"),
						path + ".prohibitCandidateOutputFields"),
				requireTextArray(
						node.required("requiredAdversaryKinds"), path + ".requiredAdversaryKinds"));
	}

	private static EvaluationContract parseEvaluation(JsonNode node, String path) {
		requireExactObject(node, path, EVALUATION_FIELDS);
		return new EvaluationContract(
				requireText(node.required("protocolId"), path + ".protocolId", 3, 100),
				requireInteger(node.required("cutoff"), path + ".cutoff"),
				requireInteger(
						node.required("relevanceThreshold"), path + ".relevanceThreshold"),
				requireText(
						node.required("rankingInputRule"), path + ".rankingInputRule", 3, 160),
				requireText(node.required("gradedGain"), path + ".gradedGain", 3, 100),
				requireText(node.required("rankDiscount"), path + ".rankDiscount", 3, 100),
				requireText(node.required("idealDcg"), path + ".idealDcg", 3, 120),
				requireText(
						node.required("recallDenominator"), path + ".recallDenominator", 3, 120),
				requireText(node.required("precisionAt1"), path + ".precisionAt1", 3, 120),
				requireText(node.required("reciprocalRank"), path + ".reciprocalRank", 3, 140),
				requireText(
						node.required("noRelevantRecall"), path + ".noRelevantRecall", 3, 100),
				requireText(node.required("noRelevantNdcg"), path + ".noRelevantNdcg", 3, 100),
				requireText(
						node.required("noRelevantPrecisionAt1"),
						path + ".noRelevantPrecisionAt1",
						3,
						100),
				requireText(
						node.required("noRelevantReciprocalRank"),
						path + ".noRelevantReciprocalRank",
						3,
						100),
				requireText(
						node.required("macroAggregation"), path + ".macroAggregation", 3, 120),
				requireText(node.required("deltaDirection"), path + ".deltaDirection", 3, 100),
				requireNumber(
						node.required("comparisonEpsilon"), path + ".comparisonEpsilon"),
				requireText(node.required("comparisonRule"), path + ".comparisonRule", 3, 140),
				requireText(
						node.required("floatingThresholdRule"),
						path + ".floatingThresholdRule",
						3,
						180),
				requireText(
						node.required("integerThresholdRule"),
						path + ".integerThresholdRule",
						3,
						120),
				requireText(
						node.required("metricArithmetic"), path + ".metricArithmetic", 3, 120),
				requireText(
						node.required("controlRegressionScope"),
						path + ".controlRegressionScope",
						3,
						160),
				requireText(
						node.required("novelRelevantAt10"), path + ".novelRelevantAt10", 3, 180),
				requireText(
						node.required("adversaryInspectionScope"),
						path + ".adversaryInspectionScope",
						3,
						120),
				requireText(
						node.required("rankOneIrrelevantScope"),
						path + ".rankOneIrrelevantScope",
						3,
						120),
				requireText(
						node.required("scopeViolationInspectionScope"),
						path + ".scopeViolationInspectionScope",
						3,
						140),
				requireText(node.required("stabilityRule"), path + ".stabilityRule", 3, 140),
				requireText(
						node.required("hiddenCandidatePerturbation"),
						path + ".hiddenCandidatePerturbation",
						3,
						200),
				requireText(node.required("labelIsolation"), path + ".labelIsolation", 3, 140),
				requireText(
						node.required("implementationFreezeRule"),
						path + ".implementationFreezeRule",
						3,
						140));
	}

	private static Gates parseGates(JsonNode node, String path) {
		requireExactObject(node, path, GATE_FIELDS);
		return new Gates(
				requireInteger(node.required("cutoff"), path + ".cutoff"),
				requireNumber(node.required("minimumMacroNdcgDelta"), path + ".minimumMacroNdcgDelta"),
				requireNumber(node.required("minimumMacroRecallDelta"), path + ".minimumMacroRecallDelta"),
				requireNumber(
						node.required("minimumMacroPrecisionAt1Delta"),
						path + ".minimumMacroPrecisionAt1Delta"),
				requireNumber(
						node.required("minimumMacroMeanReciprocalRankAt10Delta"),
						path + ".minimumMacroMeanReciprocalRankAt10Delta"),
				requireInteger(
						node.required("minimumStrictOpportunityRecallImprovements"),
						path + ".minimumStrictOpportunityRecallImprovements"),
				requireInteger(
						node.required("minimumNovelRelevantAt10"),
						path + ".minimumNovelRelevantAt10"),
				requireInteger(
						node.required("maximumPerQueryNdcgRegressionCount"),
						path + ".maximumPerQueryNdcgRegressionCount"),
				requireNumber(
						node.required("maximumPerQueryNdcgRegressionMagnitude"),
						path + ".maximumPerQueryNdcgRegressionMagnitude"),
				requireBoolean(
						node.required("requireNoPerQueryRecallRegression"),
						path + ".requireNoPerQueryRecallRegression"),
				requireBoolean(
						node.required("requireNoControlRegression"),
						path + ".requireNoControlRegression"),
				requireBoolean(
						node.required("requireFilteredOpportunityStrictRecallImprovement"),
						path + ".requireFilteredOpportunityStrictRecallImprovement"),
				requireBoolean(
						node.required("requireAuthorControlRelevantBaselineHit"),
						path + ".requireAuthorControlRelevantBaselineHit"),
				requireBoolean(
						node.required("requireAuthorControlZeroEligibleSeedsAndFeedback"),
						path + ".requireAuthorControlZeroEligibleSeedsAndFeedback"),
				requireBoolean(
						node.required("requireNoSeedZeroEligibleSeedsAndFeedback"),
						path + ".requireNoSeedZeroEligibleSeedsAndFeedback"),
				requireInteger(
						node.required("maximumRankOneIrrelevantCount"),
						path + ".maximumRankOneIrrelevantCount"),
				requireInteger(
						node.required("maximumOwnerScopeLeakCount"),
						path + ".maximumOwnerScopeLeakCount"),
				requireInteger(
						node.required("maximumFilterViolationCount"),
						path + ".maximumFilterViolationCount"),
				requireInteger(
						node.required("maximumProviderCallCount"),
						path + ".maximumProviderCallCount"),
				requireInteger(
						node.required("maximumExperimentalSnapshotWriteCount"),
						path + ".maximumExperimentalSnapshotWriteCount"),
				requireBoolean(
						node.required("requireRepeatedOrderAndScores"),
						path + ".requireRepeatedOrderAndScores"),
				requireBoolean(
						node.required("requireHiddenCandidateNoninterference"),
						path + ".requireHiddenCandidateNoninterference"),
				requireBoolean(
						node.required("requireExactFallbackWithoutFeedback"),
						path + ".requireExactFallbackWithoutFeedback"));
	}

	private static RequiredDeclarations parseRequiredDeclarations(JsonNode node, String path) {
		requireExactObject(node, path, DECLARATION_FIELDS);
		return new RequiredDeclarations(
				requireText(node.required("corpusAuthorship"), path + ".corpusAuthorship", 3, 100),
				requireText(
						node.required("judgmentAuthorship"), path + ".judgmentAuthorship", 3, 100),
				requireText(node.required("firstRunRule"), path + ".firstRunRule", 3, 100),
				requireText(node.required("noRetuningRule"), path + ".noRetuningRule", 3, 100),
				requireText(
						node.required("externalCustodyRule"), path + ".externalCustodyRule", 3, 100),
				requireText(
						node.required("evaluatorFreezeRule"), path + ".evaluatorFreezeRule", 3, 140),
				requireTextArray(
						node.required("requiredLimitations"), path + ".requiredLimitations"));
	}

	private static Interpretation parseInterpretation(JsonNode node, String path) {
		requireExactObject(node, path, INTERPRETATION_FIELDS);
		return new Interpretation(
				requireText(
						node.required("evidenceClassification"),
						path + ".evidenceClassification",
						3,
						100),
				requireBoolean(
						node.required("realHoldoutIncluded"), path + ".realHoldoutIncluded"),
				requireBoolean(node.required("evaluatorIncluded"), path + ".evaluatorIncluded"),
				requireBoolean(
						node.required("externalBundleAcceptanceAuthorized"),
						path + ".externalBundleAcceptanceAuthorized"),
				requireBoolean(
						node.required("custodyReleaseAuthorized"),
						path + ".custodyReleaseAuthorized"),
				requireBoolean(
						node.required("productActivationAuthorized"),
						path + ".productActivationAuthorized"),
				requireBoolean(
						node.required("targetDeploymentEvidenceStillRequired"),
						path + ".targetDeploymentEvidenceStillRequired"),
				requireBoolean(
						node.required("readerFacingMetrics"), path + ".readerFacingMetrics"));
	}

	private static void validateFrozenValues(RelatedTopicReuseHoldoutPolicy policy) {
		if (policy.schemaVersion() != 1
				|| !POLICY_ID.equals(policy.policyId())
				|| policy.status() != Status.PREREGISTRATION_ONLY
				|| !CANDIDATE_FREEZE_REVISION.equals(policy.candidateFreezeRevision())
				|| !RelatedTopicReuseEvaluationPolicy.POLICY_ID.equals(policy.candidatePolicyId())
				|| !RelatedTopicReuseEvaluationPolicy.POLICY_SHA256.equals(
						policy.candidatePolicySha256())
				|| !RelatedTopicReuseEvaluationFixture.FIXTURE_ID.equals(
						policy.developmentFixtureId())
				|| !RelatedTopicReuseEvaluationFixture.FIXTURE_SHA256.equals(
						policy.developmentFixtureSha256())
				|| !"CANONICAL_PAPER_TOPIC_RELEVANCE".equals(policy.labelUnit())
				|| !"INDEPENDENTLY_AUTHORED_SYNTHETIC_METADATA_ONLY".equals(
						policy.sourcePolicy())) {
			throw new IllegalArgumentException("Unexpected related-topic holdout policy identity");
		}

		BundleContract bundle = policy.bundle();
		if (!"related-topic-reuse-holdout-bundle-v1".equals(bundle.protocolId())
				|| !EXPECTED_FILES.equals(bundle.requiredFiles())
				|| bundle.maximumTotalBytes() != 1_048_576
				|| bundle.maximumManifestBytes() != 65_536
				|| bundle.maximumCorpusBytes() != 786_432
				|| bundle.maximumJudgmentsBytes() != 196_608
				|| bundle.maximumManifestBytes() + bundle.maximumCorpusBytes()
						+ bundle.maximumJudgmentsBytes() != bundle.maximumTotalBytes()
				|| !bundle.requireExactFileSet()
				|| !bundle.requireAbsoluteExternalPath()
				|| !bundle.rejectRepositoryContainment()
				|| !bundle.rejectSymlinks()
				|| !bundle.requireSha256Manifest()
				|| !bundle.requireStrictJson()) {
			throw new IllegalArgumentException("Blind holdout bundle boundary drifted");
		}

		CorpusContract corpus = policy.corpus();
		if (corpus.split() != Split.HOLDOUT
				|| corpus.minimumQueryCount() != 8
				|| corpus.maximumQueryCount() != 20
				|| corpus.minimumCandidateCount() != 40
				|| corpus.maximumCandidateCount() != 200
				|| corpus.minimumTargetVisibleCandidateCount() != 30
				|| corpus.minimumOtherOwnerCandidateCount() != 5
				|| corpus.minimumCatalogOnlyCandidateCount() != 5
				|| corpus.minimumOpportunityQueryCount() != 4
				|| corpus.minimumControlQueryCount() != 3
				|| corpus.minimumFullyFilteredQueryCount() != 1
				|| corpus.minimumNoSeedControlCount() != 1
				|| !corpus.metadataOnly()
				|| !corpus.prohibitOracleFields()
				|| !corpus.requireCandidateKeyDisjointFromDevelopment()
				|| !corpus.requireQueryKeyDisjointFromDevelopment()
				|| !corpus.requireNormalizedQueryTextDisjointFromDevelopment()
				|| !corpus.requireNormalizedTitleDisjointFromDevelopment()
				|| !Set.copyOf(corpus.requiredLineageKinds()).equals(EXPECTED_LINEAGE_KINDS)
				|| !Set.copyOf(corpus.requiredQueryKinds()).equals(EXPECTED_QUERY_KINDS)) {
			throw new IllegalArgumentException("Blind holdout corpus constraints drifted");
		}
		JudgmentsContract judgments = policy.judgments();
		if (judgments.minimumRelevanceGrade() != 0
				|| judgments.maximumRelevanceGrade() != 3
				|| !judgments.allTargetVisibleCandidatesJudged()
				|| !judgments.requireAdversaryAnnotations()
				|| !judgments.requireAdversaryReasons()
				|| !judgments.prohibitCandidateOutputFields()
				|| !Set.copyOf(judgments.requiredAdversaryKinds()).equals(
						EXPECTED_ADVERSARY_KINDS)) {
			throw new IllegalArgumentException("Blind holdout judgment constraints drifted");
		}

		EvaluationContract evaluation = policy.evaluation();
		if (!EVALUATION_PROTOCOL_ID.equals(evaluation.protocolId())
				|| evaluation.cutoff() != 10
				|| evaluation.relevanceThreshold() != 1
				|| !"UNIQUE_KEYS_WITH_UNJUDGED_KEYS_GRADED_ZERO_AND_RECORDED_AS_SCOPE_VIOLATIONS"
						.equals(evaluation.rankingInputRule())
				|| !"TWO_POWER_GRADE_MINUS_ONE".equals(evaluation.gradedGain())
				|| !"LOG2_OF_ONE_BASED_RANK_PLUS_ONE".equals(evaluation.rankDiscount())
				|| !"DESCENDING_POSITIVE_TARGET_VISIBLE_GRADES_TRUNCATED_AT_CUTOFF"
						.equals(evaluation.idealDcg())
				|| !"ALL_TARGET_VISIBLE_CANDIDATES_WITH_GRADE_AT_LEAST_ONE"
						.equals(evaluation.recallDenominator())
				|| !"ONE_IF_RANK_ONE_GRADE_AT_LEAST_ONE_ELSE_ZERO"
						.equals(evaluation.precisionAt1())
				|| !"RECIPROCAL_OF_FIRST_GRADE_AT_LEAST_ONE_RANK_WITHIN_CUTOFF_ELSE_ZERO"
						.equals(evaluation.reciprocalRank())
				|| !"UNDEFINED_EXCLUDE_FROM_MACRO".equals(evaluation.noRelevantRecall())
				|| !"UNDEFINED_EXCLUDE_FROM_MACRO".equals(evaluation.noRelevantNdcg())
				|| !"ZERO_INCLUDE_IN_MACRO".equals(evaluation.noRelevantPrecisionAt1())
				|| !"UNDEFINED_EXCLUDE_FROM_MACRO"
						.equals(evaluation.noRelevantReciprocalRank())
				|| !"UNWEIGHTED_MEAN_PER_METRIC_OVER_APPLICABLE_QUERIES"
						.equals(evaluation.macroAggregation())
				|| !"CANDIDATE_MINUS_CONTROL".equals(evaluation.deltaDirection())
				|| Double.compare(evaluation.comparisonEpsilon(), 0.000000000001d) != 0
				|| !"GAIN_ABOVE_EPSILON_REGRESSION_BELOW_NEGATIVE_EPSILON_OTHERWISE_TIE"
						.equals(evaluation.comparisonRule())
				|| !"MINIMUM_PASSES_IF_OBSERVED_PLUS_EPSILON_AT_LEAST_THRESHOLD_MAXIMUM_PASSES_IF_OBSERVED_AT_MOST_THRESHOLD_PLUS_EPSILON"
						.equals(evaluation.floatingThresholdRule())
				|| !"INTEGER_MINIMUM_AND_MAXIMUM_COMPARISONS_ARE_EXACT"
						.equals(evaluation.integerThresholdRule())
				|| !"IEEE754_BINARY64_WITH_NO_ROUNDING_BEFORE_COMPARISON"
						.equals(evaluation.metricArithmetic())
				|| !"EVERY_CONTROL_QUERY_ALL_APPLICABLE_RECALL_NDCG_PRECISION_AT_1_AND_RECIPROCAL_RANK"
						.equals(evaluation.controlRegressionScope())
				|| !"SUM_QUERY_CANDIDATE_PAIRS_GRADE_AT_LEAST_ONE_IN_CANDIDATE_TOP_10_NOT_CONTROL_TOP_10"
						.equals(evaluation.novelRelevantAt10())
				|| !"CONTROL_AND_CANDIDATE_FINAL_TOP_10"
						.equals(evaluation.adversaryInspectionScope())
				|| !"CANDIDATE_FINAL_RANK_ONE_ACROSS_ALL_QUERIES"
						.equals(evaluation.rankOneIrrelevantScope())
				|| !"CONTROL_POOL_FEEDBACK_POOLS_AND_CANDIDATE_FINAL_TOP_10"
						.equals(evaluation.scopeViolationInspectionScope())
				|| !"TWO_CONSECUTIVE_RUNS_EXACT_KEYS_AND_IEEE754_SCORE_BITS"
						.equals(evaluation.stabilityRule())
				|| !"ADD_ONE_OTHER_OWNER_AND_ONE_CATALOG_ONLY_MAXIMUM_MATCH_THEN_REQUIRE_IDENTICAL_VISIBLE_FEEDBACK_AND_FINAL_TOP_10"
						.equals(evaluation.hiddenCandidatePerturbation())
				|| !"CONTROL_AND_CANDIDATE_RANKINGS_FROZEN_BEFORE_JUDGMENTS_ARE_LOADED"
						.equals(evaluation.labelIsolation())
				|| !"EVALUATOR_REVISION_AND_SOURCE_SHA256_FROZEN_BEFORE_EXTERNAL_CUSTODY_RELEASE"
						.equals(evaluation.implementationFreezeRule())) {
			throw new IllegalArgumentException("Blind holdout evaluation contract drifted");
		}
		if (!policy.metrics().equals(EXPECTED_METRICS)) {
			throw new IllegalArgumentException("Blind holdout metrics drifted");
		}

		Gates gates = policy.gates();
		if (gates.cutoff() != 10
				|| Double.compare(gates.minimumMacroNdcgDelta(), 0.03d) != 0
				|| Double.compare(gates.minimumMacroRecallDelta(), 0.0d) != 0
				|| Double.compare(gates.minimumMacroPrecisionAt1Delta(), 0.0d) != 0
				|| Double.compare(
						gates.minimumMacroMeanReciprocalRankAt10Delta(), 0.0d) != 0
				|| gates.minimumStrictOpportunityRecallImprovements() != 2
				|| gates.minimumNovelRelevantAt10() != 2
				|| gates.maximumPerQueryNdcgRegressionCount() != 1
				|| Double.compare(gates.maximumPerQueryNdcgRegressionMagnitude(), 0.1d) != 0
				|| !gates.requireNoPerQueryRecallRegression()
				|| !gates.requireNoControlRegression()
				|| !gates.requireFilteredOpportunityStrictRecallImprovement()
				|| !gates.requireAuthorControlRelevantBaselineHit()
				|| !gates.requireAuthorControlZeroEligibleSeedsAndFeedback()
				|| !gates.requireNoSeedZeroEligibleSeedsAndFeedback()
				|| gates.maximumRankOneIrrelevantCount() != 0
				|| gates.maximumOwnerScopeLeakCount() != 0
				|| gates.maximumFilterViolationCount() != 0
				|| gates.maximumProviderCallCount() != 0
				|| gates.maximumExperimentalSnapshotWriteCount() != 0
				|| !gates.requireRepeatedOrderAndScores()
				|| !gates.requireHiddenCandidateNoninterference()
				|| !gates.requireExactFallbackWithoutFeedback()) {
			throw new IllegalArgumentException("Blind holdout gates drifted");
		}

		RequiredDeclarations declarations = policy.requiredDeclarations();
		if (!"INDEPENDENTLY_AUTHORED_WITHOUT_CANDIDATE_OUTPUTS_OR_DEVELOPMENT_LABELS"
					.equals(declarations.corpusAuthorship())
				|| !"INDEPENDENTLY_JUDGED_WITHOUT_CANDIDATE_OUTPUTS_OR_DEVELOPMENT_LABELS"
						.equals(declarations.judgmentAuthorship())
				|| !"FIRST_ELIGIBLE_RUN_IS_FINAL_FOR_POLICY_V1".equals(
						declarations.firstRunRule())
				|| !"FAILURE_REQUIRES_NEW_DEVELOPMENT_AND_VERSIONED_HOLDOUT".equals(
						declarations.noRetuningRule())
				|| !"REAL_HOLDOUT_FILES_REMAIN_EXTERNAL_AND_UNCOMMITTED".equals(
						declarations.externalCustodyRule())
				|| !"EVALUATOR_REVISION_AND_SOURCE_SHA256_FROZEN_BEFORE_EXTERNAL_CUSTODY_RELEASE"
						.equals(declarations.evaluatorFreezeRule())
				|| !EXPECTED_LIMITATIONS.equals(declarations.requiredLimitations())) {
			throw new IllegalArgumentException("Blind holdout declarations drifted");
		}

		Interpretation interpretation = policy.interpretation();
		if (!"PREREGISTERED_BLIND_HOLDOUT_POLICY".equals(
					interpretation.evidenceClassification())
				|| interpretation.realHoldoutIncluded()
				|| interpretation.evaluatorIncluded()
				|| interpretation.externalBundleAcceptanceAuthorized()
				|| interpretation.custodyReleaseAuthorized()
				|| interpretation.productActivationAuthorized()
				|| !interpretation.targetDeploymentEvidenceStillRequired()
				|| interpretation.readerFacingMetrics()) {
			throw new IllegalArgumentException("Blind holdout interpretation drifted");
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

	private static String requireTextValue(
			String value, String path, int minimum, int maximum) {
		if (value == null || !value.equals(value.strip())
				|| value.length() < minimum || value.length() > maximum
				|| value.codePoints().anyMatch(Character::isISOControl)) {
			throw new IllegalArgumentException(
					path + " must be stripped bounded text without controls");
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

	private static String requireRevision(String value, String path) {
		String revision = requireTextValue(value, path, 40, 40);
		if (!GIT_REVISION.matcher(revision).matches()) {
			throw new IllegalArgumentException(path + " must be a full lowercase Git revision");
		}
		return revision;
	}

	private static List<String> requireTextArray(JsonNode node, String path) {
		if (node == null || !node.isArray() || node.isEmpty()) {
			throw new IllegalArgumentException(path + " must be a non-empty array");
		}
		List<String> values = new ArrayList<>(node.size());
		for (int index = 0; index < node.size(); index++) {
			values.add(requireText(node.get(index), path + "[" + index + "]", 1, 128));
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

	enum Status {
		PREREGISTRATION_ONLY
	}

	enum Split {
		HOLDOUT
	}

	record BoundPolicy(RelatedTopicReuseHoldoutPolicy policy, String sha256) {

		BoundPolicy {
			policy = Objects.requireNonNull(policy, "policy");
			sha256 = requireDigest(sha256, "sha256");
		}

		void validateReference(String expectedPolicyId, String expectedSha256) {
			if (!policy.policyId().equals(expectedPolicyId) || !sha256.equals(expectedSha256)) {
				throw new IllegalArgumentException("Related-topic holdout policy reference does not match");
			}
		}
	}

	record BundleContract(
			String protocolId,
			List<String> requiredFiles,
			int maximumTotalBytes,
			int maximumManifestBytes,
			int maximumCorpusBytes,
			int maximumJudgmentsBytes,
			boolean requireExactFileSet,
			boolean requireAbsoluteExternalPath,
			boolean rejectRepositoryContainment,
			boolean rejectSymlinks,
			boolean requireSha256Manifest,
			boolean requireStrictJson) {

		BundleContract {
			protocolId = requireTextValue(protocolId, "bundle.protocolId", 3, 100);
			requiredFiles = List.copyOf(Objects.requireNonNull(requiredFiles, "requiredFiles"));
		}
	}

	record CorpusContract(
			Split split,
			int minimumQueryCount,
			int maximumQueryCount,
			int minimumCandidateCount,
			int maximumCandidateCount,
			int minimumTargetVisibleCandidateCount,
			int minimumOtherOwnerCandidateCount,
			int minimumCatalogOnlyCandidateCount,
			int minimumOpportunityQueryCount,
			int minimumControlQueryCount,
			int minimumFullyFilteredQueryCount,
			int minimumNoSeedControlCount,
			boolean metadataOnly,
			boolean prohibitOracleFields,
			boolean requireCandidateKeyDisjointFromDevelopment,
			boolean requireQueryKeyDisjointFromDevelopment,
			boolean requireNormalizedQueryTextDisjointFromDevelopment,
			boolean requireNormalizedTitleDisjointFromDevelopment,
			List<String> requiredLineageKinds,
			List<String> requiredQueryKinds) {

		CorpusContract {
			split = Objects.requireNonNull(split, "split");
			requiredLineageKinds = List.copyOf(
					Objects.requireNonNull(requiredLineageKinds, "requiredLineageKinds"));
			requiredQueryKinds = List.copyOf(
					Objects.requireNonNull(requiredQueryKinds, "requiredQueryKinds"));
		}
	}

	record JudgmentsContract(
			int minimumRelevanceGrade,
			int maximumRelevanceGrade,
			boolean allTargetVisibleCandidatesJudged,
			boolean requireAdversaryAnnotations,
			boolean requireAdversaryReasons,
			boolean prohibitCandidateOutputFields,
			List<String> requiredAdversaryKinds) {

		JudgmentsContract {
			requiredAdversaryKinds = List.copyOf(
					Objects.requireNonNull(requiredAdversaryKinds, "requiredAdversaryKinds"));
		}
	}

	record EvaluationContract(
			String protocolId,
			int cutoff,
			int relevanceThreshold,
			String rankingInputRule,
			String gradedGain,
			String rankDiscount,
			String idealDcg,
			String recallDenominator,
			String precisionAt1,
			String reciprocalRank,
			String noRelevantRecall,
			String noRelevantNdcg,
			String noRelevantPrecisionAt1,
			String noRelevantReciprocalRank,
			String macroAggregation,
			String deltaDirection,
			double comparisonEpsilon,
			String comparisonRule,
			String floatingThresholdRule,
			String integerThresholdRule,
			String metricArithmetic,
			String controlRegressionScope,
			String novelRelevantAt10,
			String adversaryInspectionScope,
			String rankOneIrrelevantScope,
			String scopeViolationInspectionScope,
			String stabilityRule,
			String hiddenCandidatePerturbation,
			String labelIsolation,
			String implementationFreezeRule) {

		EvaluationContract {
			protocolId = requireTextValue(protocolId, "evaluation.protocolId", 3, 100);
			rankingInputRule = requireTextValue(
					rankingInputRule, "evaluation.rankingInputRule", 3, 160);
			gradedGain = requireTextValue(gradedGain, "evaluation.gradedGain", 3, 100);
			rankDiscount = requireTextValue(rankDiscount, "evaluation.rankDiscount", 3, 100);
			idealDcg = requireTextValue(idealDcg, "evaluation.idealDcg", 3, 120);
			recallDenominator = requireTextValue(
					recallDenominator, "evaluation.recallDenominator", 3, 120);
			precisionAt1 = requireTextValue(precisionAt1, "evaluation.precisionAt1", 3, 120);
			reciprocalRank = requireTextValue(
					reciprocalRank, "evaluation.reciprocalRank", 3, 140);
			noRelevantRecall = requireTextValue(
					noRelevantRecall, "evaluation.noRelevantRecall", 3, 100);
			noRelevantNdcg = requireTextValue(
					noRelevantNdcg, "evaluation.noRelevantNdcg", 3, 100);
			noRelevantPrecisionAt1 = requireTextValue(
					noRelevantPrecisionAt1, "evaluation.noRelevantPrecisionAt1", 3, 100);
			noRelevantReciprocalRank = requireTextValue(
					noRelevantReciprocalRank,
					"evaluation.noRelevantReciprocalRank",
					3,
					100);
			macroAggregation = requireTextValue(
					macroAggregation, "evaluation.macroAggregation", 3, 120);
			deltaDirection = requireTextValue(
					deltaDirection, "evaluation.deltaDirection", 3, 100);
			comparisonRule = requireTextValue(
					comparisonRule, "evaluation.comparisonRule", 3, 140);
			floatingThresholdRule = requireTextValue(
					floatingThresholdRule, "evaluation.floatingThresholdRule", 3, 180);
			integerThresholdRule = requireTextValue(
					integerThresholdRule, "evaluation.integerThresholdRule", 3, 120);
			metricArithmetic = requireTextValue(
					metricArithmetic, "evaluation.metricArithmetic", 3, 120);
			controlRegressionScope = requireTextValue(
					controlRegressionScope, "evaluation.controlRegressionScope", 3, 160);
			novelRelevantAt10 = requireTextValue(
					novelRelevantAt10, "evaluation.novelRelevantAt10", 3, 180);
			adversaryInspectionScope = requireTextValue(
					adversaryInspectionScope, "evaluation.adversaryInspectionScope", 3, 120);
			rankOneIrrelevantScope = requireTextValue(
					rankOneIrrelevantScope, "evaluation.rankOneIrrelevantScope", 3, 120);
			scopeViolationInspectionScope = requireTextValue(
					scopeViolationInspectionScope,
					"evaluation.scopeViolationInspectionScope",
					3,
					140);
			stabilityRule = requireTextValue(
					stabilityRule, "evaluation.stabilityRule", 3, 140);
			hiddenCandidatePerturbation = requireTextValue(
					hiddenCandidatePerturbation,
					"evaluation.hiddenCandidatePerturbation",
					3,
					200);
			labelIsolation = requireTextValue(
					labelIsolation, "evaluation.labelIsolation", 3, 140);
			implementationFreezeRule = requireTextValue(
					implementationFreezeRule, "evaluation.implementationFreezeRule", 3, 140);
		}
	}

	record Gates(
			int cutoff,
			double minimumMacroNdcgDelta,
			double minimumMacroRecallDelta,
			double minimumMacroPrecisionAt1Delta,
			double minimumMacroMeanReciprocalRankAt10Delta,
			int minimumStrictOpportunityRecallImprovements,
			int minimumNovelRelevantAt10,
			int maximumPerQueryNdcgRegressionCount,
			double maximumPerQueryNdcgRegressionMagnitude,
			boolean requireNoPerQueryRecallRegression,
			boolean requireNoControlRegression,
			boolean requireFilteredOpportunityStrictRecallImprovement,
			boolean requireAuthorControlRelevantBaselineHit,
			boolean requireAuthorControlZeroEligibleSeedsAndFeedback,
			boolean requireNoSeedZeroEligibleSeedsAndFeedback,
			int maximumRankOneIrrelevantCount,
			int maximumOwnerScopeLeakCount,
			int maximumFilterViolationCount,
			int maximumProviderCallCount,
			int maximumExperimentalSnapshotWriteCount,
			boolean requireRepeatedOrderAndScores,
			boolean requireHiddenCandidateNoninterference,
			boolean requireExactFallbackWithoutFeedback) {
	}

	record RequiredDeclarations(
			String corpusAuthorship,
			String judgmentAuthorship,
			String firstRunRule,
			String noRetuningRule,
			String externalCustodyRule,
			String evaluatorFreezeRule,
			List<String> requiredLimitations) {

		RequiredDeclarations {
			corpusAuthorship = requireTextValue(
					corpusAuthorship, "requiredDeclarations.corpusAuthorship", 3, 100);
			judgmentAuthorship = requireTextValue(
					judgmentAuthorship, "requiredDeclarations.judgmentAuthorship", 3, 100);
			firstRunRule = requireTextValue(
					firstRunRule, "requiredDeclarations.firstRunRule", 3, 100);
			noRetuningRule = requireTextValue(
					noRetuningRule, "requiredDeclarations.noRetuningRule", 3, 100);
			externalCustodyRule = requireTextValue(
					externalCustodyRule, "requiredDeclarations.externalCustodyRule", 3, 100);
			evaluatorFreezeRule = requireTextValue(
					evaluatorFreezeRule, "requiredDeclarations.evaluatorFreezeRule", 3, 140);
			requiredLimitations = List.copyOf(
					Objects.requireNonNull(requiredLimitations, "requiredLimitations"));
		}
	}

	record Interpretation(
			String evidenceClassification,
			boolean realHoldoutIncluded,
			boolean evaluatorIncluded,
			boolean externalBundleAcceptanceAuthorized,
			boolean custodyReleaseAuthorized,
			boolean productActivationAuthorized,
			boolean targetDeploymentEvidenceStillRequired,
			boolean readerFacingMetrics) {

		Interpretation {
			evidenceClassification = requireTextValue(
					evidenceClassification, "interpretation.evidenceClassification", 3, 100);
		}
	}
}
