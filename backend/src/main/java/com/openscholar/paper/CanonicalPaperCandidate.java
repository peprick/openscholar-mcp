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
		if (publicationDate != null) {
			if (publicationDate.getYear() < 1000 || publicationDate.getYear() > 9999) {
				throw new IllegalArgumentException("Publication date year must be between 1000 and 9999");
			}
			publicationYear = publicationDate.getYear();
		}
		else if (publicationYear != null && (publicationYear < 1000 || publicationYear > 9999)) {
			throw new IllegalArgumentException("Publication year must be between 1000 and 9999");
		}
		identifiers = identifiers == null ? List.of() : List.copyOf(identifiers);
		authors = authors == null ? List.of() : List.copyOf(authors);
	}
}
