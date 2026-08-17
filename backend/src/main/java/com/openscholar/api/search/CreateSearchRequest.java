package com.openscholar.api.search;

import java.util.Set;

import com.openscholar.paper.DocumentType;
import com.openscholar.search.SearchCommand;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record CreateSearchRequest(
		@NotBlank @Size(min = 3, max = 500) String query,
		@Valid Filters filters,
		@Min(1) @Max(50) Integer pageSize,
		@Size(max = 4096) String cursor,
		Boolean forceRefresh) {

	SearchCommand toCommand() {
		Filters effectiveFilters = filters == null ? Filters.defaults() : filters;
		return new SearchCommand(
				query,
				effectiveFilters.yearFrom(),
				effectiveFilters.yearTo(),
				effectiveFilters.safeDocumentTypes(),
				Boolean.TRUE.equals(effectiveFilters.openAccessOnly()),
				effectiveFilters.minimumCitations() == null ? 0 : effectiveFilters.minimumCitations(),
				effectiveFilters.safeLanguages(),
				pageSize == null ? 20 : pageSize,
				cursor,
				Boolean.TRUE.equals(forceRefresh));
	}

	public record Filters(
			@Min(1000) @Max(9999) Integer yearFrom,
			@Min(1000) @Max(9999) Integer yearTo,
			@Size(max = 12) Set<DocumentType> documentTypes,
			Boolean openAccessOnly,
			@Min(0) Integer minimumCitations,
			@Size(max = 20) Set<@Pattern(regexp = "(?i)[a-z]{2,3}") String> languages) {

		static Filters defaults() {
			return new Filters(null, null, Set.of(), false, 0, Set.of());
		}

		Set<DocumentType> safeDocumentTypes() {
			return documentTypes == null ? Set.of() : documentTypes;
		}

		Set<String> safeLanguages() {
			return languages == null ? Set.of() : languages;
		}

		@AssertTrue(message = "yearFrom must not be after yearTo")
		public boolean hasValidYearRange() {
			return yearFrom == null || yearTo == null || yearFrom <= yearTo;
		}
	}
}
