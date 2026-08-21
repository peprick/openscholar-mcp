package com.openscholar.paper.internal.persistence;

import com.openscholar.paper.EmbeddingContentKind;
import com.openscholar.paper.EmbeddingDistanceMetric;
import com.openscholar.paper.EmbeddingProfile;

final class PaperEmbeddingAnnPolicy {

	static final int VERSION = 1;
	static final String POLICY_ID = "paper-embedding-hnsw-policy-v1";
	static final String PROFILE_KEY = "paper-semantic-v1-"
			+ "ac6da0dfba84a81fdbfbaf330198c33cd77c4cdfc53e8bc50eb581914a15621d"
			+ "-ollama-0-31-1";
	static final String PROVIDER = "ollama";
	static final String MODEL = "qwen3-embedding:0.6b";
	static final String MODEL_REVISION = "sha256:"
			+ "ac6da0dfba84a81fdbfbaf330198c33cd77c4cdfc53e8bc50eb581914a15621d"
			+ ";ollama:0.31.1";
	static final EmbeddingContentKind CONTENT_KIND = EmbeddingContentKind.TITLE_ABSTRACT;
	static final int INPUT_POLICY_VERSION = 1;
	static final int DIMENSIONS = 1024;
	static final String INDEX_NAME = "idx_paper_embedding_qwen06b_v1_cosine_hnsw";
	static final int INDEX_M = 16;
	static final int INDEX_EF_CONSTRUCTION = 64;
	static final int QUERY_EF_SEARCH = 1_000;
	static final int QUERY_MAX_SCAN_TUPLES = 20_000;
	static final int CANDIDATE_OVERSAMPLING_MULTIPLIER = 4;
	static final int MINIMUM_CANDIDATE_POOL = 100;
	static final int MAXIMUM_CANDIDATE_POOL = 400;

	private PaperEmbeddingAnnPolicy() {
	}

	static void requireSupported(EmbeddingProfile profile) {
		if (!PROFILE_KEY.equals(profile.profileKey())
				|| !PROVIDER.equals(profile.provider())
				|| !MODEL.equals(profile.model())
				|| !MODEL_REVISION.equals(profile.modelRevision())
				|| profile.contentKind() != CONTENT_KIND
				|| profile.inputPolicyVersion() != INPUT_POLICY_VERSION
				|| profile.dimensions() != DIMENSIONS
				|| profile.distanceMetric() != EmbeddingDistanceMetric.COSINE) {
			throw new IllegalArgumentException(
					"Approximate embedding lookup supports only the pinned 1024-dimensional cosine profile: "
							+ PROFILE_KEY);
		}
	}

	static int candidatePool(int requestedLimit) {
		return Math.min(
				MAXIMUM_CANDIDATE_POOL,
				Math.max(MINIMUM_CANDIDATE_POOL,
						requestedLimit * CANDIDATE_OVERSAMPLING_MULTIPLIER));
	}
}
