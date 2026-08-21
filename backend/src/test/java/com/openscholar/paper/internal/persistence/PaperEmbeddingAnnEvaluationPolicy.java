package com.openscholar.paper.internal.persistence;

import java.io.InputStream;

import org.springframework.core.io.ClassPathResource;
import tools.jackson.databind.ObjectMapper;

record PaperEmbeddingAnnEvaluationPolicy(
		int version,
		String policyId,
		String activation,
		String databaseImage,
		String profileKey,
		String provider,
		String model,
		String modelRevision,
		String contentKind,
		int inputPolicyVersion,
		int dimensions,
		String distanceMetric,
		IndexPolicy index,
		QueryPolicy query,
		RecallGate recallGate,
		LatencyGate latencyGate) {

	static final String RESOURCE_PATH =
			"search/relevance/paper-embedding-hnsw-policy-v1.json";

	static PaperEmbeddingAnnEvaluationPolicy load(ObjectMapper objectMapper) throws Exception {
		ClassPathResource resource = new ClassPathResource(RESOURCE_PATH);
		try (InputStream input = resource.getInputStream()) {
			return objectMapper.readValue(input, PaperEmbeddingAnnEvaluationPolicy.class);
		}
	}

	record IndexPolicy(String name, int m, int efConstruction) {
	}

	record QueryPolicy(
			int efSearch,
			String iterativeScan,
			int maxScanTuples,
			int candidateOversamplingMultiplier,
			int minimumCandidatePool,
			int maximumCandidatePool,
			String exactBaseline,
			String finalTieBreak) {
	}

	record RecallGate(
			int corpusSize,
			int queryCount,
			int cutoff,
			double minimumPerQueryRecall,
			double minimumMacroRecall) {
	}

	record LatencyGate(
			int warmupRuns,
			int measurementRuns,
			double maximumApproximateP95Millis,
			double minimumExactToApproximateP95Speedup,
			int referenceCpuCount,
			int referenceMemoryGiB,
			String cacheState,
			int concurrentLoad) {
	}
}
