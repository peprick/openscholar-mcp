package com.openscholar.provider.doaj;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.http.HttpMethod.GET;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.headerDoesNotExist;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Set;

import com.openscholar.paper.DocumentType;
import com.openscholar.paper.PaperIdentifierType;
import com.openscholar.provider.ProviderException;
import com.openscholar.provider.ProviderId;
import com.openscholar.provider.ProviderSearchQuery;
import com.openscholar.provider.ProviderSearchResult;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.client.response.MockRestResponseCreators;
import org.springframework.web.client.RestClient;

class DoajResearchProviderTests {

	private static final URI BASE_URL = URI.create("https://doaj.test/api/v4");
	private static final Instant NOW = Instant.parse("2026-08-22T12:00:00Z");
	private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

	@Test
	void sendsAKeylessBoundedRequestAndMapsRichArticleMetadata() {
		Harness harness = harness("researcher@example.org");
		ProviderSearchQuery query = new ProviderSearchQuery(
				"agents OR title:* / \"quoted\"",
				2020,
				2025,
				Set.of(DocumentType.ARTICLE),
				true,
				0,
				Set.of(),
				250,
				null);

			harness.server().expect(once(), request -> {
			assertThat(request.getURI().getPath()).startsWith("/api/v4/search/articles/");
			assertThat(articleQuery(request.getURI())).isEqualTo(
					"\"agents OR title\\:\\* \\/ \\\"quoted\\\"\""
							+ " AND bibjson.year:[2020 TO 2025]");
			assertThat(param(request.getURI(), "page")).isEqualTo("1");
			assertThat(param(request.getURI(), "pageSize")).isEqualTo("100");
			assertThat(request.getURI().getRawQuery()).doesNotContain("api_key", "apikey", "token");
		})
				.andExpect(method(GET))
				.andExpect(header(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE))
				.andExpect(header(HttpHeaders.USER_AGENT, "OpenScholar/0.0.1 (mailto:researcher@example.org)"))
				.andExpect(headerDoesNotExist(HttpHeaders.AUTHORIZATION))
				.andRespond(withSuccess(richResponse(), MediaType.APPLICATION_JSON));

		ProviderSearchResult result = harness.provider().search(query);

		assertThat(result.provider()).isEqualTo(ProviderId.DOAJ);
		assertThat(result.totalMatches()).isEqualTo(151);
		assertThat(result.nextCursor()).isEqualTo("doajpage1:2");
		assertThat(result.retrievedAt()).isEqualTo(NOW);
		assertThat(result.records()).singleElement().satisfies(record -> {
			assertThat(record.providerRecordId()).isEqualTo("doaj-record-1");
			assertThat(record.doi()).isEqualTo("10.1234/example.article");
			assertThat(record.title()).isEqualTo("Agentic discovery for research");
			assertThat(record.abstractText()).isEqualTo("A synthetic open-access article abstract.");
			assertThat(record.publicationDate()).isNull();
			assertThat(record.publicationYear()).isEqualTo(2024);
			assertThat(record.documentType()).isEqualTo(DocumentType.ARTICLE);
			assertThat(record.language()).isNull();
			assertThat(record.venueName()).isEqualTo("Journal of Synthetic Research");
			assertThat(record.citationCount()).isNull();
			assertThat(record.publisher()).isEqualTo("Example Open Press");
			assertThat(record.institution()).isNull();
			assertThat(record.volume()).isEqualTo("8");
			assertThat(record.issue()).isEqualTo("2");
			assertThat(record.pages()).isEqualTo("101-118");
			assertThat(record.articleNumber()).isNull();
			assertThat(record.edition()).isNull();
			assertThat(record.isbn()).isEmpty();
			assertThat(record.issn()).containsExactly("1234-567X", "2049-3630");
			assertThat(record.degree()).isNull();
			assertThat(record.providerUpdatedAt()).isEqualTo(Instant.parse("2026-08-20T09:30:00Z"));
			assertThat(record.reportedOpenAccess()).isTrue();
			assertThat(record.landingPageUrl()).hasToString("https://doi.org/10.1234/example.article");
			assertThat(record.pdfUrl()).hasToString("https://papers.example.org/article.pdf");
			assertThat(record.sourceUrl()).hasToString("https://doaj.org/article/doaj-record-1");
			assertThat(record.relevanceScore()).isNull();
			assertThat(record.arxivId()).isNull();
			assertThat(record.identifiers())
					.extracting(identifier -> identifier.type())
					.containsExactly(PaperIdentifierType.DOI);
			assertThat(record.authors()).singleElement().satisfies(author -> {
				assertThat(author.providerAuthorId()).isNull();
				assertThat(author.displayName()).isEqualTo("Ada Researcher");
				assertThat(author.orcid()).isEqualTo("0000-0002-1825-0097");
				assertThat(author.position()).isEqualTo(1);
				assertThat(author.corresponding()).isFalse();
			});
			assertThat(record.metadataFragment())
					.containsEntry("metadataLicense", "CC0-1.0")
					.containsEntry("accessClaim", "DOAJ_INDEXED_OPEN_ACCESS")
					.containsEntry("createdDate", "2024-06-10T08:00:00Z")
					.containsEntry("month", "May")
					.containsEntry("journalCountry", "GB")
					.containsEntry("journalLanguages", java.util.List.of("en", "fr"))
					.containsEntry("keywords", java.util.List.of("agents", "discovery"))
					.containsEntry("subjects", java.util.List.of("Artificial intelligence"));
			assertThat(record.metadataFragment().toString())
					.doesNotContain("editor@example.org", "futureField", "evil.test");
		});
		harness.server().verify();
	}

	@Test
	void advancesOnlyItsOwnedPageCursorAndStopsAtThePublicResultWindow() {
		Harness harness = harness(null);
		harness.server().expect(request -> {
			assertThat(param(request.getURI(), "page")).isEqualTo("2");
			assertThat(param(request.getURI(), "pageSize")).isEqualTo("100");
		})
				.andRespond(withSuccess("""
						{"total":5000,"page":2,"pageSize":100,"results":[],
						 "next":"https://evil.test/owned-by-upstream"}
						""", MediaType.APPLICATION_JSON));

		ProviderSearchResult middle = harness.provider().search(query(Set.of(), 100, "doajpage1:2"));

		assertThat(middle.nextCursor()).isEqualTo("doajpage1:3");
		harness.server().verify();

		Harness finalHarness = harness(null);
		finalHarness.server().expect(request -> assertThat(param(request.getURI(), "page")).isEqualTo("10"))
				.andRespond(withSuccess("""
						{"total":5000,"page":10,"pageSize":100,"results":[],
						 "next":"https://evil.test/must-not-be-followed"}
						""", MediaType.APPLICATION_JSON));
		assertThat(finalHarness.provider().search(query(Set.of(), 100, "doajpage1:10")).nextCursor()).isNull();
		finalHarness.server().verify();

		Harness invalidHarness = harness(null);
		assertThatThrownBy(() -> invalidHarness.provider().search(
				query(Set.of(), 100, "https://evil.test/next")))
				.isInstanceOfSatisfying(ProviderException.class, exception -> {
					assertThat(exception.errorCode()).isEqualTo(DoajResearchProvider.REQUEST_REJECTED);
					assertThat(exception.retryable()).isFalse();
				});
		assertThatThrownBy(() -> invalidHarness.provider().search(query(Set.of(), 100, "doajpage1:11")))
				.isInstanceOf(ProviderException.class);
		invalidHarness.server().verify();
	}

	@Test
	void shortCircuitsFiltersThatDoajCannotRepresentWithoutHttp() {
		Harness harness = harness(null);

		ProviderSearchResult unsupportedType = harness.provider().search(
				query(Set.of(DocumentType.THESIS), 25, "*"));
		ProviderSearchResult citations = harness.provider().search(new ProviderSearchQuery(
				"agents", null, null, Set.of(), false, 1, Set.of(), 25, null));
		ProviderSearchResult language = harness.provider().search(new ProviderSearchQuery(
				"agents", null, null, Set.of(), false, 0, Set.of("en"), 25, null));

		assertThat(unsupportedType.records()).isEmpty();
		assertThat(citations.records()).isEmpty();
		assertThat(language.records()).isEmpty();
		assertThat(unsupportedType.totalMatches()).isZero();
		assertThat(citations.totalMatches()).isZero();
		assertThat(language.totalMatches()).isZero();
		harness.server().verify();
	}

	@Test
	void toleratesSparseEvolvingJsonAndSkipsUnknownOrUnusableRecords() {
		Harness harness = harness(null);
		harness.server().expect(request -> assertThat(request.getURI().getPath())
				.startsWith("/api/v4/search/articles/"))
				.andRespond(withSuccess("""
						{
						  "total":4,
						  "results":[
						    null,
						    {"id":"no-metadata"},
						    {"id":"no-title","bibjson":{"title":"  "}},
						    {"id":"sparse-record","last_updated":"not-an-instant","bibjson":{
						      "title":"Sparse article","year":"20x4",
						      "identifier":[{"type":"doi","id":"not-a-doi"}],
						      "link":[
						        {"type":"fulltext","content_type":"PDF","url":"file:///private/paper.pdf"},
						        {"type":"fulltext","content_type":"HTML","url":"https://user:secret@evil.test"}
						      ],
						      "futureField":{"ignored":true}
						    }}
						  ],
						  "futureTopLevel":true
						}
						""", MediaType.APPLICATION_JSON));

		ProviderSearchResult result = harness.provider().search(query(Set.of(), 25, "*"));

		assertThat(result.totalMatches()).isEqualTo(4);
		assertThat(result.records()).singleElement().satisfies(record -> {
			assertThat(record.providerRecordId()).isEqualTo("sparse-record");
			assertThat(record.title()).isEqualTo("Sparse article");
			assertThat(record.doi()).isNull();
			assertThat(record.publicationYear()).isNull();
			assertThat(record.providerUpdatedAt()).isNull();
			assertThat(record.landingPageUrl()).hasToString("https://doaj.org/article/sparse-record");
			assertThat(record.pdfUrl()).isNull();
			assertThat(record.identifiers()).isEmpty();
		});
		harness.server().verify();
	}

	@Test
	void preservesEmptyResultsAndRejectsIncompleteOrOversizedResponses() {
		Harness emptyHarness = harness(null);
		emptyHarness.server().expect(request -> assertThat(request.getURI().getPath())
				.startsWith("/api/v4/search/articles/"))
				.andRespond(withSuccess(
						"{\"total\":0,\"results\":[],\"newField\":true}",
						MediaType.APPLICATION_JSON));
		assertThat(emptyHarness.provider().search(query(Set.of(), 25, "*")).records()).isEmpty();
		emptyHarness.server().verify();

		Harness incompleteHarness = harness(null);
		incompleteHarness.server().expect(request -> assertThat(request.getURI().getPath())
				.startsWith("/api/v4/search/articles/"))
				.andRespond(withSuccess("{\"total\":0,\"results\":null}", MediaType.APPLICATION_JSON));
		assertThatThrownBy(() -> incompleteHarness.provider().search(query(Set.of(), 25, "*")))
				.isInstanceOfSatisfying(ProviderException.class, exception -> {
					assertThat(exception.errorCode()).isEqualTo(DoajResearchProvider.RESPONSE_ERROR);
					assertThat(exception.retryable()).isTrue();
				});
		incompleteHarness.server().verify();

		String body = "{\"total\":0,\"results\":[]}";
		Harness oversizedHarness = harness(null, body.getBytes(StandardCharsets.UTF_8).length - 1);
		oversizedHarness.server().expect(request -> assertThat(request.getURI().getPath())
				.startsWith("/api/v4/search/articles/"))
				.andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
		assertThatThrownBy(() -> oversizedHarness.provider().search(query(Set.of(), 25, "*")))
				.isInstanceOfSatisfying(ProviderException.class, exception -> {
					assertThat(exception.errorCode()).isEqualTo(DoajResearchProvider.RESPONSE_TOO_LARGE);
					assertThat(exception.retryable()).isFalse();
				});
		oversizedHarness.server().verify();
	}

	@Test
	void translatesRateLimitsServerErrorsClientErrorsAndTimeouts() {
		Harness rateLimited = harness(null);
		rateLimited.server().expect(request -> assertThat(request.getURI().getPath())
				.startsWith("/api/v4/search/articles/"))
				.andRespond(MockRestResponseCreators.withStatus(HttpStatus.TOO_MANY_REQUESTS)
						.header(HttpHeaders.RETRY_AFTER, "120"));
		assertThatThrownBy(() -> rateLimited.provider().search(query(Set.of(), 25, "*")))
				.isInstanceOfSatisfying(ProviderException.class, exception -> {
					assertThat(exception.provider()).isEqualTo(ProviderId.DOAJ);
					assertThat(exception.errorCode()).isEqualTo(DoajResearchProvider.RATE_LIMITED);
					assertThat(exception.retryable()).isTrue();
					assertThat(exception.retryAfter()).isEqualTo(Duration.ofMinutes(2));
				});
		rateLimited.server().verify();

		assertStatus(HttpStatus.SERVICE_UNAVAILABLE, DoajResearchProvider.UPSTREAM_ERROR, true);
		assertStatus(HttpStatus.BAD_REQUEST, DoajResearchProvider.REQUEST_REJECTED, false);

		Harness timedOut = harness(null);
		timedOut.server().expect(request -> assertThat(request.getURI().getPath())
				.startsWith("/api/v4/search/articles/"))
				.andRespond(request -> {
					throw new SocketTimeoutException("read timed out");
				});
		assertThatThrownBy(() -> timedOut.provider().search(query(Set.of(), 25, "*")))
				.isInstanceOfSatisfying(ProviderException.class, exception -> {
					assertThat(exception.errorCode()).isEqualTo(DoajResearchProvider.TIMEOUT);
					assertThat(exception.retryable()).isTrue();
				});
		timedOut.server().verify();
	}

	private static void assertStatus(HttpStatus status, String expectedCode, boolean retryable) {
		Harness harness = harness(null);
		harness.server().expect(request -> assertThat(request.getURI().getPath())
				.startsWith("/api/v4/search/articles/"))
				.andRespond(MockRestResponseCreators.withStatus(status));
		assertThatThrownBy(() -> harness.provider().search(query(Set.of(), 25, "*")))
				.isInstanceOfSatisfying(ProviderException.class, exception -> {
					assertThat(exception.errorCode()).isEqualTo(expectedCode);
					assertThat(exception.retryable()).isEqualTo(retryable);
				});
		harness.server().verify();
	}

	private static Harness harness(String contactEmail) {
		return harness(contactEmail, 8 * 1024 * 1024);
	}

	private static Harness harness(String contactEmail, int maxResponseBytes) {
		DoajProperties properties = new DoajProperties(
				true,
				BASE_URL,
				Duration.ofSeconds(2),
				Duration.ofSeconds(5),
				maxResponseBytes,
				contactEmail);
		RestClient.Builder builder = DoajConfiguration.configure(RestClient.builder(), properties);
		MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
		return new Harness(new DoajResearchProvider(builder.build(), CLOCK), server);
	}

	private static ProviderSearchQuery query(Set<DocumentType> types, int pageSize, String cursor) {
		return new ProviderSearchQuery("agents", null, null, types, false, 0, Set.of(), pageSize, cursor);
	}

	private static String articleQuery(URI uri) {
		String prefix = "/api/v4/search/articles/";
		String rawPath = uri.getRawPath();
		assertThat(rawPath).startsWith(prefix);
		return decode(rawPath.substring(prefix.length()));
	}

	private static String param(URI uri, String expectedName) {
		String rawQuery = uri.getRawQuery();
		if (rawQuery == null) {
			return null;
		}
		for (String pair : rawQuery.split("&")) {
			int equals = pair.indexOf('=');
			String name = decode(equals < 0 ? pair : pair.substring(0, equals));
			if (expectedName.equals(name)) {
				return decode(equals < 0 ? "" : pair.substring(equals + 1));
			}
		}
		return null;
	}

	private static String decode(String value) {
		return URLDecoder.decode(value, StandardCharsets.UTF_8);
	}

	private static String richResponse() {
		return """
				{
				  "total":151,
				  "page":1,
				  "pageSize":100,
				  "timestamp":"2026-08-22T12:00:00Z",
				  "query":"ignored",
				  "next":"https://evil.test/upstream-cursor",
				  "results":[{
				    "id":"doaj-record-1",
				    "created_date":"2024-06-10T08:00:00Z",
				    "last_updated":"2026-08-20T09:30:00Z",
				    "bibjson":{
				      "title":" Agentic discovery for research ",
				      "year":"2024",
				      "month":"May",
				      "abstract":"A synthetic open-access article abstract.",
				      "author":[{
				        "name":"Ada Researcher",
				        "orcid_id":"https://orcid.org/0000-0002-1825-0097"
				      }],
				      "journal":{
				        "title":"Journal of Synthetic Research",
				        "publisher":"Example Open Press",
				        "volume":"8",
				        "number":"2",
				        "country":"GB",
				        "language":["fr","en"]
				      },
				      "identifier":[
				        {"type":"doi","id":"https://doi.org/10.1234/EXAMPLE.ARTICLE"},
				        {"type":"pissn","id":"1234567X"},
				        {"type":"eissn","id":"2049-3630"}
				      ],
				      "link":[
				        {"type":"fulltext","content_type":"HTML","url":"https://papers.example.org/article"},
				        {"type":"fulltext","content_type":"PDF","url":"https://papers.example.org/article.pdf"},
				        {"type":"homepage","content_type":"HTML","url":"https://evil.test/not-fulltext"}
				      ],
				      "keywords":["discovery","agents"],
				      "subject":[{"scheme":"LCC","term":"Artificial intelligence","code":"Q335"}],
				      "start_page":"101",
				      "end_page":"118",
				      "editor_email":"editor@example.org",
				      "futureField":{"ignored":true}
				    }
				  }]
				}
				""";
	}

	private record Harness(DoajResearchProvider provider, MockRestServiceServer server) {
	}
}
