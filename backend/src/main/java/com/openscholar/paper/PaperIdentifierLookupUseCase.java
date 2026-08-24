package com.openscholar.paper;

public interface PaperIdentifierLookupUseCase {

	PaperIdentifierResolutionView resolve(String identifier);
}
