package com.openscholar.citation.internal;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import com.openscholar.paper.DocumentType;

record CitationItem(
		UUID paperId,
		String citationKey,
		DocumentType documentType,
		String title,
		String abstractText,
		List<String> authors,
		LocalDate publicationDate,
		Integer publicationYear,
		String language,
		String venueName,
		String doi,
		String arxivId,
		String pmid,
		String pmcid,
		String canonicalUrl) {

	CitationItem {
		authors = authors == null ? List.of() : List.copyOf(authors);
	}

	int effectiveYear() {
		return publicationDate == null
				? publicationYear == null ? 0 : publicationYear
				: publicationDate.getYear();
	}
}
