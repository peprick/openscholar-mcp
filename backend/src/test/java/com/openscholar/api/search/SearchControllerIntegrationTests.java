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
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
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
				.andExpect(jsonPath("$.results[0].publisher").value("Open Research Press"))
				.andExpect(jsonPath("$.results[0].institution").value("Example Research Institute"))
				.andExpect(jsonPath("$.results[0].volume").value("12"))
				.andExpect(jsonPath("$.results[0].issue").value("3"))
				.andExpect(jsonPath("$.results[0].pages").value("101-119"))
				.andExpect(jsonPath("$.results[0].articleNumber").value("e2048"))
				.andExpect(jsonPath("$.results[0].edition").value("2nd"))
				.andExpect(jsonPath("$.results[0].isbn[0]").value("978-0-306-40615-7"))
				.andExpect(jsonPath("$.results[0].issn[0]").value("2049-3630"))
				.andExpect(jsonPath("$.results[0].degree").value("Doctor of Philosophy"))
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
				.andExpect(jsonPath("$.cacheDisposition").value("EXACT_HIT"))
				.andExpect(jsonPath("$.requestedMode").value("AUTO"))
				.andExpect(jsonPath("$.executionSource").value("EXACT_CACHE"));

		mockMvc.perform(get("/api/v1/searches/{searchId}", searchId))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.searchId").value(searchId))
				.andExpect(jsonPath("$.results[0].provenance[0].providerRecordId").value(provider.recordId()));

		org.assertj.core.api.Assertions.assertThat(provider.calls()).isEqualTo(1);
	}

	@Test
	void keepsAutoAndOnlineSnapshotResourcesSemanticallyConsistent() throws Exception {
		String query = "mode-specific cache " + UUID.randomUUID();
		mockMvc.perform(post("/api/v1/searches")
					.contentType(MediaType.APPLICATION_JSON)
					.content(request(query, false)))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.requestedMode").value("AUTO"));

		String onlineResponse = mockMvc.perform(post("/api/v1/searches")
					.contentType(MediaType.APPLICATION_JSON)
					.content(request(query, false, "ONLINE")))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.cacheDisposition").value("MISS_FETCHED"))
				.andExpect(jsonPath("$.requestedMode").value("ONLINE"))
				.andExpect(jsonPath("$.executionSource").value("PROVIDER_FETCH"))
				.andReturn()
				.getResponse()
				.getContentAsString();
		String onlineSearchId = JsonPath.read(onlineResponse, "$.searchId");

		mockMvc.perform(get("/api/v1/searches/{searchId}", onlineSearchId))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.requestedMode").value("ONLINE"));

		mockMvc.perform(post("/api/v1/searches")
					.contentType(MediaType.APPLICATION_JSON)
					.content(request(query, false, "ONLINE")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.searchId").value(onlineSearchId))
				.andExpect(jsonPath("$.requestedMode").value("ONLINE"))
				.andExpect(jsonPath("$.executionSource").value("EXACT_CACHE"));

		org.assertj.core.api.Assertions.assertThat(provider.calls()).isEqualTo(2);
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
						.content(request("unavailable " + UUID.randomUUID(), false, "ONLINE")))
				.andExpect(status().isServiceUnavailable())
				.andExpect(jsonPath("$.code").value("SEARCH_PROVIDER_UNAVAILABLE"))
				.andExpect(jsonPath("$.retryable").value(true));
	}

	@Test
	void localSearchReturnsKnownMetadataWithoutCallingTheProviderAndPersistsItsSource() throws Exception {
		String title = "Known local metadata " + UUID.randomUUID().toString().replace("-", "");
		provider.changeTitle(title);
		mockMvc.perform(post("/api/v1/searches")
						.contentType(MediaType.APPLICATION_JSON)
						.content(request("seed local metadata " + UUID.randomUUID(), false, "ONLINE")))
				.andExpect(status().isCreated());
		provider.clearObservations();

		String response = mockMvc.perform(post("/api/v1/searches")
						.contentType(MediaType.APPLICATION_JSON)
						.content(request(title, false, "LOCAL")))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.cacheDisposition").value("LOCAL_RESULT"))
				.andExpect(jsonPath("$.requestedMode").value("LOCAL"))
				.andExpect(jsonPath("$.executionSource").value("LOCAL_CATALOG"))
				.andExpect(jsonPath("$.providerCoverage").isEmpty())
				.andExpect(jsonPath("$.warnings").isEmpty())
				.andExpect(jsonPath("$.results[0].title").value(title))
				.andReturn()
				.getResponse()
				.getContentAsString();
		String searchId = JsonPath.read(response, "$.searchId");

		mockMvc.perform(get("/api/v1/searches/{searchId}", searchId))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.cacheDisposition").value("EXACT_HIT"))
				.andExpect(jsonPath("$.requestedMode").value("LOCAL"))
				.andExpect(jsonPath("$.executionSource").value("LOCAL_CATALOG"))
				.andExpect(jsonPath("$.results[0].title").value(title));

		org.assertj.core.api.Assertions.assertThat(provider.calls()).isZero();
	}

	@Test
	void localSearchCanRequireAProviderReportedPdfLink() throws Exception {
		String topic = "local pdf filter " + UUID.randomUUID().toString().replace("-", "");
		provider.changeTitle(topic + " available");
		mockMvc.perform(post("/api/v1/searches")
					.contentType(MediaType.APPLICATION_JSON)
					.content(request("seed pdf " + UUID.randomUUID(), false, "ONLINE")))
				.andExpect(status().isCreated());

		provider.reset();
		provider.withoutPdf();
		provider.changeTitle(topic + " metadata only");
		mockMvc.perform(post("/api/v1/searches")
					.contentType(MediaType.APPLICATION_JSON)
					.content(request("seed metadata " + UUID.randomUUID(), false, "ONLINE")))
				.andExpect(status().isCreated());
		provider.clearObservations();

		mockMvc.perform(post("/api/v1/searches")
					.contentType(MediaType.APPLICATION_JSON)
					.content("""
							{
							  "query": "%s",
							  "mode": "LOCAL",
							  "filters": {
							    "pdfAvailableOnly": true
							  }
							}
							""".formatted(topic)))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.executionSource").value("LOCAL_CATALOG"))
				.andExpect(jsonPath("$.results.length()").value(1))
				.andExpect(jsonPath("$.results[0].title").value(topic + " available"))
				.andExpect(jsonPath("$.results[0].reportedPdfUrl").isNotEmpty());
		org.assertj.core.api.Assertions.assertThat(provider.calls()).isZero();
	}

	@Test
	void autoSearchFallsBackToTheOwnerScopedLocalCatalogWhenTheProviderIsUnavailable() throws Exception {
		String title = "Automatic local fallback " + UUID.randomUUID().toString().replace("-", "");
		provider.changeTitle(title);
		mockMvc.perform(post("/api/v1/searches")
						.contentType(MediaType.APPLICATION_JSON)
						.content(request("seed automatic fallback " + UUID.randomUUID(), false, "ONLINE")))
				.andExpect(status().isCreated());
		provider.clearObservations();
		provider.fail();

		mockMvc.perform(post("/api/v1/searches")
						.contentType(MediaType.APPLICATION_JSON)
						.content(request(title, false)))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.cacheDisposition").value("LOCAL_RESULT"))
				.andExpect(jsonPath("$.requestedMode").value("AUTO"))
				.andExpect(jsonPath("$.executionSource").value("LOCAL_CATALOG"))
				.andExpect(jsonPath("$.warnings", org.hamcrest.Matchers.hasItems(
						"OPENALEX_UNAVAILABLE", "SHOWING_LOCAL_RESULTS")))
				.andExpect(jsonPath("$.results[0].title").value(title));

		org.assertj.core.api.Assertions.assertThat(provider.calls()).isOne();
	}

	@Test
	void rejectsForceRefreshForLocalMode() throws Exception {
		mockMvc.perform(post("/api/v1/searches")
						.contentType(MediaType.APPLICATION_JSON)
						.content(request("invalid local refresh", true, "LOCAL")))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

		org.assertj.core.api.Assertions.assertThat(provider.calls()).isZero();
	}

	@Test
	void continuesLocalSearchWithABoundedCursorWithoutCallingTheProvider() throws Exception {
		String token = "paging" + UUID.randomUUID().toString().replace("-", "");
		for (int index = 1; index <= 3; index++) {
			provider.reset();
			provider.changeTitle("Offline " + token + " paper " + index);
			mockMvc.perform(post("/api/v1/searches")
							.contentType(MediaType.APPLICATION_JSON)
							.content(request("seed " + token + " " + index, false, "ONLINE")))
						.andExpect(status().isCreated());
		}
		provider.clearObservations();

		String firstResponse = mockMvc.perform(post("/api/v1/searches")
						.contentType(MediaType.APPLICATION_JSON)
						.content(request("offline " + token, false, "LOCAL", 2)))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.requestedMode").value("LOCAL"))
				.andExpect(jsonPath("$.executionSource").value("LOCAL_CATALOG"))
				.andExpect(jsonPath("$.results", org.hamcrest.Matchers.hasSize(2)))
				.andExpect(jsonPath("$.nextCursor", org.hamcrest.Matchers.startsWith("oslocal1.")))
				.andReturn()
				.getResponse()
				.getContentAsString();
		String firstSearchId = JsonPath.read(firstResponse, "$.searchId");
		List<String> firstPageTitles = JsonPath.read(firstResponse, "$.results[*].title");

		String newlyDiscoveredTitle = "offline " + token;
		provider.reset();
		provider.changeTitle(newlyDiscoveredTitle);
		mockMvc.perform(post("/api/v1/searches")
					.contentType(MediaType.APPLICATION_JSON)
					.content(request("seed newer local candidate " + UUID.randomUUID(), false, "ONLINE")))
				.andExpect(status().isCreated());
		provider.clearObservations();

		String nextResponse = mockMvc.perform(post("/api/v1/searches/{searchId}/next", firstSearchId))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.requestedMode").value("LOCAL"))
				.andExpect(jsonPath("$.executionSource").value("LOCAL_CATALOG"))
				.andExpect(jsonPath("$.results", org.hamcrest.Matchers.hasSize(1)))
				.andExpect(jsonPath("$.nextCursor").value(org.hamcrest.Matchers.nullValue()))
				.andReturn()
				.getResponse()
				.getContentAsString();
		List<String> nextPageTitles = JsonPath.read(nextResponse, "$.results[*].title");
		org.assertj.core.api.Assertions.assertThat(nextPageTitles)
				.hasSize(1)
				.doesNotContainAnyElementsOf(firstPageTitles)
				.doesNotContain(newlyDiscoveredTitle);

		org.assertj.core.api.Assertions.assertThat(provider.calls()).isZero();
	}

	@Test
	void rejectsInvalidSearchRequest() throws Exception {
		mockMvc.perform(post("/api/v1/searches")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"query\":\"x\",\"pageSize\":1000}"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
	}

	@Test
	void continuesFromStoredCriteriaAndReusesTheCachedNextPage() throws Exception {
		String query = "stored continuation " + UUID.randomUUID();
		provider.returnNextCursor("opaque-cursor-token==");
		String firstResponse = mockMvc.perform(post("/api/v1/searches")
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{
								  "query": "%s",
								  "filters": {
								    "yearFrom": 2019,
								    "yearTo": 2024,
								    "documentTypes": ["ARTICLE", "THESIS"],
								    "openAccessOnly": true,
								    "pdfAvailableOnly": true,
								    "minimumCitations": 7,
								    "languages": ["EN", "fr"]
								  },
								  "pageSize": 7,
								  "forceRefresh": true
								}
								""".formatted(query)))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.nextCursor").value("opaque-cursor-token=="))
				.andReturn()
				.getResponse()
				.getContentAsString();
		String firstSearchId = JsonPath.read(firstResponse, "$.searchId");
		String firstFingerprint = JsonPath.read(firstResponse, "$.queryFingerprint");

		provider.returnNextCursor(null);
		String nextResponse = mockMvc.perform(post("/api/v1/searches/{searchId}/next", firstSearchId))
				.andExpect(status().isCreated())
				.andExpect(header().string("Location", org.hamcrest.Matchers.startsWith("/api/v1/searches/")))
				.andExpect(jsonPath("$.cacheDisposition").value("MISS_FETCHED"))
				.andExpect(jsonPath("$.nextCursor").value(org.hamcrest.Matchers.nullValue()))
				.andReturn()
				.getResponse()
				.getContentAsString();
		String nextSearchId = JsonPath.read(nextResponse, "$.searchId");
		String nextFingerprint = JsonPath.read(nextResponse, "$.queryFingerprint");

		org.assertj.core.api.Assertions.assertThat(nextSearchId).isNotEqualTo(firstSearchId);
		org.assertj.core.api.Assertions.assertThat(nextFingerprint).isNotEqualTo(firstFingerprint);
		org.assertj.core.api.Assertions.assertThat(provider.queries()).hasSize(2);
		org.assertj.core.api.Assertions.assertThat(provider.queries().get(0).cursor()).isEqualTo("*");
		org.assertj.core.api.Assertions.assertThat(provider.queries().get(1)).satisfies(continued -> {
			org.assertj.core.api.Assertions.assertThat(continued.query()).isEqualTo(query);
			org.assertj.core.api.Assertions.assertThat(continued.yearFrom()).isEqualTo(2019);
			org.assertj.core.api.Assertions.assertThat(continued.yearTo()).isEqualTo(2024);
			org.assertj.core.api.Assertions.assertThat(continued.documentTypes())
					.containsExactlyInAnyOrder(DocumentType.ARTICLE, DocumentType.THESIS);
			org.assertj.core.api.Assertions.assertThat(continued.openAccessOnly()).isTrue();
			org.assertj.core.api.Assertions.assertThat(continued.pdfAvailableOnly()).isTrue();
			org.assertj.core.api.Assertions.assertThat(continued.minimumCitations()).isEqualTo(7);
			org.assertj.core.api.Assertions.assertThat(continued.languages())
					.containsExactlyInAnyOrder("en", "fr");
			org.assertj.core.api.Assertions.assertThat(continued.pageSize()).isEqualTo(7);
			org.assertj.core.api.Assertions.assertThat(continued.cursor()).isEqualTo("opaque-cursor-token==");
		});

		mockMvc.perform(post("/api/v1/searches/{searchId}/next", firstSearchId))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.searchId").value(nextSearchId))
				.andExpect(jsonPath("$.cacheDisposition").value("EXACT_HIT"));
		org.assertj.core.api.Assertions.assertThat(provider.calls()).isEqualTo(2);
	}

	@Test
	void rejectsMissingAndExhaustedSearchContinuations() throws Exception {
		provider.returnNextCursor(null);
		String response = mockMvc.perform(post("/api/v1/searches")
						.contentType(MediaType.APPLICATION_JSON)
						.content(request("exhausted continuation " + UUID.randomUUID(), false)))
				.andExpect(status().isCreated())
				.andReturn()
				.getResponse()
				.getContentAsString();
		String exhaustedSearchId = JsonPath.read(response, "$.searchId");

		mockMvc.perform(post("/api/v1/searches/{searchId}/next", exhaustedSearchId))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("SEARCH_PAGE_EXHAUSTED"))
				.andExpect(jsonPath("$.title").value("Search page exhausted"));

		mockMvc.perform(post("/api/v1/searches/{searchId}/next", UUID.randomUUID()))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("SEARCH_NOT_FOUND"));
		org.assertj.core.api.Assertions.assertThat(provider.calls()).isEqualTo(1);
	}

	private static String request(String query, boolean forceRefresh) {
		return request(query, forceRefresh, null, 20);
	}

	private static String request(String query, boolean forceRefresh, String mode) {
		return request(query, forceRefresh, mode, 20);
	}

	private static String request(String query, boolean forceRefresh, String mode, int pageSize) {
		String modeProperty = mode == null ? "" : ",\n  \"mode\": \"" + mode + "\"";
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
				  "pageSize": %d,
				  "forceRefresh": %s%s
				}
				""".formatted(query, pageSize, forceRefresh, modeProperty);
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
		private final AtomicBoolean pdfOmitted = new AtomicBoolean();
		private final List<ProviderSearchQuery> queries = new CopyOnWriteArrayList<>();
		private String recordId;
		private String doi;
		private String arxivId;
		private String title;
		private String nextCursor;

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
			queries.add(query);
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
					pdfOmitted.get() ? null : URI.create("https://example.org/paper.pdf"),
					42.5,
					retrievedAt.minus(Duration.ofDays(1)),
					Map.of("oaStatus", "gold"),
					List.of(),
					URI.create("https://openalex.org/works/" + recordId),
					"Open Research Press",
					"Example Research Institute",
					"12",
					"3",
					"101-119",
					"e2048",
					"2nd",
					List.of("978-0-306-40615-7"),
					List.of("2049-3630"),
					"Doctor of Philosophy");
			return new ProviderSearchResult(
					ProviderId.OPENALEX, List.of(record), 42, nextCursor, retrievedAt);
		}

		void reset() {
			calls.set(0);
			failing.set(false);
			pdfOmitted.set(false);
			queries.clear();
			String suffix = UUID.randomUUID().toString().replace("-", "");
			recordId = "W" + suffix;
			doi = "10.1000/openscholar." + suffix;
			arxivId = "2501.%05d".formatted(ARXIV_SEQUENCE.incrementAndGet());
			title = "A useful OpenAlex paper";
			nextCursor = "next-cursor";
		}

		void fail() {
			failing.set(true);
		}

		void withoutPdf() {
			pdfOmitted.set(true);
		}

		void clearObservations() {
			calls.set(0);
			queries.clear();
		}

		int calls() {
			return calls.get();
		}

		List<ProviderSearchQuery> queries() {
			return List.copyOf(queries);
		}

		void returnNextCursor(String value) {
			nextCursor = value;
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
