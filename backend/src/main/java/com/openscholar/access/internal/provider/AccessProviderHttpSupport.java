package com.openscholar.access.internal.provider;

import java.io.InterruptedIOException;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.http.HttpTimeoutException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Locale;

import com.openscholar.common.ProviderResponseBodyLimit;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.ClientHttpRequestInterceptor;

public final class AccessProviderHttpSupport {

	private AccessProviderHttpSupport() {
	}

	public static Duration retryAfter(HttpHeaders headers, Clock clock) {
		if (headers == null) {
			return null;
		}
		String value = headers.getFirst(HttpHeaders.RETRY_AFTER);
		if (value == null || value.isBlank()) {
			return null;
		}
		try {
			return Duration.ofSeconds(Math.max(0, Long.parseLong(value.strip())));
		}
		catch (NumberFormatException ignored) {
			try {
				Instant retryAt = ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
				Duration duration = Duration.between(clock.instant(), retryAt);
				return duration.isNegative() ? Duration.ZERO : duration;
			}
			catch (DateTimeParseException invalidDate) {
				return null;
			}
		}
	}

	public static boolean hasTimeoutCause(Throwable throwable) {
		for (Throwable current = throwable; current != null; current = current.getCause()) {
			if (current instanceof SocketTimeoutException
					|| current instanceof HttpTimeoutException
					|| current instanceof InterruptedIOException
					|| String.valueOf(current.getMessage()).toLowerCase(Locale.ROOT).contains("timed out")) {
				return true;
			}
		}
		return false;
	}

	public static URI safeHttpUri(String value) {
		if (value == null || value.isBlank()) {
			return null;
		}
		try {
			URI uri = URI.create(value.strip());
			String scheme = uri.getScheme();
			if (!uri.isAbsolute()
					|| scheme == null
					|| (!scheme.equalsIgnoreCase("http") && !scheme.equalsIgnoreCase("https"))
					|| uri.getHost() == null
					|| uri.getUserInfo() != null) {
				return null;
			}
			return uri;
		}
		catch (IllegalArgumentException ignored) {
			return null;
		}
	}

	public static URI requireHttpBaseUrl(URI value, String provider) {
		if (value == null || !value.isAbsolute() || value.getHost() == null) {
			throw new IllegalArgumentException(provider + " baseUrl must be an absolute HTTP(S) URL");
		}
		String scheme = value.getScheme().toLowerCase(Locale.ROOT);
		if ((!scheme.equals("http") && !scheme.equals("https"))
				|| value.getRawQuery() != null
				|| value.getRawFragment() != null
				|| value.getUserInfo() != null) {
			throw new IllegalArgumentException(
					provider + " baseUrl must be an absolute HTTP(S) URL without credentials, query, or fragment");
		}
		return value;
	}

	public static Duration requirePositive(Duration value, String provider, String name) {
		if (value == null || value.isZero() || value.isNegative()) {
			throw new IllegalArgumentException(provider + " " + name + " must be positive");
		}
		return value;
	}

	public static ClientHttpRequestInterceptor boundedResponseBody(int maximumBytes) {
		return ProviderResponseBodyLimit.boundedResponseBody(maximumBytes);
	}
}
