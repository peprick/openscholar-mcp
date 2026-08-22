package com.openscholar.provider.doaj;

import java.net.URI;
import java.time.Duration;
import java.util.Locale;
import java.util.regex.Pattern;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(DoajProperties.PREFIX)
public record DoajProperties(
		@DefaultValue("false") boolean enabled,
		@DefaultValue("https://doaj.org/api/v4") URI baseUrl,
		@DefaultValue("3s") Duration connectTimeout,
		@DefaultValue("10s") Duration requestTimeout,
		@DefaultValue("8388608") int maxResponseBytes,
		String contactEmail) {

	static final String PREFIX = "openscholar.providers.doaj";
	private static final Pattern CONTACT_EMAIL = Pattern.compile("^[^\\s@]+@[^\\s@]+$");

	public DoajProperties {
		baseUrl = requireHttpBaseUrl(baseUrl);
		connectTimeout = requirePositive(connectTimeout, "connectTimeout");
		requestTimeout = requirePositive(requestTimeout, "requestTimeout");
		if (maxResponseBytes < 1) {
			throw new IllegalArgumentException("DOAJ maxResponseBytes must be positive");
		}
		contactEmail = contactEmail == null || contactEmail.isBlank() ? null : contactEmail.strip();
		if (contactEmail != null && !CONTACT_EMAIL.matcher(contactEmail).matches()) {
			throw new IllegalArgumentException("DOAJ contactEmail must be a single valid email address");
		}
	}

	String userAgent() {
		return contactEmail == null
				? "OpenScholar/0.0.1"
				: "OpenScholar/0.0.1 (mailto:" + contactEmail + ")";
	}

	private static URI requireHttpBaseUrl(URI value) {
		if (value == null || !value.isAbsolute() || value.getHost() == null) {
			throw new IllegalArgumentException("DOAJ baseUrl must be an absolute HTTP(S) URL");
		}
		String scheme = value.getScheme().toLowerCase(Locale.ROOT);
		if ((!scheme.equals("http") && !scheme.equals("https"))
				|| value.getRawQuery() != null
				|| value.getRawFragment() != null
				|| value.getUserInfo() != null) {
			throw new IllegalArgumentException(
					"DOAJ baseUrl must be an absolute HTTP(S) URL without credentials, query, or fragment");
		}
		return value;
	}

	private static Duration requirePositive(Duration value, String name) {
		if (value == null || value.isZero() || value.isNegative()) {
			throw new IllegalArgumentException("DOAJ " + name + " must be positive");
		}
		return value;
	}
}
