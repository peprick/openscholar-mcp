package com.openscholar.paper.internal.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.openscholar.paper.EmbeddingContentKind;
import com.openscholar.paper.EmbeddingDistanceMetric;
import com.openscholar.paper.EmbeddingProfile;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class PaperEmbeddingAnnPolicyTests {

	private static final String DATABASE_IMAGE = "pgvector/pgvector:pg17@sha256:"
			+ "cf134a767f474095eeba57e0117be8e568e011a63f33fbf252f14c9b760f8e6f";

	@Test
	void frozenEvaluationResourceMatchesTheRuntimePolicy() throws Exception {
		PaperEmbeddingAnnEvaluationPolicy policy = PaperEmbeddingAnnEvaluationPolicy.load(
				JsonMapper.builder().build());

		assertThat(policy.version()).isEqualTo(PaperEmbeddingAnnPolicy.VERSION);
		assertThat(policy.policyId()).isEqualTo(PaperEmbeddingAnnPolicy.POLICY_ID);
		assertThat(policy.activation()).isEqualTo("EVALUATION_ONLY");
		assertThat(policy.databaseImage()).isEqualTo(DATABASE_IMAGE);
		assertThat(policy.profileKey()).isEqualTo(PaperEmbeddingAnnPolicy.PROFILE_KEY);
		assertThat(policy.provider()).isEqualTo(PaperEmbeddingAnnPolicy.PROVIDER);
		assertThat(policy.model()).isEqualTo(PaperEmbeddingAnnPolicy.MODEL);
		assertThat(policy.modelRevision()).isEqualTo(PaperEmbeddingAnnPolicy.MODEL_REVISION);
		assertThat(policy.contentKind()).isEqualTo(PaperEmbeddingAnnPolicy.CONTENT_KIND.name());
		assertThat(policy.inputPolicyVersion())
				.isEqualTo(PaperEmbeddingAnnPolicy.INPUT_POLICY_VERSION);
		assertThat(policy.dimensions()).isEqualTo(PaperEmbeddingAnnPolicy.DIMENSIONS);
		assertThat(policy.distanceMetric()).isEqualTo(EmbeddingDistanceMetric.COSINE.name());
		assertThat(policy.index().name()).isEqualTo(PaperEmbeddingAnnPolicy.INDEX_NAME);
		assertThat(policy.index().m()).isEqualTo(PaperEmbeddingAnnPolicy.INDEX_M);
		assertThat(policy.index().efConstruction())
				.isEqualTo(PaperEmbeddingAnnPolicy.INDEX_EF_CONSTRUCTION);
		assertThat(policy.query().efSearch()).isEqualTo(PaperEmbeddingAnnPolicy.QUERY_EF_SEARCH);
		assertThat(policy.query().iterativeScan()).isEqualTo("strict_order");
		assertThat(policy.query().maxScanTuples())
				.isEqualTo(PaperEmbeddingAnnPolicy.QUERY_MAX_SCAN_TUPLES);
		assertThat(policy.query().candidateOversamplingMultiplier())
				.isEqualTo(PaperEmbeddingAnnPolicy.CANDIDATE_OVERSAMPLING_MULTIPLIER);
		assertThat(policy.query().minimumCandidatePool())
				.isEqualTo(PaperEmbeddingAnnPolicy.MINIMUM_CANDIDATE_POOL);
		assertThat(policy.query().maximumCandidatePool())
				.isEqualTo(PaperEmbeddingAnnPolicy.MAXIMUM_CANDIDATE_POOL);
		assertThat(policy.query().exactBaseline()).isEqualTo("SET_LOCAL_ENABLE_INDEXSCAN_OFF");
		assertThat(policy.query().finalTieBreak()).isEqualTo("PAPER_ID");
		assertThat(policy.recallGate().minimumPerQueryRecall()).isBetween(0.0d, 1.0d);
		assertThat(policy.recallGate().minimumMacroRecall()).isBetween(0.0d, 1.0d);
		assertThat(policy.latencyGate().referenceCpuCount()).isPositive();
		assertThat(policy.latencyGate().referenceMemoryGiB()).isPositive();
	}

	@Test
	void approximatePolicyAcceptsOnlyItsPinnedVectorSpace() {
		EmbeddingProfile pinned = profile(
				PaperEmbeddingAnnPolicy.PROFILE_KEY,
				PaperEmbeddingAnnPolicy.DIMENSIONS,
				EmbeddingDistanceMetric.COSINE);
		PaperEmbeddingAnnPolicy.requireSupported(pinned);

		assertThatThrownBy(() -> PaperEmbeddingAnnPolicy.requireSupported(profile(
				"another-profile", PaperEmbeddingAnnPolicy.DIMENSIONS,
				EmbeddingDistanceMetric.COSINE)))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining(PaperEmbeddingAnnPolicy.PROFILE_KEY);
	}

	@Test
	void candidatePoolIsBoundedAndAlwaysOversamplesTheRequestedLimit() {
		assertThat(PaperEmbeddingAnnPolicy.candidatePool(1))
				.isEqualTo(PaperEmbeddingAnnPolicy.MINIMUM_CANDIDATE_POOL);
		assertThat(PaperEmbeddingAnnPolicy.candidatePool(25)).isEqualTo(100);
		assertThat(PaperEmbeddingAnnPolicy.candidatePool(100))
				.isEqualTo(PaperEmbeddingAnnPolicy.MAXIMUM_CANDIDATE_POOL);
	}

	private EmbeddingProfile profile(
			String profileKey, int dimensions, EmbeddingDistanceMetric metric) {
		return new EmbeddingProfile(
				profileKey,
				PaperEmbeddingAnnPolicy.PROVIDER,
				PaperEmbeddingAnnPolicy.MODEL,
				PaperEmbeddingAnnPolicy.MODEL_REVISION,
				EmbeddingContentKind.TITLE_ABSTRACT,
				1,
				dimensions,
				metric);
	}
}
