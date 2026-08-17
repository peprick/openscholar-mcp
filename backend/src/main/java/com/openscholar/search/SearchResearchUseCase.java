package com.openscholar.search;

import java.util.UUID;

public interface SearchResearchUseCase {

	SearchView search(SearchCommand command);

	SearchView get(UUID searchId);
}
