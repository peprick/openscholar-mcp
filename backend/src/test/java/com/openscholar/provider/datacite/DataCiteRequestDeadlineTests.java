package com.openscholar.provider.datacite;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import com.openscholar.provider.ProviderException;
import com.openscholar.provider.ProviderSearchQuery;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.mock.http.client.MockClientHttpResponse;
import org.springframework.web.client.RestClient;

class DataCiteRequestDeadlineTests {

	private static final Duration REQUEST_TIMEOUT = Duration.ofMillis(500);
	private static final Duration TEST_TIMEOUT = Duration.ofSeconds(5);

	@Test
	void marksDeadlineExpiredEndOfStreamUsingSpringsMillisecondPrecision() throws Exception {
		AtomicLong ticker = new AtomicLong();
		DataCiteRequestDeadline deadline = new DataCiteRequestDeadline(
				Duration.ofNanos(1_500_000), ticker::get);
		MockClientHttpResponse delegate = new MockClientHttpResponse(new byte[0], HttpStatus.OK);
		var response = deadline.intercept(
				new MockClientHttpRequest(),
				new byte[0],
				(request, body) -> delegate);
		ticker.set(MILLISECONDS.toNanos(1));

		assertThatThrownBy(() -> response.getBody().read())
				.isInstanceOfSatisfying(IOException.class,
						failure -> assertThat(DataCiteRequestDeadline.wasExceeded(failure)).isTrue());
	}

	@Test
	void rejectsADeadlineThatSpringWouldTruncateToZeroMilliseconds() {
		assertThatThrownBy(() -> new DataCiteRequestDeadline(Duration.ofNanos(999_999)))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessage("DataCite request deadline must be at least one millisecond");
	}

	@Test
	void timesOutOnceWhileWaitingForResponseHeaders() throws Exception {
		try (StallingServer server = StallingServer.waitingForHeaders()) {
			RequestInvocation invocation = invoke(server.baseUrl());

			assertThat(server.awaitStall(TEST_TIMEOUT)).isTrue();
			assertThat(invocation.awaitCompletion(TEST_TIMEOUT)).isTrue();
			assertStableTimeout(invocation.failure());
			assertThat(DataCiteRequestDeadline.wasExceeded(invocation.failure())).isTrue();
			assertThat(server.requestCount()).isOne();
		}
	}

	@Test
	void timesOutOnceWhileWaitingForTheRestOfAChunkedBody() throws Exception {
		try (StallingServer server = StallingServer.waitingInChunkedBody()) {
			RequestInvocation invocation = invoke(server.baseUrl());

			assertThat(server.awaitStall(TEST_TIMEOUT)).isTrue();
			assertThat(invocation.awaitCompletion(TEST_TIMEOUT)).isTrue();
			assertStableTimeout(invocation.failure());
			assertThat(DataCiteRequestDeadline.wasExceeded(invocation.failure())).isTrue();
			assertThat(server.requestCount()).isOne();
		}
	}

	private static RequestInvocation invoke(URI baseUrl) {
		DataCiteProperties properties = new DataCiteProperties(
				true,
				baseUrl,
				Duration.ofSeconds(1),
				REQUEST_TIMEOUT,
				8 * 1024 * 1024,
				null);
		RestClient restClient = new DataCiteConfiguration().dataCiteRestClient(RestClient.builder(), properties);
		DataCiteResearchProvider provider = new DataCiteResearchProvider(restClient, properties, Clock.systemUTC());
		AtomicReference<Throwable> failure = new AtomicReference<>();
		CountDownLatch completed = new CountDownLatch(1);
		Thread.ofVirtual().start(() -> {
			try {
				provider.search(new ProviderSearchQuery(
						"agents", null, null, Set.of(), false, 0, Set.of(), 25, null));
			}
			catch (Throwable thrown) {
				failure.set(thrown);
			}
			finally {
				completed.countDown();
			}
		});
		return new RequestInvocation(failure, completed);
	}

	private static void assertStableTimeout(Throwable failure) {
		assertThat(failure)
				.isInstanceOfSatisfying(ProviderException.class, exception -> {
					assertThat(exception.errorCode()).isEqualTo(DataCiteResearchProvider.TIMEOUT);
					assertThat(exception.retryable()).isTrue();
					assertThat(exception.retryAfter()).isNull();
					assertThat(exception.getMessage()).isEqualTo("DataCite request timed out");
				});
	}

	private record RequestInvocation(AtomicReference<Throwable> failureReference, CountDownLatch completed) {

		private boolean awaitCompletion(Duration timeout) throws InterruptedException {
			return completed.await(timeout.toMillis(), MILLISECONDS);
		}

		private Throwable failure() {
			return failureReference.get();
		}
	}

	private static final class StallingServer implements AutoCloseable {

		private static final byte[] PARTIAL_JSON = "{\"data\":["
				.getBytes(StandardCharsets.UTF_8);

		private final HttpServer server;
		private final ExecutorService executor;
		private final AtomicInteger requestCount = new AtomicInteger();
		private final CountDownLatch stallReached = new CountDownLatch(1);
		private final CountDownLatch releaseHandler = new CountDownLatch(1);

		private StallingServer(boolean writePartialChunkedBody) throws IOException {
			server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
			executor = Executors.newVirtualThreadPerTaskExecutor();
			server.setExecutor(executor);
			HttpHandler handler = writePartialChunkedBody ? this::stallInChunkedBody : this::stallBeforeHeaders;
			server.createContext("/dois", handler);
			server.start();
		}

		private static StallingServer waitingForHeaders() throws IOException {
			return new StallingServer(false);
		}

		private static StallingServer waitingInChunkedBody() throws IOException {
			return new StallingServer(true);
		}

		private URI baseUrl() {
			return URI.create("http://" + server.getAddress().getHostString() + ":" + server.getAddress().getPort());
		}

		private int requestCount() {
			return requestCount.get();
		}

		private boolean awaitStall(Duration timeout) throws InterruptedException {
			return stallReached.await(timeout.toMillis(), MILLISECONDS);
		}

		private void stallBeforeHeaders(HttpExchange exchange) {
			requestCount.incrementAndGet();
			stallReached.countDown();
			awaitRelease(exchange);
		}

		private void stallInChunkedBody(HttpExchange exchange) throws IOException {
			requestCount.incrementAndGet();
			exchange.getResponseHeaders().set(HttpHeaders.CONTENT_TYPE, "application/vnd.api+json");
			exchange.sendResponseHeaders(200, 0);
			try (OutputStream responseBody = exchange.getResponseBody()) {
				responseBody.write(PARTIAL_JSON);
				responseBody.flush();
				stallReached.countDown();
				awaitRelease();
			}
		}

		private void awaitRelease(HttpExchange exchange) {
			try {
				awaitRelease();
			}
			finally {
				exchange.close();
			}
		}

		private void awaitRelease() {
			try {
				releaseHandler.await();
			}
			catch (InterruptedException interrupted) {
				Thread.currentThread().interrupt();
			}
		}

		@Override
		public void close() throws InterruptedException {
			releaseHandler.countDown();
			server.stop(0);
			executor.shutdownNow();
			assertThat(executor.awaitTermination(TEST_TIMEOUT.toMillis(), MILLISECONDS))
					.isTrue();
		}
	}
}
