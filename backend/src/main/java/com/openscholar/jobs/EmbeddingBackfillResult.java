package com.openscholar.jobs;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public record EmbeddingBackfillResult(
		String profileKey,
		EmbeddingBackfillDisposition disposition,
		int scannedCount,
		int storedCount,
		int unchangedCount,
		int deletedCount,
		List<EmbeddingBackfillFailure> failures,
		UUID nextCursor) {

	public EmbeddingBackfillResult {
		profileKey = Objects.requireNonNull(profileKey, "profileKey");
		disposition = Objects.requireNonNull(disposition, "disposition");
		failures = List.copyOf(Objects.requireNonNull(failures, "failures"));
		if (scannedCount < 0 || storedCount < 0 || unchangedCount < 0 || deletedCount < 0) {
			throw new IllegalArgumentException("Embedding backfill counts must not be negative");
		}
		if (scannedCount > 500 || failures.size() > 500) {
			throw new IllegalArgumentException("Embedding backfill results must remain bounded to 500 papers");
		}
		if (storedCount > scannedCount || unchangedCount > scannedCount
				|| deletedCount > scannedCount || failures.size() > scannedCount) {
			throw new IllegalArgumentException(
					"Embedding backfill outcome counts must not exceed the scanned count");
		}
		if (new HashSet<>(failures.stream().map(EmbeddingBackfillFailure::paperId).toList()).size()
				!= failures.size()) {
			throw new IllegalArgumentException("Embedding backfill failures must contain unique paper IDs");
		}
		if (disposition == EmbeddingBackfillDisposition.ALREADY_RUNNING) {
			if (scannedCount != 0 || storedCount != 0 || unchangedCount != 0 || deletedCount != 0
					|| !failures.isEmpty()) {
				throw new IllegalArgumentException("An already-running result must not report processed papers");
			}
		}
		else {
			if (scannedCount == 0 && nextCursor != null) {
				throw new IllegalArgumentException(
						"An empty completed result must not report a next cursor");
			}
			if ((long) storedCount + unchangedCount + deletedCount + failures.size() != scannedCount) {
				throw new IllegalArgumentException(
						"Embedding backfill outcome counts must equal the scanned count");
			}
		}
	}

	public int failureCount() {
		return failures.size();
	}
}
