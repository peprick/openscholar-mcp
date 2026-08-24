package com.openscholar.api.paper;

import java.util.UUID;

import com.openscholar.paper.ResolvablePaperIdentifierType;

public record PaperIdentifierResolutionResponse(
		UUID paperId,
		ResolvablePaperIdentifierType identifierType,
		String normalizedValue) {
}
