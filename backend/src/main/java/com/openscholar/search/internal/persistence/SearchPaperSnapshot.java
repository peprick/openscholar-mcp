package com.openscholar.search.internal.persistence;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import com.openscholar.paper.DocumentType;
import com.openscholar.paper.PaperAuthorView;
import com.openscholar.paper.PaperIdentifier;
import com.openscholar.paper.PaperView;

/** Renderable paper state captured when a search snapshot is created. */
public record SearchPaperSnapshot(
		UUID id,
		String title,
		String abstractText,
		LocalDate publicationDate,
		Integer publicationYear,
		DocumentType documentType,
		String language,
		String venueName,
		Integer citationCount,
		Instant citationCountAsOf,
		List<PaperIdentifier> identifiers,
		List<PaperAuthorView> authors,
		String publisher,
		String institution,
		String volume,
		String issue,
		String pages,
		String articleNumber,
		String edition,
		List<String> isbn,
		List<String> issn,
		String degree) {

	public SearchPaperSnapshot {
		identifiers = identifiers == null ? List.of() : List.copyOf(identifiers);
		authors = authors == null ? List.of() : List.copyOf(authors);
		isbn = isbn == null ? List.of() : List.copyOf(isbn);
		issn = issn == null ? List.of() : List.copyOf(issn);
	}

	static SearchPaperSnapshot from(PaperView paper) {
		return new SearchPaperSnapshot(
				paper.id(),
				paper.title(),
				paper.abstractText(),
				paper.publicationDate(),
				paper.publicationYear(),
				paper.documentType(),
				paper.language(),
				paper.venueName(),
				paper.citationCount(),
				paper.citationCountAsOf(),
				paper.identifiers(),
				paper.authors(),
				paper.publisher(),
				paper.institution(),
				paper.volume(),
				paper.issue(),
				paper.pages(),
				paper.articleNumber(),
				paper.edition(),
				paper.isbn(),
				paper.issn(),
				paper.degree());
	}

	PaperView toView() {
		return new PaperView(
				id,
				title,
				abstractText,
				publicationDate,
				publicationYear,
				documentType,
				language,
				venueName,
				citationCount,
				citationCountAsOf,
				identifiers,
				authors,
				publisher,
				institution,
				volume,
				issue,
				pages,
				articleNumber,
				edition,
				isbn,
				issn,
				degree);
	}
}
