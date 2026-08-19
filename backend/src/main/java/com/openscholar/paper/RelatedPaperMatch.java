package com.openscholar.paper;

import java.util.List;
import java.util.Objects;

public record RelatedPaperMatch(
		int rank,
		PaperView paper,
		Double score,
		List<String> rankingReasons) {

	public static final String POSTGRES_FULL_TEXT_REASON = "POSTGRES_FULL_TEXT";

	public RelatedPaperMatch {
		if (rank < 1) {
			throw new IllegalArgumentException("Related-paper rank must be positive");
		}
		Objects.requireNonNull(paper, "paper");
		Objects.requireNonNull(score, "score");
		if (!Double.isFinite(score) || score < 0) {
			throw new IllegalArgumentException("Related-paper score must be finite and non-negative");
		}
		rankingReasons = rankingReasons == null ? List.of() : List.copyOf(rankingReasons);
	}
}
