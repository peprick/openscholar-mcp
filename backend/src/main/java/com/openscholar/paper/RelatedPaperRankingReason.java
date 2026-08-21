package com.openscholar.paper;

import java.util.Objects;

public record RelatedPaperRankingReason(RelatedPaperRankingFeature feature, double value) {

	public RelatedPaperRankingReason {
		Objects.requireNonNull(feature, "feature");
		if (!Double.isFinite(value) || value < 0.0d || value > 1.0d) {
			throw new IllegalArgumentException(
					"Related-paper ranking feature value must be finite and between 0 and 1");
		}
	}
}
