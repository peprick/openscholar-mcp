package com.openscholar.api.jobs;

import java.util.UUID;

import com.openscholar.jobs.ResearchRefreshJobUseCase;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Validated
@RequestMapping("/api/v1/refresh-jobs")
class ResearchRefreshJobController {

	private final ResearchRefreshJobUseCase jobs;

	ResearchRefreshJobController(ResearchRefreshJobUseCase jobs) {
		this.jobs = jobs;
	}

	@PostMapping
	@ResponseStatus(HttpStatus.ACCEPTED)
	ResearchRefreshJobResponse enqueue(@Valid @RequestBody CreateResearchRefreshJobRequest request) {
		return ResearchRefreshJobResponse.from(jobs.enqueue(request.jobType(), request.targetId()));
	}

	@GetMapping
	ResearchRefreshJobPageResponse list(
			@RequestParam(defaultValue = "0") @Min(0) int page,
			@RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
		return ResearchRefreshJobPageResponse.from(jobs.list(page, size));
	}

	@GetMapping("/{jobId}")
	ResearchRefreshJobResponse get(@PathVariable UUID jobId) {
		return ResearchRefreshJobResponse.from(jobs.get(jobId));
	}

	@PostMapping("/{jobId}/retry")
	@ResponseStatus(HttpStatus.ACCEPTED)
	ResearchRefreshJobResponse retry(@PathVariable UUID jobId) {
		return ResearchRefreshJobResponse.from(jobs.retry(jobId));
	}
}
