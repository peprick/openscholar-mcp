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
		List<PaperAuthorCandidate> authors,
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
		isbn = normalizedValues(isbn);
		issn = normalizedValues(issn);
	}

	public CanonicalPaperCandidate(
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
		this(title, abstractText, publicationDate, publicationYear, documentType, language, venueName,
				citationCount, citationCountAsOf, identifiers, authors, null, null, null, null, null, null,
				null, List.of(), List.of(), null);
	}

	private static List<String> normalizedValues(List<String> values) {
		if (values == null || values.isEmpty()) {
			return List.of();
		}
		return values.stream()
				.filter(java.util.Objects::nonNull)
				.map(String::strip)
				.filter(value -> !value.isEmpty())
				.distinct()
				.sorted()
				.toList();
	}
}
