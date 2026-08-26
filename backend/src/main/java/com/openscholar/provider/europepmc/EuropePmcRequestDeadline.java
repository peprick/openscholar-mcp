package com.openscholar.provider.europepmc;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;

/**
 * Preserves an explicit timeout marker when Spring's JDK client closes a
 * partially consumed response body at its whole-exchange deadline.
 */
final class EuropePmcRequestDeadline implements ClientHttpRequestInterceptor {

	private final long timeoutNanos;
	private final LongSupplier ticker;

	EuropePmcRequestDeadline(Duration timeout) {
		this(timeout, System::nanoTime);
	}

	EuropePmcRequestDeadline(Duration timeout, LongSupplier ticker) {
		if (timeout == null || timeout.isZero() || timeout.isNegative()) {
			throw new IllegalArgumentException("Europe PMC request deadline must be positive");
		}
		this.timeoutNanos = effectiveTimeoutNanos(timeout);
		this.ticker = Objects.requireNonNull(ticker, "ticker must not be null");
	}

	@Override
	public ClientHttpResponse intercept(
			org.springframework.http.HttpRequest request,
			byte[] body,
			ClientHttpRequestExecution execution) throws IOException {
		Deadline deadline = new Deadline(ticker.getAsLong(), timeoutNanos, ticker);
		try {
			return new DeadlineClientHttpResponse(execution.execute(request, body), deadline);
		}
		catch (IOException failure) {
			throw deadline.markIfExceeded(failure);
		}
	}

	static boolean wasExceeded(Throwable failure) {
		for (Throwable current = failure; current != null; current = current.getCause()) {
			if (current instanceof EuropePmcRequestDeadlineExceededException) {
				return true;
			}
		}
		return false;
	}

	private static long effectiveTimeoutNanos(Duration timeout) {
		long timeoutMillis = timeout.toMillis();
		if (timeoutMillis < 1) {
			throw new IllegalArgumentException("Europe PMC request deadline must be at least one millisecond");
		}
		return TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
	}

	private record Deadline(long startedAtNanos, long timeoutNanos, LongSupplier ticker) {

		private IOException markIfExceeded(IOException failure) {
			if (failure instanceof EuropePmcRequestDeadlineExceededException || !isExceeded()) {
				return failure;
			}
			return new EuropePmcRequestDeadlineExceededException(failure);
		}

		private void throwIfExceeded() throws EuropePmcRequestDeadlineExceededException {
			if (isExceeded()) {
				throw new EuropePmcRequestDeadlineExceededException();
			}
		}

		private boolean isExceeded() {
			return ticker.getAsLong() - startedAtNanos >= timeoutNanos;
		}
	}

	private static final class DeadlineClientHttpResponse implements ClientHttpResponse {

		private final ClientHttpResponse delegate;
		private final Deadline deadline;
		private InputStream body;

		private DeadlineClientHttpResponse(ClientHttpResponse delegate, Deadline deadline) {
			this.delegate = delegate;
			this.deadline = deadline;
		}

		@Override
		public HttpStatusCode getStatusCode() throws IOException {
			try {
				return delegate.getStatusCode();
			}
			catch (IOException failure) {
				throw deadline.markIfExceeded(failure);
			}
		}

		@Override
		public String getStatusText() throws IOException {
			try {
				return delegate.getStatusText();
			}
			catch (IOException failure) {
				throw deadline.markIfExceeded(failure);
			}
		}

		@Override
		public HttpHeaders getHeaders() {
			return delegate.getHeaders();
		}

		@Override
		public InputStream getBody() throws IOException {
			if (body == null) {
				try {
					body = new DeadlineInputStream(delegate.getBody(), deadline);
				}
				catch (IOException failure) {
					throw deadline.markIfExceeded(failure);
				}
			}
			return body;
		}

		@Override
		public void close() {
			delegate.close();
		}
	}

	private static final class DeadlineInputStream extends FilterInputStream {

		private final Deadline deadline;

		private DeadlineInputStream(InputStream delegate, Deadline deadline) {
			super(delegate);
			this.deadline = deadline;
		}

		@Override
		public int read() throws IOException {
			try {
				int value = super.read();
				deadline.throwIfExceeded();
				return value;
			}
			catch (IOException failure) {
				throw deadline.markIfExceeded(failure);
			}
		}

		@Override
		public int read(byte[] bytes, int offset, int length) throws IOException {
			try {
				int count = super.read(bytes, offset, length);
				deadline.throwIfExceeded();
				return count;
			}
			catch (IOException failure) {
				throw deadline.markIfExceeded(failure);
			}
		}

		@Override
		public long skip(long count) throws IOException {
			try {
				long skipped = super.skip(count);
				deadline.throwIfExceeded();
				return skipped;
			}
			catch (IOException failure) {
				throw deadline.markIfExceeded(failure);
			}
		}

		@Override
		public byte[] readAllBytes() throws IOException {
			try {
				byte[] bytes = super.readAllBytes();
				deadline.throwIfExceeded();
				return bytes;
			}
			catch (IOException failure) {
				throw deadline.markIfExceeded(failure);
			}
		}

		@Override
		public byte[] readNBytes(int length) throws IOException {
			try {
				byte[] bytes = super.readNBytes(length);
				deadline.throwIfExceeded();
				return bytes;
			}
			catch (IOException failure) {
				throw deadline.markIfExceeded(failure);
			}
		}

		@Override
		public int readNBytes(byte[] bytes, int offset, int length) throws IOException {
			try {
				int count = super.readNBytes(bytes, offset, length);
				deadline.throwIfExceeded();
				return count;
			}
			catch (IOException failure) {
				throw deadline.markIfExceeded(failure);
			}
		}

		@Override
		public long transferTo(OutputStream output) throws IOException {
			try {
				long count = super.transferTo(output);
				deadline.throwIfExceeded();
				return count;
			}
			catch (IOException failure) {
				throw deadline.markIfExceeded(failure);
			}
		}
	}

	private static final class EuropePmcRequestDeadlineExceededException extends IOException {

		private EuropePmcRequestDeadlineExceededException() {
			super("Europe PMC request deadline exceeded");
		}

		private EuropePmcRequestDeadlineExceededException(IOException cause) {
			super("Europe PMC request deadline exceeded", cause);
		}
	}
}
