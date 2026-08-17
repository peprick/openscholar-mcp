package com.openscholar.provider;

import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import com.openscholar.paper.DocumentType;

public record ProviderSearchQuery(
		String query,
		Integer yearFrom,
		Integer yearTo,
		Set<DocumentType> documentTypes,
		boolean openAccessOnly,
		int minimumCitations,
		Set<String> languages,
		int pageSize,
		String cursor) {

	public ProviderSearchQuery {
		query = Objects.requireNonNull(query, "query").strip();
		documentTypes = documentTypes == null ? Set.of() : Set.copyOf(documentTypes);
		languages = languages == null
				? Set.of()
				: languages.stream()
						.map(language -> language.toLowerCase(Locale.ROOT))
						.collect(Collectors.toUnmodifiableSet());
		cursor = cursor == null || cursor.isBlank() ? "*" : cursor;
	}
}
