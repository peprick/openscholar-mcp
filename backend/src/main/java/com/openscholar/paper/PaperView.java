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
		List<PaperAuthorView> authors) {

	public PaperView {
		identifiers = identifiers == null ? List.of() : List.copyOf(identifiers);
		authors = authors == null ? List.of() : List.copyOf(authors);
	}
}
