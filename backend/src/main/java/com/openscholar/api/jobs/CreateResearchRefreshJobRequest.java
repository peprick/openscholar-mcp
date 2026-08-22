package com.openscholar.api.jobs;

import java.util.UUID;

import com.openscholar.jobs.ResearchRefreshJobType;
import jakarta.validation.constraints.NotNull;

public record CreateResearchRefreshJobRequest(
		@NotNull ResearchRefreshJobType jobType,
		@NotNull UUID targetId) {
}
