package com.openscholar.paper.internal.persistence;

import java.io.InputStream;

import org.springframework.core.io.ClassPathResource;
import tools.jackson.databind.ObjectMapper;

record RelatedPaperHybridPolicy(
		int version,
		String policyId,
		String developmentFixtureId,
		double semanticWeight,
		String lexicalTransform,
		String semanticTransform,
		String candidateRule,
		String mixedWeightTieBreak,
		Acceptance acceptance) {

	static RelatedPaperHybridPolicy load(ObjectMapper objectMapper, String policyPath)
			throws Exception {
		ClassPathResource resource = new ClassPathResource(policyPath);
		try (InputStream input = resource.getInputStream()) {
			return objectMapper.readValue(input, RelatedPaperHybridPolicy.class);
		}
	}

	record Acceptance(
			double minimumQueryRecall,
			double minimumQueryNdcg,
			boolean preserveLexicalQueryRecall,
			double minimumMacroNdcgGainOverLexical,
			boolean preserveLexicalMacroRecall,
			boolean preserveLexicalMacroPrecisionAtOne,
			boolean preserveLexicalMeanReciprocalRank,
			int minimumStrictNdcgImprovementQueryCount,
			int maximumNdcgRegressionQueryCount,
			double maximumPerQueryNdcgRegression) {
	}
}
