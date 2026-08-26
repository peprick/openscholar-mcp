package com.openscholar.provider.europepmc;

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

class EuropePmcResearchProviderTests {

	private static final URI BASE_URL = URI.create("https://europe-pmc.test/europepmc/webservices/rest");
	private static final Instant NOW = Instant.parse("2026-08-26T12:00:00Z");
	private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

	@Test
	void sendsAKeylessBoundedLiteralQueryAndMapsCorePublicationMetadata() {
		Harness harness = harness();
		ProviderSearchQuery query = new ProviderSearchQuery(
				"agents OR title:* / path\\term \"quoted\"",
				2020,
				2025,
				Set.of(DocumentType.ARTICLE),
				true,
				7,
				Set.of(),
				250,
				null);

		harness.server().expect(once(), request -> {
			assertThat(request.getURI().getPath()).isEqualTo("/europepmc/webservices/rest/search");
			assertThat(param(request.getURI(), "query")).isEqualTo(
					"(\"agents\" \"OR\" \"title:*\" \"/\" \"path\\\\term\" \"\\\"quoted\\\"\")"
							+ " AND SRC:MED AND PUB_TYPE:\"Journal Article\" AND IN_PMC:Y"
							+ " AND PUB_YEAR:[2020 TO 2025] AND OPEN_ACCESS:Y AND CITED:[7 TO *]");
			assertThat(param(request.getURI(), "format")).isEqualTo("json");
			assertThat(param(request.getURI(), "resultType")).isEqualTo("core");
			assertThat(param(request.getURI(), "synonym")).isEqualTo("false");
			assertThat(param(request.getURI(), "cursorMark")).isEqualTo("*");
			assertThat(param(request.getURI(), "pageSize")).isEqualTo("50");
			assertThat(request.getURI().getRawQuery())
					.doesNotContain("api_key", "apikey", "token", "email");
		})
				.andExpect(method(GET))
				.andExpect(header(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE))
				.andExpect(header(HttpHeaders.USER_AGENT, "OpenScholar/0.0.1"))
				.andExpect(headerDoesNotExist(HttpHeaders.AUTHORIZATION))
				.andRespond(withSuccess(richResponse(), MediaType.APPLICATION_JSON));

		ProviderSearchResult result = harness.provider().search(query);

		assertThat(result.provider()).isEqualTo(ProviderId.EUROPE_PMC);
		assertThat(result.totalMatches()).isEqualTo(151);
		assertThat(result.nextCursor()).isEqualTo("AoIIQNext/+=");
		assertThat(result.retrievedAt()).isEqualTo(NOW);
		assertThat(result.records()).singleElement().satisfies(record -> {
			assertThat(record.providerRecordId()).isEqualTo("MED:36946755");
			assertThat(record.doi()).isEqualTo("10.1234/example.article");
			assertThat(record.title()).isEqualTo("Agentic discovery for medicine");
			assertThat(record.abstractText()).isEqualTo("Background A & B important finding.");
			assertThat(record.publicationDate()).isEqualTo(LocalDate.parse("2023-05-08"));
			assertThat(record.publicationYear()).isEqualTo(2023);
			assertThat(record.documentType()).isEqualTo(DocumentType.ARTICLE);
			assertThat(record.language()).isEqualTo("eng");
			assertThat(record.venueName()).isEqualTo("Journal of Synthetic Medicine");
			assertThat(record.citationCount()).isEqualTo(8);
			assertThat(record.reportedOpenAccess()).isTrue();
			assertThat(record.landingPageUrl())
					.hasToString("https://europepmc.org/article/MED/36946755");
			assertThat(record.sourceUrl())
					.hasToString("https://europepmc.org/article/MED/36946755");
			assertThat(record.pdfUrl()).isNull();
			assertThat(record.relevanceScore()).isNull();
			assertThat(record.providerUpdatedAt()).isEqualTo(Instant.parse("2025-05-30T00:00:00Z"));
			assertThat(record.volume()).isEqualTo("8");
			assertThat(record.issue()).isEqualTo("2");
			assertThat(record.pages()).isEqualTo("e694");
			assertThat(record.issn()).containsExactly("1234-567X", "2049-3630");
			assertThat(record.identifiers())
					.extracting(identifier -> identifier.type())
					.containsExactly(
							PaperIdentifierType.DOI,
							PaperIdentifierType.PMID,
							PaperIdentifierType.PMCID);
			assertThat(record.identifiers())
					.extracting(identifier -> identifier.value())
					.containsExactly("10.1234/example.article", "36946755", "PMC7614751");
			assertThat(record.authors()).singleElement().satisfies(author -> {
				assertThat(author.providerAuthorId()).isNull();
				assertThat(author.displayName()).isEqualTo("Ada Researcher");
				assertThat(author.orcid()).isEqualTo("0000-0002-1825-0097");
				assertThat(author.position()).isEqualTo(1);
				assertThat(author.corresponding()).isFalse();
			});
			assertThat(record.metadataFragment())
					.containsEntry("metadataScope", "PUBLICATION_METADATA_ONLY")
					.containsEntry("source", "MED")
					.containsEntry("publicationStatus", "ppublish")
					.containsEntry("publicationModel", "Print-Electronic")
					.containsEntry("reportedLicense", "CC BY")
					.containsEntry("firstPublicationDate", "2023-05-08")
					.containsEntry("electronicPublicationDate", "2023-05-09")
					.containsEntry(
							"publicationTypes", java.util.List.of("research-article", "Journal Article"));
			assertThat(record.metadataFragment().toString())
					.doesNotContain("fullTextUrlList", "evil.test", "article.pdf", "futureField");
		});
		harness.server().verify();
	}

	@Test
	void advancesOnlyBoundedCursorMarksAndTreatsARepeatedSolrCursorAsTheEnd() {
		String cursor = "AoIIQG4+3ig1NjExNjU0Mg==";
		Harness endingHarness = harness();
		endingHarness.server().expect(request -> {
			assertThat(param(request.getURI(), "cursorMark")).isEqualTo(cursor);
			assertThat(request.getURI().getHost()).isEqualTo("europe-pmc.test");
		})
				.andRespond(withSuccess("""
						{"hitCount":50,"nextCursorMark":"AoIIQG4+3ig1NjExNjU0Mg==",
						 "nextPageUrl":"https://evil.test/must-not-be-followed",
						 "resultList":{"result":[]}}
						""", MediaType.APPLICATION_JSON));

		assertThat(endingHarness.provider().search(query(Set.of(), 25, cursor)).nextCursor()).isNull();
		endingHarness.server().verify();

		Harness invalidUpstream = harness();
		invalidUpstream.server().expect(once(), request -> assertThat(request.getURI().getHost())
				.isEqualTo("europe-pmc.test"))
				.andRespond(withSuccess("""
						{"hitCount":1,"nextCursorMark":"https://evil.test/next",
						 "resultList":{"result":[]}}
						""", MediaType.APPLICATION_JSON));
		assertThatThrownBy(() -> invalidUpstream.provider().search(query(Set.of(), 25, "*")))
				.isInstanceOfSatisfying(ProviderException.class, exception -> {
					assertThat(exception.errorCode()).isEqualTo(EuropePmcResearchProvider.RESPONSE_ERROR);
					assertThat(exception.retryable()).isFalse();
				});
		invalidUpstream.server().verify();

		Harness invalidLocal = harness();
		assertThatThrownBy(() -> invalidLocal.provider().search(
				query(Set.of(), 25, "https://evil.test/next")))
				.isInstanceOfSatisfying(ProviderException.class, exception -> {
					assertThat(exception.errorCode()).isEqualTo(EuropePmcResearchProvider.REQUEST_REJECTED);
					assertThat(exception.retryable()).isFalse();
				});
		assertThatThrownBy(() -> invalidLocal.provider().search(
				query(Set.of(), 25, "A".repeat(1025))))
				.isInstanceOf(ProviderException.class);
		invalidLocal.server().verify();
	}

	@Test
	void shortCircuitsUnsupportedLanguageAndNonArticleFiltersWithoutHttp() {
		Harness harness = harness();

		ProviderSearchResult language = harness.provider().search(new ProviderSearchQuery(
				"agents", null, null, Set.of(), false, 0, Set.of("en"), 25, null));
		ProviderSearchResult unsupportedType = harness.provider().search(
				query(Set.of(DocumentType.THESIS), 25, "*"));

		assertThat(language.records()).isEmpty();
		assertThat(language.totalMatches()).isZero();
		assertThat(unsupportedType.records()).isEmpty();
		assertThat(unsupportedType.totalMatches()).isZero();
		harness.server().verify();
	}

	@Test
	void defensivelyReappliesFiltersAndRequiresPmcHeldMedJournalArticles() {
		Harness harness = harness();
		harness.server().expect(once(), request -> assertThat(param(request.getURI(), "query"))
				.contains("SRC:MED", "PUB_TYPE:\"Journal Article\"", "IN_PMC:Y"))
				.andRespond(withSuccess("""
						{
						  "hitCount":5,
						  "resultList":{"result":[
						    {"id":"1001","source":"MED","pmcid":"PMC1001","inPMC":"Y","title":"Kept",
						     "pubYear":"2024","isOpenAccess":"Y","citedByCount":8,
						     "pubTypeList":{"pubType":["Journal Article"]}},
						    {"id":"1002","source":"MED","pmcid":"PMC1002","inPMC":"Y","title":"Too old",
						     "pubYear":"2019","isOpenAccess":"Y","citedByCount":8,
						     "pubTypeList":{"pubType":["Journal Article"]}},
						    {"id":"1003","source":"MED","pmcid":"PMC1003","inPMC":"Y","title":"Closed",
						     "pubYear":"2024","isOpenAccess":"N","citedByCount":8,
						     "pubTypeList":{"pubType":["Journal Article"]}},
						    {"id":"1004","source":"MED","pmcid":"PMC1004","inPMC":"Y","title":"Too few citations",
						     "pubYear":"2024","isOpenAccess":"Y","citedByCount":6,
						     "pubTypeList":{"pubType":["Journal Article"]}},
						    {"id":"1005","source":"MED","pmcid":"PMC1005","inPMC":"N","title":"Not held in PMC",
						     "pubYear":"2024","isOpenAccess":"Y","citedByCount":8,
						     "pubTypeList":{"pubType":["Journal Article"]}}
						  ]}
						}
						""", MediaType.APPLICATION_JSON));

		ProviderSearchResult result = harness.provider().search(new ProviderSearchQuery(
				"agents", 2020, 2025, Set.of(), true, 7, Set.of(), 25, null));

		assertThat(result.totalMatches()).isEqualTo(5);
		assertThat(result.records())
				.extracting(record -> record.providerRecordId())
				.containsExactly("MED:1001");
		harness.server().verify();
	}

	@Test
	void toleratesEvolvingJsonAndSkipsUnusablePublicationRecords() {
		Harness harness = harness();
		harness.server().expect(once(), request -> assertThat(request.getURI().getPath())
				.isEqualTo("/europepmc/webservices/rest/search"))
				.andRespond(withSuccess("""
						{
						  "hitCount":6,
						  "futureTopLevel":true,
						  "resultList":{"result":[
						    null,
						    {"id":"2001","source":"PPR","pmcid":"PMC2001","inPMC":"Y","title":"Wrong source",
						     "pubTypeList":{"pubType":["Journal Article"]}},
						    {"id":"2002","source":"MED","pmid":"9999","pmcid":"PMC2002","inPMC":"Y",
						     "title":"Mismatched PMID","pubTypeList":{"pubType":["Journal Article"]}},
						    {"id":"2003","source":"MED","pmcid":"PMC2003","inPMC":"Y","title":"Wrong type",
						     "pubTypeList":{"pubType":["Editorial"]}},
						    {"id":"2004","source":"MED","pmcid":"PMC2004","inPMC":"Y","title":"  ",
						     "pubTypeList":{"pubType":["Journal Article"]}},
						    {"id":"2005","source":"MED","pmcid":"pmc2005","inPMC":"Y","title":"Sparse article",
						     "pubYear":"20x4","dateOfRevision":"not-a-date",
						     "pubTypeList":{"pubType":["research-article"]},
						     "futureField":{"ignored":true}}
						  ]}
						}
						""", MediaType.APPLICATION_JSON));

		ProviderSearchResult result = harness.provider().search(query(Set.of(), 25, "*"));

		assertThat(result.totalMatches()).isEqualTo(6);
		assertThat(result.records()).singleElement().satisfies(record -> {
			assertThat(record.providerRecordId()).isEqualTo("MED:2005");
			assertThat(record.title()).isEqualTo("Sparse article");
			assertThat(record.doi()).isNull();
			assertThat(record.publicationYear()).isNull();
			assertThat(record.providerUpdatedAt()).isNull();
			assertThat(record.pdfUrl()).isNull();
			assertThat(record.identifiers())
					.extracting(identifier -> identifier.type())
					.containsExactly(PaperIdentifierType.PMID, PaperIdentifierType.PMCID);
		});
		harness.server().verify();
	}

	@Test
	void preservesEmptyResultsAndRejectsIncompleteOrOversizedResponses() {
		Harness emptyHarness = harness();
		emptyHarness.server().expect(once(), request -> assertThat(request.getURI().getPath())
				.isEqualTo("/europepmc/webservices/rest/search"))
				.andRespond(withSuccess(
						"{\"hitCount\":0,\"resultList\":{\"result\":[]},\"futureField\":true}",
						MediaType.APPLICATION_JSON));
		assertThat(emptyHarness.provider().search(query(Set.of(), 25, "*")).records()).isEmpty();
		emptyHarness.server().verify();

		Harness incompleteHarness = harness();
		incompleteHarness.server().expect(once(), request -> assertThat(request.getURI().getPath())
				.isEqualTo("/europepmc/webservices/rest/search"))
				.andRespond(withSuccess("{\"hitCount\":0,\"resultList\":null}", MediaType.APPLICATION_JSON));
		assertThatThrownBy(() -> incompleteHarness.provider().search(query(Set.of(), 25, "*")))
				.isInstanceOfSatisfying(ProviderException.class, exception -> {
					assertThat(exception.errorCode()).isEqualTo(EuropePmcResearchProvider.RESPONSE_ERROR);
					assertThat(exception.retryable()).isFalse();
				});
		incompleteHarness.server().verify();

		String body = "{\"hitCount\":0,\"resultList\":{\"result\":[]}}";
		Harness oversizedHarness = harness(body.getBytes(StandardCharsets.UTF_8).length - 1);
		oversizedHarness.server().expect(once(), request -> assertThat(request.getURI().getPath())
				.isEqualTo("/europepmc/webservices/rest/search"))
				.andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
		assertThatThrownBy(() -> oversizedHarness.provider().search(query(Set.of(), 25, "*")))
				.isInstanceOfSatisfying(ProviderException.class, exception -> {
					assertThat(exception.errorCode()).isEqualTo(EuropePmcResearchProvider.RESPONSE_TOO_LARGE);
					assertThat(exception.retryable()).isFalse();
				});
		oversizedHarness.server().verify();
	}

	@Test
	void translatesRateLimitsServerErrorsClientErrorsAndTimeouts() {
		Harness rateLimited = harness();
		rateLimited.server().expect(once(), request -> assertThat(request.getURI().getPath())
				.isEqualTo("/europepmc/webservices/rest/search"))
				.andRespond(MockRestResponseCreators.withStatus(HttpStatus.TOO_MANY_REQUESTS)
						.header(HttpHeaders.RETRY_AFTER, "120"));
		assertThatThrownBy(() -> rateLimited.provider().search(query(Set.of(), 25, "*")))
				.isInstanceOfSatisfying(ProviderException.class, exception -> {
					assertThat(exception.provider()).isEqualTo(ProviderId.EUROPE_PMC);
					assertThat(exception.errorCode()).isEqualTo(EuropePmcResearchProvider.RATE_LIMITED);
					assertThat(exception.retryable()).isTrue();
					assertThat(exception.retryAfter()).isEqualTo(Duration.ofMinutes(2));
				});
		rateLimited.server().verify();

		assertStatus(HttpStatus.SERVICE_UNAVAILABLE, EuropePmcResearchProvider.UPSTREAM_ERROR, true);
		assertStatus(HttpStatus.BAD_REQUEST, EuropePmcResearchProvider.REQUEST_REJECTED, false);

		Harness timedOut = harness();
		timedOut.server().expect(once(), request -> assertThat(request.getURI().getPath())
				.isEqualTo("/europepmc/webservices/rest/search"))
				.andRespond(request -> {
					throw new SocketTimeoutException("read timed out");
				});
		assertThatThrownBy(() -> timedOut.provider().search(query(Set.of(), 25, "*")))
				.isInstanceOfSatisfying(ProviderException.class, exception -> {
					assertThat(exception.errorCode()).isEqualTo(EuropePmcResearchProvider.TIMEOUT);
					assertThat(exception.retryable()).isTrue();
				});
		timedOut.server().verify();
	}

	private static void assertStatus(HttpStatus status, String expectedCode, boolean retryable) {
		Harness harness = harness();
		harness.server().expect(once(), request -> assertThat(request.getURI().getPath())
				.isEqualTo("/europepmc/webservices/rest/search"))
				.andRespond(MockRestResponseCreators.withStatus(status));
		assertThatThrownBy(() -> harness.provider().search(query(Set.of(), 25, "*")))
				.isInstanceOfSatisfying(ProviderException.class, exception -> {
					assertThat(exception.errorCode()).isEqualTo(expectedCode);
					assertThat(exception.retryable()).isEqualTo(retryable);
				});
		harness.server().verify();
	}

	private static Harness harness() {
		return harness(8 * 1024 * 1024);
	}

	private static Harness harness(int maxResponseBytes) {
		EuropePmcProperties properties = new EuropePmcProperties(
				true,
				BASE_URL,
				Duration.ofSeconds(2),
				Duration.ofSeconds(5),
				maxResponseBytes);
		RestClient.Builder builder = EuropePmcConfiguration.configure(RestClient.builder(), properties);
		MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
		return new Harness(new EuropePmcResearchProvider(builder.build(), CLOCK), server);
	}

	private static ProviderSearchQuery query(Set<DocumentType> types, int pageSize, String cursor) {
		return new ProviderSearchQuery(
				"agents", null, null, types, false, 0, Set.of(), pageSize, cursor);
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
				  "version":"6.9",
				  "hitCount":151,
				  "nextCursorMark":"AoIIQNext/+=",
				  "nextPageUrl":"https://evil.test/owned-by-upstream",
				  "request":{"queryString":"ignored","resultType":"core","cursorMark":"*","pageSize":50},
				  "resultList":{"result":[{
				    "id":"36946755",
				    "source":"MED",
				    "pmid":"36946755",
				    "pmcid":"pmc7614751",
				    "doi":"https://doi.org/10.1234/EXAMPLE.ARTICLE",
				    "title":" Agentic discovery for medicine ",
				    "authorString":"Researcher A",
				    "authorList":{"author":[
				      {"fullName":"Ada Researcher","authorId":{"type":"ORCID","value":"https://orcid.org/0000-0002-1825-0097"}},
				      {"fullName":"  ","authorId":{"type":"ORCID","value":"0000-0000-0000-0000"}}
				    ]},
				    "journalInfo":{"issue":"2","volume":"8","yearOfPublication":2023,
				      "printPublicationDate":"2023-05-10",
				      "journal":{"title":"Journal of Synthetic Medicine","issn":"1234567X","essn":"2049-3630"}},
				    "pubYear":"2023",
				    "pageInfo":"e694",
				    "abstractText":"<h4>Background</h4>A &amp; B <i>important</i> finding.",
				    "publicationStatus":"ppublish",
				    "language":"ENG",
				    "pubModel":"Print-Electronic",
				    "pubTypeList":{"pubType":["research-article","Journal Article"]},
				    "inPMC":"Y",
				    "isOpenAccess":"Y",
				    "citedByCount":8,
				    "license":"CC BY",
				    "dateOfRevision":"2025-05-30",
				    "firstPublicationDate":"2023-05-08",
				    "electronicPublicationDate":"2023-05-09",
				    "fullTextUrlList":{"fullTextUrl":[
				      {"availability":"Open access","documentStyle":"pdf","url":"https://evil.test/article.pdf"}
				    ]},
				    "futureField":{"ignored":true}
				  }]}
				}
				""";
	}

	private record Harness(EuropePmcResearchProvider provider, MockRestServiceServer server) {
	}
}
