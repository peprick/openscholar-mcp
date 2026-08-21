package com.openscholar.search.internal;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;

import com.openscholar.provider.ProviderException;
import com.openscholar.provider.ProviderId;
import com.openscholar.provider.ProviderSearchBatchResult;
import com.openscholar.provider.ProviderSearchQuery;
import com.openscholar.provider.ProviderSearchResult;
import com.openscholar.provider.ResearchProvider;
import com.openscholar.search.SearchExecutionInterruptedException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

@Component
class ResearchProviderFanout {

	private final List<ResearchProvider> providers;
	private final ProviderCursorCodec cursorCodec;
	private final ExecutorService executor;
	private final Clock clock;

	@Autowired
	ResearchProviderFanout(
			Map<String, ResearchProvider> providerBeans,
			ConfigurableListableBeanFactory beanFactory,
			ProviderCursorCodec cursorCodec,
			@Qualifier(SearchConfiguration.PROVIDER_EXECUTOR_BEAN) ExecutorService executor,
			Clock clock) {
		this(selectProviders(providerBeans, beanFactory), cursorCodec, executor, clock);
	}

	ResearchProviderFanout(
			List<ResearchProvider> providers,
			ProviderCursorCodec cursorCodec,
			ExecutorService executor,
			Clock clock) {
		this.providers = orderedProviders(providers);
		this.cursorCodec = Objects.requireNonNull(cursorCodec, "cursorCodec");
		this.executor = Objects.requireNonNull(executor, "executor");
		this.clock = Objects.requireNonNull(clock, "clock");
	}

	ProviderSearchBatchResult search(ProviderSearchQuery query) {
		Objects.requireNonNull(query, "query");
		List<ProviderId> enabled = providerIds();
		Map<ProviderId, String> cursors = cursorCodec.decode(query.cursor(), enabled);
		List<ProviderRequest> requests = providers.stream()
				.filter(provider -> cursors.containsKey(provider.id()))
				.map(provider -> new ProviderRequest(provider, withCursor(query, cursors.get(provider.id()))))
				.toList();
		if (requests.isEmpty()) {
			throw new IllegalArgumentException("Search cursor has no enabled provider continuation");
		}

		List<ProviderCall> calls = requests.size() == 1
				? List.of(invoke(requests.getFirst()))
				: invokeConcurrently(requests);
		calls = calls.stream()
				.sorted(Comparator.comparing(call -> call.provider().name()))
				.toList();

		List<ProviderSearchResult> results = calls.stream()
				.map(ProviderCall::result)
				.filter(Objects::nonNull)
				.toList();
		List<ProviderException> failures = calls.stream()
				.map(ProviderCall::failure)
				.filter(Objects::nonNull)
				.toList();
		if (results.isEmpty()) {
			throw new ProviderFanoutUnavailableException(failures);
		}
		Map<ProviderId, String> nextCursors = new EnumMap<>(ProviderId.class);
		results.forEach(result -> {
			if (result.nextCursor() != null && !result.nextCursor().isBlank()) {
				nextCursors.put(result.provider(), result.nextCursor());
			}
		});
		Instant retrievedAt = results.stream()
				.map(ProviderSearchResult::retrievedAt)
				.max(Comparator.naturalOrder())
				.orElseGet(clock::instant);
		return new ProviderSearchBatchResult(
				results,
				failures,
				cursorCodec.encode(nextCursors, enabled),
				retrievedAt);
	}

	List<ProviderId> providerIds() {
		return providers.stream().map(ResearchProvider::id).toList();
	}

	private List<ProviderCall> invokeConcurrently(List<ProviderRequest> requests) {
		List<Future<ProviderCall>> futures = new ArrayList<>();
		try {
			for (ProviderRequest request : requests) {
				futures.add(executor.submit(() -> invoke(request)));
			}
			List<ProviderCall> calls = new ArrayList<>(futures.size());
			for (Future<ProviderCall> future : futures) {
				calls.add(future.get());
			}
			return List.copyOf(calls);
		}
		catch (InterruptedException exception) {
			cancel(futures);
			Thread.currentThread().interrupt();
			throw new SearchExecutionInterruptedException(exception);
		}
		catch (CancellationException | RejectedExecutionException exception) {
			cancel(futures);
			throw new SearchExecutionInterruptedException(exception);
		}
		catch (ExecutionException exception) {
			cancel(futures);
			Throwable cause = exception.getCause();
			if (cause instanceof Error error) {
				throw error;
			}
			throw new IllegalStateException("A provider worker failed unexpectedly", cause);
		}
	}

	private ProviderCall invoke(ProviderRequest request) {
		ResearchProvider provider = request.provider();
		try {
			ProviderSearchResult result = Objects.requireNonNull(
					provider.search(request.query()), "Research providers must not return null");
			validateResult(provider.id(), result);
			return new ProviderCall(provider.id(), result, null);
		}
		catch (ProviderException exception) {
			ProviderException failure = exception.provider() == provider.id()
					? exception
					: unexpectedFailure(provider.id(), exception);
			return new ProviderCall(provider.id(), null, failure);
		}
		catch (RuntimeException exception) {
			return new ProviderCall(provider.id(), null, unexpectedFailure(provider.id(), exception));
		}
	}

	private static void validateResult(ProviderId expected, ProviderSearchResult result) {
		if (result.provider() != expected
				|| result.records().stream().anyMatch(record -> record.provider() != expected)) {
			throw new IllegalStateException("Provider result identity does not match the invoked provider");
		}
	}

	private static ProviderException unexpectedFailure(ProviderId provider, RuntimeException cause) {
		return new ProviderException(
				provider,
				provider.name() + "_UNEXPECTED_ERROR",
				"Research provider failed unexpectedly",
				true,
				null,
				cause);
	}

	private static ProviderSearchQuery withCursor(ProviderSearchQuery query, String cursor) {
		return new ProviderSearchQuery(
				query.query(),
				query.yearFrom(),
				query.yearTo(),
				query.documentTypes(),
				query.openAccessOnly(),
				query.minimumCitations(),
				query.languages(),
				query.pageSize(),
				cursor);
	}

	private static List<ResearchProvider> selectProviders(
			Map<String, ResearchProvider> providerBeans,
			ConfigurableListableBeanFactory beanFactory) {
		Objects.requireNonNull(providerBeans, "providerBeans");
		Objects.requireNonNull(beanFactory, "beanFactory");
		Map<ProviderId, List<Map.Entry<String, ResearchProvider>>> grouped = new EnumMap<>(ProviderId.class);
		providerBeans.entrySet().forEach(entry -> grouped
				.computeIfAbsent(entry.getValue().id(), ignored -> new ArrayList<>())
				.add(entry));
		List<ResearchProvider> selected = new ArrayList<>();
		grouped.forEach((id, candidates) -> {
			if (candidates.size() == 1) {
				selected.add(candidates.getFirst().getValue());
				return;
			}
			List<ResearchProvider> primary = candidates.stream()
					.filter(candidate -> beanFactory.findAnnotationOnBean(candidate.getKey(), Primary.class) != null)
					.map(Map.Entry::getValue)
					.toList();
			if (primary.size() != 1) {
				throw new IllegalStateException("Exactly one provider bean must be primary for duplicate id " + id);
			}
			selected.add(primary.getFirst());
		});
		return List.copyOf(selected);
	}

	private static List<ResearchProvider> orderedProviders(List<ResearchProvider> providers) {
		Objects.requireNonNull(providers, "providers");
		List<ResearchProvider> ordered = providers.stream()
				.map(provider -> Objects.requireNonNull(provider, "providers must not contain null"))
				.sorted(Comparator.comparing(provider -> provider.id().name()))
				.toList();
		if (ordered.isEmpty()) {
			throw new IllegalStateException("At least one research provider must be enabled");
		}
		if (ordered.stream().map(ResearchProvider::id).distinct().count() != ordered.size()) {
			throw new IllegalStateException("Research provider identifiers must be unique");
		}
		return ordered;
	}

	private static void cancel(List<? extends Future<?>> futures) {
		futures.forEach(future -> future.cancel(true));
	}

	private record ProviderRequest(ResearchProvider provider, ProviderSearchQuery query) {
	}

	private record ProviderCall(
			ProviderId provider, ProviderSearchResult result, ProviderException failure) {
	}
}
