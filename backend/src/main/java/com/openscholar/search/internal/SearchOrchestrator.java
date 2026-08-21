package com.openscholar.search.internal;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.UUID;

import com.openscholar.provider.ProviderSearchBatchResult;
import com.openscholar.provider.ProviderSearchQuery;
import com.openscholar.search.CacheDisposition;
import com.openscholar.search.SearchCommand;
import com.openscholar.search.SearchCoordinationTimeoutException;
import com.openscholar.search.SearchNotFoundException;
import com.openscholar.search.SearchPageExhaustedException;
import com.openscholar.search.SearchResearchUseCase;
import com.openscholar.search.SearchUnavailableException;
import com.openscholar.search.SearchView;
import com.openscholar.search.internal.persistence.SearchSnapshotStore;
import org.springframework.stereotype.Service;

@Service
class SearchOrchestrator implements SearchResearchUseCase {

	private static final String COORDINATION_TIMEOUT_WARNING = "SEARCH_COORDINATION_TIMEOUT";

	private final ResearchProviderFanout providerFanout;
	private final QueryFingerprinter fingerprinter;
	private final SearchSnapshotStore snapshotStore;
	private final SearchProperties properties;
	private final SearchRequestCoordinator requestCoordinator;
	private final SearchExecutionDeadline executionDeadline;
	private final Clock clock;

	SearchOrchestrator(
			ResearchProviderFanout providerFanout,
			QueryFingerprinter fingerprinter,
			SearchSnapshotStore snapshotStore,
			SearchProperties properties,
			SearchRequestCoordinator requestCoordinator,
			SearchExecutionDeadline executionDeadline,
			Clock clock) {
		this.providerFanout = providerFanout;
		this.fingerprinter = fingerprinter;
		this.snapshotStore = snapshotStore;
		this.properties = properties;
		this.requestCoordinator = requestCoordinator;
		this.executionDeadline = executionDeadline;
		this.clock = clock;
	}

	@Override
	public SearchView search(SearchCommand command) {
		return executionDeadline.execute(() -> searchWithinDeadline(command));
	}

	private SearchView searchWithinDeadline(SearchCommand command) {
		executionDeadline.checkpoint();
		String normalizedQuery = fingerprinter.normalizedQuery(command);
		String fingerprint = fingerprinter.fingerprint(command);
		Instant now = Instant.now(clock);
		var latest = snapshotStore.findLatest(fingerprint);
		executionDeadline.checkpoint();
		if (!command.forceRefresh() && latest.isPresent() && latest.orElseThrow().isFreshAt(now)) {
			return withDisposition(latest.orElseThrow().view(), CacheDisposition.EXACT_HIT, null);
		}
		try {
			return requestCoordinator.execute(
					fingerprint,
					() -> searchCoordinated(command, normalizedQuery, fingerprint));
		}
		catch (SearchCoordinationTimeoutException exception) {
			return afterCoordinationTimeout(command, fingerprint, exception);
		}
	}

	private SearchView afterCoordinationTimeout(
			SearchCommand command,
			String fingerprint,
			SearchCoordinationTimeoutException exception) {
		executionDeadline.checkpoint();
		var latest = snapshotStore.findLatest(fingerprint);
		executionDeadline.checkpoint();
		if (latest.isEmpty()) {
			throw exception;
		}
		var snapshot = latest.orElseThrow();
		if (!command.forceRefresh() && snapshot.isFreshAt(Instant.now(clock))) {
			return withDisposition(snapshot.view(), CacheDisposition.EXACT_HIT, null);
		}
		return withDisposition(
				snapshot.view(),
				CacheDisposition.STALE_FALLBACK,
				COORDINATION_TIMEOUT_WARNING);
	}

	private SearchView searchCoordinated(SearchCommand command, String normalizedQuery, String fingerprint) {
		executionDeadline.checkpoint();
		Instant now = Instant.now(clock);
		var latest = snapshotStore.findLatest(fingerprint);
		executionDeadline.checkpoint();
		// A normal caller that waited for an identical request reuses the snapshot just
		// written by the leader. forceRefresh is an explicit provider-fetch instruction:
		// it is serialized for same-key safety but intentionally never becomes a cache hit.
		if (!command.forceRefresh() && latest.isPresent() && latest.orElseThrow().isFreshAt(now)) {
			return withDisposition(latest.orElseThrow().view(), CacheDisposition.EXACT_HIT, null);
		}
		try {
			executionDeadline.checkpoint();
			ProviderSearchBatchResult result = providerFanout.search(new ProviderSearchQuery(
					command.query(),
					command.yearFrom(),
					command.yearTo(),
					command.documentTypes(),
					command.openAccessOnly(),
					command.minimumCitations(),
					command.languages(),
					command.pageSize(),
					command.cursor()));
			executionDeadline.checkpoint();
			CacheDisposition disposition = dispositionFor(command, latest.isPresent());
			SearchView stored = snapshotStore.store(
					command,
					normalizedQuery,
					fingerprint,
					fingerprinter.fingerprintVersion(),
					fingerprinter.pipelineVersion(),
					result,
					result.retrievedAt().plus(properties.getCacheTtl()),
					disposition);
			executionDeadline.checkpoint();
			return stored;
		}
		catch (ProviderFanoutUnavailableException exception) {
			executionDeadline.checkpoint();
			if (latest.isPresent()) {
				return withDispositionAndWarnings(
						latest.orElseThrow().view(),
						CacheDisposition.STALE_FALLBACK,
						exception.warningCodes());
			}
			throw new SearchUnavailableException(
					"Research provider is temporarily unavailable",
					exception.retryable(),
					exception.retryAfter(),
					exception);
		}
	}

	@Override
	public SearchView next(UUID searchId) {
		return executionDeadline.execute(() -> nextWithinDeadline(searchId));
	}

	private SearchView nextWithinDeadline(UUID searchId) {
		executionDeadline.checkpoint();
		var continuation = snapshotStore.findContinuation(searchId)
				.orElseThrow(() -> new SearchNotFoundException(searchId));
		executionDeadline.checkpoint();
		if (!continuation.hasNextPage()) {
			throw new SearchPageExhaustedException(searchId);
		}
		return searchWithinDeadline(continuation.nextCommand());
	}

	@Override
	public SearchView get(UUID searchId) {
		return executionDeadline.execute(() -> {
			executionDeadline.checkpoint();
			SearchView result = snapshotStore.findById(searchId)
					.orElseThrow(() -> new SearchNotFoundException(searchId));
			executionDeadline.checkpoint();
			return result;
		});
	}

	private static CacheDisposition dispositionFor(SearchCommand command, boolean hasPreviousSnapshot) {
		if (command.forceRefresh() && hasPreviousSnapshot) {
			return CacheDisposition.FORCED_REFRESH;
		}
		return hasPreviousSnapshot ? CacheDisposition.STALE_REFRESHED : CacheDisposition.MISS_FETCHED;
	}

	private static SearchView withDisposition(
			SearchView view, CacheDisposition disposition, String additionalWarning) {
		return withDispositionAndWarnings(
				view,
				disposition,
				additionalWarning == null ? java.util.List.of() : java.util.List.of(additionalWarning));
	}

	private static SearchView withDispositionAndWarnings(
			SearchView view, CacheDisposition disposition, java.util.List<String> additionalWarnings) {
		var warnings = new ArrayList<>(view.warnings());
		for (String warning : additionalWarnings) {
			if (warning != null && !warnings.contains(warning)) {
				warnings.add(warning);
			}
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
