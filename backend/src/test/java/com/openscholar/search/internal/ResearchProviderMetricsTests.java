package com.openscholar.search.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

import com.openscholar.provider.ProviderException;
import com.openscholar.provider.ProviderId;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

class ResearchProviderMetricsTests {

	@Test
	void recordsLowCardinalitySuccessLatencyAndResultCount() {
		SimpleMeterRegistry registry = new SimpleMeterRegistry();
		ResearchProviderMetrics metrics = new ResearchProviderMetrics(registry);

		metrics.success(ProviderId.OPENALEX, 2_000_000, 7);

		assertThat(registry.get("openscholar.provider.requests")
				.tags("provider", "OPENALEX", "outcome", "success", "retryable", "false")
				.counter().count()).isEqualTo(1);
		assertThat(registry.get("openscholar.provider.request.duration")
				.tags("provider", "OPENALEX", "outcome", "success", "retryable", "false")
				.timer().totalTime(TimeUnit.MILLISECONDS)).isEqualTo(2);
		assertThat(registry.get("openscholar.provider.results")
				.tag("provider", "OPENALEX").summary().totalAmount()).isEqualTo(7);
	}

	@Test
	void recordsRetryabilityWithoutUsingUnboundedErrorCodesAsTags() {
		SimpleMeterRegistry registry = new SimpleMeterRegistry();
		ResearchProviderMetrics metrics = new ResearchProviderMetrics(registry);
		ProviderException failure = new ProviderException(
				ProviderId.CORE,
				"UPSTREAM_DYNAMIC_ERROR",
				"synthetic failure",
				true,
				Duration.ofSeconds(5),
				null);

		metrics.failure(ProviderId.CORE, 1_000, failure);

		assertThat(registry.get("openscholar.provider.requests")
				.tags("provider", "CORE", "outcome", "failure", "retryable", "true")
				.counter().count()).isEqualTo(1);
		assertThat(registry.getMeters()).noneMatch(meter ->
				meter.getId().getTags().stream().anyMatch(tag -> tag.getKey().equals("errorCode")));
	}
}
