package com.openscholar.access.internal.provider.unpaywall;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.http.HttpMethod.GET;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import com.openscholar.access.internal.provider.AccessEvidenceLookup;
import com.openscholar.access.internal.provider.AccessProviderException;
import com.openscholar.access.internal.provider.AccessResolutionStatus;
import com.openscholar.access.internal.provider.AccessSource;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.client.response.MockRestResponseCreators;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

class UnpaywallAccessEvidenceProviderTests {

	private static final URI BASE_URL = URI.create("https://api.unpaywall.test");
	private static final Instant NOW = Instant.parse("2026-08-17T06:30:00Z");
	private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

	@Test
	void mapsEveryUsableNonEmbargoedLocationBestFirstWithoutGuessingPdfUrls() {
		Harness harness = harness("backend@example.test");
		harness.server().expect(request -> {
			var components = UriComponentsBuilder.fromUri(request.getURI()).build();
			assertThat(URLDecoder.decode(components.getPath(), StandardCharsets.UTF_8))
					.isEqualTo("/v2/10.1000/example.doi");
			assertThat(decoded(components.getQueryParams().getFirst("email"))).isEqualTo("backend@example.test");
			assertThat(components.getQueryParams()).containsOnlyKeys("email");
		})
				.andExpect(method(GET))
				.andRespond(withSuccess(openResponse(), MediaType.APPLICATION_JSON));

		var result = harness.provider().resolve(new AccessEvidenceLookup("doi:10.1000/Example.DOI", null));

		assertThat(result.source()).isEqualTo(AccessSource.UNPAYWALL);
		assertThat(result.status()).isEqualTo(AccessResolutionStatus.RESOLVED);
		assertThat(result.retrievedAt()).isEqualTo(NOW);
		assertThat(result.evidence())
				.containsEntry("oaStatus", "green")
				.doesNotContainValue("backend@example.test");
		assertThat(result.candidates()).satisfiesExactly(
				best -> {
					assertThat(best.best()).isTrue();
					assertThat(best.sourceKey()).startsWith("unpaywall:").hasSize(74);
					assertThat(best.hostType()).isEqualTo("repository");
					assertThat(best.version()).isEqualTo("acceptedVersion");
					assertThat(best.license()).isEqualTo("cc-by");
					assertThat(best.landingPageUrl()).hasToString("https://repository.example/item/123");
					assertThat(best.pdfUrl()).hasToString("https://repository.example/item/123.pdf");
					assertThat(best.providerUpdatedAt()).isEqualTo(Instant.parse("2026-08-16T09:00:00Z"));
					assertThat(best.evidence()).containsEntry("endpointId", "repo-endpoint");
				},
				publisher -> {
					assertThat(publisher.best()).isFalse();
					assertThat(publisher.landingPageUrl()).hasToString("https://publisher.example/article.pdf");
					assertThat(publisher.pdfUrl()).as("generic url must never be promoted to PDF").isNull();
				});
		harness.server().verify();
	}

	@Test
	void capsLocationsAtTwentyAfterPromotingTheBestLocation() {
		Harness harness = harness("backend@example.test");
		harness.server().expect(request -> assertThat(request.getURI().getPath()).contains("10.1000"))
				.andRespond(withSuccess(responseWithTwentyTwoLocations(), MediaType.APPLICATION_JSON));

		var result = harness.provider().resolve(new AccessEvidenceLookup("10.1000/bounded", null));

		assertThat(result.candidates()).hasSize(20);
		assertThat(result.candidates().getFirst().best()).isTrue();
		assertThat(result.candidates().getFirst().landingPageUrl()).hasToString("https://repo.example/21");
		harness.server().verify();
	}

	@Test
	void distinguishesClosedWorksNoRecordsAndMissingConfiguration() {
		Harness closed = harness("backend@example.test");
		closed.server().expect(request -> assertThat(request.getURI().getPath()).contains("10.1000"))
				.andRespond(withSuccess("""
						{"doi":"10.1000/closed","is_oa":false,"oa_status":"closed","unknown":true}
						""", MediaType.APPLICATION_JSON));

		assertThat(closed.provider().resolve(new AccessEvidenceLookup("10.1000/closed", null)).status())
				.isEqualTo(AccessResolutionStatus.CLOSED);
		closed.server().verify();

		Harness absent = harness("backend@example.test");
		absent.server().expect(request -> assertThat(request.getURI().getPath()).contains("10.1000"))
				.andRespond(MockRestResponseCreators.withStatus(HttpStatus.NOT_FOUND));
		assertThat(absent.provider().resolve(new AccessEvidenceLookup("10.1000/absent", null)).status())
				.isEqualTo(AccessResolutionStatus.NO_RECORD);
		absent.server().verify();

		Harness unconfigured = harness(null);
		var notConfigured = unconfigured.provider().resolve(new AccessEvidenceLookup("10.1000/paper", null));
		assertThat(notConfigured.status()).isEqualTo(AccessResolutionStatus.NOT_CONFIGURED);
		assertThat(notConfigured.evidence()).containsEntry("reason", "backend_email_missing");
		unconfigured.server().verify();
	}

	@Test
	void isNotApplicableWithoutADoi() {
		Harness harness = harness("backend@example.test");

		var result = harness.provider().resolve(new AccessEvidenceLookup(null, "2401.12345"));

		assertThat(result.status()).isEqualTo(AccessResolutionStatus.NOT_APPLICABLE);
		harness.server().verify();
	}

	@Test
	void rejectsMismatchedAndInconsistentSuccessfulResponses() {
		Harness mismatch = harness("backend@example.test");
		mismatch.server().expect(request -> assertThat(request.getURI().getPath()).contains("10.1000"))
				.andRespond(withSuccess("{" +
						"\"doi\":\"10.1000/other\",\"is_oa\":false}", MediaType.APPLICATION_JSON));

		assertThatThrownBy(() -> mismatch.provider().resolve(new AccessEvidenceLookup("10.1000/requested", null)))
				.isInstanceOfSatisfying(AccessProviderException.class, failure -> {
					assertThat(failure.errorCode()).isEqualTo(UnpaywallAccessEvidenceProvider.RESPONSE_ERROR);
					assertThat(failure.retryable()).isTrue();
				});
		mismatch.server().verify();

		Harness inconsistent = harness("backend@example.test");
		inconsistent.server().expect(request -> assertThat(request.getURI().getPath()).contains("10.1000"))
				.andRespond(withSuccess("""
						{"doi":"10.1000/inconsistent","is_oa":true,"oa_locations":[]}
						""", MediaType.APPLICATION_JSON));
		assertThatThrownBy(() -> inconsistent.provider().resolve(new AccessEvidenceLookup("10.1000/inconsistent", null)))
				.isInstanceOfSatisfying(AccessProviderException.class, failure -> {
					assertThat(failure.errorCode()).isEqualTo(UnpaywallAccessEvidenceProvider.RESPONSE_ERROR);
					assertThat(failure.retryable()).isTrue();
				});
		inconsistent.server().verify();
	}

	@Test
	void translatesRetryableStatusesWithoutLeakingTheBackendEmail() {
		Harness harness = harness("private-backend@example.test");
		harness.server().expect(request -> assertThat(request.getURI().getPath()).contains("10.1000"))
				.andRespond(MockRestResponseCreators.withStatus(HttpStatus.TOO_MANY_REQUESTS)
						.header(HttpHeaders.RETRY_AFTER, "90"));

		assertThatThrownBy(() -> harness.provider().resolve(new AccessEvidenceLookup("10.1000/rate", null)))
				.isInstanceOfSatisfying(AccessProviderException.class, failure -> {
					assertThat(failure.source()).isEqualTo(AccessSource.UNPAYWALL);
					assertThat(failure.errorCode()).isEqualTo(UnpaywallAccessEvidenceProvider.RATE_LIMITED);
					assertThat(failure.retryable()).isTrue();
					assertThat(failure.retryAfter()).isEqualTo(Duration.ofSeconds(90));
					assertThat(failure.getMessage()).doesNotContain("private-backend@example.test");
				});
		harness.server().verify();
	}

	private static Harness harness(String email) {
		RestClient.Builder builder = RestClient.builder().baseUrl(BASE_URL);
		MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
		UnpaywallProperties properties = new UnpaywallProperties(
				BASE_URL, Duration.ofSeconds(2), Duration.ofSeconds(5), email);
		return new Harness(new UnpaywallAccessEvidenceProvider(builder.build(), properties, CLOCK), server);
	}

	private static String decoded(String value) {
		return value == null ? null : URLDecoder.decode(value, StandardCharsets.UTF_8);
	}

	private static String openResponse() {
		return """
				{
				  "doi": "10.1000/EXAMPLE.DOI",
				  "is_oa": true,
				  "oa_status": "green",
				  "updated": "2026-08-16T10:00:00Z",
				  "data_standard": 2,
				  "best_oa_location": {
				    "endpoint_id": "repo-endpoint",
				    "pmh_id": "oai:repo:123",
				    "url_for_landing_page": "https://repository.example/item/123",
				    "url_for_pdf": "https://repository.example/item/123.pdf"
				  },
				  "oa_locations": [
				    {
				      "endpoint_id": "publisher-endpoint",
				      "host_type": "publisher",
				      "url": "https://publisher.example/article.pdf",
				      "url_for_pdf": null,
				      "future_field": "ignored"
				    },
				    {
				      "endpoint_id": "repo-endpoint",
				      "pmh_id": "oai:repo:123",
				      "evidence": "oa repository copy",
				      "host_type": "repository",
				      "license": "cc-by",
				      "repository_institution": "Example University",
				      "updated": "2026-08-16T09:00:00Z",
				      "url_for_landing_page": "https://repository.example/item/123",
				      "url_for_pdf": "https://repository.example/item/123.pdf",
				      "version": "acceptedVersion"
				    }
				  ],
				  "oa_locations_embargoed": [{"url_for_pdf":"https://embargoed.example/secret.pdf"}],
				  "new_top_level_field": {"ignored": true}
				}
				""";
	}

	private static String responseWithTwentyTwoLocations() {
		StringBuilder locations = new StringBuilder();
		for (int index = 0; index < 22; index++) {
			if (index > 0) {
				locations.append(',');
			}
			locations.append("{\"endpoint_id\":\"endpoint-")
					.append(index)
					.append("\",\"url_for_landing_page\":\"https://repo.example/")
					.append(index)
					.append("\"}");
		}
		return "{" +
				"\"doi\":\"10.1000/bounded\"," +
				"\"is_oa\":true," +
				"\"best_oa_location\":{\"endpoint_id\":\"endpoint-21\","
				+ "\"url_for_landing_page\":\"https://repo.example/21\"}," +
				"\"oa_locations\":[" + locations + "]}";
	}

	private record Harness(UnpaywallAccessEvidenceProvider provider, MockRestServiceServer server) {
	}
}
