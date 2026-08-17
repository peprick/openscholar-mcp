package com.openscholar.paper;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

public record CanonicalPaperCandidate(
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
		List<PaperAuthorCandidate> authors) {

	public CanonicalPaperCandidate {
		title = Objects.requireNonNull(title, "title").strip();
		documentType = Objects.requireNonNull(documentType, "documentType");
		identifiers = identifiers == null ? List.of() : List.copyOf(identifiers);
		authors = authors == null ? List.of() : List.copyOf(authors);
	}
}
