package com.openscholar.search.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Function;

import com.openscholar.provider.ProviderException;
import com.openscholar.provider.ProviderId;
import com.openscholar.provider.ProviderSearchBatchResult;
import com.openscholar.provider.ProviderSearchQuery;
import com.openscholar.provider.ProviderSearchResult;
import com.openscholar.provider.ResearchProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class ResearchProviderFanoutTests {

	private static final Instant NOW = Instant.parse("2026-08-21T08:00:00Z");

	private final ProviderCursorCodec cursorCodec = new ProviderCursorCodec(new ObjectMapper());
	private final ExecutorService executor = Executors.newFixedThreadPool(1);

	@AfterEach
	void shutDownExecutor() {
		executor.shutdownNow();
	}

	@Test
	void isolatesProviderFailuresAndContinuesOnlySuccessfulProviderCursors() {
		AtomicInteger active = new AtomicInteger();
		AtomicInteger maximumActive = new AtomicInteger();
		AtomicInteger coreCalls = new AtomicInteger();
		AtomicInteger openAlexCalls = new AtomicInteger();
		TrackingProvider core = new TrackingProvider(ProviderId.CORE, query -> {
			coreCalls.incrementAndGet();
			int current = active.incrementAndGet();
			maximumActive.accumulateAndGet(current, Math::max);
			try {
				LockSupport.parkNanos(Duration.ofMillis(20).toNanos());
				String nextCursor = query.cursor().equals("*") ? "core-next" : null;
				return new ProviderSearchResult(ProviderId.CORE, List.of(), 12, nextCursor, NOW);
			}
			finally {
				active.decrementAndGet();
			}
		});
		TrackingProvider openAlex = new TrackingProvider(ProviderId.OPENALEX, query -> {
			openAlexCalls.incrementAndGet();
			int current = active.incrementAndGet();
			maximumActive.accumulateAndGet(current, Math::max);
			try {
				throw failure(ProviderId.OPENALEX, "OPENALEX_RATE_LIMITED", true, Duration.ofSeconds(3));
			}
			finally {
				active.decrementAndGet();
			}
		});
		ResearchProviderFanout fanout = fanout(List.of(openAlex, core));

		ProviderSearchBatchResult first = fanout.search(query("*"));

		assertThat(first.results()).extracting(ProviderSearchResult::provider)
				.containsExactly(ProviderId.CORE);
		assertThat(first.failures()).extracting(ProviderException::provider)
				.containsExactly(ProviderId.OPENALEX);
		assertThat(first.nextCursor()).startsWith(ProviderCursorCodec.PREFIX);
		assertThat(cursorCodec.decode(first.nextCursor(), fanout.providerIds()))
				.containsExactlyEntriesOf(java.util.Map.of(ProviderId.CORE, "core-next"));
		assertThat(maximumActive).hasValue(1);

		ProviderSearchBatchResult second = fanout.search(query(first.nextCursor()));

		assertThat(second.results()).extracting(ProviderSearchResult::provider)
				.containsExactly(ProviderId.CORE);
		assertThat(second.failures()).isEmpty();
		assertThat(second.nextCursor()).isNull();
		assertThat(coreCalls).hasValue(2);
		assertThat(openAlexCalls).hasValue(1);
	}

	@Test
	void reportsDeterministicAggregateFailureMetadataWhenAllProvidersFail() {
		ResearchProvider core = new TrackingProvider(ProviderId.CORE, query -> {
			throw failure(ProviderId.CORE, "CORE_UNAVAILABLE", true, Duration.ofSeconds(5));
		});
		ResearchProvider openAlex = new TrackingProvider(ProviderId.OPENALEX, query -> {
			throw failure(ProviderId.OPENALEX, "OPENALEX_RATE_LIMITED", true, Duration.ofSeconds(2));
		});
		ResearchProviderFanout fanout = fanout(List.of(openAlex, core));

		ProviderFanoutUnavailableException exception = catchThrowableOfType(
				ProviderFanoutUnavailableException.class,
				() -> fanout.search(query("*")));

		assertThat(exception.warningCodes())
				.containsExactly("CORE_UNAVAILABLE", "OPENALEX_RATE_LIMITED");
		assertThat(exception.retryable()).isTrue();
		assertThat(exception.retryAfter()).isEqualTo(Duration.ofSeconds(2));
		assertThat(exception.getCause()).isInstanceOf(ProviderException.class);
		assertThat(((ProviderException) exception.getCause()).provider()).isEqualTo(ProviderId.CORE);
	}

	@Test
	void preservesRawCursorBehaviorWithOneOpenAlexProvider() {
		TrackingProvider openAlex = new TrackingProvider(
				ProviderId.OPENALEX,
				query -> new ProviderSearchResult(
						ProviderId.OPENALEX, List.of(), 4, "oa-next", NOW));
		ResearchProviderFanout fanout = fanout(List.of(openAlex));

		ProviderSearchBatchResult result = fanout.search(query("legacy-cursor"));

		assertThat(openAlex.queries()).singleElement()
				.extracting(ProviderSearchQuery::cursor)
				.isEqualTo("legacy-cursor");
		assertThat(result.nextCursor()).isEqualTo("oa-next");
	}

	private ResearchProviderFanout fanout(List<ResearchProvider> providers) {
		return new ResearchProviderFanout(
				providers,
				cursorCodec,
				executor,
				Clock.fixed(NOW, ZoneOffset.UTC));
	}

	private static ProviderSearchQuery query(String cursor) {
		return new ProviderSearchQuery("graph models", null, null, Set.of(), false, 0, Set.of(), 20, cursor);
	}

	private static ProviderException failure(
			ProviderId provider, String code, boolean retryable, Duration retryAfter) {
		return new ProviderException(provider, code, code, retryable, retryAfter, null);
	}

	private static final class TrackingProvider implements ResearchProvider {

		private final ProviderId id;
		private final Function<ProviderSearchQuery, ProviderSearchResult> behavior;
		private final java.util.concurrent.CopyOnWriteArrayList<ProviderSearchQuery> queries =
				new java.util.concurrent.CopyOnWriteArrayList<>();

		private TrackingProvider(
				ProviderId id, Function<ProviderSearchQuery, ProviderSearchResult> behavior) {
			this.id = id;
			this.behavior = behavior;
		}

		@Override
		public ProviderId id() {
			return id;
		}

		@Override
		public ProviderSearchResult search(ProviderSearchQuery query) {
			queries.add(query);
			return behavior.apply(query);
		}

		private List<ProviderSearchQuery> queries() {
			return List.copyOf(queries);
		}
	}
}
