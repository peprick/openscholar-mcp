package com.openscholar.access.internal.provider;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
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

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;

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
		if (maximumBytes < 1) {
			throw new IllegalArgumentException("Provider response limit must be positive");
		}
		return (request, body, execution) ->
				new BoundedClientHttpResponse(execution.execute(request, body), maximumBytes);
	}

	private static final class BoundedClientHttpResponse implements ClientHttpResponse {

		private final ClientHttpResponse delegate;
		private final int maximumBytes;
		private InputStream body;

		private BoundedClientHttpResponse(ClientHttpResponse delegate, int maximumBytes) {
			this.delegate = delegate;
			this.maximumBytes = maximumBytes;
		}

		@Override
		public HttpStatusCode getStatusCode() throws IOException {
			return delegate.getStatusCode();
		}

		@Override
		public String getStatusText() throws IOException {
			return delegate.getStatusText();
		}

		@Override
		public HttpHeaders getHeaders() {
			return delegate.getHeaders();
		}

		@Override
		public InputStream getBody() throws IOException {
			long contentLength = delegate.getHeaders().getContentLength();
			if (contentLength > maximumBytes) {
				throw new ProviderResponseTooLargeException(maximumBytes);
			}
			if (body == null) {
				body = new BoundedInputStream(delegate.getBody(), maximumBytes);
			}
			return body;
		}

		@Override
		public void close() {
			delegate.close();
		}
	}

	private static final class BoundedInputStream extends FilterInputStream {

		private final long maximumBytes;
		private long bytesRead;

		private BoundedInputStream(InputStream delegate, long maximumBytes) {
			super(delegate);
			this.maximumBytes = maximumBytes;
		}

		@Override
		public int read() throws IOException {
			if (bytesRead < maximumBytes) {
				int value = super.read();
				if (value != -1) {
					bytesRead++;
				}
				return value;
			}
			int extra = super.read();
			if (extra == -1) {
				return -1;
			}
			throw new ProviderResponseTooLargeException(maximumBytes);
		}

		@Override
		public int read(byte[] bytes, int offset, int length) throws IOException {
			if (length == 0) {
				return 0;
			}
			long remaining = maximumBytes - bytesRead;
			if (remaining <= 0) {
				return readBeyondLimit();
			}
			int count = super.read(bytes, offset, (int) Math.min(length, remaining));
			if (count > 0) {
				bytesRead += count;
			}
			return count;
		}

		@Override
		public long skip(long count) throws IOException {
			long skipped = 0;
			byte[] buffer = new byte[(int) Math.min(8192, Math.max(1, count))];
			while (skipped < count) {
				int read = read(buffer, 0, (int) Math.min(buffer.length, count - skipped));
				if (read == -1) {
					break;
				}
				skipped += read;
			}
			return skipped;
		}

		private int readBeyondLimit() throws IOException {
			int extra = super.read();
			if (extra == -1) {
				return -1;
			}
			throw new ProviderResponseTooLargeException(maximumBytes);
		}
	}

	private static final class ProviderResponseTooLargeException extends IOException {

		private ProviderResponseTooLargeException(long maximumBytes) {
			super("Provider response exceeded " + maximumBytes + " bytes");
		}
	}
}
