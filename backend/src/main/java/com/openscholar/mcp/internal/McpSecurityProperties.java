package com.openscholar.mcp.internal;

import java.net.URI;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("openscholar.mcp.security")
record McpSecurityProperties(String localApiKey, List<String> allowedOrigins) {

	McpSecurityProperties {
		localApiKey = localApiKey == null ? "" : localApiKey.strip();
		allowedOrigins = allowedOrigins == null ? List.of() : allowedOrigins.stream()
				.filter(value -> value != null && !value.isBlank())
				.map(McpSecurityProperties::normalizeOrigin)
				.distinct()
				.toList();
	}

	boolean hasApiKey() {
		return !localApiKey.isEmpty();
	}

	boolean allowsOrigin(String origin) {
		if (origin == null) {
			return true;
		}
		try {
			return allowedOrigins.contains(normalizeOrigin(origin));
		}
		catch (IllegalArgumentException exception) {
			return false;
		}
	}

	private static String normalizeOrigin(String value) {
		URI uri = URI.create(value.strip());
		String scheme = uri.getScheme();
		String host = uri.getHost();
		if (scheme == null || host == null || uri.getUserInfo() != null
				|| uri.getPath() != null && !uri.getPath().isEmpty()
				|| uri.getQuery() != null || uri.getFragment() != null) {
			throw new IllegalArgumentException("MCP origins must contain only scheme, host, and optional port");
		}
		if (!scheme.equalsIgnoreCase("http") && !scheme.equalsIgnoreCase("https")) {
			throw new IllegalArgumentException("MCP origins must use HTTP or HTTPS");
		}
		String normalizedScheme = scheme.toLowerCase(java.util.Locale.ROOT);
		String normalizedHost = host.toLowerCase(java.util.Locale.ROOT);
		String displayHost = normalizedHost.indexOf(':') >= 0
				? "[" + normalizedHost + "]"
				: normalizedHost;
		return normalizedScheme + "://" + displayHost
				+ (uri.getPort() < 0 ? "" : ":" + uri.getPort());
	}
}
