package com.openscholar.api.access;

import java.util.UUID;

import com.openscholar.access.PaperAccessUseCase;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/papers/{paperId}")
public class PaperAccessController {

	private final PaperAccessUseCase accessUseCase;

	public PaperAccessController(PaperAccessUseCase accessUseCase) {
		this.accessUseCase = accessUseCase;
	}

	@GetMapping("/versions")
	public PaperAccessResponse getVersions(@PathVariable UUID paperId) {
		return PaperAccessApiMapper.toResponse(accessUseCase.get(paperId));
	}

	@PostMapping("/access/verify")
	public PaperAccessResponse verify(
			@PathVariable UUID paperId,
			@RequestParam(defaultValue = "false") boolean forceRefresh) {
		return PaperAccessApiMapper.toResponse(accessUseCase.resolve(paperId, forceRefresh));
	}
}
