package com.openscholar.search.internal;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("openscholar.search")
class SearchProperties {

	private Duration cacheTtl = Duration.ofHours(24);
	private Duration coordinationWaitTimeout = Duration.ofSeconds(12);
	private Duration executionTimeout = Duration.ofSeconds(18);

	public Duration getCacheTtl() {
		return cacheTtl;
	}

	public void setCacheTtl(Duration cacheTtl) {
		if (cacheTtl == null || cacheTtl.isNegative() || cacheTtl.isZero()) {
			throw new IllegalArgumentException("Search cache TTL must be positive");
		}
		this.cacheTtl = cacheTtl;
	}

	public Duration getCoordinationWaitTimeout() {
		return coordinationWaitTimeout;
	}

	public void setCoordinationWaitTimeout(Duration coordinationWaitTimeout) {
		if (coordinationWaitTimeout == null || coordinationWaitTimeout.compareTo(Duration.ofMillis(1)) < 0) {
			throw new IllegalArgumentException("Search coordination wait timeout must be at least one millisecond");
		}
		this.coordinationWaitTimeout = coordinationWaitTimeout;
	}

	public Duration getExecutionTimeout() {
		return executionTimeout;
	}

	public void setExecutionTimeout(Duration executionTimeout) {
		if (executionTimeout == null || executionTimeout.compareTo(Duration.ofMillis(1)) < 0) {
			throw new IllegalArgumentException("Search execution timeout must be at least one millisecond");
		}
		this.executionTimeout = executionTimeout;
	}
}
