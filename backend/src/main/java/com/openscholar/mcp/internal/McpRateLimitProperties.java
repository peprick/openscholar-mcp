package com.openscholar.mcp.internal;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("openscholar.mcp.rate-limit")
record McpRateLimitProperties(
		Boolean enabled,
		Integer requestsPerWindow,
		Duration window,
		Integer maximumTrackedClients) {

	private static final int DEFAULT_REQUESTS_PER_WINDOW = 120;
	private static final Duration DEFAULT_WINDOW = Duration.ofMinutes(1);
	private static final int DEFAULT_MAXIMUM_TRACKED_CLIENTS = 10_000;

	McpRateLimitProperties {
		enabled = enabled == null || enabled;
		requestsPerWindow = requestsPerWindow == null
				? DEFAULT_REQUESTS_PER_WINDOW
				: requestsPerWindow;
		window = window == null ? DEFAULT_WINDOW : window;
		maximumTrackedClients = maximumTrackedClients == null
				? DEFAULT_MAXIMUM_TRACKED_CLIENTS
				: maximumTrackedClients;

		if (requestsPerWindow < 1) {
			throw new IllegalArgumentException("MCP rate-limit requests-per-window must be positive");
		}
		if (window.isNegative() || window.toMillis() < 1) {
			throw new IllegalArgumentException("MCP rate-limit window must be at least one millisecond");
		}
		if (maximumTrackedClients < 1) {
			throw new IllegalArgumentException("MCP rate-limit maximum-tracked-clients must be positive");
		}
	}
}
