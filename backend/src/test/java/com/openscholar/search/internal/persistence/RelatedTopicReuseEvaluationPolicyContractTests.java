package com.openscholar.search.internal.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import tools.jackson.databind.ObjectMapper;

class RelatedTopicReuseEvaluationPolicyContractTests {

	private final ObjectMapper objectMapper = new ObjectMapper();

	@Test
	void frozenPolicyBindsTheFixtureControlCandidateFusionAndFailClosedGates()
			throws Exception {
		var fixture = RelatedTopicReuseEvaluationFixture.loadFrozen(objectMapper);
		var bound = RelatedTopicReuseEvaluationPolicy.loadFrozen(objectMapper);
		var policy = bound.policy();

		policy.validateFixture(
				fixture.fixture().fixtureId(),
				fixture.sha256(),
				fixture.fixture().queries().size(),
				fixture.fixture().candidates().size(),
				fixture.fixture().targetVisibleKeys().size(),
				(int) fixture.fixture().queries().stream()
						.filter(query -> query.kind().opportunity()).count(),
				(int) fixture.fixture().queries().stream()
						.filter(query -> !query.kind().opportunity()).count());
		assertThat(bound.sha256()).isEqualTo(RelatedTopicReuseEvaluationPolicy.POLICY_SHA256);
		assertThat(policy.developmentFixtureSha256())
				.isEqualTo(RelatedTopicReuseEvaluationFixture.FIXTURE_SHA256);
		assertThat(policy.baseline().poolSize())
				.isEqualTo(RelatedTopicRankFusion.MAXIMUM_BASELINE_CANDIDATES);
		assertThat(policy.candidate().maximumSeeds())
				.isEqualTo(OwnerScopedRelatedTopicComparator.MAXIMUM_SEEDS)
				.isEqualTo(RelatedTopicRankFusion.MAXIMUM_FEEDBACK_LISTS);
		assertThat(policy.candidate().maximumSeedLexemes())
				.isEqualTo(OwnerScopedRelatedTopicComparator.MAXIMUM_SEED_LEXEMES);
		assertThat(policy.candidate().maximumRelatedCandidatesPerSeed())
				.isEqualTo(OwnerScopedRelatedTopicComparator.MAXIMUM_RELATED_CANDIDATES_PER_SEED)
				.isEqualTo(RelatedTopicRankFusion.MAXIMUM_FEEDBACK_CANDIDATES);
		assertThat(policy.candidate().fusion().k()).isEqualTo(RelatedTopicRankFusion.RRF_K);
		assertThat(policy.candidate().seedEligibilityFeatures()).containsExactly(
				"TITLE_EXACT", "TITLE_PREFIX", "TITLE_CONTAINS", "POSTGRES_FULL_TEXT");
		assertThat(OwnerScopedRelatedTopicReuseEvaluationTests.SEED_ELIGIBILITY_FEATURES)
				.containsExactlyInAnyOrderElementsOf(policy.candidate().seedEligibilityFeatures());
		assertThat(policy.gates().maximumOwnerScopeLeakCount()).isZero();
		assertThat(policy.gates().maximumFilterViolationCount()).isZero();
		assertThat(policy.gates().requireNoPerQueryNdcgRegression()).isTrue();
		assertThat(policy.gates().maximumBaselineAdversaryAt10Count()).isEqualTo(1);
		assertThat(policy.gates().maximumCandidateAdversaryAt10Count()).isEqualTo(3);
		assertThat(policy.gates().maximumRankOneAdversaryCount()).isZero();
		assertThat(policy.gates().requireFilteredOpportunityStrictRecallImprovement()).isTrue();
		assertThat(policy.gates().requireAuthorControlRelevantBaselineHit()).isTrue();
		assertThat(policy.gates().maximumProviderCallCount()).isZero();
		assertThat(policy.gates().maximumExperimentalSnapshotWriteCount()).isZero();
	}

	@Test
	void scopedComparatorAppliesOwnerAndEveryFilterBeforeRelatedRankAndLimit() {
		String sql = OwnerScopedRelatedTopicComparator.sqlContract();

		assertThat(sql)
				.contains(
						"WHERE snapshot.owner_id = :ownerId",
						"WHERE collection.owner_id = :ownerId",
						"UNION",
						"JOIN filtered_paper seed ON seed.id = requested.seed_id",
						"JOIN filtered_paper candidate ON candidate.id <> seed.seed_id",
						"candidate.search_vector @@ seed.related_query",
						"PARTITION BY seed_id",
						"lexical_score DESC",
						"metadata_quality DESC",
						"citation_count DESC NULLS LAST",
						"publication_year DESC NULLS LAST",
						"paper_id",
						"WHERE related_rank <= 25")
				.doesNotContain("UNION ALL", "paper_embedding", "hnsw", "cosine_distance");
		assertThat(sql.indexOf("filtered_paper AS"))
				.isLessThan(sql.indexOf("ts_rank_cd("));
		assertThat(sql.indexOf("ts_rank_cd("))
				.isLessThan(sql.indexOf("row_number() OVER (\n               PARTITION BY seed_id"));
		assertThat(sql.indexOf("PARTITION BY seed_id"))
				.isLessThan(sql.indexOf("WHERE related_rank <= 25"));
	}

	@Test
	void strictPolicyParserRejectsDuplicateUnknownTrailingAndWrongTypes() throws Exception {
		String original = policyText();
		String duplicate = original.replaceFirst(
				"\"policyId\": \"related-topic-reuse-policy-v1\",",
				"\"policyId\": \"related-topic-reuse-policy-v1\",\n"
						+ "  \"policyId\": \"related-topic-reuse-policy-v1\",");
		String unknown = original.replaceFirst(
				"\"schemaVersion\": 1,",
				"\"schemaVersion\": 1,\n  \"unexpected\": true,");
		String wrongBoolean = original.replaceFirst(
				"\"metadataOnly\": true", "\"metadataOnly\": \"true\"");

		assertThatThrownBy(() -> parse(duplicate)).isInstanceOf(Exception.class);
		assertThatThrownBy(() -> parse(unknown))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("exactly");
		assertThatThrownBy(() -> parse(original + "\n{}"))
				.isInstanceOf(Exception.class);
		assertThatThrownBy(() -> parse(wrongBoolean))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("metadataOnly");
	}

	@Test
	void frozenReferenceAndFixtureBindingRejectRawByteOrDigestDrift() throws Exception {
		String rawDrift = policyText().replaceFirst(
				"\\{\\n  \\\"schemaVersion\\\"", "{\n   \"schemaVersion\"");
		var rebound = parse(rawDrift);
		assertThat(rebound.sha256())
				.isNotEqualTo(RelatedTopicReuseEvaluationPolicy.POLICY_SHA256);
		assertThatThrownBy(() -> rebound.validateReference(
				RelatedTopicReuseEvaluationPolicy.POLICY_ID,
				RelatedTopicReuseEvaluationPolicy.POLICY_SHA256))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("does not match");

		var frozen = RelatedTopicReuseEvaluationPolicy.loadFrozen(objectMapper).policy();
		assertThatThrownBy(() -> frozen.validateFixture(
				RelatedTopicReuseEvaluationFixture.FIXTURE_ID,
				"0".repeat(64),
				5,
				25,
				19,
				3,
				2))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("digest/count bound");
	}

	@Test
	void semanticPolicyDriftCannotSilentlyEnableBlindOrUnscopedFeedback() throws Exception {
		String original = policyText();
		String blindSeeds = original.replaceFirst(
				"PRODUCTION_LOCAL_TOPIC_SIGNAL_RANK", "PRODUCTION_LOCAL_RANK");
		String wrongScope = original.replaceFirst(
				"\"OWNER_ELIGIBILITY\",\n      \"COMMAND_FILTERS\"",
				"\"COMMAND_FILTERS\",\n      \"OWNER_ELIGIBILITY\"");
		String oversizedPool = original.replaceFirst(
				"\"maximumRelatedCandidatesPerSeed\": 25",
				"\"maximumRelatedCandidatesPerSeed\": 250");

		assertThatThrownBy(() -> parse(blindSeeds))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("candidate semantics drifted");
		assertThatThrownBy(() -> parse(wrongScope))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("candidate semantics drifted");
		assertThatThrownBy(() -> parse(oversizedPool))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("candidate semantics drifted");
	}

	private RelatedTopicReuseEvaluationPolicy.BoundPolicy parse(String json) throws Exception {
		return RelatedTopicReuseEvaluationPolicy.parseBound(
				objectMapper, json.getBytes(StandardCharsets.UTF_8));
	}

	private static String policyText() throws Exception {
		return new ClassPathResource(RelatedTopicReuseEvaluationPolicy.RESOURCE_PATH)
				.getContentAsString(StandardCharsets.UTF_8);
	}
}
