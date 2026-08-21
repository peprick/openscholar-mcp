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
		Instant retrievedAt,
		List<ProviderContributionView> providerContributions) {

	public SearchResultView {
		rankingReasons = rankingReasons == null ? List.of() : List.copyOf(rankingReasons);
		providerContributions = providerContributions == null
				? List.of(new ProviderContributionView(provider, providerRecordId, retrievedAt))
				: List.copyOf(providerContributions);
	}

	public SearchResultView(
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
		this(rank, paper, reportedOpenAccess, landingPageUrl, pdfUrl, score, rankingReasons, provider,
				providerRecordId, retrievedAt, null);
	}
}
