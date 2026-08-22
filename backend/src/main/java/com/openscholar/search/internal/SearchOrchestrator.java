package com.openscholar.search.internal;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.UUID;
import java.util.function.Supplier;

import com.openscholar.provider.ProviderSearchBatchResult;
import com.openscholar.provider.ProviderSearchQuery;
import com.openscholar.search.CacheDisposition;
import com.openscholar.search.SearchCommand;
import com.openscholar.search.SearchCoordinationTimeoutException;
import com.openscholar.search.SearchNotFoundException;
import com.openscholar.search.SearchPageExhaustedException;
import com.openscholar.search.SearchResearchUseCase;
import com.openscholar.search.SearchRefreshUseCase;
import com.openscholar.search.SearchUnavailableException;
import com.openscholar.search.SearchView;
import com.openscholar.search.internal.persistence.SearchSnapshotStore;
import com.openscholar.security.CurrentUserIdProvider;
import org.springframework.stereotype.Service;

@Service
class SearchOrchestrator implements SearchResearchUseCase, SearchRefreshUseCase {

	private static final String COORDINATION_TIMEOUT_WARNING = "SEARCH_COORDINATION_TIMEOUT";

	private final ResearchProviderFanout providerFanout;
	private final QueryFingerprinter fingerprinter;
	private final SearchSnapshotStore snapshotStore;
	private final SearchProperties properties;
	private final SearchRequestCoordinator requestCoordinator;
	private final SearchExecutionDeadline executionDeadline;
	private final CurrentUserIdProvider currentUser;
	private final SearchMetrics metrics;
	private final Clock clock;

	SearchOrchestrator(
			ResearchProviderFanout providerFanout,
			QueryFingerprinter fingerprinter,
			SearchSnapshotStore snapshotStore,
			SearchProperties properties,
			SearchRequestCoordinator requestCoordinator,
			SearchExecutionDeadline executionDeadline,
			CurrentUserIdProvider currentUser,
			SearchMetrics metrics,
			Clock clock) {
		this.providerFanout = providerFanout;
		this.fingerprinter = fingerprinter;
		this.snapshotStore = snapshotStore;
		this.properties = properties;
		this.requestCoordinator = requestCoordinator;
		this.executionDeadline = executionDeadline;
		this.currentUser = currentUser;
		this.metrics = metrics;
		this.clock = clock;
	}

	@Override
	public SearchView search(SearchCommand command) {
		return measured("search", () -> {
			UUID ownerId = currentUser.currentUserId();
			return executionDeadline.execute(() -> searchWithinDeadline(ownerId, command));
		});
	}

	private SearchView searchWithinDeadline(UUID ownerId, SearchCommand command) {
		executionDeadline.checkpoint();
		String normalizedQuery = fingerprinter.normalizedQuery(command);
		String fingerprint = fingerprinter.fingerprint(command);
		Instant now = Instant.now(clock);
		var latest = snapshotStore.findLatest(ownerId, fingerprint);
		executionDeadline.checkpoint();
		if (!command.forceRefresh() && latest.isPresent() && latest.orElseThrow().isFreshAt(now)) {
			return withDisposition(latest.orElseThrow().view(), CacheDisposition.EXACT_HIT, null);
		}
		try {
			return requestCoordinator.execute(
					ownerId + ":" + fingerprint,
					() -> searchCoordinated(ownerId, command, normalizedQuery, fingerprint));
		}
		catch (SearchCoordinationTimeoutException exception) {
			return afterCoordinationTimeout(ownerId, command, fingerprint, exception);
		}
	}

	private SearchView afterCoordinationTimeout(
			UUID ownerId,
			SearchCommand command,
			String fingerprint,
			SearchCoordinationTimeoutException exception) {
		executionDeadline.checkpoint();
		var latest = snapshotStore.findLatest(ownerId, fingerprint);
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

	private SearchView searchCoordinated(
			UUID ownerId, SearchCommand command, String normalizedQuery, String fingerprint) {
		executionDeadline.checkpoint();
		Instant now = Instant.now(clock);
		var latest = snapshotStore.findLatest(ownerId, fingerprint);
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
					ownerId,
					command,
					normalizedQuery,
					fingerprint,
					fingerprinter.fingerprintVersion(),
					fingerprinter.pipelineVersion(),
					result,
					result.retrievedAt().plus(properties.getCacheTtl()),
					disposition);
			metrics.stored(result, stored);
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
		return measured("next", () -> {
			UUID ownerId = currentUser.currentUserId();
			return executionDeadline.execute(() -> nextWithinDeadline(ownerId, searchId));
		});
	}

	private SearchView nextWithinDeadline(UUID ownerId, UUID searchId) {
		executionDeadline.checkpoint();
		var continuation = snapshotStore.findContinuation(ownerId, searchId)
				.orElseThrow(() -> new SearchNotFoundException(searchId));
		executionDeadline.checkpoint();
		if (!continuation.hasNextPage()) {
			throw new SearchPageExhaustedException(searchId);
		}
		return searchWithinDeadline(ownerId, continuation.nextCommand());
	}

	@Override
	public SearchView get(UUID searchId) {
		return measured("get", () -> {
			UUID ownerId = currentUser.currentUserId();
			return executionDeadline.execute(() -> {
				executionDeadline.checkpoint();
				SearchView result = snapshotStore.findById(ownerId, searchId)
						.orElseThrow(() -> new SearchNotFoundException(searchId));
				executionDeadline.checkpoint();
				return result;
			});
		});
	}

	@Override
	public SearchView refresh(UUID searchId) {
		return measured("refresh", () -> executionDeadline.execute(() -> {
			executionDeadline.checkpoint();
			SearchSnapshotStore.StoredSearch stored = snapshotStore.findStoredSearch(searchId)
					.orElseThrow(() -> new SearchNotFoundException(searchId));
			executionDeadline.checkpoint();
			SearchCommand command = stored.command();
			return searchWithinDeadline(stored.ownerId(), new SearchCommand(
					command.query(),
					command.yearFrom(),
					command.yearTo(),
					command.documentTypes(),
					command.openAccessOnly(),
					command.minimumCitations(),
					command.languages(),
					command.pageSize(),
					command.cursor(),
					true));
		}));
	}

	private SearchView measured(String operation, Supplier<SearchView> request) {
		var sample = metrics.start();
		try {
			SearchView result = request.get();
			metrics.completed(operation, result.cacheDisposition(), sample);
			return result;
		}
		catch (RuntimeException | Error exception) {
			metrics.failed(operation, sample);
			throw exception;
		}
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
