package com.openscholar.paper;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

public record RelatedPapersView(
		UUID sourcePaperId,
		RelatedPaperRankingMode rankingMode,
		RelatedPaperFallbackReason fallbackReason,
		List<RelatedPaperMatch> results) {

	public RelatedPapersView {
		Objects.requireNonNull(sourcePaperId, "sourcePaperId");
		Objects.requireNonNull(rankingMode, "rankingMode");
		if (rankingMode == RelatedPaperRankingMode.HYBRID && fallbackReason != null) {
			throw new IllegalArgumentException("Hybrid related-paper results cannot have a fallback reason");
		}
		if (rankingMode == RelatedPaperRankingMode.LEXICAL && fallbackReason == null) {
			throw new IllegalArgumentException("Lexical related-paper results require a fallback reason");
		}
		results = results == null ? List.of() : List.copyOf(results);
	}

	public RelatedPapersView(UUID sourcePaperId, List<RelatedPaperMatch> results) {
		this(
				sourcePaperId,
				RelatedPaperRankingMode.LEXICAL,
				RelatedPaperFallbackReason.HYBRID_DISABLED,
				results);
	}
}
