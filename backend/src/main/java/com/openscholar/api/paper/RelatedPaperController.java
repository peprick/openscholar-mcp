package com.openscholar.api.paper;

import java.util.UUID;

import com.openscholar.paper.RelatedPaperUseCase;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequestMapping("/api/v1/papers")
public class RelatedPaperController {

	private final RelatedPaperUseCase relatedPapers;

	public RelatedPaperController(RelatedPaperUseCase relatedPapers) {
		this.relatedPapers = relatedPapers;
	}

	@GetMapping("/{paperId}/related")
	public RelatedPapersResponse findRelated(
			@PathVariable UUID paperId,
			@RequestParam(defaultValue = "10") @Min(1) @Max(25) int limit) {
		return RelatedPaperApiMapper.toResponse(relatedPapers.findRelated(paperId, limit));
	}
}
