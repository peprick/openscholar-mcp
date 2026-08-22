package com.openscholar.jobs;

import java.util.UUID;

public class ResearchRefreshJobNotFoundException extends RuntimeException {

	public ResearchRefreshJobNotFoundException(UUID jobId) {
		super("Research refresh job was not found: " + jobId);
	}
}
