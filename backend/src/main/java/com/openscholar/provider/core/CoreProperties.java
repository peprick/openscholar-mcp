package com.openscholar.provider.core;

import java.net.URI;
import java.time.Duration;
import java.util.Locale;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(CoreProperties.PREFIX)
public record CoreProperties(
		@DefaultValue("false") boolean enabled,
		@DefaultValue("false") boolean licenseConfirmed,
		@DefaultValue("https://api.core.ac.uk") URI baseUrl,
		@DefaultValue("3s") Duration connectTimeout,
		@DefaultValue("10s") Duration requestTimeout,
		@DefaultValue("8388608") int maxResponseBytes,
		String apiKey) {

	static final String PREFIX = "openscholar.providers.core";

	public CoreProperties {
		if (enabled && !licenseConfirmed) {
			throw new IllegalArgumentException(
					"CORE cannot be enabled until the operator confirms an applicable licence and terms");
		}
		baseUrl = requireHttpBaseUrl(baseUrl);
		connectTimeout = requirePositive(connectTimeout, "connectTimeout");
		requestTimeout = requirePositive(requestTimeout, "requestTimeout");
		if (maxResponseBytes < 1) {
			throw new IllegalArgumentException("CORE maxResponseBytes must be positive");
		}
		apiKey = apiKey == null || apiKey.isBlank() ? null : apiKey.strip();
		if (apiKey != null && (apiKey.indexOf('\r') >= 0 || apiKey.indexOf('\n') >= 0)) {
			throw new IllegalArgumentException("CORE apiKey must not contain line breaks");
		}
	}

	boolean hasApiKey() {
		return apiKey != null;
	}

	private static URI requireHttpBaseUrl(URI value) {
		if (value == null || !value.isAbsolute() || value.getHost() == null) {
			throw new IllegalArgumentException("CORE baseUrl must be an absolute HTTP(S) URL");
		}
		String scheme = value.getScheme().toLowerCase(Locale.ROOT);
		if ((!scheme.equals("http") && !scheme.equals("https"))
				|| value.getRawQuery() != null
				|| value.getRawFragment() != null
				|| value.getUserInfo() != null) {
			throw new IllegalArgumentException(
					"CORE baseUrl must be an absolute HTTP(S) URL without credentials, query, or fragment");
		}
		return value;
	}

	private static Duration requirePositive(Duration value, String name) {
		if (value == null || value.isZero() || value.isNegative()) {
			throw new IllegalArgumentException("CORE " + name + " must be positive");
		}
		return value;
	}
}
