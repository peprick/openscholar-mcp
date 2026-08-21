package com.openscholar.paper.internal.persistence;

import java.util.List;

import com.openscholar.paper.internal.persistence.RelatedPaperEvaluationMetrics.QueryMeasurement;
import com.openscholar.paper.internal.persistence.RelatedPaperHybridScorer.HybridRankedPaper;

final class RelatedPaperHybridEvaluationSummary {

	private RelatedPaperHybridEvaluationSummary() {
	}

	static HybridWeightMeasurement summarize(
			double semanticWeight, List<QueryMeasurement<HybridRankedPaper>> queries) {
		RelatedPaperEvaluationMetrics.Summary summary =
				RelatedPaperEvaluationMetrics.summarize(queries);
		return new HybridWeightMeasurement(
				semanticWeight,
				summary.macroRecall(),
				summary.macroNdcg(),
				summary.macroPrecisionAtOne(),
				summary.meanReciprocalRank(),
				queries);
	}

	record HybridWeightMeasurement(
			double semanticWeight,
			double macroRecall,
			double macroNdcg,
			double macroPrecisionAtOne,
			double meanReciprocalRank,
			List<QueryMeasurement<HybridRankedPaper>> queries) {

		HybridWeightMeasurement {
			queries = List.copyOf(queries);
		}
	}
}
