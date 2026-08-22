package com.openscholar.jobs;

import java.util.UUID;

public class ResearchRefreshJobNotRetryableException extends RuntimeException {

	public ResearchRefreshJobNotRetryableException(UUID jobId) {
		super("Research refresh job is not in a retryable failed state: " + jobId);
	}
}
