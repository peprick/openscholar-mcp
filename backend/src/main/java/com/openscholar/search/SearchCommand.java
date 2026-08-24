package com.openscholar.search;

import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import com.openscholar.paper.DocumentType;

public record SearchCommand(
		String query,
		Integer yearFrom,
		Integer yearTo,
		Set<DocumentType> documentTypes,
		boolean openAccessOnly,
		int minimumCitations,
		Set<String> languages,
		int pageSize,
		String cursor,
		boolean forceRefresh,
		SearchMode mode) {

	public SearchCommand {
		query = Objects.requireNonNull(query, "query").strip();
		if (query.length() < 3 || query.length() > 500) {
			throw new IllegalArgumentException("Query must contain between 3 and 500 characters");
		}
		if (yearFrom != null && (yearFrom < 1000 || yearFrom > 9999)) {
			throw new IllegalArgumentException("yearFrom must be between 1000 and 9999");
		}
		if (yearTo != null && (yearTo < 1000 || yearTo > 9999)) {
			throw new IllegalArgumentException("yearTo must be between 1000 and 9999");
		}
		if (yearFrom != null && yearTo != null && yearFrom > yearTo) {
			throw new IllegalArgumentException("yearFrom must not be after yearTo");
		}
		if (minimumCitations < 0) {
			throw new IllegalArgumentException("minimumCitations must not be negative");
		}
		if (pageSize < 1 || pageSize > 50) {
			throw new IllegalArgumentException("pageSize must be between 1 and 50");
		}
		documentTypes = documentTypes == null ? Set.of() : Set.copyOf(documentTypes);
		languages = languages == null
				? Set.of()
				: languages.stream()
						.map(language -> language.toLowerCase(Locale.ROOT))
						.collect(Collectors.toUnmodifiableSet());
		cursor = cursor == null || cursor.isBlank() ? "*" : cursor;
		if (cursor.length() > 4096) {
			throw new IllegalArgumentException("Cursor must not exceed 4096 characters");
		}
		mode = mode == null ? SearchMode.AUTO : mode;
		if (mode == SearchMode.LOCAL && forceRefresh) {
			throw new IllegalArgumentException("Local search cannot force a provider refresh");
		}
	}

	public SearchCommand(
			String query,
			Integer yearFrom,
			Integer yearTo,
			Set<DocumentType> documentTypes,
			boolean openAccessOnly,
			int minimumCitations,
			Set<String> languages,
			int pageSize,
			String cursor,
			boolean forceRefresh) {
		this(query, yearFrom, yearTo, documentTypes, openAccessOnly, minimumCitations,
				languages, pageSize, cursor, forceRefresh, SearchMode.AUTO);
	}
}
