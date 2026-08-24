package com.openscholar.api.paper;

import com.openscholar.paper.PaperIdentifierLookupUseCase;
import com.openscholar.paper.PaperIdentifierResolutionView;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/papers")
public class PaperIdentifierLookupController {

	private final PaperIdentifierLookupUseCase identifierLookup;

	public PaperIdentifierLookupController(PaperIdentifierLookupUseCase identifierLookup) {
		this.identifierLookup = identifierLookup;
	}

	@GetMapping("/resolve")
	public ResponseEntity<PaperIdentifierResolutionResponse> resolve(
			@RequestParam(required = false) String identifier) {
		PaperIdentifierResolutionView resolution = identifierLookup.resolve(identifier);
		return ResponseEntity.ok()
				.cacheControl(CacheControl.noStore())
				.body(new PaperIdentifierResolutionResponse(
						resolution.paperId(),
						resolution.identifierType(),
						resolution.normalizedValue()));
	}
}
