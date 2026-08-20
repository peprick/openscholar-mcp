package com.openscholar.api.search;

import java.net.URI;
import java.util.UUID;

import com.openscholar.search.CacheDisposition;
import com.openscholar.search.SearchResearchUseCase;
import com.openscholar.search.SearchView;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/searches")
public class SearchController {

	private final SearchResearchUseCase searchUseCase;

	public SearchController(SearchResearchUseCase searchUseCase) {
		this.searchUseCase = searchUseCase;
	}

	@PostMapping
	public ResponseEntity<SearchResponse> create(@Valid @RequestBody CreateSearchRequest request) {
		return searchResponse(searchUseCase.search(request.toCommand()));
	}

	@PostMapping("/{searchId}/next")
	public ResponseEntity<SearchResponse> next(@PathVariable UUID searchId) {
		return searchResponse(searchUseCase.next(searchId));
	}

	@GetMapping("/{searchId}")
	public SearchResponse get(@PathVariable UUID searchId) {
		return SearchApiMapper.toResponse(searchUseCase.get(searchId));
	}

	private static ResponseEntity<SearchResponse> searchResponse(SearchView view) {
		SearchResponse response = SearchApiMapper.toResponse(view);
		if (view.cacheDisposition() == CacheDisposition.EXACT_HIT
				|| view.cacheDisposition() == CacheDisposition.STALE_FALLBACK) {
			return ResponseEntity.ok(response);
		}
		return ResponseEntity.created(URI.create("/api/v1/searches/" + view.searchId())).body(response);
	}
}
