package com.openscholar.search.internal;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

import com.openscholar.search.SearchCommand;
import com.openscholar.search.SearchMode;
import com.openscholar.search.SearchResultView;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

/**
 * Test-only bridge to the production LOCAL read path. It deliberately bypasses
 * {@link SearchOrchestrator}, whose public LOCAL operation also persists an
 * immutable snapshot and would confound read-query timing.
 */
public final class LocalCatalogSearchEvaluationAdapter {

	private final LocalCatalogSearch localCatalogSearch;
	private final QueryFingerprinter fingerprinter;

	private LocalCatalogSearchEvaluationAdapter(
			LocalCatalogSearch localCatalogSearch, QueryFingerprinter fingerprinter) {
		this.localCatalogSearch = Objects.requireNonNull(localCatalogSearch, "localCatalogSearch");
		this.fingerprinter = Objects.requireNonNull(fingerprinter, "fingerprinter");
	}

	public EvaluationPage search(UUID ownerId, SearchCommand command) {
		Objects.requireNonNull(ownerId, "ownerId");
		Objects.requireNonNull(command, "command");
		if (command.mode() != SearchMode.LOCAL || !"*".equals(command.cursor())) {
			throw new IllegalArgumentException(
					"The evaluation adapter accepts only an initial production LOCAL command");
		}
		var page = localCatalogSearch.search(
				ownerId,
				command,
				fingerprinter.normalizedQuery(command),
				fingerprinter.localScopeFingerprint(command));
		return new EvaluationPage(page.totalMatches(), page.nextCursor(), page.results());
	}

	public record EvaluationPage(
			long totalMatches, String nextCursor, List<SearchResultView> results) {

		public EvaluationPage {
			results = List.copyOf(Objects.requireNonNull(results, "results"));
			if (totalMatches < results.size()) {
				throw new IllegalArgumentException("LOCAL total matches cannot be below page size");
			}
		}
	}

	@TestConfiguration(proxyBeanMethods = false)
	public static class Configuration {

		@Bean
		LocalCatalogSearchEvaluationAdapter localCatalogSearchEvaluationAdapter(
				LocalCatalogSearch localCatalogSearch, QueryFingerprinter fingerprinter) {
			return new LocalCatalogSearchEvaluationAdapter(localCatalogSearch, fingerprinter);
		}
	}
}
