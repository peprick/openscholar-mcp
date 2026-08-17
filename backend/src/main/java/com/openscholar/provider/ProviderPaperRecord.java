package com.openscholar.provider;

import java.net.URI;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import com.openscholar.paper.DocumentType;

public record ProviderPaperRecord(
		ProviderId provider,
		String providerRecordId,
		String doi,
		String arxivId,
		String title,
		String abstractText,
		LocalDate publicationDate,
		Integer publicationYear,
		DocumentType documentType,
		String language,
		String venueName,
		Integer citationCount,
		List<ProviderAuthor> authors,
		boolean reportedOpenAccess,
		URI landingPageUrl,
		URI pdfUrl,
		Double relevanceScore,
		Instant providerUpdatedAt,
		Map<String, Object> metadataFragment) {

	public ProviderPaperRecord {
		provider = Objects.requireNonNull(provider, "provider");
		providerRecordId = Objects.requireNonNull(providerRecordId, "providerRecordId");
		title = Objects.requireNonNull(title, "title").strip();
		documentType = Objects.requireNonNull(documentType, "documentType");
		authors = authors == null ? List.of() : List.copyOf(authors);
		metadataFragment = metadataFragment == null ? Map.of() : Map.copyOf(metadataFragment);
	}
}
