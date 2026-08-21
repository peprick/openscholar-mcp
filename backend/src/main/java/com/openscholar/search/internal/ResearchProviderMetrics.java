package com.openscholar.search.internal;

import java.time.Duration;

import com.openscholar.provider.ProviderException;
import com.openscholar.provider.ProviderId;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

@Component
class ResearchProviderMetrics {

	private static final String REQUESTS = "openscholar.provider.requests";
	private static final String DURATION = "openscholar.provider.request.duration";
	private static final String RESULTS = "openscholar.provider.results";

	private final MeterRegistry meterRegistry;

	ResearchProviderMetrics(MeterRegistry meterRegistry) {
		this.meterRegistry = meterRegistry;
	}

	void success(ProviderId provider, long elapsedNanos, int returnedCount) {
		recordRequest(provider, "success", false, elapsedNanos);
		DistributionSummary.builder(RESULTS)
				.description("Research records returned by successful provider calls")
				.baseUnit("records")
				.tag("provider", provider.name())
				.register(meterRegistry)
				.record(returnedCount);
	}

	void failure(ProviderId provider, long elapsedNanos, ProviderException failure) {
		recordRequest(provider, "failure", failure.retryable(), elapsedNanos);
	}

	private void recordRequest(ProviderId provider, String outcome, boolean retryable, long elapsedNanos) {
		String providerTag = provider.name();
		String retryableTag = Boolean.toString(retryable);
		Counter.builder(REQUESTS)
				.description("Completed research provider calls")
				.tag("provider", providerTag)
				.tag("outcome", outcome)
				.tag("retryable", retryableTag)
				.register(meterRegistry)
				.increment();
		Timer.builder(DURATION)
				.description("Research provider call duration")
				.tag("provider", providerTag)
				.tag("outcome", outcome)
				.tag("retryable", retryableTag)
				.register(meterRegistry)
				.record(Duration.ofNanos(Math.max(0, elapsedNanos)));
	}
}
