package com.openscholar.library;

import java.time.Instant;
import java.util.UUID;

public record CollectionSummaryView(
		UUID collectionId,
		String name,
		String description,
		long paperCount,
		Instant createdAt,
		Instant updatedAt) {
}
