package com.openscholar.access.internal.provider.unpaywall;

import java.net.URI;
import java.time.Duration;

import com.openscholar.access.internal.provider.AccessProviderHttpSupport;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(UnpaywallProperties.PREFIX)
public record UnpaywallProperties(
		@DefaultValue("https://api.unpaywall.org") URI baseUrl,
		@DefaultValue("3s") Duration connectTimeout,
		@DefaultValue("15s") Duration readTimeout,
		String email) {

	static final String PREFIX = "openscholar.access.providers.unpaywall";

	public UnpaywallProperties {
		baseUrl = AccessProviderHttpSupport.requireHttpBaseUrl(baseUrl, "Unpaywall");
		connectTimeout = AccessProviderHttpSupport.requirePositive(connectTimeout, "Unpaywall", "connectTimeout");
		readTimeout = AccessProviderHttpSupport.requirePositive(readTimeout, "Unpaywall", "readTimeout");
		email = cleanEmail(email);
	}

	boolean configured() {
		return email != null;
	}

	private static String cleanEmail(String value) {
		if (value == null || value.isBlank()) {
			return null;
		}
		String clean = value.strip();
		int at = clean.indexOf('@');
		if (clean.length() > 320
				|| at <= 0
				|| at != clean.lastIndexOf('@')
				|| at == clean.length() - 1
				|| clean.codePoints().anyMatch(Character::isWhitespace)
				|| clean.codePoints().anyMatch(Character::isISOControl)) {
			throw new IllegalArgumentException("Unpaywall email must be a valid backend contact email");
		}
		return clean;
	}
}
