package com.openscholar.search.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.openscholar.paper.DocumentType;
import com.openscholar.provider.ProviderId;
import com.openscholar.provider.ProviderPaperRecord;
import com.openscholar.provider.ProviderSearchBatchResult;
import com.openscholar.provider.ProviderSearchResult;
import com.openscholar.search.CacheDisposition;
import com.openscholar.search.SearchView;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

class SearchMetricsTests {

	private static final Instant NOW = Instant.parse("2026-08-22T12:00:00Z");

	private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
	private final SearchMetrics metrics = new SearchMetrics(registry);

	@Test
	void recordsSuccessfulCacheDispositionAndDuration() {
		metrics.completed("search", CacheDisposition.EXACT_HIT, metrics.start());

		assertThat(registry.get("openscholar.search.requests")
				.tags("operation", "search", "outcome", "success", "cache_disposition", "EXACT_HIT")
				.counter().count()).isEqualTo(1);
		assertThat(registry.get("openscholar.search.duration")
				.tags("operation", "search", "outcome", "success", "cache_disposition", "EXACT_HIT")
				.timer().count()).isEqualTo(1);
	}

	@Test
	void recordsFailuresWithoutInventingACacheDisposition() {
		metrics.failed("get", metrics.start());

		assertThat(registry.get("openscholar.search.requests")
				.tags("operation", "get", "outcome", "failure", "cache_disposition", "NONE")
				.counter().count()).isEqualTo(1);
	}

	@Test
	void recordsBoundedProviderAndPersistedCountsWithoutCallingTheDifferenceDeduplication() {
		ProviderPaperRecord record = new ProviderPaperRecord(
				ProviderId.OPENALEX, "W-1", null, null, "Synthetic paper", null, null, 2026,
				DocumentType.ARTICLE, "en", null, null, List.of(), false, null, null, null, NOW, Map.of());
		ProviderSearchBatchResult batch = new ProviderSearchBatchResult(
				List.of(new ProviderSearchResult(ProviderId.OPENALEX, List.of(record, record), 2, null, NOW)),
				List.of(), null, NOW);
		SearchView stored = new SearchView(
				UUID.randomUUID(), "query", "fingerprint", CacheDisposition.MISS_FETCHED,
				NOW, NOW.plusSeconds(60), null, List.of(), List.of(), List.of());

		metrics.stored(batch, stored);

		assertThat(registry.get("openscholar.search.records").tag("stage", "provider")
				.summary().totalAmount()).isEqualTo(2);
		assertThat(registry.get("openscholar.search.records").tag("stage", "persisted")
				.summary().totalAmount()).isZero();
		assertThat(registry.find("openscholar.search.deduplicated.records").counter()).isNull();
	}
}
