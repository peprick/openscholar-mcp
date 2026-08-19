package com.openscholar.paper;

import java.util.List;
import java.util.UUID;

public interface PaperEmbeddingStore {

	EmbeddingProfile registerProfile(EmbeddingProfile profile);

	PaperEmbeddingSource prepareSource(UUID paperId, String profileKey);

	StoreEmbeddingOutcome saveIfSourceCurrent(PaperEmbeddingCandidate candidate);

	PaperEmbeddingWorkPage findMissing(String profileKey, UUID afterExclusive, int limit);

	List<PaperEmbeddingMatch> findNearest(UUID sourcePaperId, String profileKey, int limit);
}
