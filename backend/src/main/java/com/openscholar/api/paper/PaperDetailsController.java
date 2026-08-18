package com.openscholar.api.paper;

import java.util.UUID;

import com.openscholar.access.PaperAccessUseCase;
import com.openscholar.paper.PaperDetailsUseCase;
import com.openscholar.paper.PaperDetailsView;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/papers")
public class PaperDetailsController {

	private final PaperDetailsUseCase paperDetailsUseCase;
	private final PaperAccessUseCase paperAccessUseCase;

	public PaperDetailsController(
			PaperDetailsUseCase paperDetailsUseCase, PaperAccessUseCase paperAccessUseCase) {
		this.paperDetailsUseCase = paperDetailsUseCase;
		this.paperAccessUseCase = paperAccessUseCase;
	}

	@GetMapping("/{paperId}")
	public PaperDetailsResponse get(@PathVariable UUID paperId) {
		PaperDetailsView details = paperDetailsUseCase.get(paperId);
		return PaperDetailsApiMapper.toResponse(details, paperAccessUseCase.get(paperId));
	}
}
