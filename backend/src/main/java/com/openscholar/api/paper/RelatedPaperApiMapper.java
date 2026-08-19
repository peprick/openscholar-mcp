package com.openscholar.api.paper;

import com.openscholar.paper.PaperIdentifierType;
import com.openscholar.paper.PaperView;
import com.openscholar.paper.RelatedPaperMatch;
import com.openscholar.paper.RelatedPapersView;

final class RelatedPaperApiMapper {

	private RelatedPaperApiMapper() {
	}

	static RelatedPapersResponse toResponse(RelatedPapersView view) {
		return new RelatedPapersResponse(
				view.sourcePaperId(),
				view.results().stream().map(RelatedPaperApiMapper::toResult).toList());
	}

	private static RelatedPapersResponse.Result toResult(RelatedPaperMatch match) {
		PaperView paper = match.paper();
		return new RelatedPapersResponse.Result(
				match.rank(),
				paper.id(),
				paper.title(),
				paper.abstractText(),
				paper.authors().stream()
						.map(author -> new RelatedPapersResponse.Author(
								author.displayName(), author.orcid(), author.openAlexId()))
						.toList(),
				paper.publicationDate(),
				paper.publicationYear(),
				paper.documentType(),
				paper.language(),
				paper.venueName(),
				paper.citationCount(),
				new RelatedPapersResponse.Identifiers(
						identifier(paper, PaperIdentifierType.DOI),
						identifier(paper, PaperIdentifierType.ARXIV),
						identifier(paper, PaperIdentifierType.OPENALEX)),
				match.score(),
				match.rankingReasons().stream()
						.map(feature -> new RelatedPapersResponse.Reason(feature, match.score()))
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
