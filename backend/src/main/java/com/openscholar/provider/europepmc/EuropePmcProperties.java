package com.openscholar.provider.europepmc;

import java.net.URI;
import java.time.Duration;
import java.util.Locale;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(EuropePmcProperties.PREFIX)
public record EuropePmcProperties(
		@DefaultValue("false") boolean enabled,
		@DefaultValue("https://www.ebi.ac.uk/europepmc/webservices/rest") URI baseUrl,
		@DefaultValue("3s") Duration connectTimeout,
		@DefaultValue("10s") Duration requestTimeout,
		@DefaultValue("8388608") int maxResponseBytes) {

	static final String PREFIX = "openscholar.providers.europe-pmc";

	public EuropePmcProperties {
		baseUrl = requireHttpBaseUrl(baseUrl);
		connectTimeout = requirePositive(connectTimeout, "connectTimeout");
		requestTimeout = requirePositive(requestTimeout, "requestTimeout");
		if (maxResponseBytes < 1) {
			throw new IllegalArgumentException("Europe PMC maxResponseBytes must be positive");
		}
	}

	String userAgent() {
		return "OpenScholar/0.0.1";
	}

	private static URI requireHttpBaseUrl(URI value) {
		if (value == null || !value.isAbsolute() || value.getHost() == null) {
			throw new IllegalArgumentException("Europe PMC baseUrl must be an absolute HTTP(S) URL");
		}
		String scheme = value.getScheme().toLowerCase(Locale.ROOT);
		if ((!scheme.equals("http") && !scheme.equals("https"))
				|| value.getRawQuery() != null
				|| value.getRawFragment() != null
				|| value.getUserInfo() != null) {
			throw new IllegalArgumentException(
					"Europe PMC baseUrl must be an absolute HTTP(S) URL without credentials, query, or fragment");
		}
		return value;
	}

	private static Duration requirePositive(Duration value, String name) {
		if (value == null || value.isZero() || value.isNegative()) {
			throw new IllegalArgumentException("Europe PMC " + name + " must be positive");
		}
		return value;
	}
}
