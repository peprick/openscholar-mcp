package com.openscholar.provider.datacite;

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

class DataCiteResearchProviderTests {

	private static final URI BASE_URL = URI.create("https://api.datacite.test");
	private static final MediaType JSON_API = MediaType.parseMediaType("application/vnd.api+json");
	private static final Instant NOW = Instant.parse("2026-08-22T12:00:00Z");
	private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

	@Test
	void sendsAKeylessBoundedRequestAndMapsRichDissertationMetadata() {
		Harness harness = harness("researcher@example.org");
		ProviderSearchQuery query = new ProviderSearchQuery(
				"agents OR title:*",
				2020,
				2025,
				Set.of(DocumentType.DISSERTATION, DocumentType.ARTICLE),
				false,
				10,
				Set.of("fr", "EN"),
				50,
				null);

		harness.server().expect(once(), request -> {
			assertThat(request.getURI().getPath()).isEqualTo("/dois");
			assertThat(param(request.getURI(), "query")).isEqualTo(
					"(\"agents\" \"OR\" \"title:*\") AND (types.resourceTypeGeneral:Dissertation"
							+ " OR types.resourceType:*thesis* OR types.resourceType:*dissertation*)"
							+ " AND publicationYear:[2020 TO 2025] AND language:(\"en\" OR \"fr\")");
			assertThat(param(request.getURI(), "sort")).isEqualTo("relevance");
			assertThat(param(request.getURI(), "page[number]")).isEqualTo("1");
			assertThat(param(request.getURI(), "page[size]")).isEqualTo("50");
			assertThat(param(request.getURI(), "disable-facets")).isEqualTo("true");
			assertThat(param(request.getURI(), "has-citations")).isEqualTo("10");
			assertThat(param(request.getURI(), "fields[dois]"))
					.contains("doi", "types", "relatedItems", "rightsList", "citationCount")
					.doesNotContain("contentUrl", "url");
			assertThat(request.getURI().getRawQuery()).doesNotContain("api_key", "apikey");
		})
				.andExpect(method(GET))
				.andExpect(header(HttpHeaders.ACCEPT, "application/vnd.api+json"))
				.andExpect(header(HttpHeaders.USER_AGENT, "OpenScholar/0.0.1 (mailto:researcher@example.org)"))
				.andExpect(headerDoesNotExist(HttpHeaders.AUTHORIZATION))
				.andRespond(withSuccess(richResponse(), JSON_API));

		ProviderSearchResult result = harness.provider().search(query);

		assertThat(result.provider()).isEqualTo(ProviderId.DATACITE);
		assertThat(result.totalMatches()).isEqualTo(61);
		assertThat(result.nextCursor()).isEqualTo("dcpage1:2");
		assertThat(result.retrievedAt()).isEqualTo(NOW);
		assertThat(result.records()).singleElement().satisfies(record -> {
			assertThat(record.providerRecordId()).isEqualTo("10.1234/example.thesis");
			assertThat(record.doi()).isEqualTo("10.1234/example.thesis");
			assertThat(record.title()).isEqualTo("Agentic discovery for research");
			assertThat(record.abstractText()).isEqualTo("A synthetic dissertation abstract.");
			assertThat(record.publicationDate()).isEqualTo(LocalDate.parse("2024-05-17"));
			assertThat(record.publicationYear()).isEqualTo(2024);
			assertThat(record.documentType()).isEqualTo(DocumentType.DISSERTATION);
			assertThat(record.language()).isEqualTo("en");
			assertThat(record.venueName()).isEqualTo("Institutional Dissertation Series");
			assertThat(record.citationCount()).isEqualTo(12);
			assertThat(record.publisher()).isEqualTo("Example University Press");
			assertThat(record.institution()).isEqualTo("Example University");
			assertThat(record.volume()).isEqualTo("8");
			assertThat(record.issue()).isEqualTo("2");
			assertThat(record.pages()).isEqualTo("101-188");
			assertThat(record.articleNumber()).isEqualTo("D-2048");
			assertThat(record.edition()).isEqualTo("First");
			assertThat(record.isbn()).containsExactly("978-1-4028-9462-6");
			assertThat(record.issn()).containsExactly("2049-3630", "2049-3649");
			assertThat(record.degree()).isEqualTo("PhD");
			assertThat(record.providerUpdatedAt()).isEqualTo(Instant.parse("2026-08-20T09:30:00Z"));
			assertThat(record.reportedOpenAccess()).isFalse();
			assertThat(record.pdfUrl()).isNull();
			assertThat(record.landingPageUrl()).hasToString("https://doi.org/10.1234/example.thesis");
			assertThat(record.sourceUrl()).hasToString("https://api.datacite.org/dois/10.1234/example.thesis");
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
					.containsEntry("providerType", "PhD thesis")
					.containsEntry("resourceTypeGeneral", "Dissertation")
					.containsEntry("schemaVersion", "http://datacite.org/schema/kernel-4")
					.containsEntry("clientId", "repo.example");
			assertThat(record.metadataFragment().toString()).doesNotContain("evil.test", "contentUrl");
		});
		harness.server().verify();
	}

	@Test
	void classifiesLegacyThesesAndPostFiltersTheConflatedDataCiteType() {
		Harness thesisHarness = harness(null);
		thesisHarness.server().expect(request -> assertThat(request.getURI().getPath()).isEqualTo("/dois"))
				.andRespond(withSuccess(classificationResponse(), JSON_API));

		ProviderSearchResult theses = thesisHarness.provider().search(query(Set.of(DocumentType.THESIS), "*"));

		assertThat(theses.totalMatches()).isEqualTo(2);
		assertThat(theses.records())
				.extracting(record -> record.doi(), record -> record.documentType(), record -> record.degree())
				.containsExactly(org.assertj.core.groups.Tuple.tuple(
						"10.1234/masters-thesis", DocumentType.THESIS, "Master's"));
		thesisHarness.server().verify();

		Harness dissertationHarness = harness(null);
		dissertationHarness.server().expect(request -> assertThat(request.getURI().getPath()).isEqualTo("/dois"))
				.andRespond(withSuccess(classificationResponse(), JSON_API));

		ProviderSearchResult dissertations = dissertationHarness.provider()
				.search(query(Set.of(DocumentType.DISSERTATION), "*"));

		assertThat(dissertations.records())
				.extracting(record -> record.doi(), record -> record.documentType())
				.containsExactly(org.assertj.core.groups.Tuple.tuple(
						"10.1234/controlled-dissertation", DocumentType.DISSERTATION));
		dissertationHarness.server().verify();
	}

	@Test
	void shortCircuitsUnsupportedAndOpenAccessOnlySearchesWithoutHttp() {
		Harness harness = harness(null);

		ProviderSearchResult unsupported = harness.provider().search(query(Set.of(DocumentType.ARTICLE), "*"));
		ProviderSearchResult openAccessOnly = harness.provider().search(new ProviderSearchQuery(
				"agents", null, null, Set.of(), true, 0, Set.of(), 25, null));

		assertThat(unsupported.records()).isEmpty();
		assertThat(unsupported.totalMatches()).isZero();
		assertThat(openAccessOnly.records()).isEmpty();
		assertThat(openAccessOnly.totalMatches()).isZero();
		harness.server().verify();
	}

	@Test
	void advancesOnlyItsOwnedRelevancePageCursorAndStopsAtTheResultWindow() {
		Harness harness = harness(null);
		harness.server().expect(request -> assertThat(param(request.getURI(), "page[number]")).isEqualTo("2"))
				.andRespond(withSuccess("""
						{"data":[],"meta":{"total":75,"totalPages":3,"page":2}}
						""", JSON_API));

		ProviderSearchResult middle = harness.provider().search(query(Set.of(), "dcpage1:2"));

		assertThat(middle.nextCursor()).isEqualTo("dcpage1:3");
		harness.server().verify();

		Harness lastHarness = harness(null);
		lastHarness.server().expect(request -> assertThat(param(request.getURI(), "page[number]")).isEqualTo("3"))
				.andRespond(withSuccess("""
						{"data":[],"meta":{"total":75,"totalPages":3,"page":3}}
						""", JSON_API));
		assertThat(lastHarness.provider().search(query(Set.of(), "dcpage1:3")).nextCursor()).isNull();
		lastHarness.server().verify();

		Harness invalidHarness = harness(null);
		assertThatThrownBy(() -> invalidHarness.provider().search(query(Set.of(), "https://evil.test/next")))
				.isInstanceOfSatisfying(ProviderException.class, exception -> {
					assertThat(exception.errorCode()).isEqualTo(DataCiteResearchProvider.REQUEST_REJECTED);
					assertThat(exception.retryable()).isFalse();
				});
		assertThatThrownBy(() -> invalidHarness.provider().search(query(Set.of(), "dcpage1:401")))
				.isInstanceOf(ProviderException.class);
		invalidHarness.server().verify();
	}

	@Test
	void toleratesSparseEvolvingJsonAndSkipsUnknownOrUnusableRecords() {
		Harness harness = harness(null);
		harness.server().expect(request -> assertThat(request.getURI().getPath()).isEqualTo("/dois"))
				.andRespond(withSuccess("""
						{
						  "data": [
						    {"id":"10.1/no-attributes","type":"dois"},
						    {"id":"10.1234/no-title","type":"dois","attributes":{
						      "doi":"10.1234/no-title","titles":[{"title":":unkn"}],
						      "types":{"resourceTypeGeneral":"Dissertation"}
						    }},
						    {"id":"10.1234/sparse","type":"dois","attributes":{
						      "doi":"doi:10.1234/SPARSE","titles":[{"title":"Sparse dissertation"}],
						      "publisher":"Example Repository","publicationYear":2021,
						      "dates":[{"date":"2021","dateType":"Issued"}],
						      "types":{"resourceTypeGeneral":"Dissertation","resourceType":":null"},
						      "updated":"not-an-instant","futureField":{"ignored":true}
						    }}
						  ],
						  "meta":{"total":3,"totalPages":1,"page":1},
						  "futureTopLevel":true
						}
						""", JSON_API));

		ProviderSearchResult result = harness.provider().search(query(Set.of(), "*"));

		assertThat(result.records()).singleElement().satisfies(record -> {
			assertThat(record.doi()).isEqualTo("10.1234/sparse");
			assertThat(record.publisher()).isEqualTo("Example Repository");
			assertThat(record.publicationDate()).isNull();
			assertThat(record.publicationYear()).isEqualTo(2021);
			assertThat(record.providerUpdatedAt()).isNull();
			assertThat(record.documentType()).isEqualTo(DocumentType.DISSERTATION);
		});
		harness.server().verify();
	}

	@Test
	void preservesEmptyResultsAndRejectsIncompleteOrOversizedResponses() {
		Harness emptyHarness = harness(null);
		emptyHarness.server().expect(request -> assertThat(request.getURI().getPath()).isEqualTo("/dois"))
				.andRespond(withSuccess("{\"data\":[],\"meta\":{\"total\":0},\"newField\":true}", JSON_API));
		assertThat(emptyHarness.provider().search(query(Set.of(), "*")).records()).isEmpty();
		emptyHarness.server().verify();

		Harness incompleteHarness = harness(null);
		incompleteHarness.server().expect(request -> assertThat(request.getURI().getPath()).isEqualTo("/dois"))
				.andRespond(withSuccess("{\"data\":null,\"meta\":{}}", JSON_API));
		assertThatThrownBy(() -> incompleteHarness.provider().search(query(Set.of(), "*")))
				.isInstanceOfSatisfying(ProviderException.class, exception -> {
					assertThat(exception.errorCode()).isEqualTo(DataCiteResearchProvider.RESPONSE_ERROR);
					assertThat(exception.retryable()).isTrue();
				});
		incompleteHarness.server().verify();

		String body = "{\"data\":[],\"meta\":{\"total\":0}}";
		Harness oversizedHarness = harness(null, body.getBytes(StandardCharsets.UTF_8).length - 1);
		oversizedHarness.server().expect(request -> assertThat(request.getURI().getPath()).isEqualTo("/dois"))
				.andRespond(withSuccess(body, JSON_API));
		assertThatThrownBy(() -> oversizedHarness.provider().search(query(Set.of(), "*")))
				.isInstanceOfSatisfying(ProviderException.class, exception -> {
					assertThat(exception.errorCode()).isEqualTo(DataCiteResearchProvider.RESPONSE_TOO_LARGE);
					assertThat(exception.retryable()).isFalse();
				});
		oversizedHarness.server().verify();
	}

	@Test
	void translatesRateLimitsServerErrorsClientErrorsAndTimeouts() {
		Harness rateLimited = harness(null);
		rateLimited.server().expect(request -> assertThat(request.getURI().getPath()).isEqualTo("/dois"))
				.andRespond(MockRestResponseCreators.withStatus(HttpStatus.TOO_MANY_REQUESTS)
						.header(HttpHeaders.RETRY_AFTER, "120"));
		assertThatThrownBy(() -> rateLimited.provider().search(query(Set.of(), "*")))
				.isInstanceOfSatisfying(ProviderException.class, exception -> {
					assertThat(exception.provider()).isEqualTo(ProviderId.DATACITE);
					assertThat(exception.errorCode()).isEqualTo(DataCiteResearchProvider.RATE_LIMITED);
					assertThat(exception.retryable()).isTrue();
					assertThat(exception.retryAfter()).isEqualTo(Duration.ofMinutes(2));
				});
		rateLimited.server().verify();

		assertStatus(HttpStatus.SERVICE_UNAVAILABLE, DataCiteResearchProvider.UPSTREAM_ERROR, true);
		assertStatus(HttpStatus.BAD_REQUEST, DataCiteResearchProvider.REQUEST_REJECTED, false);

		Harness timedOut = harness(null);
		timedOut.server().expect(request -> assertThat(request.getURI().getPath()).isEqualTo("/dois"))
				.andRespond(request -> {
					throw new SocketTimeoutException("read timed out");
				});
		assertThatThrownBy(() -> timedOut.provider().search(query(Set.of(), "*")))
				.isInstanceOfSatisfying(ProviderException.class, exception -> {
					assertThat(exception.errorCode()).isEqualTo(DataCiteResearchProvider.TIMEOUT);
					assertThat(exception.retryable()).isTrue();
				});
		timedOut.server().verify();
	}

	private static void assertStatus(HttpStatus status, String expectedCode, boolean retryable) {
		Harness harness = harness(null);
		harness.server().expect(request -> assertThat(request.getURI().getPath()).isEqualTo("/dois"))
				.andRespond(MockRestResponseCreators.withStatus(status));
		assertThatThrownBy(() -> harness.provider().search(query(Set.of(), "*")))
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
		DataCiteProperties properties = new DataCiteProperties(
				true,
				BASE_URL,
				Duration.ofSeconds(2),
				Duration.ofSeconds(5),
				maxResponseBytes,
				contactEmail);
		RestClient.Builder builder = DataCiteConfiguration.configure(RestClient.builder(), properties);
		MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
		return new Harness(new DataCiteResearchProvider(builder.build(), properties, CLOCK), server);
	}

	private static ProviderSearchQuery query(Set<DocumentType> types, String cursor) {
		return new ProviderSearchQuery("agents", null, null, types, false, 0, Set.of(), 25, cursor);
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
				  "data": [{
				    "id": "10.1234/EXAMPLE.THESIS",
				    "type": "dois",
				    "attributes": {
				      "doi": "https://doi.org/10.1234/EXAMPLE.THESIS",
				      "titles": [
				        {"title":"Agents translated","titleType":"TranslatedTitle"},
				        {"title":" Agentic discovery for research "}
				      ],
				      "creators": [{
				        "name":"Ada Researcher",
				        "nameIdentifiers":[{
				          "nameIdentifier":"https://orcid.org/0000-0002-1825-0097",
				          "nameIdentifierScheme":"ORCID",
				          "schemeUri":"https://orcid.org"
				        }]
				      }],
				      "publisher":{"name":"Example University Press","publisherIdentifier":"ignored"},
				      "publicationYear":2024,
				      "contributors":[
				        {"name":"Not the institution","contributorType":"Editor"},
				        {"name":"Example University","contributorType":"HostingInstitution"}
				      ],
				      "dates":[
				        {"date":"2024-05","dateType":"Submitted"},
				        {"date":"2024-05-17","dateType":"Issued"}
				      ],
				      "language":"EN",
				      "types":{"resourceTypeGeneral":"Dissertation","resourceType":"PhD thesis"},
				      "relatedIdentifiers":[
				        {"relatedIdentifier":"978-1-4028-9462-6","relatedIdentifierType":"ISBN","relationType":"IsPublishedIn"},
				        {"relatedIdentifier":"2049-3649","relatedIdentifierType":"EISSN","relationType":"IsPublishedIn"},
				        {"relatedIdentifier":"should-not-map","relatedIdentifierType":"ISSN","relationType":"IsSupplementTo"}
				      ],
				      "relatedItems":[
				        {"relationType":"IsSupplementTo","titles":[{"title":"Wrong container"}],"volume":"99"},
				        {
				          "relationType":"IsPublishedIn","relatedItemType":"Text",
				          "relatedItemIdentifier":{"relatedItemIdentifier":"2049-3630","relatedItemIdentifierType":"ISSN"},
				          "titles":[{"title":"Institutional Dissertation Series"}],
				          "volume":"8","issue":"2","number":"D-2048","numberType":"Article",
				          "firstPage":"101","lastPage":"188","edition":"First"
				        }
				      ],
				      "rightsList":[{"rightsIdentifier":"CC-BY-4.0","rightsUri":"https://evil.test/license"}],
				      "descriptions":[
				        {"description":"Not the abstract","descriptionType":"Methods"},
				        {"description":"A synthetic dissertation abstract.","descriptionType":"Abstract"}
				      ],
				      "url":"https://evil.test/paywall",
				      "contentUrl":["https://evil.test/paper.pdf"],
				      "updated":"2026-08-20T09:30:00Z",
				      "citationCount":12,
				      "schemaVersion":"http://datacite.org/schema/kernel-4",
				      "version":"1",
				      "clientId":"repo.example",
				      "futureField":{"anything":true}
				    }
				  }],
				  "meta":{"total":61,"totalPages":2,"page":1},
				  "links":{"self":"https://api.datacite.test/dois","next":"https://evil.test/next"}
				}
				""";
	}

	private static String classificationResponse() {
		return """
				{
				  "data": [
				    {"id":"10.1234/masters-thesis","type":"dois","attributes":{
				      "doi":"10.1234/masters-thesis","titles":[{"title":"Legacy text thesis"}],
				      "types":{"resourceTypeGeneral":"Text","resourceType":"Master's thesis"}
				    }},
				    {"id":"10.1234/controlled-dissertation","type":"dois","attributes":{
				      "doi":"10.1234/controlled-dissertation","titles":[{"title":"Controlled dissertation"}],
				      "types":{"resourceTypeGeneral":"Dissertation"}
				    }}
				  ],
				  "meta":{"total":2,"totalPages":1,"page":1}
				}
				""";
	}

	private record Harness(DataCiteResearchProvider provider, MockRestServiceServer server) {
	}
}
