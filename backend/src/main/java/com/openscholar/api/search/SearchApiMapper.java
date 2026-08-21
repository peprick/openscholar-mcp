package com.openscholar.api.search;

import com.openscholar.paper.PaperIdentifierType;
import com.openscholar.paper.PaperView;
import com.openscholar.search.SearchResultView;
import com.openscholar.search.SearchView;

final class SearchApiMapper {

	private SearchApiMapper() {
	}

	static SearchResponse toResponse(SearchView view) {
		return new SearchResponse(
				view.searchId(),
				view.query(),
				view.queryFingerprint(),
				view.cacheDisposition(),
				view.searchedAt(),
				view.freshUntil(),
				view.nextCursor(),
				view.providerCoverage().stream()
						.map(coverage -> new SearchResponse.ProviderCoverage(
								coverage.provider(),
								coverage.status(),
								coverage.returnedCount(),
								coverage.totalMatches()))
						.toList(),
				view.warnings(),
				view.results().stream().map(SearchApiMapper::toResult).toList());
	}

	private static SearchResponse.Result toResult(SearchResultView result) {
		PaperView paper = result.paper();
		return new SearchResponse.Result(
				result.rank(),
				paper.id(),
				paper.title(),
				paper.abstractText(),
				paper.authors().stream()
						.map(author -> new SearchResponse.Author(
								author.displayName(), author.orcid(), author.openAlexId()))
						.toList(),
				paper.publicationDate(),
				paper.publicationYear(),
				paper.documentType(),
				paper.language(),
				paper.venueName(),
				paper.publisher(),
				paper.institution(),
				paper.volume(),
				paper.issue(),
				paper.pages(),
				paper.articleNumber(),
				paper.edition(),
				paper.isbn(),
				paper.issn(),
				paper.degree(),
				paper.citationCount(),
				new SearchResponse.Identifiers(
						identifier(paper, PaperIdentifierType.DOI),
						identifier(paper, PaperIdentifierType.ARXIV),
						identifier(paper, PaperIdentifierType.OPENALEX)),
				result.reportedOpenAccess(),
				result.landingPageUrl(),
				result.pdfUrl(),
				result.score(),
				result.rankingReasons().stream()
						.map(reason -> new SearchResponse.Reason(reason.feature(), reason.value()))
						.toList(),
				result.providerContributions().stream()
						.map(contribution -> new SearchResponse.Provenance(
								contribution.provider(),
								contribution.providerRecordId(),
								contribution.retrievedAt()))
						.toList());
	}

	private static String identifier(PaperView paper, PaperIdentifierType type) {
		return paper.identifiers().stream()
				.filter(identifier -> identifier.type() == type)
				.map(com.openscholar.paper.PaperIdentifier::value)
				.findFirst()
				.orElse(null);
	}
}
