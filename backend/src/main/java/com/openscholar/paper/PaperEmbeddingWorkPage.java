package com.openscholar.paper;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

public record PaperEmbeddingWorkPage(
		List<UUID> paperIds,
		UUID nextCursor,
		boolean hasMore) {

	public PaperEmbeddingWorkPage {
		List<UUID> requiredPaperIds = Objects.requireNonNull(paperIds, "paperIds");
		if (requiredPaperIds.stream().anyMatch(Objects::isNull)) {
			throw new IllegalArgumentException("Embedding work paper IDs must not contain nulls");
		}
		paperIds = List.copyOf(requiredPaperIds);
		if (hasMore) {
			if (paperIds.isEmpty()) {
				throw new IllegalArgumentException("A resumable embedding work page must not be empty");
			}
			UUID lastPaperId = paperIds.get(paperIds.size() - 1);
			if (!lastPaperId.equals(nextCursor)) {
				throw new IllegalArgumentException(
						"Embedding work next cursor must equal the last returned paper ID");
			}
		}
		else if (nextCursor != null) {
			throw new IllegalArgumentException(
					"An exhausted embedding work page must not expose a next cursor");
		}
	}
}
