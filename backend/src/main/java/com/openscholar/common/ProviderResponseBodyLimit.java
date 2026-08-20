package com.openscholar.common;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;

public final class ProviderResponseBodyLimit {

	private ProviderResponseBodyLimit() {
	}

	public static ClientHttpRequestInterceptor boundedResponseBody(int maximumBytes) {
		if (maximumBytes < 1) {
			throw new IllegalArgumentException("Provider response limit must be positive");
		}
		return (request, body, execution) ->
				new BoundedClientHttpResponse(execution.execute(request, body), maximumBytes);
	}

	public static boolean wasExceeded(Throwable failure) {
		for (Throwable current = failure; current != null; current = current.getCause()) {
			if (current instanceof ProviderResponseTooLargeException) {
				return true;
			}
		}
		return false;
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

		@Override
		public boolean markSupported() {
			return false;
		}

		@Override
		public synchronized void mark(int readLimit) {
			// Rewinding a bounded response would make its byte accounting ambiguous.
		}

		@Override
		public synchronized void reset() throws IOException {
			throw new IOException("mark/reset is not supported for bounded provider responses");
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
