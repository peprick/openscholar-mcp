package com.openscholar.privacy;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.openscholar.library.ReadingStatus;
import com.openscholar.search.SearchExecutionSource;
import com.openscholar.search.SearchMode;

public record PrivacyExport(
		UUID userId,
		String displayName,
		Instant accountCreatedAt,
		Instant generatedAt,
		List<PrivacySearch> searches,
		List<PrivacyCollection> collections,
		List<PrivacySavedPaper> savedPapers) {

	public PrivacyExport {
		searches = searches == null ? List.of() : List.copyOf(searches);
		collections = collections == null ? List.of() : List.copyOf(collections);
		savedPapers = savedPapers == null ? List.of() : List.copyOf(savedPapers);
	}

	public record PrivacySearch(
			UUID searchId,
			String query,
			SearchMode requestedMode,
			SearchExecutionSource executionSource,
			PrivacySearchFilters filters,
			Instant searchedAt,
			Instant freshUntil,
			int resultCount,
			List<String> warnings) {

		public PrivacySearch {
			warnings = warnings == null ? List.of() : List.copyOf(warnings);
		}
	}

	public record PrivacySearchFilters(
			Integer yearFrom,
			Integer yearTo,
			List<String> documentTypes,
			boolean openAccessOnly,
			int minimumCitations,
			List<String> languages,
			int pageSize) {

		public PrivacySearchFilters {
			documentTypes = documentTypes == null ? List.of() : List.copyOf(documentTypes);
			languages = languages == null ? List.of() : List.copyOf(languages);
		}
	}

	public record PrivacyCollection(
			UUID collectionId,
			String name,
			String description,
			Instant createdAt,
			Instant updatedAt) {
	}

	public record PrivacySavedPaper(
			UUID collectionId,
			UUID paperId,
			String title,
			ReadingStatus readingStatus,
			List<String> tags,
			Instant savedAt,
			Instant updatedAt) {

		public PrivacySavedPaper {
			tags = tags == null ? List.of() : List.copyOf(tags);
		}
	}
}
