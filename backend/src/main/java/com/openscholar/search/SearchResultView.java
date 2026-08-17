package com.openscholar.search;

import java.net.URI;
import java.time.Instant;
import java.util.List;

import com.openscholar.paper.PaperView;
import com.openscholar.provider.ProviderId;

public record SearchResultView(
		int rank,
		PaperView paper,
		boolean reportedOpenAccess,
		URI landingPageUrl,
		URI pdfUrl,
		Double score,
		List<RankingReason> rankingReasons,
		ProviderId provider,
		String providerRecordId,
		Instant retrievedAt) {

	public SearchResultView {
		rankingReasons = rankingReasons == null ? List.of() : List.copyOf(rankingReasons);
	}
}
