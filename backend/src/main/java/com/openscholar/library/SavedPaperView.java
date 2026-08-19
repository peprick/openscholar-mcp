package com.openscholar.library;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.openscholar.paper.DocumentType;

public record SavedPaperView(
		UUID collectionId,
		String collectionName,
		UUID paperId,
		String title,
		List<String> authors,
		Integer publicationYear,
		DocumentType documentType,
		ReadingStatus readingStatus,
		List<String> tags,
		Instant savedAt,
		Instant updatedAt) {

	public SavedPaperView {
		authors = authors == null ? List.of() : List.copyOf(authors);
		tags = tags == null ? List.of() : List.copyOf(tags);
	}
}
