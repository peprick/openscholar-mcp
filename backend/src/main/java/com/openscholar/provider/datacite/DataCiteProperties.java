package com.openscholar.provider.datacite;

import java.net.URI;
import java.time.Duration;
import java.util.Locale;
import java.util.regex.Pattern;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(DataCiteProperties.PREFIX)
public record DataCiteProperties(
		@DefaultValue("false") boolean enabled,
		@DefaultValue("https://api.datacite.org") URI baseUrl,
		@DefaultValue("3s") Duration connectTimeout,
		@DefaultValue("10s") Duration requestTimeout,
		@DefaultValue("8388608") int maxResponseBytes,
		String contactEmail) {

	static final String PREFIX = "openscholar.providers.datacite";
	private static final Pattern CONTACT_EMAIL = Pattern.compile("^[^\\s@]+@[^\\s@]+$");

	public DataCiteProperties {
		baseUrl = requireHttpBaseUrl(baseUrl);
		connectTimeout = requirePositive(connectTimeout, "connectTimeout");
		requestTimeout = requirePositive(requestTimeout, "requestTimeout");
		if (maxResponseBytes < 1) {
			throw new IllegalArgumentException("DataCite maxResponseBytes must be positive");
		}
		contactEmail = contactEmail == null || contactEmail.isBlank() ? null : contactEmail.strip();
		if (contactEmail != null && !CONTACT_EMAIL.matcher(contactEmail).matches()) {
			throw new IllegalArgumentException("DataCite contactEmail must be a single valid email address");
		}
	}

	boolean hasContactEmail() {
		return contactEmail != null;
	}

	String userAgent() {
		return hasContactEmail()
				? "OpenScholar/0.0.1 (mailto:" + contactEmail + ")"
				: "OpenScholar/0.0.1";
	}

	private static URI requireHttpBaseUrl(URI value) {
		if (value == null || !value.isAbsolute() || value.getHost() == null) {
			throw new IllegalArgumentException("DataCite baseUrl must be an absolute HTTP(S) URL");
		}
		String scheme = value.getScheme().toLowerCase(Locale.ROOT);
		if ((!scheme.equals("http") && !scheme.equals("https"))
				|| value.getRawQuery() != null
				|| value.getRawFragment() != null
				|| value.getUserInfo() != null) {
			throw new IllegalArgumentException(
					"DataCite baseUrl must be an absolute HTTP(S) URL without credentials, query, or fragment");
		}
		return value;
	}

	private static Duration requirePositive(Duration value, String name) {
		if (value == null || value.isZero() || value.isNegative()) {
			throw new IllegalArgumentException("DataCite " + name + " must be positive");
		}
		return value;
	}
}
