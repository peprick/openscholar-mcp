package com.openscholar.search.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import com.openscholar.TestcontainersConfiguration;
import com.openscholar.provider.ProviderId;
import com.openscholar.provider.ProviderSearchQuery;
import com.openscholar.provider.ProviderSearchResult;
import com.openscholar.provider.ResearchProvider;
import com.openscholar.search.SearchCommand;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@AutoConfigureMockMvc
@Import({
	TestcontainersConfiguration.class,
	SearchExecutionDeadlineTransportIntegrationTests.BlockingProviderConfiguration.class
})
@SpringBootTest(properties = {
	"openscholar.search.execution-timeout=500ms",
	"openscholar.mcp.security.local-api-key=deadline-transport-test-key",
	"openscholar.mcp.security.allowed-origins=http://deadline-client.test"
})
class SearchExecutionDeadlineTransportIntegrationTests {

	private static final String API_KEY = "deadline-transport-test-key";
	private static final String PROTOCOL_VERSION = "2025-11-25";
	private static final String NESTED_SECRET = "private provider cancellation detail";
	private static final Duration TEST_WAIT = Duration.ofSeconds(5);

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ObjectMapper objectMapper;

	@Autowired
	private BlockingResearchProvider provider;

	@Autowired
	private QueryFingerprinter fingerprinter;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@BeforeEach
	void resetProvider() {
		provider.reset();
	}

	@AfterEach
	void releaseProvider() throws InterruptedException {
		provider.releaseForCleanup();
	}

	@Test
	void restSearchReturnsGatewayTimeoutAndCancelsTheProviderWithoutPersisting() throws Exception {
		String topic = "REST deadline " + UUID.randomUUID();
		String fingerprint = fingerprint(topic, 20);
		ExecutorService requester = Executors.newSingleThreadExecutor();

		try {
			Future<MvcResult> response = requester.submit(() -> mockMvc.perform(post("/api/v1/searches")
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{
								  "query": "%s",
								  "pageSize": 20
								}
								""".formatted(topic)))
					.andReturn());

			assertThat(provider.awaitEntered(TEST_WAIT)).isTrue();
			MvcResult result = response.get(TEST_WAIT.toMillis(), TimeUnit.MILLISECONDS);
			JsonNode problem = objectMapper.readTree(result.getResponse().getContentAsString());

			assertThat(result.getResponse().getStatus()).isEqualTo(504);
			assertThat(result.getResponse().getHeader(HttpHeaders.RETRY_AFTER)).isNull();
			assertThat(problem.required("code").asString()).isEqualTo("SEARCH_DEADLINE_EXCEEDED");
			assertThat(problem.required("retryable").asBoolean()).isTrue();
			assertThat(problem.toString()).doesNotContain(NESTED_SECRET);
			assertCancelledWithoutSnapshot(fingerprint);
		}
		finally {
			requester.shutdownNow();
			awaitTermination(requester);
		}
	}

	@Test
	void mcpSearchReturnsSafeToolErrorAndCancelsTheProviderWithoutPersisting() throws Exception {
		String topic = "MCP deadline " + UUID.randomUUID();
		String fingerprint = fingerprint(topic, 1);
		int requestId = 91;
		Map<String, Object> message = Map.of(
				"jsonrpc", "2.0",
				"id", requestId,
				"method", "tools/call",
				"params", Map.of(
						"name", "search_research",
						"arguments", Map.of("topic", topic, "limit", 1)));
		ExecutorService requester = Executors.newSingleThreadExecutor();

		try {
			Future<MvcResult> response = requester.submit(() -> mockMvc.perform(post("/mcp")
						.header(HttpHeaders.AUTHORIZATION, "Bearer " + API_KEY)
						.header("MCP-Protocol-Version", PROTOCOL_VERSION)
						.contentType(MediaType.APPLICATION_JSON)
						.accept(MediaType.APPLICATION_JSON, MediaType.TEXT_EVENT_STREAM)
						.content(objectMapper.writeValueAsString(message)))
					.andReturn());

			assertThat(provider.awaitEntered(TEST_WAIT)).isTrue();
			MvcResult result = response.get(TEST_WAIT.toMillis(), TimeUnit.MILLISECONDS);
			JsonNode envelope = objectMapper.readTree(result.getResponse().getContentAsString());
			JsonNode toolResult = envelope.required("result");
			JsonNode errorContent = toolResult.required("content").required(0);

			assertThat(result.getResponse().getStatus()).isEqualTo(200);
			assertThat(envelope.required("jsonrpc").asString()).isEqualTo("2.0");
			assertThat(envelope.required("id").asInt()).isEqualTo(requestId);
			assertThat(toolResult.required("isError").asBoolean()).isTrue();
			assertThat(errorContent.required("type").asString()).isEqualTo("text");
			assertThat(errorContent.required("text").asString())
					.contains("SEARCH_DEADLINE_EXCEEDED: Search execution deadline exceeded; retryable=true")
					.endsWith("SEARCH_DEADLINE_EXCEEDED: Search execution deadline exceeded; retryable=true")
					.doesNotContain(NESTED_SECRET);
			assertThat(toolResult.has("structuredContent")).isFalse();
			assertCancelledWithoutSnapshot(fingerprint);
		}
		finally {
			requester.shutdownNow();
			awaitTermination(requester);
		}
	}

	private static void awaitTermination(ExecutorService executor) throws InterruptedException {
		if (!executor.awaitTermination(TEST_WAIT.toMillis(), TimeUnit.MILLISECONDS)) {
			throw new IllegalStateException("Search deadline transport requester did not terminate");
		}
	}

	private void assertCancelledWithoutSnapshot(String fingerprint) throws InterruptedException {
		assertThat(provider.awaitInterrupted(TEST_WAIT)).isTrue();
		assertThat(provider.awaitExited(TEST_WAIT)).isTrue();
		assertThat(provider.interruptObserved()).isTrue();
		assertThat(provider.calls()).isOne();
		assertThat(provider.activeCalls()).isZero();
		assertThat(snapshotCount(fingerprint)).isZero();
	}

	private String fingerprint(String topic, int pageSize) {
		return fingerprinter.fingerprint(new SearchCommand(
				topic,
				null,
				null,
				Set.of(),
				false,
				0,
				Set.of(),
				pageSize,
				"*",
				false));
	}

	private long snapshotCount(String fingerprint) {
		Long count = jdbcTemplate.queryForObject(
				"SELECT count(*) FROM search_snapshot WHERE fingerprint = ?",
				Long.class,
				fingerprint);
		return count == null ? 0 : count;
	}

	@TestConfiguration(proxyBeanMethods = false)
	static class BlockingProviderConfiguration {

		@Bean
		@Primary
		BlockingResearchProvider blockingResearchProvider() {
			return new BlockingResearchProvider();
		}
	}

	static final class BlockingResearchProvider implements ResearchProvider {

		private final AtomicInteger calls = new AtomicInteger();
		private final AtomicInteger activeCalls = new AtomicInteger();
		private volatile Invocation invocation = new Invocation();

		@Override
		public ProviderId id() {
			return ProviderId.OPENALEX;
		}

		@Override
		public ProviderSearchResult search(ProviderSearchQuery query) {
			calls.incrementAndGet();
			activeCalls.incrementAndGet();
			Invocation current = invocation;
			current.entered().countDown();
			try {
				current.release().await();
				throw new IllegalStateException("Blocking provider was released only for test cleanup");
			}
			catch (InterruptedException interrupted) {
				current.interruptObserved().set(true);
				current.interrupted().countDown();
				Thread.currentThread().interrupt();
				throw new CancellationException(NESTED_SECRET);
			}
			finally {
				activeCalls.decrementAndGet();
				current.exited().countDown();
			}
		}

		void reset() {
			if (activeCalls.get() != 0) {
				throw new IllegalStateException("Previous blocking provider invocation is still active");
			}
			calls.set(0);
			invocation = new Invocation();
		}

		boolean awaitEntered(Duration timeout) throws InterruptedException {
			return invocation.entered().await(timeout.toMillis(), TimeUnit.MILLISECONDS);
		}

		boolean awaitInterrupted(Duration timeout) throws InterruptedException {
			return invocation.interrupted().await(timeout.toMillis(), TimeUnit.MILLISECONDS);
		}

		boolean awaitExited(Duration timeout) throws InterruptedException {
			return invocation.exited().await(timeout.toMillis(), TimeUnit.MILLISECONDS);
		}

		void releaseForCleanup() throws InterruptedException {
			Invocation current = invocation;
			current.release().countDown();
			if (current.entered().getCount() == 0
					&& !current.exited().await(TEST_WAIT.toMillis(), TimeUnit.MILLISECONDS)) {
				throw new IllegalStateException("Deadline transport provider did not exit during cleanup");
			}
		}

		boolean interruptObserved() {
			return invocation.interruptObserved().get();
		}

		int calls() {
			return calls.get();
		}

		int activeCalls() {
			return activeCalls.get();
		}
	}

	private record Invocation(
			CountDownLatch entered,
			CountDownLatch release,
			CountDownLatch interrupted,
			CountDownLatch exited,
			AtomicBoolean interruptObserved) {

		private Invocation() {
			this(
					new CountDownLatch(1),
					new CountDownLatch(1),
					new CountDownLatch(1),
					new CountDownLatch(1),
					new AtomicBoolean());
		}
	}
}
