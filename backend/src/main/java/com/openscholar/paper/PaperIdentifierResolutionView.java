package com.openscholar.paper;

import java.util.Objects;
import java.util.UUID;

public record PaperIdentifierResolutionView(
		UUID paperId,
		ResolvablePaperIdentifierType identifierType,
		String normalizedValue) {

	public PaperIdentifierResolutionView {
		paperId = Objects.requireNonNull(paperId, "paperId");
		identifierType = Objects.requireNonNull(identifierType, "identifierType");
		normalizedValue = Objects.requireNonNull(normalizedValue, "normalizedValue");
	}
}
