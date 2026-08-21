package com.openscholar.api.search;

import java.net.URI;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import com.openscholar.paper.DocumentType;
import com.openscholar.provider.ProviderId;
import com.openscholar.search.CacheDisposition;

public record SearchResponse(
		UUID searchId,
		String query,
		String queryFingerprint,
		CacheDisposition cacheDisposition,
		Instant searchedAt,
		Instant freshUntil,
		String nextCursor,
		List<ProviderCoverage> providerCoverage,
		List<String> warnings,
		List<Result> results) {

	public record ProviderCoverage(
			ProviderId provider,
			String status,
			int returnedCount,
			long totalMatches) {
	}

	public record Result(
			int rank,
			UUID paperId,
			String title,
			String abstractText,
			List<Author> authors,
			LocalDate publicationDate,
			Integer publicationYear,
			DocumentType documentType,
			String language,
			String venue,
			String publisher,
			String institution,
			String volume,
			String issue,
			String pages,
			String articleNumber,
			String edition,
			List<String> isbn,
			List<String> issn,
			String degree,
			Integer citationCount,
			Identifiers identifiers,
			boolean reportedOpenAccess,
			URI landingPageUrl,
			URI reportedPdfUrl,
			Double score,
			List<Reason> rankingReasons,
			List<Provenance> provenance) {
	}

	public record Author(String name, String orcid, String openAlexId) {
	}

	public record Identifiers(String doi, String arxiv, String openAlex) {
	}

	public record Reason(String feature, Double value) {
	}

	public record Provenance(
			ProviderId provider,
			String providerRecordId,
			Instant retrievedAt) {
	}
}
