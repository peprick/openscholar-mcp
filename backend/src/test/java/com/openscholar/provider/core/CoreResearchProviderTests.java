package com.openscholar.provider.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.http.HttpMethod.GET;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.headerDoesNotExist;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
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
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

class CoreResearchProviderTests {

	private static final URI BASE_URL = URI.create("https://api.core.test");
	private static final Instant NOW = Instant.parse("2026-08-22T00:00:00Z");
	private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

	@Test
	void sendsAuthorizedBoundedRequestAndMapsTypedMetadataWithoutRetainingFullTextOrPdfLinks() {
		Harness harness = harness("test-api-key");
		ProviderSearchQuery query = new ProviderSearchQuery(
				"retrieval agents",
				2020,
				2025,
				Set.of(DocumentType.ARTICLE, DocumentType.DISSERTATION),
				true,
				10,
				Set.of("EN"),
				2,
				null);

		harness.server().expect(once(), request -> {
			var components = UriComponentsBuilder.fromUri(request.getURI()).build();
			assertThat(components.getPath()).isEqualTo("/v3/search/works/");
			String providerQuery = decodedParam(components.getQueryParams().getFirst("q"));
			assertThat(providerQuery)
					.startsWith("(\"retrieval\" \"agents\")")
					.contains("yearPublished>=2020", "yearPublished<=2025")
					.contains("documentType:\"doctoral thesis\"")
					.contains("documentType:\"journal article\"")
					.endsWith("_exists_:fullText");
			assertThat(decodedParam(components.getQueryParams().getFirst("offset"))).isEqualTo("0");
			assertThat(decodedParam(components.getQueryParams().getFirst("limit"))).isEqualTo("2");
			assertThat(decodedParam(components.getQueryParams().getFirst("stats"))).isEqualTo("false");
			assertThat(components.getQueryParams()).doesNotContainKeys("api_key", "apikey", "key", "scroll");
		})
				.andExpect(method(GET))
				.andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer test-api-key"))
				.andRespond(withSuccess(completeResponse(), MediaType.APPLICATION_JSON));

		ProviderSearchResult result = harness.provider().search(query);

		assertThat(result.provider()).isEqualTo(ProviderId.CORE);
		assertThat(result.totalMatches()).isEqualTo(5);
		assertThat(result.nextCursor()).startsWith("core1.").doesNotContain("2");
		assertThat(result.retrievedAt()).isEqualTo(NOW);
		assertThat(result.records()).singleElement().satisfies(record -> {
			assertThat(record.providerRecordId()).isEqualTo("123456");
			assertThat(record.doi()).isEqualTo("10.1000/example.doi");
			assertThat(record.arxivId()).isEqualTo("2401.01234v2");
			assertThat(record.title()).isEqualTo("Research agents in practice");
			assertThat(record.abstractText()).isEqualTo("A bounded abstract.");
			assertThat(record.publicationDate()).isEqualTo(LocalDate.parse("2024-03-15"));
			assertThat(record.publicationYear()).isEqualTo(2024);
			assertThat(record.documentType()).isEqualTo(DocumentType.ARTICLE);
			assertThat(record.language()).isEqualTo("en");
			assertThat(record.venueName()).isEqualTo("Journal of Research Agents");
			assertThat(record.citationCount()).isEqualTo(42);
			assertThat(record.reportedOpenAccess()).isTrue();
			assertThat(record.landingPageUrl()).hasToString("https://core.test/display/123456");
			assertThat(record.pdfUrl()).isNull();
			assertThat(record.sourceUrl()).hasToString("https://api.core.test/v3/works/123456");
			assertThat(record.publisher()).isEqualTo("Open Research Press");
			assertThat(record.institution()).isEqualTo("Example University Repository");
			assertThat(record.issn()).containsExactly("1234-567X", "2049-3630");
			assertThat(record.authors()).extracting(author -> author.displayName())
					.containsExactly("Ada Researcher", "Grace Scholar");
			assertThat(record.identifiers()).anySatisfy(identifier -> {
				assertThat(identifier.type()).isEqualTo(PaperIdentifierType.PMID);
				assertThat(identifier.value()).isEqualTo("9876543");
			});
			assertThat(record.identifiers()).anySatisfy(identifier -> {
				assertThat(identifier.type()).isEqualTo(PaperIdentifierType.REPOSITORY);
				assertThat(identifier.namespace()).isEqualTo("oai");
				assertThat(identifier.value()).isEqualTo("oai:example:123456");
			});
			assertThat(record.metadataFragment())
					.containsEntry("reportedFullText", true)
					.containsEntry("providerTypes", java.util.List.of("research article"));
			assertThat(record.metadataFragment().toString())
					.doesNotContain("DO_NOT_RETAIN", "download/123456.pdf", "source-fulltext");
		});
		harness.server().verify();
	}

	@Test
	void returnsAndConsumesAnOpaqueVersionedOffsetCursor() {
		Harness first = harness(null);
		first.server().expect(headerDoesNotExist(HttpHeaders.AUTHORIZATION))
				.andRespond(withSuccess("""
						{"totalHits":6,"limit":2,"offset":0,"results":[
						 {"id":1,"title":"One"},{"id":2,"title":"Two"}
						]}
						""", MediaType.APPLICATION_JSON));
		ProviderSearchResult firstPage = first.provider().search(query(2, null));
		first.server().verify();

		assertThat(firstPage.nextCursor()).startsWith("core1.");
		assertThat(firstPage.nextCursor()).isNotEqualTo("2");

		Harness second = harness(null);
		second.server().expect(request -> {
			var parameters = UriComponentsBuilder.fromUri(request.getURI()).build().getQueryParams();
			assertThat(decodedParam(parameters.getFirst("offset"))).isEqualTo("2");
		})
				.andRespond(withSuccess("""
						{"total_hits":6,"limit":2,"offset":2,"results":[
						 {"id":3,"title":"Three"},{"id":4,"title":"Four"}
						]}
						""", MediaType.APPLICATION_JSON));
		ProviderSearchResult secondPage = second.provider().search(query(2, firstPage.nextCursor()));

		assertThat(secondPage.records()).extracting(record -> record.providerRecordId())
				.containsExactly("3", "4");
		assertThat(secondPage.nextCursor()).isNotNull();
		second.server().verify();
	}

	@Test
	void rejectsRawCorruptAndOutOfWindowCursorsWithoutCallingCore() {
		Harness harness = harness(null);
		for (String cursor : Set.of("25", "core1.not-base64!", "core1.djE6MTAwMDA")) {
			assertThatThrownBy(() -> harness.provider().search(query(25, cursor)))
					.isInstanceOfSatisfying(ProviderException.class, exception -> {
						assertThat(exception.errorCode()).isEqualTo(CoreResearchProvider.REQUEST_REJECTED);
						assertThat(exception.retryable()).isFalse();
					});
		}
		harness.server().verify();
	}

	@Test
	void appliesUnsupportedCitationAndLanguageFiltersLocally() {
		Harness harness = harness(null);
		harness.server().expect(request -> assertThat(decodedParam(
				UriComponentsBuilder.fromUri(request.getURI()).build().getQueryParams().getFirst("q")))
				.doesNotContain("citationCount", "language:"))
				.andRespond(withSuccess("""
						{"totalHits":3,"limit":3,"offset":0,"results":[
						 {"id":1,"title":"Enough","language":{"code":"en"},"citationCount":12},
						 {"id":2,"title":"Wrong language","language":{"code":"fr"},"citationCount":20},
						 {"id":3,"title":"Too few","language":{"code":"en"},"citationCount":2}
						]}
						""", MediaType.APPLICATION_JSON));

		ProviderSearchResult result = harness.provider().search(new ProviderSearchQuery(
				"agents", null, null, Set.of(), false, 10, Set.of("en"), 3, null));

		assertThat(result.records()).singleElement()
				.extracting(record -> record.providerRecordId())
				.isEqualTo("1");
		assertThat(result.totalMatches()).isEqualTo(3);
		assertThat(result.nextCursor()).isNull();
		harness.server().verify();
	}

	@Test
	void rejectsAnIncompleteSuccessfulEnvelope() {
		Harness harness = harness(null);
		harness.server().expect(request -> assertThat(request.getURI().getPath()).isEqualTo("/v3/search/works/"))
				.andRespond(withSuccess("{\"totalHits\":1,\"limit\":25,\"results\":null}", MediaType.APPLICATION_JSON));

		assertThatThrownBy(() -> harness.provider().search(query(25, null)))
				.isInstanceOfSatisfying(ProviderException.class, exception -> {
					assertThat(exception.errorCode()).isEqualTo(CoreResearchProvider.RESPONSE_ERROR);
					assertThat(exception.retryable()).isFalse();
				});
		harness.server().verify();
	}

	@Test
	void translatesCoreRateLimitTimestampWithoutRetrying() {
		Harness harness = harness(null);
		harness.server().expect(once(), request -> { })
				.andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS)
						.header("X-RateLimit-Retry-After", "2026-08-22T00:05:00+0000"));

		assertThatThrownBy(() -> harness.provider().search(query(25, null)))
				.isInstanceOfSatisfying(ProviderException.class, exception -> {
					assertThat(exception.errorCode()).isEqualTo(CoreResearchProvider.RATE_LIMITED);
					assertThat(exception.retryable()).isTrue();
					assertThat(exception.retryAfter()).isEqualTo(Duration.ofMinutes(5));
				});
		harness.server().verify();
	}

	@Test
	void translatesTransientAndPermanentStatusesDeterministically() {
		Harness unavailable = harness(null);
		unavailable.server().expect(once(), request -> { })
				.andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE).header(HttpHeaders.RETRY_AFTER, "7"));
		assertThatThrownBy(() -> unavailable.provider().search(query(25, null)))
				.isInstanceOfSatisfying(ProviderException.class, exception -> {
					assertThat(exception.errorCode()).isEqualTo(CoreResearchProvider.UPSTREAM_ERROR);
					assertThat(exception.retryable()).isTrue();
					assertThat(exception.retryAfter()).isEqualTo(Duration.ofSeconds(7));
				});
		unavailable.server().verify();

		Harness rejected = harness(null);
		rejected.server().expect(once(), request -> { })
				.andRespond(withStatus(HttpStatus.BAD_REQUEST));
		assertThatThrownBy(() -> rejected.provider().search(query(25, null)))
				.isInstanceOfSatisfying(ProviderException.class, exception -> {
					assertThat(exception.errorCode()).isEqualTo(CoreResearchProvider.REQUEST_REJECTED);
					assertThat(exception.retryable()).isFalse();
					assertThat(exception.retryAfter()).isNull();
				});
		rejected.server().verify();
	}

	@Test
	void rejectsOversizedDeclaredAndStreamedResponses() {
		String response = "{\"totalHits\":0,\"limit\":25,\"offset\":0,\"results\":[]}";
		int bytes = response.getBytes(StandardCharsets.UTF_8).length;

		Harness declared = harness(null, bytes - 1);
		declared.server().expect(once(), request -> { })
				.andRespond(withSuccess(response, MediaType.APPLICATION_JSON)
						.header(HttpHeaders.CONTENT_LENGTH, Integer.toString(bytes)));
		assertResponseTooLarge(declared);

		Harness streamed = harness(null, bytes - 1);
		streamed.server().expect(once(), request -> { })
				.andRespond(withSuccess(response, MediaType.APPLICATION_JSON));
		assertResponseTooLarge(streamed);
	}

	@Test
	void distinguishesTimeoutFromOtherConnectionFailures() {
		Harness timeout = harness(null);
		timeout.server().expect(once(), request -> { })
				.andRespond(request -> { throw new SocketTimeoutException("read timed out"); });
		assertThatThrownBy(() -> timeout.provider().search(query(25, null)))
				.isInstanceOfSatisfying(ProviderException.class, exception -> {
					assertThat(exception.errorCode()).isEqualTo(CoreResearchProvider.TIMEOUT);
					assertThat(exception.retryable()).isTrue();
				});
		timeout.server().verify();

		Harness closed = harness(null);
		closed.server().expect(once(), request -> { })
				.andRespond(request -> { throw new java.io.IOException("closed"); });
		assertThatThrownBy(() -> closed.provider().search(query(25, null)))
				.isInstanceOfSatisfying(ProviderException.class, exception -> {
					assertThat(exception.errorCode()).isEqualTo(CoreResearchProvider.UNAVAILABLE);
					assertThat(exception.retryable()).isTrue();
				});
		closed.server().verify();
	}

	private static Harness harness(String apiKey) {
		return harness(apiKey, 8 * 1024 * 1024);
	}

	private static Harness harness(String apiKey, int maxResponseBytes) {
		CoreProperties properties = new CoreProperties(
				true,
				true,
				BASE_URL,
				Duration.ofSeconds(2),
				Duration.ofSeconds(5),
				maxResponseBytes,
				apiKey);
		RestClient.Builder builder = CoreConfiguration.configure(RestClient.builder(), properties);
		MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
		return new Harness(new CoreResearchProvider(builder.build(), properties, CLOCK), server);
	}

	private static void assertResponseTooLarge(Harness harness) {
		assertThatThrownBy(() -> harness.provider().search(query(25, null)))
				.isInstanceOfSatisfying(ProviderException.class, exception -> {
					assertThat(exception.errorCode()).isEqualTo(CoreResearchProvider.RESPONSE_TOO_LARGE);
					assertThat(exception.retryable()).isFalse();
				});
		harness.server().verify();
	}

	private static ProviderSearchQuery query(int pageSize, String cursor) {
		return new ProviderSearchQuery(
				"agents", null, null, Set.of(), false, 0, Set.of(), pageSize, cursor);
	}

	private static String decodedParam(String value) {
		return value == null ? null : URLDecoder.decode(value, StandardCharsets.UTF_8);
	}

	private static String completeResponse() {
		return """
				{
				  "totalHits": 5,
				  "limit": 2,
				  "offset": 0,
				  "results": [{
				    "id": 123456,
				    "title": " Research agents in practice ",
				    "abstract": "A bounded abstract.",
				    "doi": "https://doi.org/10.1000/Example.DOI",
				    "arxivId": "arXiv:2401.01234v2",
				    "authors": [{"name":"Ada Researcher"}, "Grace Scholar", {"name":"Ada Researcher"}],
				    "citationCount": 42,
				    "dataProviders": [{"id":7,"name":"Example University Repository"}],
				    "documentType": ["research article"],
				    "downloadUrl": "https://core.test/download/123456.pdf",
				    "fullText": "DO_NOT_RETAIN",
				    "language": {"code":"EN","name":"English"},
				    "identifiers": [
				      {"type":"PMID","identifier":"9876543"},
				      {"type":"OAI-PMH","identifier":"oai:example:123456"}
				    ],
				    "oaiIds": ["oai:example:123456"],
				    "publishedDate": "2024-03-15T00:00:00",
				    "publisher": {"name":"Open Research Press"},
				    "sourceFulltextUrls": ["https://core.test/source-fulltext/123456"],
				    "journals": [{"title":"Journal of Research Agents","identifiers":["20493630","1234-567x","not-issn"]}],
				    "updatedDate": "2026-08-20T09:30:00Z",
				    "yearPublished": 2024,
				    "links": [
				      {"type":"download","url":"https://core.test/download/123456.pdf"},
				      {"type":"display","url":"https://core.test/display/123456"}
				    ],
				    "futureField": {"ignored":true}
				  }]
				}
				""";
	}

	private record Harness(CoreResearchProvider provider, MockRestServiceServer server) {
	}
}
