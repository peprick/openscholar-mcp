package com.openscholar.search;

import java.util.UUID;

public interface SearchResearchUseCase {

	SearchView search(SearchCommand command);

	SearchView next(UUID searchId);

	SearchView get(UUID searchId);
}
