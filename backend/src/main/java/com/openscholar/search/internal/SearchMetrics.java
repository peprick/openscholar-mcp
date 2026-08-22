package com.openscholar.search.internal;

import java.time.Duration;

import com.openscholar.provider.ProviderSearchBatchResult;
import com.openscholar.search.CacheDisposition;
import com.openscholar.search.SearchView;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

@Component
class SearchMetrics {

	private static final String REQUESTS = "openscholar.search.requests";
	private static final String DURATION = "openscholar.search.duration";
	private static final String RECORDS = "openscholar.search.records";

	private final MeterRegistry meterRegistry;

	SearchMetrics(MeterRegistry meterRegistry) {
		this.meterRegistry = meterRegistry;
	}

	Timer.Sample start() {
		return Timer.start(meterRegistry);
	}

	void completed(String operation, CacheDisposition disposition, Timer.Sample sample) {
		record(operation, "success", disposition.name(), sample);
	}

	void failed(String operation, Timer.Sample sample) {
		record(operation, "failure", "NONE", sample);
	}

	void stored(ProviderSearchBatchResult batch, SearchView stored) {
		long providerRecords = batch.results().stream()
				.mapToLong(result -> result.records().size())
				.sum();
		long canonicalRecords = stored.results().size();
		recordCount("provider", providerRecords);
		recordCount("persisted", canonicalRecords);
	}

	private void record(String operation, String outcome, String disposition, Timer.Sample sample) {
		Counter.builder(REQUESTS)
				.description("Completed search use-case requests")
				.tag("operation", operation)
				.tag("outcome", outcome)
				.tag("cache_disposition", disposition)
				.register(meterRegistry)
				.increment();
		sample.stop(Timer.builder(DURATION)
				.description("Search use-case request duration")
				.publishPercentileHistogram()
				.minimumExpectedValue(Duration.ofMillis(1))
				.maximumExpectedValue(Duration.ofSeconds(30))
				.tag("operation", operation)
				.tag("outcome", outcome)
				.tag("cache_disposition", disposition)
				.register(meterRegistry));
	}

	private void recordCount(String stage, long count) {
		DistributionSummary.builder(RECORDS)
				.description("Records observed at bounded search-pipeline stages; differences may include merging and page truncation")
				.baseUnit("records")
				.tag("stage", stage)
				.register(meterRegistry)
				.record(count);
	}
}
