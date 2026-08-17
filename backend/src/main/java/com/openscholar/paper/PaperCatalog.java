package com.openscholar.paper;

import java.time.Instant;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public interface PaperCatalog {

	PaperView upsert(
			CanonicalPaperCandidate candidate,
			ProviderRecordCandidate providerRecord,
			Instant now);

	Map<UUID, PaperView> findAllByIds(Collection<UUID> paperIds);

	Optional<PaperView> findById(UUID paperId);
}
