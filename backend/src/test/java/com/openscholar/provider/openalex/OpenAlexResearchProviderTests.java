package com.openscholar.provider.openalex;

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
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Set;

import com.openscholar.paper.DocumentType;
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
import org.springframework.web.util.UriComponentsBuilder;

class OpenAlexResearchProviderTests {

	private static final URI BASE_URL = URI.create("https://api.openalex.test");
	private static final Instant NOW = Instant.parse("2026-08-16T12:00:00Z");
	private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

	@Test
	void sendsAuthorizedFilteredRequestAndMapsCompleteWork() {
		Harness harness = harness("test-api-key");
		ProviderSearchQuery query = new ProviderSearchQuery(
				"retrieval augmented generation",
				2020,
				2025,
				Set.of(DocumentType.THESIS, DocumentType.ARTICLE),
				true,
				10,
				Set.of("fr", "EN"),
				50,
				"cursor-token==");

		harness.server().expect(once(), request -> {
			var components = UriComponentsBuilder.fromUri(request.getURI()).build();
			assertThat(components.getPath()).isEqualTo("/works");
			assertThat(decodedParam(components.getQueryParams().getFirst("search")))
					.isEqualTo("retrieval augmented generation");
			assertThat(decodedParam(components.getQueryParams().getFirst("cursor"))).isEqualTo("cursor-token==");
			assertThat(decodedParam(components.getQueryParams().getFirst("corpus"))).isEqualTo("core");
			assertThat(decodedParam(components.getQueryParams().getFirst("per_page"))).isEqualTo("50");
			assertThat(decodedParam(components.getQueryParams().getFirst("select")))
					.contains("abstract_inverted_index", "authorships", "best_oa_location", "is_retracted");
			assertThat(decodedParam(components.getQueryParams().getFirst("filter"))).isEqualTo(
					"from_publication_date:2020-01-01,to_publication_date:2025-12-31,"
							+ "type:article|dissertation,open_access.is_oa:true,cited_by_count:>9,language:en|fr");
			assertThat(components.getQueryParams()).doesNotContainKeys("api_key", "apikey", "key");
		})
				.andExpect(method(GET))
				.andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer test-api-key"))
				.andRespond(withSuccess(completeResponse(), MediaType.APPLICATION_JSON));

		ProviderSearchResult result = harness.provider().search(query);

		assertThat(result.provider()).isEqualTo(ProviderId.OPENALEX);
		assertThat(result.totalMatches()).isEqualTo(234);
		assertThat(result.nextCursor()).isEqualTo("next-cursor");
		assertThat(result.retrievedAt()).isEqualTo(NOW);
		assertThat(result.records()).singleElement().satisfies(record -> {
			assertThat(record.providerRecordId()).isEqualTo("W2741809807");
			assertThat(record.doi()).isEqualTo("10.1000/example.doi");
			assertThat(record.arxivId()).isEqualTo("2501.12345");
			assertThat(record.title()).isEqualTo("Research agents in practice");
			assertThat(record.abstractText()).isEqualTo("Research agents help researchers agents");
			assertThat(record.publicationDate()).isEqualTo(LocalDate.parse("2024-03-15"));
			assertThat(record.publicationYear()).isEqualTo(2024);
			assertThat(record.documentType()).isEqualTo(DocumentType.ARTICLE);
			assertThat(record.language()).isEqualTo("en");
			assertThat(record.venueName()).isEqualTo("Journal of Research Agents");
			assertThat(record.citationCount()).isEqualTo(42);
			assertThat(record.reportedOpenAccess()).isTrue();
			assertThat(record.landingPageUrl()).hasToString("https://publisher.test/article/123");
			assertThat(record.pdfUrl()).hasToString("https://repository.test/article-123.pdf");
			assertThat(record.relevanceScore()).isEqualTo(0.987);
			assertThat(record.providerUpdatedAt()).isEqualTo(Instant.parse("2026-08-15T09:30:00Z"));
			assertThat(record.authors()).singleElement().satisfies(author -> {
				assertThat(author.providerAuthorId()).isEqualTo("A5023888391");
				assertThat(author.displayName()).isEqualTo("Ada Researcher");
				assertThat(author.orcid()).isEqualTo("0000-0002-1825-0097");
				assertThat(author.position()).isEqualTo(1);
				assertThat(author.corresponding()).isTrue();
			});
			assertThat(record.metadataFragment())
					.containsEntry("providerType", "article")
					.containsEntry("openAccessStatus", "gold");
			assertThat(record.metadataFragment().get("externalIds")).asString()
					.contains("pmid", "12345678");
		});
		harness.server().verify();
	}

	@Test
	void normalizesModernAndLegacyArxivIdentifiersOnlyFromTrustedExactSources() {
		Harness harness = harness(null);
		harness.server().expect(request -> assertThat(request.getURI().getPath()).isEqualTo("/works"))
				.andRespond(withSuccess("""
						{
						  "meta": {"count": 5},
						  "results": [
						    {
						      "id": "https://openalex.org/W801",
						      "title": "Modern identifier",
						      "ids": {"arxiv": "arXiv:2501.12345v3"}
						    },
						    {
						      "id": "https://openalex.org/W802",
						      "title": "Legacy landing page",
						      "ids": {"arxiv": "not-an-arxiv-id"},
						      "primary_location": {
						        "landing_page_url": "https://arxiv.org/abs/hep-th/9901001v2"
						      }
						    },
						    {
						      "id": "https://openalex.org/W803",
						      "title": "Trusted PDF URL",
						      "best_oa_location": {
						        "pdf_url": "https://export.arxiv.org/pdf/2406.01234v1.pdf",
						        "is_oa": true
						      }
						    },
						    {
						      "id": "https://openalex.org/W804",
						      "title": "Foreign lookalike URL",
						      "ids": {"arxiv": "https://arxiv.org.evil.test/abs/2501.12345"},
						      "primary_location": {
						        "landing_page_url": "https://papers.test/abs/2501.12345"
						      }
						    },
						    {
						      "id": "https://openalex.org/W805",
						      "title": "Malformed identifier",
						      "ids": {"arxiv": "2500.12345v0"},
						      "primary_location": {
						        "landing_page_url": "https://arxiv.org/abstract/2501.12345"
						      }
						    }
						  ]
						}
						""", MediaType.APPLICATION_JSON));

		ProviderSearchResult result = harness.provider().search(minimalQuery());

		assertThat(result.records()).extracting(record -> record.arxivId())
				.containsExactly("2501.12345", "hep-th/9901001", "2406.01234", null, null);
		harness.server().verify();
	}

	@Test
	void toleratesMissingFieldsAndSkipsRetractedOrUnusableRecords() {
		Harness harness = harness(null);
		harness.server().expect(request -> assertThat(request.getURI().getRawQuery()).doesNotContain("api_key"))
				.andExpect(headerDoesNotExist(HttpHeaders.AUTHORIZATION))
				.andRespond(withSuccess(sparseResponse(), MediaType.APPLICATION_JSON));

		ProviderSearchResult result = harness.provider().search(minimalQuery());

		assertThat(result.totalMatches()).isEqualTo(4);
		assertThat(result.nextCursor()).isNull();
		assertThat(result.records()).singleElement().satisfies(record -> {
			assertThat(record.providerRecordId()).isEqualTo("W999");
			assertThat(record.doi()).isEqualTo("10.5555/sparse");
			assertThat(record.arxivId()).isNull();
			assertThat(record.title()).isEqualTo("Sparse but usable");
			assertThat(record.abstractText()).isNull();
			assertThat(record.publicationDate()).isNull();
			assertThat(record.publicationYear()).isEqualTo(2021);
			assertThat(record.documentType()).isEqualTo(DocumentType.OTHER);
			assertThat(record.language()).isNull();
			assertThat(record.venueName()).isNull();
			assertThat(record.citationCount()).isNull();
			assertThat(record.authors()).isEmpty();
			assertThat(record.reportedOpenAccess()).isFalse();
			assertThat(record.landingPageUrl()).isNull();
			assertThat(record.pdfUrl()).isNull();
			assertThat(record.providerUpdatedAt()).isNull();
		});
		harness.server().verify();
	}

	@Test
	void preservesAValidEmptyResultSet() {
		Harness harness = harness(null);
		harness.server().expect(request -> assertThat(request.getURI().getPath()).isEqualTo("/works"))
				.andRespond(withSuccess("{\"meta\":{\"count\":0},\"results\":[],\"new_field\":true}", MediaType.APPLICATION_JSON));

		ProviderSearchResult result = harness.provider().search(minimalQuery());

		assertThat(result.records()).isEmpty();
		assertThat(result.totalMatches()).isZero();
		assertThat(result.nextCursor()).isNull();
		harness.server().verify();
	}

	@Test
	void acceptsAValidResponseAtTheConfiguredByteLimit() {
		String response = "{\"meta\":{\"count\":0},\"results\":[]}";
		int responseBytes = response.getBytes(StandardCharsets.UTF_8).length;
		Harness harness = harness(null, responseBytes);
		harness.server().expect(request -> assertThat(request.getURI().getPath()).isEqualTo("/works"))
				.andRespond(withSuccess(response, MediaType.APPLICATION_JSON)
						.header(HttpHeaders.CONTENT_LENGTH, Integer.toString(responseBytes)));

		ProviderSearchResult result = harness.provider().search(minimalQuery());

		assertThat(result.records()).isEmpty();
		assertThat(result.totalMatches()).isZero();
		harness.server().verify();
	}

	@Test
	void rejectsAnOversizedDeclaredResponseBeforeDeserialization() {
		String response = "{\"meta\":{\"count\":0},\"results\":[]}";
		int responseBytes = response.getBytes(StandardCharsets.UTF_8).length;
		Harness harness = harness(null, responseBytes - 1);
		harness.server().expect(request -> assertThat(request.getURI().getPath()).isEqualTo("/works"))
				.andRespond(withSuccess(response, MediaType.APPLICATION_JSON)
						.header(HttpHeaders.CONTENT_LENGTH, Integer.toString(responseBytes)));

		assertResponseTooLarge(harness);
	}

	@Test
	void rejectsAnOversizedStreamedResponseWithUnknownContentLength() {
		String response = "{\"meta\":{\"count\":0},\"results\":[]}";
		Harness harness = harness(null, response.getBytes(StandardCharsets.UTF_8).length - 1);
		harness.server().expect(request -> assertThat(request.getURI().getPath()).isEqualTo("/works"))
				.andRespond(withSuccess(response, MediaType.APPLICATION_JSON));

		assertResponseTooLarge(harness);
	}

	@Test
	void rejectsAnIncompleteSuccessfulEnvelope() {
		Harness harness = harness(null);
		harness.server().expect(request -> assertThat(request.getURI().getPath()).isEqualTo("/works"))
				.andRespond(withSuccess("{\"meta\":{},\"results\":null}", MediaType.APPLICATION_JSON));

		assertThatThrownBy(() -> harness.provider().search(minimalQuery()))
				.isInstanceOfSatisfying(ProviderException.class, exception -> {
					assertThat(exception.errorCode()).isEqualTo(OpenAlexResearchProvider.RESPONSE_ERROR);
					assertThat(exception.retryable()).isTrue();
				});
		harness.server().verify();
	}

	@Test
	void reconstructsAbstractByWordPosition() {
		Harness harness = harness(null);
		harness.server().expect(request -> assertThat(request.getURI().getPath()).isEqualTo("/works"))
				.andRespond(withSuccess("""
						{
						  "meta": {"count": 1},
						  "results": [{
						    "id": "https://openalex.org/W123",
						    "title": "Position test",
						    "type": "preprint",
						    "abstract_inverted_index": {
						      "order": [3], "Correct": [0], "is": [2], "word": [1]
						    }
						  }]
						}
						""", MediaType.APPLICATION_JSON));

		ProviderSearchResult result = harness.provider().search(minimalQuery());

		assertThat(result.records()).singleElement()
				.extracting(record -> record.abstractText())
				.isEqualTo("Correct word is order");
		harness.server().verify();
	}

	@Test
	void exposesOnlyPdfUrlsFromOpenAccessLocations() {
		Harness harness = harness(null);
		harness.server().expect(request -> assertThat(request.getURI().getPath()).isEqualTo("/works"))
				.andRespond(withSuccess("""
						{
						  "meta": {"count": 2},
						  "results": [
						    {
						      "id": "https://openalex.org/W701",
						      "title": "Closed primary copy",
						      "type": "article",
						      "primary_location": {"pdf_url": "https://publisher.test/closed.pdf", "is_oa": false},
						      "open_access": {"is_oa": false}
						    },
						    {
						      "id": "https://openalex.org/W702",
						      "title": "Open repository copy",
						      "type": "article",
						      "primary_location": {"pdf_url": "https://publisher.test/closed-again.pdf", "is_oa": false},
						      "best_oa_location": {"pdf_url": "https://repository.test/open.pdf", "is_oa": true},
						      "open_access": {"is_oa": true}
						    }
						  ]
						}
						""", MediaType.APPLICATION_JSON));

		ProviderSearchResult result = harness.provider().search(minimalQuery());

		assertThat(result.records()).satisfiesExactly(
				record -> assertThat(record.pdfUrl()).isNull(),
				record -> assertThat(record.pdfUrl()).hasToString("https://repository.test/open.pdf"));
		harness.server().verify();
	}

	@Test
	void translatesRateLimitAndRetryAfter() {
		Harness harness = harness(null);
		harness.server().expect(request -> assertThat(request.getURI().getPath()).isEqualTo("/works"))
				.andRespond(MockRestResponseCreators.withStatus(HttpStatus.TOO_MANY_REQUESTS)
						.header(HttpHeaders.RETRY_AFTER, "120"));

		assertThatThrownBy(() -> harness.provider().search(minimalQuery()))
				.isInstanceOfSatisfying(ProviderException.class, exception -> {
					assertThat(exception.provider()).isEqualTo(ProviderId.OPENALEX);
					assertThat(exception.errorCode()).isEqualTo(OpenAlexResearchProvider.RATE_LIMITED);
					assertThat(exception.retryable()).isTrue();
					assertThat(exception.retryAfter()).isEqualTo(Duration.ofMinutes(2));
				});
		harness.server().verify();
	}

	@Test
	void translatesServerErrors() {
		Harness harness = harness(null);
		harness.server().expect(request -> assertThat(request.getURI().getPath()).isEqualTo("/works"))
				.andRespond(MockRestResponseCreators.withStatus(HttpStatus.SERVICE_UNAVAILABLE));

		assertThatThrownBy(() -> harness.provider().search(minimalQuery()))
				.isInstanceOfSatisfying(ProviderException.class, exception -> {
					assertThat(exception.errorCode()).isEqualTo(OpenAlexResearchProvider.UPSTREAM_ERROR);
					assertThat(exception.retryable()).isTrue();
				});
		harness.server().verify();
	}

	@Test
	void translatesTimeouts() {
		Harness harness = harness(null);
		harness.server().expect(request -> assertThat(request.getURI().getPath()).isEqualTo("/works"))
				.andRespond(request -> {
					throw new SocketTimeoutException("read timed out");
				});

		assertThatThrownBy(() -> harness.provider().search(minimalQuery()))
				.isInstanceOfSatisfying(ProviderException.class, exception -> {
					assertThat(exception.errorCode()).isEqualTo(OpenAlexResearchProvider.TIMEOUT);
					assertThat(exception.retryable()).isTrue();
				});
		harness.server().verify();
	}

	@Test
	void doesNotClassifyAnArbitraryClosedConnectionAsATimeout() {
		Harness harness = harness(null);
		harness.server().expect(request -> assertThat(request.getURI().getPath()).isEqualTo("/works"))
				.andRespond(request -> {
					throw new java.io.IOException("closed");
				});

		assertThatThrownBy(() -> harness.provider().search(minimalQuery()))
				.isInstanceOfSatisfying(ProviderException.class, exception -> {
					assertThat(exception.errorCode()).isEqualTo(OpenAlexResearchProvider.UNAVAILABLE);
					assertThat(exception.retryable()).isTrue();
				});
		harness.server().verify();
	}

	private static Harness harness(String apiKey) {
		return harness(apiKey, 8 * 1024 * 1024);
	}

	private static Harness harness(String apiKey, int maxResponseBytes) {
		OpenAlexProperties properties = new OpenAlexProperties(
				BASE_URL,
				Duration.ofSeconds(2),
				Duration.ofSeconds(5),
				maxResponseBytes,
				apiKey);
		RestClient.Builder builder = OpenAlexConfiguration.configure(RestClient.builder(), properties);
		MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
		return new Harness(new OpenAlexResearchProvider(builder.build(), properties, CLOCK), server);
	}

	private static void assertResponseTooLarge(Harness harness) {
		assertThatThrownBy(() -> harness.provider().search(minimalQuery()))
				.isInstanceOfSatisfying(ProviderException.class, exception -> {
					assertThat(exception.errorCode()).isEqualTo(OpenAlexResearchProvider.RESPONSE_TOO_LARGE);
					assertThat(exception.retryable()).isFalse();
				});
		harness.server().verify();
	}

	private static ProviderSearchQuery minimalQuery() {
		return new ProviderSearchQuery("agents", null, null, Set.of(), false, 0, Set.of(), 25, null);
	}

	private static String decodedParam(String value) {
		return value == null ? null : URLDecoder.decode(value, StandardCharsets.UTF_8);
	}

	private static String completeResponse() {
		return """
				{
				  "meta": {"count": 234, "next_cursor": "next-cursor", "unknown": "ignored"},
				  "results": [{
				    "id": "https://openalex.org/W2741809807",
				    "ids": {
				      "openalex": "https://openalex.org/W2741809807",
				      "doi": "https://doi.org/10.1000/Example.DOI",
				      "arxiv": "arXiv:2501.12345v2",
				      "pmid": "https://pubmed.ncbi.nlm.nih.gov/12345678"
				    },
				    "doi": "https://doi.org/10.1000/Example.DOI",
				    "title": " Research agents in practice ",
				    "abstract_inverted_index": {
				      "agents": [1, 4], "researchers": [3], "Research": [0], "help": [2]
				    },
				    "publication_date": "2024-03-15",
				    "publication_year": 2024,
				    "type": "article",
				    "language": "EN",
				    "primary_location": {
				      "landing_page_url": "https://publisher.test/article/123",
				      "pdf_url": "https://publisher.test/paywalled.pdf",
				      "is_oa": false,
				      "source": {"id": "https://openalex.org/S123", "display_name": "Journal of Research Agents"}
				    },
				    "best_oa_location": {
				      "landing_page_url": "https://repository.test/article-123",
				      "pdf_url": "https://repository.test/article-123.pdf",
				      "is_oa": true
				    },
				    "open_access": {"is_oa": true, "oa_status": "gold", "oa_url": "https://repository.test/article-123.pdf"},
				    "cited_by_count": 42,
				    "authorships": [{
				      "author_position": "first",
				      "is_corresponding": true,
				      "author": {
				        "id": "https://openalex.org/A5023888391",
				        "display_name": "Ada Researcher",
				        "orcid": "https://orcid.org/0000-0002-1825-0097"
				      }
				    }],
				    "relevance_score": 0.987,
				    "updated_date": "2026-08-15T09:30:00Z",
				    "is_retracted": false,
				    "future_field": {"anything": true}
				  }]
				}
				""";
	}

	private static String sparseResponse() {
		return """
				{
				  "meta": {"count": 4},
				  "results": [
				    {"id": "https://openalex.org/W1", "title": "Retracted", "type": "article", "is_retracted": true},
				    {"id": "https://openalex.org/W2", "title": "  ", "display_name": null, "type": "article"},
				    {"id": "not-an-openalex-id", "title": "No usable identifier", "type": "article"},
				    {
				      "ids": {"openalex": "https://openalex.org/W999", "doi": "doi:10.5555/SPARSE"},
				      "display_name": "Sparse but usable",
				      "publication_date": "not-a-date",
				      "publication_year": 2021,
				      "type": "future-work-type",
				      "primary_location": {"landing_page_url": "javascript:alert(1)", "pdf_url": "not a URI"},
				      "open_access": {"is_oa": false, "oa_url": "file:///etc/passwd"},
				      "updated_date": "also-not-a-date"
				    }
				  ]
				}
				""";
	}

	private record Harness(OpenAlexResearchProvider provider, MockRestServiceServer server) {
	}
}
