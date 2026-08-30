package com.openscholar.search.internal.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import tools.jackson.core.JacksonException;
import tools.jackson.core.json.JsonReadFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

class RelatedTopicReuseHoldoutPolicyContractTests {

	private final ObjectMapper objectMapper = new ObjectMapper();

	@Test
	void frozenPolicyPreregistersBindingsBoundaryMetricsGatesAndDeclarations()
			throws Exception {
		var candidate = RelatedTopicReuseEvaluationPolicy.loadFrozen(objectMapper);
		var development = RelatedTopicReuseEvaluationFixture.loadFrozen(objectMapper);
		var bound = RelatedTopicReuseHoldoutPolicy.loadFrozen(objectMapper);
		var policy = bound.policy();

		policy.validateFrozenInputs(candidate, development);
		assertThat(bound.sha256()).isEqualTo(RelatedTopicReuseHoldoutPolicy.POLICY_SHA256);
		assertThat(policy.candidateFreezeRevision())
				.isEqualTo("6b22f6185d9c14a3dd0bf0a80a4b08c045396bff");
		assertThat(policy.candidatePolicyId())
				.isEqualTo(RelatedTopicReuseEvaluationPolicy.POLICY_ID);
		assertThat(policy.candidatePolicySha256())
				.isEqualTo(RelatedTopicReuseEvaluationPolicy.POLICY_SHA256);
		assertThat(policy.developmentFixtureId())
				.isEqualTo(RelatedTopicReuseEvaluationFixture.FIXTURE_ID);
		assertThat(policy.developmentFixtureSha256())
				.isEqualTo(RelatedTopicReuseEvaluationFixture.FIXTURE_SHA256);

		assertThat(policy.bundle().requiredFiles()).containsExactly(
				"manifest.json", "holdout-corpus.json", "judgments.json");
		assertThat(policy.bundle().maximumTotalBytes()).isEqualTo(1_048_576);
		assertThat(policy.bundle().maximumManifestBytes()
				+ policy.bundle().maximumCorpusBytes()
				+ policy.bundle().maximumJudgmentsBytes())
				.isEqualTo(policy.bundle().maximumTotalBytes());
		assertThat(policy.bundle().requireExactFileSet()).isTrue();
		assertThat(policy.bundle().requireAbsoluteExternalPath()).isTrue();
		assertThat(policy.bundle().rejectRepositoryContainment()).isTrue();
		assertThat(policy.bundle().rejectSymlinks()).isTrue();
		assertThat(policy.bundle().requireSha256Manifest()).isTrue();
		assertThat(policy.bundle().requireStrictJson()).isTrue();

		assertThat(policy.corpus().minimumQueryCount()).isEqualTo(8);
		assertThat(policy.corpus().maximumQueryCount()).isEqualTo(20);
		assertThat(policy.corpus().minimumCandidateCount()).isEqualTo(40);
		assertThat(policy.corpus().maximumCandidateCount()).isEqualTo(200);
		assertThat(policy.corpus().minimumTargetVisibleCandidateCount()).isEqualTo(30);
		assertThat(policy.corpus().minimumOtherOwnerCandidateCount()).isEqualTo(5);
		assertThat(policy.corpus().minimumCatalogOnlyCandidateCount()).isEqualTo(5);
		assertThat(policy.corpus().minimumOpportunityQueryCount()).isEqualTo(4);
		assertThat(policy.corpus().minimumControlQueryCount()).isEqualTo(3);
		assertThat(policy.corpus().minimumFullyFilteredQueryCount()).isOne();
		assertThat(policy.corpus().minimumNoSeedControlCount()).isOne();
		assertThat(policy.corpus().metadataOnly()).isTrue();
		assertThat(policy.corpus().prohibitOracleFields()).isTrue();
		assertThat(policy.corpus().requireCandidateKeyDisjointFromDevelopment()).isTrue();
		assertThat(policy.corpus().requireQueryKeyDisjointFromDevelopment()).isTrue();
		assertThat(policy.corpus().requireNormalizedQueryTextDisjointFromDevelopment()).isTrue();
		assertThat(policy.corpus().requireNormalizedTitleDisjointFromDevelopment()).isTrue();
		assertThat(policy.corpus().requiredLineageKinds()).containsExactlyInAnyOrder(
				"TARGET_OWNER_SEARCH", "TARGET_OWNER_COLLECTION", "OTHER_OWNER_SEARCH",
				"OTHER_OWNER_COLLECTION", "CATALOG_ONLY");
		assertThat(policy.corpus().requiredQueryKinds()).containsExactlyInAnyOrder(
				"LEXICAL_BRIDGE_OPPORTUNITY", "FILTERED_LEXICAL_BRIDGE_OPPORTUNITY",
				"AUTHOR_NO_RELATED_SIGNAL_CONTROL", "NO_SEED_FALLBACK_CONTROL");

		assertThat(policy.judgments().minimumRelevanceGrade()).isZero();
		assertThat(policy.judgments().maximumRelevanceGrade()).isEqualTo(3);
		assertThat(policy.judgments().allTargetVisibleCandidatesJudged()).isTrue();
		assertThat(policy.judgments().requireAdversaryAnnotations()).isTrue();
		assertThat(policy.judgments().requireAdversaryReasons()).isTrue();
		assertThat(policy.judgments().prohibitCandidateOutputFields()).isTrue();
		assertThat(policy.judgments().requiredAdversaryKinds()).containsExactlyInAnyOrder(
				"OWNER_VISIBLE_TOPIC_DRIFT", "OTHER_OWNER_TOPIC_MATCH",
				"CATALOG_ONLY_TOPIC_MATCH", "FILTER_VIOLATION",
				"AUTHOR_SUBSTRING_COLLISION");

		assertThat(policy.evaluation().protocolId())
				.isEqualTo("related-topic-reuse-holdout-evaluation-v1");
		assertThat(policy.evaluation().cutoff()).isEqualTo(10);
		assertThat(policy.evaluation().relevanceThreshold()).isOne();
		assertThat(policy.evaluation().gradedGain()).isEqualTo("TWO_POWER_GRADE_MINUS_ONE");
		assertThat(policy.evaluation().rankDiscount())
				.isEqualTo("LOG2_OF_ONE_BASED_RANK_PLUS_ONE");
		assertThat(policy.evaluation().noRelevantRecall())
				.isEqualTo("UNDEFINED_EXCLUDE_FROM_MACRO");
		assertThat(policy.evaluation().noRelevantNdcg())
				.isEqualTo("UNDEFINED_EXCLUDE_FROM_MACRO");
		assertThat(policy.evaluation().noRelevantPrecisionAt1())
				.isEqualTo("ZERO_INCLUDE_IN_MACRO");
		assertThat(policy.evaluation().noRelevantReciprocalRank())
				.isEqualTo("UNDEFINED_EXCLUDE_FROM_MACRO");
		assertThat(policy.evaluation().comparisonEpsilon()).isEqualTo(0.000000000001d);
		assertThat(policy.evaluation().floatingThresholdRule())
				.isEqualTo(
						"MINIMUM_PASSES_IF_OBSERVED_PLUS_EPSILON_AT_LEAST_THRESHOLD_MAXIMUM_PASSES_IF_OBSERVED_AT_MOST_THRESHOLD_PLUS_EPSILON");
		assertThat(policy.evaluation().integerThresholdRule())
				.isEqualTo("INTEGER_MINIMUM_AND_MAXIMUM_COMPARISONS_ARE_EXACT");
		assertThat(policy.evaluation().metricArithmetic())
				.isEqualTo("IEEE754_BINARY64_WITH_NO_ROUNDING_BEFORE_COMPARISON");
		assertThat(policy.evaluation().controlRegressionScope())
				.isEqualTo(
						"EVERY_CONTROL_QUERY_ALL_APPLICABLE_RECALL_NDCG_PRECISION_AT_1_AND_RECIPROCAL_RANK");
		assertThat(policy.evaluation().novelRelevantAt10())
				.isEqualTo(
						"SUM_QUERY_CANDIDATE_PAIRS_GRADE_AT_LEAST_ONE_IN_CANDIDATE_TOP_10_NOT_CONTROL_TOP_10");
		assertThat(policy.evaluation().scopeViolationInspectionScope())
				.isEqualTo("CONTROL_POOL_FEEDBACK_POOLS_AND_CANDIDATE_FINAL_TOP_10");
		assertThat(policy.evaluation().rankOneIrrelevantScope())
				.isEqualTo("CANDIDATE_FINAL_RANK_ONE_ACROSS_ALL_QUERIES");
		assertThat(policy.evaluation().labelIsolation())
				.isEqualTo(
						"CONTROL_AND_CANDIDATE_RANKINGS_FROZEN_BEFORE_JUDGMENTS_ARE_LOADED");
		assertThat(policy.evaluation().implementationFreezeRule())
				.isEqualTo(
						"EVALUATOR_REVISION_AND_SOURCE_SHA256_FROZEN_BEFORE_EXTERNAL_CUSTODY_RELEASE");

		assertThat(policy.metrics()).containsExactly(
				"RECALL_AT_10", "NDCG_AT_10", "PRECISION_AT_1",
				"MEAN_RECIPROCAL_RANK_AT_10", "MACRO_RECALL_AT_10_DELTA",
				"MACRO_NDCG_AT_10_DELTA", "MACRO_PRECISION_AT_1_DELTA",
				"MACRO_MEAN_RECIPROCAL_RANK_AT_10_DELTA",
				"STRICT_OPPORTUNITY_RECALL_IMPROVEMENT_COUNT", "NOVEL_RELEVANT_AT_10",
				"PER_QUERY_NDCG_REGRESSION_COUNT", "MAXIMUM_PER_QUERY_NDCG_REGRESSION",
				"CONTROL_EXPLICIT_ADVERSARY_AT_10_COUNT",
				"CANDIDATE_EXPLICIT_ADVERSARY_AT_10_COUNT", "RANK_ONE_IRRELEVANT_COUNT",
				"OWNER_SCOPE_LEAK_COUNT", "FILTER_VIOLATION_COUNT", "PROVIDER_CALL_COUNT",
				"EXPERIMENTAL_SNAPSHOT_WRITE_COUNT");

		assertThat(policy.gates().cutoff()).isEqualTo(10);
		assertThat(policy.gates().minimumMacroNdcgDelta()).isEqualTo(0.03d);
		assertThat(policy.gates().minimumMacroRecallDelta()).isZero();
		assertThat(policy.gates().minimumMacroPrecisionAt1Delta()).isZero();
		assertThat(policy.gates().minimumMacroMeanReciprocalRankAt10Delta()).isZero();
		assertThat(policy.gates().minimumStrictOpportunityRecallImprovements()).isEqualTo(2);
		assertThat(policy.gates().minimumNovelRelevantAt10()).isEqualTo(2);
		assertThat(policy.gates().maximumPerQueryNdcgRegressionCount()).isOne();
		assertThat(policy.gates().maximumPerQueryNdcgRegressionMagnitude()).isEqualTo(0.1d);
		assertThat(policy.gates().requireNoPerQueryRecallRegression()).isTrue();
		assertThat(policy.gates().requireNoControlRegression()).isTrue();
		assertThat(policy.gates().requireFilteredOpportunityStrictRecallImprovement()).isTrue();
		assertThat(policy.gates().requireAuthorControlRelevantBaselineHit()).isTrue();
		assertThat(policy.gates().requireAuthorControlZeroEligibleSeedsAndFeedback()).isTrue();
		assertThat(policy.gates().requireNoSeedZeroEligibleSeedsAndFeedback()).isTrue();
		assertThat(policy.gates().maximumRankOneIrrelevantCount()).isZero();
		assertThat(policy.gates().maximumOwnerScopeLeakCount()).isZero();
		assertThat(policy.gates().maximumFilterViolationCount()).isZero();
		assertThat(policy.gates().maximumProviderCallCount()).isZero();
		assertThat(policy.gates().maximumExperimentalSnapshotWriteCount()).isZero();
		assertThat(policy.gates().requireRepeatedOrderAndScores()).isTrue();
		assertThat(policy.gates().requireHiddenCandidateNoninterference()).isTrue();
		assertThat(policy.gates().requireExactFallbackWithoutFeedback()).isTrue();

		assertThat(policy.requiredDeclarations().corpusAuthorship())
				.isEqualTo(
						"INDEPENDENTLY_AUTHORED_WITHOUT_CANDIDATE_OUTPUTS_OR_DEVELOPMENT_LABELS");
		assertThat(policy.requiredDeclarations().judgmentAuthorship())
				.isEqualTo(
						"INDEPENDENTLY_JUDGED_WITHOUT_CANDIDATE_OUTPUTS_OR_DEVELOPMENT_LABELS");
		assertThat(policy.requiredDeclarations().firstRunRule())
				.isEqualTo("FIRST_ELIGIBLE_RUN_IS_FINAL_FOR_POLICY_V1");
		assertThat(policy.requiredDeclarations().noRetuningRule())
				.isEqualTo("FAILURE_REQUIRES_NEW_DEVELOPMENT_AND_VERSIONED_HOLDOUT");
		assertThat(policy.requiredDeclarations().externalCustodyRule())
				.isEqualTo("REAL_HOLDOUT_FILES_REMAIN_EXTERNAL_AND_UNCOMMITTED");
		assertThat(policy.requiredDeclarations().evaluatorFreezeRule())
				.isEqualTo(
						"EVALUATOR_REVISION_AND_SOURCE_SHA256_FROZEN_BEFORE_EXTERNAL_CUSTODY_RELEASE");
		assertThat(policy.requiredDeclarations().requiredLimitations()).containsExactly(
				"DECLARATIONS_ARE_PROCEDURAL_NOT_CRYPTOGRAPHIC_PROOF",
				"SHA256_PROVES_INTEGRITY_NOT_AUTHORSHIP_OR_AUTHENTICITY",
				"LITERAL_DISJOINTNESS_DOES_NOT_PROVE_SEMANTIC_DISJOINTNESS");
		assertThat(policy.interpretation().realHoldoutIncluded()).isFalse();
		assertThat(policy.interpretation().evaluatorIncluded()).isFalse();
		assertThat(policy.interpretation().externalBundleAcceptanceAuthorized()).isFalse();
		assertThat(policy.interpretation().custodyReleaseAuthorized()).isFalse();
		assertThat(policy.interpretation().productActivationAuthorized()).isFalse();
		assertThat(policy.interpretation().targetDeploymentEvidenceStillRequired()).isTrue();
		assertThat(policy.interpretation().readerFacingMetrics()).isFalse();
	}

	@Test
	void strictParserRejectsDuplicateUnknownTrailingWrongTypedAndOversizedInput()
			throws Exception {
		String original = policyText();
		String duplicate = original.replaceFirst(
				"\"policyId\": \"related-topic-reuse-holdout-policy-v1\",",
				"\"policyId\": \"related-topic-reuse-holdout-policy-v1\",\n"
						+ "  \"policyId\": \"related-topic-reuse-holdout-policy-v1\",");
		String unknown = original.replaceFirst(
				"\"schemaVersion\": 1,",
				"\"schemaVersion\": 1,\n  \"unexpected\": true,");
		String wrongBoolean = original.replaceFirst(
				"\"metadataOnly\": true", "\"metadataOnly\": \"true\"");
		String wrongNumber = original.replaceFirst(
				"\"minimumQueryCount\": 8", "\"minimumQueryCount\": \"8\"");
		String commented = original.replaceFirst("\\{", "{/* caller-enabled comment */");
		ObjectMapper permissiveMapper = JsonMapper.builder()
				.enable(JsonReadFeature.ALLOW_JAVA_COMMENTS)
				.build();

		assertThatThrownBy(() -> parse(duplicate)).isInstanceOf(Exception.class);
		assertThatThrownBy(() -> parse(unknown))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("exactly");
		assertThatThrownBy(() -> parse(original + "\n{}"))
				.isInstanceOf(Exception.class);
		assertThatThrownBy(() -> parse(wrongBoolean))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("metadataOnly");
		assertThatThrownBy(() -> parse(wrongNumber))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("minimumQueryCount");
		assertThatThrownBy(() -> RelatedTopicReuseHoldoutPolicy.parseBound(
				permissiveMapper, commented.getBytes(StandardCharsets.UTF_8)))
				.isInstanceOf(JacksonException.class);
		assertThatThrownBy(() -> RelatedTopicReuseHoldoutPolicy.parseBound(
				objectMapper, new byte[RelatedTopicReuseHoldoutPolicy.MAXIMUM_INPUT_BYTES + 1]))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("65536");
	}

	@Test
	void frozenReferenceAndSubjectBindingsRejectDigestOrInputDrift() throws Exception {
		String byteDrift = policyText().replaceFirst(
				"\\{\\n  \\\"schemaVersion\\\"", "{\n   \"schemaVersion\"");
		var rebound = parse(byteDrift);
		assertThat(rebound.sha256()).isNotEqualTo(RelatedTopicReuseHoldoutPolicy.POLICY_SHA256);
		assertThatThrownBy(() -> rebound.validateReference(
				RelatedTopicReuseHoldoutPolicy.POLICY_ID,
				RelatedTopicReuseHoldoutPolicy.POLICY_SHA256))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("does not match");

		var candidate = RelatedTopicReuseEvaluationPolicy.loadFrozen(objectMapper);
		var development = RelatedTopicReuseEvaluationFixture.loadFrozen(objectMapper);
		var policy = RelatedTopicReuseHoldoutPolicy.loadFrozen(objectMapper).policy();
		var wrongCandidate = new RelatedTopicReuseEvaluationPolicy.BoundPolicy(
				candidate.policy(), "0".repeat(64));
		var wrongDevelopment = new RelatedTopicReuseEvaluationFixture.BoundFixture(
				development.fixture(), "0".repeat(64));

		assertThatThrownBy(() -> policy.validateFrozenInputs(wrongCandidate, development))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("frozen candidate inputs");
		assertThatThrownBy(() -> policy.validateFrozenInputs(candidate, wrongDevelopment))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("frozen candidate inputs");
	}

	@Test
	void candidateAndDevelopmentBindingsRequireExactFrozenDigestsAndFullRevision()
			throws Exception {
		String original = policyText();
		String shortRevision = original.replace(
				"6b22f6185d9c14a3dd0bf0a80a4b08c045396bff", "6b22f61");
		String wrongCandidateDigest = original.replace(
				RelatedTopicReuseEvaluationPolicy.POLICY_SHA256, "0".repeat(64));
		String wrongFixtureDigest = original.replace(
				RelatedTopicReuseEvaluationFixture.FIXTURE_SHA256, "1".repeat(64));

		assertThatThrownBy(() -> parse(shortRevision))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("candidateFreezeRevision");
		assertThatThrownBy(() -> parse(wrongCandidateDigest))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("identity");
		assertThatThrownBy(() -> parse(wrongFixtureDigest))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("identity");
	}

	@Test
	void bundleAndCorpusContractsFailClosedOnBoundaryOrDisjointnessDrift()
			throws Exception {
		String original = policyText();
		String reorderedFiles = original.replace(
				"\"manifest.json\",\n      \"holdout-corpus.json\"",
				"\"holdout-corpus.json\",\n      \"manifest.json\"");
		String largerTotal = original.replace(
				"\"maximumTotalBytes\": 1048576", "\"maximumTotalBytes\": 1048577");
		String allowSymlinks = original.replace(
				"\"rejectSymlinks\": true", "\"rejectSymlinks\": false");
		String tooFewQueries = original.replace(
				"\"minimumQueryCount\": 8", "\"minimumQueryCount\": 7");
		String allowDevelopmentTitles = original.replace(
				"\"requireNormalizedTitleDisjointFromDevelopment\": true",
				"\"requireNormalizedTitleDisjointFromDevelopment\": false");
		String allowDevelopmentQueryKeys = original.replace(
				"\"requireQueryKeyDisjointFromDevelopment\": true",
				"\"requireQueryKeyDisjointFromDevelopment\": false");

		assertThatThrownBy(() -> parse(reorderedFiles))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("bundle boundary");
		assertThatThrownBy(() -> parse(largerTotal))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("bundle boundary");
		assertThatThrownBy(() -> parse(allowSymlinks))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("bundle boundary");
		assertThatThrownBy(() -> parse(tooFewQueries))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("corpus constraints");
		assertThatThrownBy(() -> parse(allowDevelopmentTitles))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("corpus constraints");
		assertThatThrownBy(() -> parse(allowDevelopmentQueryKeys))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("corpus constraints");
	}

	@Test
	void evaluationSemanticsAndAbsoluteControlRulesFailClosedOnDrift() throws Exception {
		String original = policyText();
		String binaryGain = original.replace(
				"\"gradedGain\": \"TWO_POWER_GRADE_MINUS_ONE\"",
				"\"gradedGain\": \"BINARY_GAIN\"");
		String includeUndefinedRecall = original.replace(
				"\"noRelevantRecall\": \"UNDEFINED_EXCLUDE_FROM_MACRO\"",
				"\"noRelevantRecall\": \"ZERO_INCLUDE_IN_MACRO\"");
		String zeroEpsilon = original.replace(
				"\"comparisonEpsilon\": 0.000000000001",
				"\"comparisonEpsilon\": 0.0");
		String allowMissingAuthorBaseline = original.replace(
				"\"requireAuthorControlRelevantBaselineHit\": true",
				"\"requireAuthorControlRelevantBaselineHit\": false");
		String allowNoSeedFeedback = original.replace(
				"\"requireNoSeedZeroEligibleSeedsAndFeedback\": true",
				"\"requireNoSeedZeroEligibleSeedsAndFeedback\": false");
		String allowAuthorFeedback = original.replace(
				"\"requireAuthorControlZeroEligibleSeedsAndFeedback\": true",
				"\"requireAuthorControlZeroEligibleSeedsAndFeedback\": false");
		String authorizeExternalBundle = original.replace(
				"\"externalBundleAcceptanceAuthorized\": false",
				"\"externalBundleAcceptanceAuthorized\": true");
		String authorizeCustodyRelease = original.replace(
				"\"custodyReleaseAuthorized\": false",
				"\"custodyReleaseAuthorized\": true");
		String differentDenominator = original.replace(
				"ALL_TARGET_VISIBLE_CANDIDATES_WITH_GRADE_AT_LEAST_ONE",
				"ONLY_RETRIEVED_RELEVANT_CANDIDATES");
		String reverseDelta = original.replace(
				"\"deltaDirection\": \"CANDIDATE_MINUS_CONTROL\"",
				"\"deltaDirection\": \"CONTROL_MINUS_CANDIDATE\"");
		String narrowerControlScope = original.replace(
				"EVERY_CONTROL_QUERY_ALL_APPLICABLE_RECALL_NDCG_PRECISION_AT_1_AND_RECIPROCAL_RANK",
				"AUTHOR_CONTROL_NDCG_ONLY");
		String metricListDrift = original.replace("\"RECALL_AT_10\"", "\"RECALL_AT_9\"");

		assertThatThrownBy(() -> parse(binaryGain))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("evaluation contract");
		assertThatThrownBy(() -> parse(includeUndefinedRecall))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("evaluation contract");
		assertThatThrownBy(() -> parse(zeroEpsilon))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("evaluation contract");
		assertThatThrownBy(() -> parse(allowMissingAuthorBaseline))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("gates");
		assertThatThrownBy(() -> parse(allowNoSeedFeedback))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("gates");
		assertThatThrownBy(() -> parse(allowAuthorFeedback))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("gates");
		assertThatThrownBy(() -> parse(authorizeExternalBundle))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("interpretation");
		assertThatThrownBy(() -> parse(authorizeCustodyRelease))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("interpretation");
		assertThatThrownBy(() -> parse(differentDenominator))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("evaluation contract");
		assertThatThrownBy(() -> parse(reverseDelta))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("evaluation contract");
		assertThatThrownBy(() -> parse(narrowerControlScope))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("evaluation contract");
		assertThatThrownBy(() -> parse(metricListDrift))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("metrics");
	}

	@Test
	void judgmentsUseOutputIndependentAnnotationsAndRejectOracleOrScoreDerivedKinds()
			throws Exception {
		String original = policyText();
		var root = objectMapper.readTree(original);
		assertThat(root.required("corpus").has("requiredAdversaryKinds")).isFalse();
		var adversaryKinds = root.required("judgments").required("requiredAdversaryKinds");
		assertThat(adversaryKinds.get(1).asString()).isEqualTo("OTHER_OWNER_TOPIC_MATCH");
		assertThat(adversaryKinds.get(2).asString()).isEqualTo("CATALOG_ONLY_TOPIC_MATCH");
		assertThat(original).doesNotContain(
				"OTHER_OWNER_HIGHER_RELATED_SCORE", "CATALOG_ONLY_HIGHER_RELATED_SCORE");

		String allowsOutputFields = original.replace(
				"\"prohibitCandidateOutputFields\": true",
				"\"prohibitCandidateOutputFields\": false");
		String scoreDerivedAdversary = original.replace(
				"OTHER_OWNER_TOPIC_MATCH", "OTHER_OWNER_HIGHER_RELATED_SCORE");
		assertThatThrownBy(() -> parse(allowsOutputFields))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("judgment constraints");
		assertThatThrownBy(() -> parse(scoreDerivedAdversary))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("judgment constraints");
	}

	@Test
	void prospectiveGatesDeclarationsAndInterpretationCannotBeRelaxedAfterScoring()
			throws Exception {
		String original = policyText();
		String weakerGain = original.replace(
				"\"minimumMacroNdcgDelta\": 0.03", "\"minimumMacroNdcgDelta\": 0.02");
		String rankOnePollution = original.replace(
				"\"maximumRankOneIrrelevantCount\": 0",
				"\"maximumRankOneIrrelevantCount\": 1");
		String retuningAllowed = original.replace(
				"FAILURE_REQUIRES_NEW_DEVELOPMENT_AND_VERSIONED_HOLDOUT",
				"FAILURE_MAY_RETUNE_POLICY_V1");
		String activationAllowed = original.replace(
				"\"productActivationAuthorized\": false",
				"\"productActivationAuthorized\": true");

		assertThatThrownBy(() -> parse(weakerGain))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("gates drifted");
		assertThatThrownBy(() -> parse(rankOnePollution))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("gates drifted");
		assertThatThrownBy(() -> parse(retuningAllowed))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("declarations drifted");
		assertThatThrownBy(() -> parse(activationAllowed))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("interpretation drifted");
	}

	private RelatedTopicReuseHoldoutPolicy.BoundPolicy parse(String json) throws Exception {
		return RelatedTopicReuseHoldoutPolicy.parseBound(
				objectMapper, json.getBytes(StandardCharsets.UTF_8));
	}

	private static String policyText() throws Exception {
		return new ClassPathResource(RelatedTopicReuseHoldoutPolicy.RESOURCE_PATH)
				.getContentAsString(StandardCharsets.UTF_8);
	}
}
