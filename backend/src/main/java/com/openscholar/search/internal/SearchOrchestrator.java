package com.openscholar.search.internal;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.UUID;

import com.openscholar.provider.ProviderException;
import com.openscholar.provider.ProviderSearchQuery;
import com.openscholar.provider.ProviderSearchResult;
import com.openscholar.provider.ResearchProvider;
import com.openscholar.search.CacheDisposition;
import com.openscholar.search.SearchCommand;
import com.openscholar.search.SearchNotFoundException;
import com.openscholar.search.SearchResearchUseCase;
import com.openscholar.search.SearchUnavailableException;
import com.openscholar.search.SearchView;
import com.openscholar.search.internal.persistence.SearchSnapshotStore;
import org.springframework.stereotype.Service;

@Service
class SearchOrchestrator implements SearchResearchUseCase {

	private final ResearchProvider provider;
	private final QueryFingerprinter fingerprinter;
	private final SearchSnapshotStore snapshotStore;
	private final SearchProperties properties;
	private final Clock clock;

	SearchOrchestrator(
			ResearchProvider provider,
			QueryFingerprinter fingerprinter,
			SearchSnapshotStore snapshotStore,
			SearchProperties properties,
			Clock clock) {
		this.provider = provider;
		this.fingerprinter = fingerprinter;
		this.snapshotStore = snapshotStore;
		this.properties = properties;
		this.clock = clock;
	}

	@Override
	public SearchView search(SearchCommand command) {
		String normalizedQuery = fingerprinter.normalizedQuery(command);
		String fingerprint = fingerprinter.fingerprint(command);
		Instant now = Instant.now(clock);
		var latest = snapshotStore.findLatest(fingerprint);
		if (!command.forceRefresh() && latest.isPresent() && latest.orElseThrow().isFreshAt(now)) {
			return withDisposition(latest.orElseThrow().view(), CacheDisposition.EXACT_HIT, null);
		}

		try {
			ProviderSearchResult result = provider.search(new ProviderSearchQuery(
					command.query(),
					command.yearFrom(),
					command.yearTo(),
					command.documentTypes(),
					command.openAccessOnly(),
					command.minimumCitations(),
					command.languages(),
					command.pageSize(),
					command.cursor()));
			CacheDisposition disposition = dispositionFor(command, latest.isPresent());
			return snapshotStore.store(
					command,
					normalizedQuery,
					fingerprint,
					QueryFingerprinter.FINGERPRINT_VERSION,
					QueryFingerprinter.PIPELINE_VERSION,
					result,
					result.retrievedAt().plus(properties.getCacheTtl()),
					disposition);
		}
		catch (ProviderException exception) {
			if (latest.isPresent()) {
				return withDisposition(
						latest.orElseThrow().view(),
						CacheDisposition.STALE_FALLBACK,
						exception.provider().name() + "_UNAVAILABLE");
			}
			throw new SearchUnavailableException(
					"Research provider is temporarily unavailable",
					exception.retryable(),
					exception.retryAfter(),
					exception);
		}
	}

	@Override
	public SearchView get(UUID searchId) {
		return snapshotStore.findById(searchId)
				.orElseThrow(() -> new SearchNotFoundException(searchId));
	}

	private static CacheDisposition dispositionFor(SearchCommand command, boolean hasPreviousSnapshot) {
		if (command.forceRefresh() && hasPreviousSnapshot) {
			return CacheDisposition.FORCED_REFRESH;
		}
		return hasPreviousSnapshot ? CacheDisposition.STALE_REFRESHED : CacheDisposition.MISS_FETCHED;
	}

	private static SearchView withDisposition(
			SearchView view, CacheDisposition disposition, String additionalWarning) {
		var warnings = new ArrayList<>(view.warnings());
		if (additionalWarning != null && !warnings.contains(additionalWarning)) {
			warnings.add(additionalWarning);
		}
		return new SearchView(
				view.searchId(),
				view.query(),
				view.queryFingerprint(),
				disposition,
				view.searchedAt(),
				view.freshUntil(),
				view.nextCursor(),
				view.providerCoverage(),
				warnings,
				view.results());
	}
}
