package com.openscholar.search.internal;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("openscholar.search")
class SearchProperties {

	private Duration cacheTtl = Duration.ofHours(24);

	public Duration getCacheTtl() {
		return cacheTtl;
	}

	public void setCacheTtl(Duration cacheTtl) {
		if (cacheTtl == null || cacheTtl.isNegative() || cacheTtl.isZero()) {
			throw new IllegalArgumentException("Search cache TTL must be positive");
		}
		this.cacheTtl = cacheTtl;
	}
}
