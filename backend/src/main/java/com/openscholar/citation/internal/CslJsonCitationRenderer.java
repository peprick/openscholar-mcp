package com.openscholar.citation.internal;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

final class CslJsonCitationRenderer {

	private final ObjectMapper objectMapper;

	CslJsonCitationRenderer(ObjectMapper objectMapper) {
		this.objectMapper = objectMapper;
	}

	String render(CitationItem item) {
		return render(List.of(item));
	}

	String render(List<CitationItem> items) {
		try {
			return objectMapper.writeValueAsString(items.stream()
					.map(this::renderItem)
					.toList()) + "\n";
		}
		catch (JacksonException exception) {
			throw new IllegalStateException("Citation metadata could not be serialized", exception);
		}
	}

	private Map<String, Object> renderItem(CitationItem item) {
		Map<String, Object> output = new LinkedHashMap<>();
		output.put("id", item.paperId().toString());
		output.put("type", itemType(item));
		output.put("citation-key", item.citationKey());
		output.put("title", item.title());
		put(output, "abstract", item.abstractText());
		if (!item.authors().isEmpty()) {
			output.put("author", item.authors().stream()
					.map(author -> Map.of("literal", author))
					.toList());
		}
		addVenue(output, item);
		addGenre(output, item);
		if (item.publicationDate() != null) {
			output.put("issued", Map.of("date-parts", List.of(List.of(
					item.publicationDate().getYear(),
					item.publicationDate().getMonthValue(),
					item.publicationDate().getDayOfMonth()))));
		}
		else if (item.publicationYear() != null) {
			output.put("issued", Map.of("date-parts", List.of(List.of(item.publicationYear()))));
		}
		put(output, "language", item.language());
		put(output, "DOI", item.doi());
		if (item.doi() == null) {
			put(output, "URL", item.canonicalUrl());
		}
		if (item.arxivId() != null) {
			output.put("archive", "arXiv");
			output.put("archive_location", item.arxivId());
		}
		put(output, "PMID", item.pmid());
		put(output, "PMCID", item.pmcid());

		return output;
	}

	private static String itemType(CitationItem item) {
		return switch (item.documentType()) {
			case ARTICLE -> "article-journal";
			case PREPRINT -> "article";
			case CONFERENCE_PAPER -> "paper-conference";
			case THESIS, DISSERTATION -> "thesis";
			case BOOK -> "book";
			case BOOK_CHAPTER -> "chapter";
			case REPORT -> "report";
			case DATASET -> "dataset";
			case OTHER -> "document";
		};
	}

	private static void addVenue(Map<String, Object> output, CitationItem item) {
		if (item.venueName() == null) {
			return;
		}
		switch (item.documentType()) {
			case ARTICLE, PREPRINT, CONFERENCE_PAPER, BOOK_CHAPTER ->
					output.put("container-title", item.venueName());
			case THESIS, DISSERTATION, BOOK, REPORT, DATASET, OTHER ->
					output.put("custom", Map.of("openscholar-venue", item.venueName()));
		}
	}

	private static void addGenre(Map<String, Object> output, CitationItem item) {
		if (item.documentType() == com.openscholar.paper.DocumentType.PREPRINT) {
			output.put("genre", "Preprint");
		}
		if (item.documentType() == com.openscholar.paper.DocumentType.THESIS) {
			output.put("genre", "Thesis");
		}
		if (item.documentType() == com.openscholar.paper.DocumentType.DISSERTATION) {
			output.put("genre", "Doctoral dissertation");
		}
	}

	private static void put(Map<String, Object> output, String key, String value) {
		if (value != null) {
			output.put(key, value);
		}
	}
}
