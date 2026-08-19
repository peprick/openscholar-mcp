package com.openscholar.embedding.internal.ollama;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpRequest;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;

final class OllamaResponseSizeLimitInterceptor implements ClientHttpRequestInterceptor {

	private final long maximumBytes;

	OllamaResponseSizeLimitInterceptor(long maximumBytes) {
		if (maximumBytes < 1) {
			throw new IllegalArgumentException("Ollama response byte limit must be positive");
		}
		this.maximumBytes = maximumBytes;
	}

	@Override
	public ClientHttpResponse intercept(
			HttpRequest request,
			byte[] body,
			ClientHttpRequestExecution execution) throws IOException {
		ClientHttpResponse response = execution.execute(request, body);
		long contentLength = response.getHeaders().getContentLength();
		if (contentLength > maximumBytes) {
			response.close();
			throw new OllamaResponseTooLargeException(maximumBytes);
		}
		return new LimitedClientHttpResponse(response, maximumBytes);
	}

	private static final class LimitedClientHttpResponse implements ClientHttpResponse {

		private final ClientHttpResponse delegate;
		private final long maximumBytes;
		private InputStream limitedBody;

		private LimitedClientHttpResponse(ClientHttpResponse delegate, long maximumBytes) {
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
			if (limitedBody == null) {
				limitedBody = new LimitedInputStream(delegate.getBody(), maximumBytes);
			}
			return limitedBody;
		}

		@Override
		public void close() {
			delegate.close();
		}
	}

	private static final class LimitedInputStream extends FilterInputStream {

		private final long maximumBytes;
		private long bytesRead;

		private LimitedInputStream(InputStream input, long maximumBytes) {
			super(input);
			this.maximumBytes = maximumBytes;
		}

		@Override
		public int read() throws IOException {
			int value = super.read();
			if (value != -1) {
				recordRead(1);
			}
			return value;
		}

		@Override
		public int read(byte[] buffer, int offset, int length) throws IOException {
			int boundedLength = (int) Math.min(length, maximumBytes - bytesRead + 1);
			int count = super.read(buffer, offset, boundedLength);
			if (count > 0) {
				recordRead(count);
			}
			return count;
		}

		@Override
		public long skip(long count) throws IOException {
			long boundedCount = Math.min(count, maximumBytes - bytesRead + 1);
			long skipped = super.skip(boundedCount);
			if (skipped > 0) {
				recordRead(skipped);
			}
			return skipped;
		}

		@Override
		public boolean markSupported() {
			return false;
		}

		@Override
		public synchronized void mark(int readLimit) {
			// Deliberately unsupported so consumed bytes cannot be counted twice.
		}

		@Override
		public synchronized void reset() throws IOException {
			throw new IOException("mark/reset is not supported for bounded Ollama responses");
		}

		private void recordRead(long count) throws OllamaResponseTooLargeException {
			bytesRead += count;
			if (bytesRead > maximumBytes) {
				throw new OllamaResponseTooLargeException(maximumBytes);
			}
		}
	}
}
