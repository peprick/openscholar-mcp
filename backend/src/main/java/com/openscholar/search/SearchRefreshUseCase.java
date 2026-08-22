package com.openscholar.search;

import java.util.UUID;

public interface SearchRefreshUseCase {

	SearchView refresh(UUID searchId);
}
