package com.openscholar.jobs;

import java.util.UUID;

public interface ResearchRefreshJobUseCase {

	ResearchRefreshJobView enqueue(ResearchRefreshJobType jobType, UUID targetId);

	ResearchRefreshJobView get(UUID jobId);

	ResearchRefreshJobPage list(int page, int size);

	ResearchRefreshJobView retry(UUID jobId);
}
