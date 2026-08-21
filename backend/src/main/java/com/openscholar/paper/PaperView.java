package com.openscholar.paper;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record PaperView(
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

	public PaperView {
		identifiers = identifiers == null ? List.of() : List.copyOf(identifiers);
		authors = authors == null ? List.of() : List.copyOf(authors);
		isbn = isbn == null ? List.of() : List.copyOf(isbn);
		issn = issn == null ? List.of() : List.copyOf(issn);
	}

	public PaperView(
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
			List<PaperAuthorView> authors) {
		this(id, title, abstractText, publicationDate, publicationYear, documentType, language, venueName,
				citationCount, citationCountAsOf, identifiers, authors, null, null, null, null, null, null,
				null, List.of(), List.of(), null);
	}
}
