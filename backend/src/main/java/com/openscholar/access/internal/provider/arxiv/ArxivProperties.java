package com.openscholar.access.internal.provider.arxiv;

import java.net.URI;
import java.time.Duration;

import com.openscholar.access.internal.provider.AccessProviderHttpSupport;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(ArxivProperties.PREFIX)
public record ArxivProperties(
		@DefaultValue("https://export.arxiv.org/api") URI baseUrl,
		@DefaultValue("3s") Duration connectTimeout,
		@DefaultValue("20s") Duration readTimeout,
		@DefaultValue("3s") Duration minimumRequestInterval) {

	static final String PREFIX = "openscholar.access.providers.arxiv";
	private static final Duration MINIMUM_PRODUCTION_INTERVAL = Duration.ofSeconds(3);

	public ArxivProperties {
		baseUrl = AccessProviderHttpSupport.requireHttpBaseUrl(baseUrl, "arXiv");
		connectTimeout = AccessProviderHttpSupport.requirePositive(connectTimeout, "arXiv", "connectTimeout");
		readTimeout = AccessProviderHttpSupport.requirePositive(readTimeout, "arXiv", "readTimeout");
		if (minimumRequestInterval == null || minimumRequestInterval.compareTo(MINIMUM_PRODUCTION_INTERVAL) < 0) {
			throw new IllegalArgumentException("arXiv minimumRequestInterval must be at least 3 seconds");
		}
		if (minimumRequestInterval.compareTo(Duration.ofHours(1)) > 0) {
			throw new IllegalArgumentException("arXiv minimumRequestInterval must not exceed 1 hour");
		}
	}
}
