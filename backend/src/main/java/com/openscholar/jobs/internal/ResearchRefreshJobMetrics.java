package com.openscholar.jobs.internal;

import java.time.Duration;

import com.openscholar.jobs.ResearchRefreshJobStatus;
import com.openscholar.jobs.ResearchRefreshJobType;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

@Component
class ResearchRefreshJobMetrics {

	private final MeterRegistry meterRegistry;

	ResearchRefreshJobMetrics(MeterRegistry meterRegistry) {
		this.meterRegistry = meterRegistry;
	}

	void enqueued(ResearchRefreshJobType type, String trigger) {
		Counter.builder("openscholar.refresh.jobs.enqueue.requests")
				.description("Durable refresh enqueue requests, including active-target deduplication")
				.tag("type", type.name())
				.tag("trigger", trigger)
				.register(meterRegistry)
				.increment();
	}

	void completed(ResearchRefreshJobType type, ResearchRefreshJobStatus status, Duration duration) {
		String statusTag = status.name();
		Counter.builder("openscholar.refresh.jobs.completed")
				.description("Durable research refresh job terminal outcomes")
				.tag("type", type.name())
				.tag("status", statusTag)
				.register(meterRegistry)
				.increment();
		Timer.builder("openscholar.refresh.jobs.duration")
				.description("Durable research refresh execution duration")
				.tag("type", type.name())
				.tag("status", statusTag)
				.register(meterRegistry)
				.record(duration.isNegative() ? Duration.ZERO : duration);
	}
}
