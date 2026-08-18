package com.openscholar.paper;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public record PaperDetailsView(
		PaperView paper,
		BigDecimal metadataCompleteness,
		Instant metadataUpdatedAt,
		List<PaperProviderRecordView> provenance,
		UUID authorshipProviderRecordId) {

	public PaperDetailsView {
		paper = Objects.requireNonNull(paper, "paper");
		metadataCompleteness = Objects.requireNonNull(
				metadataCompleteness, "metadataCompleteness");
		metadataUpdatedAt = Objects.requireNonNull(metadataUpdatedAt, "metadataUpdatedAt");
		provenance = provenance == null ? List.of() : List.copyOf(provenance);
	}
}
