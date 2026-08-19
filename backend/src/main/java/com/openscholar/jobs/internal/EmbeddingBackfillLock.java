package com.openscholar.jobs.internal;

import java.util.Optional;

interface EmbeddingBackfillLock {

	Optional<EmbeddingBackfillLease> tryAcquire(String profileKey);
}
