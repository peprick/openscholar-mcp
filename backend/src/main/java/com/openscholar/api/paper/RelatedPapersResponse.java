package com.openscholar.api.paper;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import com.openscholar.paper.DocumentType;
import com.openscholar.paper.RelatedPaperFallbackReason;
import com.openscholar.paper.RelatedPaperRankingFeature;
import com.openscholar.paper.RelatedPaperRankingMode;

public record RelatedPapersResponse(
		UUID sourcePaperId,
		RelatedPaperRankingMode rankingMode,
		RelatedPaperFallbackReason fallbackReason,
		List<Result> results) {

	public RelatedPapersResponse {
		results = results == null ? List.of() : List.copyOf(results);
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
			Double score,
			List<Reason> rankingReasons) {
	}

	public record Author(String name, String orcid, String openAlexId) {
	}

	public record Identifiers(String doi, String arxiv, String openAlex) {
	}

	public record Reason(RelatedPaperRankingFeature feature, double value) {
	}
}
