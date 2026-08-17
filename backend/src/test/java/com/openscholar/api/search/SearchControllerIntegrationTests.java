package com.openscholar.api.search;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import com.jayway.jsonpath.JsonPath;
import com.openscholar.TestcontainersConfiguration;
import com.openscholar.paper.DocumentType;
import com.openscholar.provider.ProviderAuthor;
import com.openscholar.provider.ProviderException;
import com.openscholar.provider.ProviderId;
import com.openscholar.provider.ProviderPaperRecord;
import com.openscholar.provider.ProviderSearchQuery;
import com.openscholar.provider.ProviderSearchResult;
import com.openscholar.provider.ResearchProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, SearchControllerIntegrationTests.FakeProviderConfiguration.class})
@SpringBootTest(properties = "openscholar.search.cache-ttl=1h")
class SearchControllerIntegrationTests {

	private static final Instant INITIAL_TIME = Instant.parse("2026-08-16T12:00:00Z");
	private static final AtomicInteger ARXIV_SEQUENCE = new AtomicInteger(10000);

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private FakeResearchProvider provider;

	@Autowired
	private MutableClock clock;

	@BeforeEach
	void resetFakes() {
		provider.reset();
		clock.set(INITIAL_TIME);
	}

	@Test
	void fetchesPersistsReusesAndRetrievesSearch() throws Exception {
		String query = "graph neural networks " + UUID.randomUUID();
		String firstBody = request(query, false);

		String response = mockMvc.perform(post("/api/v1/searches")
						.contentType(MediaType.APPLICATION_JSON)
						.content(firstBody))
				.andExpect(status().isCreated())
				.andExpect(header().string("Location", org.hamcrest.Matchers.startsWith("/api/v1/searches/")))
				.andExpect(jsonPath("$.cacheDisposition").value("MISS_FETCHED"))
				.andExpect(jsonPath("$.providerCoverage[0].provider").value("OPENALEX"))
				.andExpect(jsonPath("$.results[0].title").value("A useful OpenAlex paper"))
				.andExpect(jsonPath("$.results[0].authors[0].name").value("Ada Researcher"))
				.andExpect(jsonPath("$.results[0].identifiers.doi").value(provider.doi()))
				.andExpect(jsonPath("$.results[0].identifiers.arxiv").value(provider.arxivId()))
				.andReturn()
				.getResponse()
				.getContentAsString();
		String searchId = JsonPath.read(response, "$.searchId");

		mockMvc.perform(post("/api/v1/searches")
						.contentType(MediaType.APPLICATION_JSON)
						.content(request("  " + query.toUpperCase() + "  ", false)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.searchId").value(searchId))
				.andExpect(jsonPath("$.cacheDisposition").value("EXACT_HIT"));

		mockMvc.perform(get("/api/v1/searches/{searchId}", searchId))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.searchId").value(searchId))
				.andExpect(jsonPath("$.results[0].provenance[0].providerRecordId").value(provider.recordId()));

		org.assertj.core.api.Assertions.assertThat(provider.calls()).isEqualTo(1);
	}

	@Test
	void forceRefreshCreatesANewSnapshot() throws Exception {
		String query = "force refresh " + UUID.randomUUID();
		mockMvc.perform(post("/api/v1/searches")
						.contentType(MediaType.APPLICATION_JSON)
						.content(request(query, false)))
				.andExpect(status().isCreated());

		mockMvc.perform(post("/api/v1/searches")
						.contentType(MediaType.APPLICATION_JSON)
						.content(request(query, true)))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.cacheDisposition").value("FORCED_REFRESH"));

		org.assertj.core.api.Assertions.assertThat(provider.calls()).isEqualTo(2);
	}

	@Test
	void savedSnapshotKeepsItsOriginalPaperProjectionAfterCatalogRefresh() throws Exception {
		String query = "immutable snapshot " + UUID.randomUUID();
		String originalResponse = mockMvc.perform(post("/api/v1/searches")
						.contentType(MediaType.APPLICATION_JSON)
						.content(request(query, false)))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.results[0].title").value("A useful OpenAlex paper"))
				.andReturn()
				.getResponse()
				.getContentAsString();
		String originalSearchId = JsonPath.read(originalResponse, "$.searchId");

		clock.advance(Duration.ofHours(2));
		provider.changeTitle("A revised canonical title");
		mockMvc.perform(post("/api/v1/searches")
						.contentType(MediaType.APPLICATION_JSON)
						.content(request(query, true)))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.results[0].title").value("A revised canonical title"));

		mockMvc.perform(get("/api/v1/searches/{searchId}", originalSearchId))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.results[0].title").value("A useful OpenAlex paper"));
	}

	@Test
	void returnsStaleSnapshotWhenRefreshFails() throws Exception {
		String query = "stale fallback " + UUID.randomUUID();
		mockMvc.perform(post("/api/v1/searches")
						.contentType(MediaType.APPLICATION_JSON)
						.content(request(query, false)))
				.andExpect(status().isCreated());

		clock.advance(Duration.ofHours(2));
		provider.fail();

		mockMvc.perform(post("/api/v1/searches")
						.contentType(MediaType.APPLICATION_JSON)
						.content(request(query, false)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.cacheDisposition").value("STALE_FALLBACK"))
				.andExpect(jsonPath("$.warnings[0]").value("OPENALEX_UNAVAILABLE"));
	}

	@Test
	void returnsProblemDetailsWhenProviderHasNoFallback() throws Exception {
		provider.fail();

		mockMvc.perform(post("/api/v1/searches")
						.contentType(MediaType.APPLICATION_JSON)
						.content(request("unavailable " + UUID.randomUUID(), false)))
				.andExpect(status().isServiceUnavailable())
				.andExpect(jsonPath("$.code").value("SEARCH_PROVIDER_UNAVAILABLE"))
				.andExpect(jsonPath("$.retryable").value(true));
	}

	@Test
	void rejectsInvalidSearchRequest() throws Exception {
		mockMvc.perform(post("/api/v1/searches")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"query\":\"x\",\"pageSize\":1000}"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
	}

	private static String request(String query, boolean forceRefresh) {
		return """
				{
				  "query": "%s",
				  "filters": {
				    "yearFrom": 2020,
				    "yearTo": 2026,
				    "documentTypes": ["ARTICLE", "PREPRINT"],
				    "openAccessOnly": true,
				    "minimumCitations": 0,
				    "languages": ["en"]
				  },
				  "pageSize": 20,
				  "forceRefresh": %s
				}
				""".formatted(query, forceRefresh);
	}

	@TestConfiguration(proxyBeanMethods = false)
	static class FakeProviderConfiguration {

		@Bean
		@Primary
		FakeResearchProvider fakeResearchProvider(MutableClock clock) {
			return new FakeResearchProvider(clock);
		}

		@Bean
		@Primary
		MutableClock mutableClock() {
			return new MutableClock(INITIAL_TIME);
		}
	}

	static final class FakeResearchProvider implements ResearchProvider {

		private final MutableClock clock;
		private final AtomicInteger calls = new AtomicInteger();
		private final AtomicBoolean failing = new AtomicBoolean();
		private String recordId;
		private String doi;
		private String arxivId;
		private String title;

		FakeResearchProvider(MutableClock clock) {
			this.clock = clock;
		}

		@Override
		public ProviderId id() {
			return ProviderId.OPENALEX;
		}

		@Override
		public ProviderSearchResult search(ProviderSearchQuery query) {
			calls.incrementAndGet();
			if (failing.get()) {
				throw new ProviderException(
						ProviderId.OPENALEX,
						"OPENALEX_UNAVAILABLE",
						"OpenAlex is unavailable",
						true,
						Duration.ofSeconds(30),
						null);
			}
			Instant retrievedAt = clock.instant();
			ProviderPaperRecord record = new ProviderPaperRecord(
					ProviderId.OPENALEX,
					recordId,
					doi,
					arxivId,
					title,
					"An abstract reconstructed from OpenAlex.",
					LocalDate.of(2025, 5, 2),
					2025,
					DocumentType.ARTICLE,
					"en",
					"Journal of Open Research",
					12,
					List.of(new ProviderAuthor("A987654321", "Ada Researcher", null, 0, true)),
					true,
					URI.create("https://example.org/paper"),
					URI.create("https://example.org/paper.pdf"),
					42.5,
					retrievedAt.minus(Duration.ofDays(1)),
					Map.of("oaStatus", "gold"));
			return new ProviderSearchResult(
					ProviderId.OPENALEX, List.of(record), 42, "next-cursor", retrievedAt);
		}

		void reset() {
			calls.set(0);
			failing.set(false);
			String suffix = UUID.randomUUID().toString().replace("-", "");
			recordId = "W" + suffix;
			doi = "10.1000/openscholar." + suffix;
			arxivId = "2501.%05d".formatted(ARXIV_SEQUENCE.incrementAndGet());
			title = "A useful OpenAlex paper";
		}

		void fail() {
			failing.set(true);
		}

		int calls() {
			return calls.get();
		}

		void changeTitle(String value) {
			title = value;
		}

		String recordId() {
			return recordId;
		}

		String doi() {
			return doi;
		}

		String arxivId() {
			return arxivId;
		}
	}

	static final class MutableClock extends Clock {

		private Instant current;

		MutableClock(Instant current) {
			this.current = current;
		}

		void set(Instant instant) {
			this.current = instant;
		}

		void advance(Duration duration) {
			this.current = current.plus(duration);
		}

		@Override
		public ZoneId getZone() {
			return ZoneOffset.UTC;
		}

		@Override
		public Clock withZone(ZoneId zone) {
			return this;
		}

		@Override
		public Instant instant() {
			return current;
		}
	}
}
