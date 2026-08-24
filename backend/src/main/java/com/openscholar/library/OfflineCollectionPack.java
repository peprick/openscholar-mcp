package com.openscholar.library;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import com.openscholar.paper.DocumentType;
import com.openscholar.paper.PaperIdentifier;

public record OfflineCollectionPack(
		int schemaVersion,
		Instant generatedAt,
		CollectionMetadata collection,
		List<PaperMetadata> papers) {

	public OfflineCollectionPack {
		papers = List.copyOf(papers);
	}

	public record CollectionMetadata(UUID collectionId, String name, String description) {
	}

	public record PaperMetadata(
			UUID paperId,
			String title,
			List<String> authors,
			LocalDate publicationDate,
			Integer publicationYear,
			DocumentType documentType,
			String language,
			String venueName,
			List<PaperIdentifier> identifiers,
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
			ReadingStatus readingStatus,
			List<String> tags) {

		public PaperMetadata {
			authors = List.copyOf(authors);
			identifiers = List.copyOf(identifiers);
			isbn = List.copyOf(isbn);
			issn = List.copyOf(issn);
			tags = List.copyOf(tags);
		}
	}
}
