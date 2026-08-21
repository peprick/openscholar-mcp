package com.openscholar.api.paper;

import java.math.BigDecimal;
import java.net.URI;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import com.openscholar.access.AccessDisposition;
import com.openscholar.access.AccessStatus;
import com.openscholar.paper.DocumentType;
import com.openscholar.paper.PaperIdentifierType;

public record PaperDetailsResponse(
		UUID paperId,
		String title,
		String abstractText,
		List<Author> authors,
		LocalDate publicationDate,
		Integer publicationYear,
		DocumentType documentType,
		String language,
		String venueName,
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
		Instant citationCountAsOf,
		List<Identifier> identifiers,
		BigDecimal metadataCompleteness,
		Instant metadataUpdatedAt,
		List<Provenance> provenance,
		AccessSummary access) {

	public record Author(
			String name,
			String orcid,
			String openAlexId,
			int position,
			boolean corresponding) {
	}

	public record Identifier(PaperIdentifierType type, String namespace, String value) {
	}

	public record Provenance(
			String provider,
			String providerRecordId,
			URI sourceUrl,
			Instant providerUpdatedAt,
			Instant retrievedAt,
			boolean reportedOpenAccess,
			boolean authorshipSource) {
	}

	public record AccessSummary(
			AccessStatus status,
			AccessDisposition cacheDisposition,
			Instant checkedAt,
			Instant freshUntil,
			UUID bestLocationId,
			int locationCount,
			List<String> warnings) {
	}
}
