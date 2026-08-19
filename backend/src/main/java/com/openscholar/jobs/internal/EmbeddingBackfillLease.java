package com.openscholar.jobs.internal;

interface EmbeddingBackfillLease extends AutoCloseable {

	@Override
	void close();
}
