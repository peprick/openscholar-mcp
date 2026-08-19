package com.openscholar.library;

import java.util.Collection;
import java.util.UUID;

public interface LibraryUseCase {

	LibraryPage<CollectionSummaryView> listCollections(int page, int size);

	CollectionSummaryView createCollection(String name, String description);

	CollectionDetailsView getCollection(UUID collectionId, int page, int size);

	CollectionSummaryView updateCollection(UUID collectionId, String name, String description);

	void deleteCollection(UUID collectionId);

	SavedPaperView addPaper(
			UUID collectionId, UUID paperId, ReadingStatus readingStatus, Collection<String> tags);

	SavedPaperView updatePaper(
			UUID collectionId, UUID paperId, ReadingStatus readingStatus, Collection<String> tags);

	void removePaper(UUID collectionId, UUID paperId);

	LibraryPage<SavedPaperView> searchSavedPapers(
			String query,
			UUID collectionId,
			ReadingStatus readingStatus,
			String tag,
			int page,
			int size);
}
