package com.openscholar.access.internal.provider.arxiv;

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
import java.util.concurrent.atomic.AtomicLong;

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

class ArxivAccessEvidenceProviderTests {

	private static final URI BASE_URL = URI.create("https://export.arxiv.test/api");
	private static final Instant NOW = Instant.parse("2026-08-17T06:30:00Z");
	private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

	@Test
	void performsExactIdLookupAndMapsTrustedHttpsUrls() {
		Harness harness = harness();
		harness.server().expect(request -> {
			var components = UriComponentsBuilder.fromUri(request.getURI()).build();
			assertThat(components.getPath()).isEqualTo("/api/query");
			assertThat(decoded(components.getQueryParams().getFirst("id_list"))).isEqualTo("2401.12345");
			assertThat(decoded(components.getQueryParams().getFirst("max_results"))).isEqualTo("1");
			assertThat(components.getQueryParams()).containsOnlyKeys("id_list", "max_results");
		})
				.andExpect(method(GET))
				.andRespond(withSuccess(completeFeed(), MediaType.APPLICATION_ATOM_XML));

		var result = harness.provider().resolve(new AccessEvidenceLookup(null, "arXiv:2401.12345"));

		assertThat(result.source()).isEqualTo(AccessSource.ARXIV);
		assertThat(result.status()).isEqualTo(AccessResolutionStatus.RESOLVED);
		assertThat(result.retrievedAt()).isEqualTo(NOW);
		assertThat(result.evidence())
				.containsEntry("requestedArxivId", "2401.12345")
				.containsEntry("returnedArxivId", "2401.12345v2");
		assertThat(result.candidates()).singleElement().satisfies(candidate -> {
			assertThat(candidate.sourceKey()).isEqualTo("arxiv:2401.12345v2");
			assertThat(candidate.best()).isTrue();
			assertThat(candidate.hostType()).isEqualTo("preprint_server");
			assertThat(candidate.version()).isEqualTo("preprint");
			assertThat(candidate.license()).isEqualTo("http://creativecommons.org/licenses/by/4.0/");
			assertThat(candidate.landingPageUrl()).hasToString("https://arxiv.org/abs/2401.12345v2");
			assertThat(candidate.pdfUrl()).hasToString("https://arxiv.org/pdf/2401.12345v2");
			assertThat(candidate.providerUpdatedAt()).isEqualTo(Instant.parse("2026-08-16T09:00:00Z"));
			assertThat(candidate.evidence())
					.containsEntry("arxivId", "2401.12345v2")
					.containsEntry("arxivVersion", "v2");
		});
		harness.server().verify();
	}

	@Test
	void supportsLegacyIdentifiersAndRejectsForeignOrMismatchedLinks() {
		Harness harness = harness();
		harness.server().expect(request -> assertThat(decoded(
				UriComponentsBuilder.fromUri(request.getURI()).build().getQueryParams().getFirst("id_list")))
				.isEqualTo("hep-th/9901001"))
				.andRespond(withSuccess("""
						<?xml version="1.0"?>
						<feed xmlns="http://www.w3.org/2005/Atom">
						  <entry>
						    <id>http://arxiv.org/abs/hep-th/9901001v3</id>
						    <title>Legacy paper</title>
						    <updated>2026-08-16T09:00:00Z</updated>
						    <link rel="alternate" type="text/html" href="https://evil.example/abs/hep-th/9901001v3"/>
						    <link title="pdf" type="application/pdf" href="https://arxiv.org/pdf/hep-th/0000001"/>
						  </entry>
						</feed>
						""", MediaType.APPLICATION_ATOM_XML));

		var result = harness.provider().resolve(new AccessEvidenceLookup(null, "hep-th/9901001"));

		assertThat(result.candidates()).singleElement().satisfies(candidate -> {
			assertThat(candidate.landingPageUrl()).hasToString("https://arxiv.org/abs/hep-th/9901001v3");
			assertThat(candidate.pdfUrl()).isNull();
		});
		harness.server().verify();
	}

	@Test
	void distinguishesNotApplicableEmptyFeedsAndHttpNotFound() {
		Harness notApplicable = harness();
		assertThat(notApplicable.provider().resolve(new AccessEvidenceLookup("10.1000/example", null)).status())
				.isEqualTo(AccessResolutionStatus.NOT_APPLICABLE);
		notApplicable.server().verify();

		Harness empty = harness();
		empty.server().expect(request -> assertThat(request.getURI().getPath()).isEqualTo("/api/query"))
				.andRespond(withSuccess("""
						<?xml version="1.0"?>
						<feed xmlns="http://www.w3.org/2005/Atom">
						  <title>arXiv Query: search results</title>
						</feed>
						""", MediaType.APPLICATION_ATOM_XML));
		assertThat(empty.provider().resolve(new AccessEvidenceLookup(null, "2401.12345")).status())
				.isEqualTo(AccessResolutionStatus.NO_RECORD);
		empty.server().verify();

		Harness absent = harness();
		absent.server().expect(request -> assertThat(request.getURI().getPath()).isEqualTo("/api/query"))
				.andRespond(MockRestResponseCreators.withStatus(HttpStatus.NOT_FOUND));
		assertThat(absent.provider().resolve(new AccessEvidenceLookup(null, "2401.12345")).status())
				.isEqualTo(AccessResolutionStatus.NO_RECORD);
		absent.server().verify();
	}

	@Test
	void rejectsErrorFeedsAndMismatchedEntries() {
		Harness error = harness();
		error.server().expect(request -> assertThat(request.getURI().getPath()).isEqualTo("/api/query"))
				.andRespond(withSuccess("""
						<feed xmlns="http://www.w3.org/2005/Atom">
						  <entry><id>http://arxiv.org/api/errors#bad-id</id><title>Error</title></entry>
						</feed>
						""", MediaType.APPLICATION_ATOM_XML));
		assertThatThrownBy(() -> error.provider().resolve(new AccessEvidenceLookup(null, "2401.12345")))
				.isInstanceOfSatisfying(AccessProviderException.class, failure -> {
					assertThat(failure.errorCode()).isEqualTo(ArxivAccessEvidenceProvider.REQUEST_REJECTED);
					assertThat(failure.retryable()).isFalse();
				});
		error.server().verify();

		Harness mismatch = harness();
		mismatch.server().expect(request -> assertThat(request.getURI().getPath()).isEqualTo("/api/query"))
				.andRespond(withSuccess(feedFor("2401.99999v1"), MediaType.APPLICATION_ATOM_XML));
		assertThatThrownBy(() -> mismatch.provider().resolve(new AccessEvidenceLookup(null, "2401.12345")))
				.isInstanceOfSatisfying(AccessProviderException.class, failure -> {
					assertThat(failure.errorCode()).isEqualTo(ArxivAccessEvidenceProvider.RESPONSE_ERROR);
					assertThat(failure.retryable()).isTrue();
				});
		mismatch.server().verify();
	}

	@Test
	void disablesDoctypeAndExternalEntityResolution() {
		Harness harness = harness();
		harness.server().expect(request -> assertThat(request.getURI().getPath()).isEqualTo("/api/query"))
				.andRespond(withSuccess("""
						<?xml version="1.0"?>
						<!DOCTYPE feed [<!ENTITY xxe SYSTEM "file:///etc/passwd">]>
						<feed xmlns="http://www.w3.org/2005/Atom">
						  <entry><id>http://arxiv.org/abs/2401.12345</id><title>&xxe;</title></entry>
						</feed>
						""", MediaType.APPLICATION_ATOM_XML));

		assertThatThrownBy(() -> harness.provider().resolve(new AccessEvidenceLookup(null, "2401.12345")))
				.isInstanceOfSatisfying(AccessProviderException.class, failure -> {
					assertThat(failure.errorCode()).isEqualTo(ArxivAccessEvidenceProvider.RESPONSE_ERROR);
					assertThat(failure.retryable()).isTrue();
				});
		harness.server().verify();
	}

	@Test
	void translatesRetryableStatusesAndRetryAfter() {
		Harness harness = harness();
		harness.server().expect(request -> assertThat(request.getURI().getPath()).isEqualTo("/api/query"))
				.andRespond(MockRestResponseCreators.withStatus(HttpStatus.SERVICE_UNAVAILABLE)
						.header(HttpHeaders.RETRY_AFTER, "120"));

		assertThatThrownBy(() -> harness.provider().resolve(new AccessEvidenceLookup(null, "2401.12345")))
				.isInstanceOfSatisfying(AccessProviderException.class, failure -> {
					assertThat(failure.source()).isEqualTo(AccessSource.ARXIV);
					assertThat(failure.errorCode()).isEqualTo(ArxivAccessEvidenceProvider.UPSTREAM_ERROR);
					assertThat(failure.retryable()).isTrue();
					assertThat(failure.retryAfter()).isEqualTo(Duration.ofMinutes(2));
				});
		harness.server().verify();
	}

	@Test
	void enforcesProductionDelayButOffersADeterministicZeroDelayTestSeam() {
		assertThatThrownBy(() -> new ArxivProperties(
				BASE_URL, Duration.ofSeconds(2), Duration.ofSeconds(5), Duration.ZERO))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("at least 3 seconds");

		AtomicLong nanoTime = new AtomicLong(100);
		AtomicLong slept = new AtomicLong();
		ArxivRateGate gate = new ArxivRateGate(
				Duration.ofSeconds(3),
				nanoTime::get,
				nanoseconds -> {
					slept.addAndGet(nanoseconds);
					nanoTime.addAndGet(nanoseconds);
				});
		try {
			gate.acquire();
			gate.acquire();
		}
		catch (InterruptedException impossible) {
			throw new AssertionError(impossible);
		}

		assertThat(slept).hasValue(Duration.ofSeconds(3).toNanos());
	}

	private static Harness harness() {
		RestClient.Builder builder = RestClient.builder().baseUrl(BASE_URL);
		MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
		return new Harness(
				new ArxivAccessEvidenceProvider(builder.build(), CLOCK, Duration.ZERO),
				server);
	}

	private static String decoded(String value) {
		return value == null ? null : URLDecoder.decode(value, StandardCharsets.UTF_8);
	}

	private static String completeFeed() {
		return """
				<?xml version="1.0" encoding="UTF-8"?>
				<feed xmlns="http://www.w3.org/2005/Atom" xmlns:arxiv="http://arxiv.org/schemas/atom">
				  <title>arXiv Query: search results</title>
				  <entry>
				    <id>http://arxiv.org/abs/2401.12345v2</id>
				    <updated>2026-08-16T09:00:00Z</updated>
				    <published>2024-01-20T00:00:00Z</published>
				    <title>Research agents</title>
				    <arxiv:license>http://creativecommons.org/licenses/by/4.0/</arxiv:license>
				    <link href="http://arxiv.org/abs/2401.12345v2" rel="alternate" type="text/html"/>
				    <link title="pdf" href="http://www.arxiv.org/pdf/2401.12345v2" rel="related" type="application/pdf"/>
				  </entry>
				</feed>
				""";
	}

	private static String feedFor(String id) {
		return """
				<feed xmlns="http://www.w3.org/2005/Atom">
				  <entry>
				    <id>http://arxiv.org/abs/%s</id>
				    <title>Paper</title>
				    <link href="http://arxiv.org/abs/%s" rel="alternate" type="text/html"/>
				  </entry>
				</feed>
				""".formatted(id, id);
	}

	private record Harness(ArxivAccessEvidenceProvider provider, MockRestServiceServer server) {
	}
}
