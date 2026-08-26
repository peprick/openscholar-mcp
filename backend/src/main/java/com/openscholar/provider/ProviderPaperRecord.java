package com.openscholar.provider;

import java.net.URI;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import com.openscholar.paper.DocumentType;
import com.openscholar.paper.PaperIdentifier;
import com.openscholar.paper.PaperIdentifierType;

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
		Map<String, Object> metadataFragment,
		List<PaperIdentifier> identifiers,
		URI sourceUrl,
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

	public ProviderPaperRecord {
		provider = Objects.requireNonNull(provider, "provider");
		providerRecordId = Objects.requireNonNull(providerRecordId, "providerRecordId");
		title = Objects.requireNonNull(title, "title").strip();
		documentType = Objects.requireNonNull(documentType, "documentType");
		authors = authors == null ? List.of() : List.copyOf(authors);
		metadataFragment = metadataFragment == null ? Map.of() : Map.copyOf(metadataFragment);
		identifiers = normalizedIdentifiers(provider, providerRecordId, doi, arxivId, identifiers);
		isbn = normalizedValues(isbn);
		issn = normalizedValues(issn);
	}

	public ProviderPaperRecord(
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
			Map<String, Object> metadataFragment,
			List<PaperIdentifier> identifiers,
			URI sourceUrl) {
		this(provider, providerRecordId, doi, arxivId, title, abstractText, publicationDate, publicationYear,
				documentType, language, venueName, citationCount, authors, reportedOpenAccess, landingPageUrl,
				pdfUrl, relevanceScore, providerUpdatedAt, metadataFragment, identifiers, sourceUrl, null, null,
				null, null, null, null, null, List.of(), List.of(), null);
	}

	/**
	 * Compatibility constructor for the original OpenAlex-shaped provider contract.
	 * New adapters should use the canonical constructor so every provider-specific
	 * exact identifier and source URL is explicit.
	 */
	public ProviderPaperRecord(
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
		this(provider, providerRecordId, doi, arxivId, title, abstractText, publicationDate, publicationYear,
				documentType, language, venueName, citationCount, authors, reportedOpenAccess, landingPageUrl,
				pdfUrl, relevanceScore, providerUpdatedAt, metadataFragment, List.of(),
				defaultSourceUrl(provider, providerRecordId), null, null, null, null, null, null, null,
				List.of(), List.of(), null);
	}

	private static List<PaperIdentifier> normalizedIdentifiers(
			ProviderId provider,
			String providerRecordId,
			String doi,
			String arxivId,
			List<PaperIdentifier> supplied) {
		Map<String, PaperIdentifier> values = new LinkedHashMap<>();
		if (supplied != null) {
			for (PaperIdentifier identifier : supplied) {
				PaperIdentifier value = Objects.requireNonNull(identifier, "identifiers must not contain null");
				values.putIfAbsent(identifierKey(value), value);
			}
		}
		addIdentifier(values, PaperIdentifierType.DOI, "", doi);
		addIdentifier(values, PaperIdentifierType.ARXIV, "", arxivId);
		switch (provider) {
			case OPENALEX -> addIdentifier(values, PaperIdentifierType.OPENALEX, "", providerRecordId);
			case CORE -> addIdentifier(values, PaperIdentifierType.CORE, "", providerRecordId);
			case DATACITE, DOAJ, EUROPE_PMC -> {
				// These adapters retain their provider ID in record provenance. Their DOI,
				// when present, is already represented by the canonical DOI identifier.
			}
		}
		return List.copyOf(values.values());
	}

	private static void addIdentifier(
			Map<String, PaperIdentifier> values, PaperIdentifierType type, String namespace, String value) {
		if (value == null || value.isBlank()) {
			return;
		}
		PaperIdentifier identifier = new PaperIdentifier(type, namespace, value.strip());
		values.putIfAbsent(identifierKey(identifier), identifier);
	}

	private static String identifierKey(PaperIdentifier identifier) {
		return identifier.type().name() + '\n' + identifier.namespace() + '\n' + identifier.value();
	}

	private static List<String> normalizedValues(List<String> values) {
		if (values == null || values.isEmpty()) {
			return List.of();
		}
		return values.stream()
				.filter(Objects::nonNull)
				.map(String::strip)
				.filter(value -> !value.isEmpty())
				.distinct()
				.sorted()
				.toList();
	}

	private static URI defaultSourceUrl(ProviderId provider, String providerRecordId) {
		if (provider != ProviderId.OPENALEX || providerRecordId == null || providerRecordId.isBlank()) {
			return null;
		}
		return URI.create("https://openalex.org/works/" + providerRecordId.strip());
	}
}
