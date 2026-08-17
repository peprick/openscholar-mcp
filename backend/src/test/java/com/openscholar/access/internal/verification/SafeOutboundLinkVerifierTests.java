package com.openscholar.access.internal.verification;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpHeaders;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class SafeOutboundLinkVerifierTests {

	private static final URI PDF_URI = URI.create("https://papers.example/paper.pdf");
	private static final InetAddress PUBLIC_ADDRESS = address("93.184.216.34");

	@AfterEach
	void clearInterruptedFlag() {
		Thread.interrupted();
	}

	@Test
	void acceptsOnlyProviderReturnedCandidates() {
		RecordingClient client = new RecordingClient(response(200));
		SafeOutboundLinkVerifier verifier = verifier(host -> List.of(PUBLIC_ADDRESS), client);

		LinkVerificationResult userInput = verifier.verify(
				new OutboundLinkCandidate(PDF_URI, ProviderLinkType.PDF, LinkProvenance.USER_INPUT));
		LinkVerificationResult unknown = verifier.verify(
				new OutboundLinkCandidate(PDF_URI, ProviderLinkType.PDF, LinkProvenance.UNKNOWN));
		LinkVerificationResult missing = verifier.verify(null);

		assertThat(List.of(userInput.failure(), unknown.failure(), missing.failure()))
				.containsOnly(LinkVerificationFailure.SOURCE_NOT_PROVIDER);
		assertThat(client.requests()).isEmpty();
	}

	@Test
	void rejectsUnsafeUrlShapesBeforeDnsOrHttp() {
		List<String> resolvedHosts = new ArrayList<>();
		DnsResolver resolver = host -> {
			resolvedHosts.add(host);
			return List.of(PUBLIC_ADDRESS);
		};
		RecordingClient client = new RecordingClient(response(200));
		SafeOutboundLinkVerifier verifier = verifier(resolver, client);

		assertFailure(verifier, URI.create("/relative"), LinkVerificationFailure.INVALID_URL);
		assertFailure(verifier, URI.create("http://papers.example/paper"), LinkVerificationFailure.HTTPS_REQUIRED);
		assertFailure(verifier, URI.create("https://user:secret@papers.example/paper"),
				LinkVerificationFailure.CREDENTIALS_NOT_ALLOWED);
		assertFailure(verifier, URI.create("https://papers.example/paper#page=3"),
				LinkVerificationFailure.FRAGMENT_NOT_ALLOWED);
		assertFailure(verifier, URI.create("https://papers.example:8443/paper"),
				LinkVerificationFailure.NON_DEFAULT_PORT_NOT_ALLOWED);

		assertThat(resolvedHosts).isEmpty();
		assertThat(client.requests()).isEmpty();
	}

	@Test
	void allowsHttpOnlyWhenExplicitlyConfigured() {
		URI localDevelopmentUri = URI.create("http://papers.example/paper");
		RecordingClient secureClient = new RecordingClient(response(200));
		SafeOutboundLinkVerifier secureVerifier = verifier(host -> List.of(PUBLIC_ADDRESS), secureClient);

		assertFailure(secureVerifier, localDevelopmentUri, LinkVerificationFailure.HTTPS_REQUIRED);

		LinkVerificationPolicy localPolicy = new LinkVerificationPolicy(
				true, 2, Duration.ofSeconds(1), Duration.ofSeconds(2), 128);
		RecordingClient localClient = new RecordingClient(response(200));
		SafeOutboundLinkVerifier localVerifier = new SafeOutboundLinkVerifier(
				localPolicy, host -> List.of(PUBLIC_ADDRESS), localClient);

		assertThat(localVerifier.verify(OutboundLinkCandidate.providerLandingPage(localDevelopmentUri)).verified()).isTrue();
		assertThat(localClient.requests()).singleElement().extracting(HttpProbeRequest::method).isEqualTo(ProbeMethod.HEAD);
	}

	@Test
	void rejectsEveryResolvedNonPublicAddress() {
		List<String> blockedAddresses = List.of(
				"0.0.0.0",
				"10.1.2.3",
				"100.64.0.1",
				"100.127.255.254",
				"127.0.0.1",
				"169.254.10.20",
				"172.16.0.1",
				"192.168.1.1",
				"224.0.0.1",
				"192.0.2.1",
				"198.51.100.2",
				"203.0.113.3",
				"198.18.0.1",
				"::",
				"::1",
				"fe80::1",
				"fec0::1",
				"fc00::1",
				"fd12:3456::1",
				"ff02::1",
				"2001:db8::1",
				"2001:2::1");

		for (String blockedAddress : blockedAddresses) {
			RecordingClient client = new RecordingClient(response(200));
			SafeOutboundLinkVerifier verifier = verifier(host -> List.of(address(blockedAddress)), client);

			LinkVerificationResult result = verifier.verify(OutboundLinkCandidate.providerPdf(PDF_URI));

			assertThat(result.failure()).as(blockedAddress).isEqualTo(LinkVerificationFailure.ADDRESS_NOT_PUBLIC);
			assertThat(client.requests()).as(blockedAddress).isEmpty();
		}
	}

	@Test
	void rejectsAHostnameWhenAnyDnsAnswerIsNonPublic() {
		RecordingClient client = new RecordingClient(response(200));
		SafeOutboundLinkVerifier verifier = verifier(
				host -> List.of(PUBLIC_ADDRESS, address("127.0.0.1")), client);

		LinkVerificationResult result = verifier.verify(OutboundLinkCandidate.providerPdf(PDF_URI));

		assertThat(result.failure()).isEqualTo(LinkVerificationFailure.ADDRESS_NOT_PUBLIC);
		assertThat(client.requests()).isEmpty();
	}

	@Test
	void resolvesAndValidatesEveryRedirectHop() {
		RecordingClient client = new RecordingClient(
				response(302, Map.of("Location", List.of("https://cdn.example/final"))),
				response(200));
		List<String> resolvedHosts = new ArrayList<>();
		DnsResolver resolver = host -> {
			resolvedHosts.add(host);
			return List.of(PUBLIC_ADDRESS);
		};
		SafeOutboundLinkVerifier verifier = verifier(resolver, client);

		LinkVerificationResult result = verifier.verify(
				OutboundLinkCandidate.providerLandingPage(URI.create("https://papers.example/start")));

		assertThat(result.verified()).isTrue();
		assertThat(result.finalUri()).hasToString("https://cdn.example/final");
		assertThat(result.redirectCount()).isEqualTo(1);
		assertThat(resolvedHosts).containsExactly("papers.example", "cdn.example");
		assertThat(client.requests()).extracting(HttpProbeRequest::method)
				.containsExactly(ProbeMethod.HEAD, ProbeMethod.HEAD);
	}

	@Test
	void rejectsRedirectToPrivateAddressBeforeFollowingIt() {
		RecordingClient client = new RecordingClient(
				response(302, Map.of("Location", List.of("https://internal.example/secret"))));
		DnsResolver resolver = host -> host.equals("internal.example")
				? List.of(address("10.0.0.8"))
				: List.of(PUBLIC_ADDRESS);
		SafeOutboundLinkVerifier verifier = verifier(resolver, client);

		LinkVerificationResult result = verifier.verify(
				OutboundLinkCandidate.providerLandingPage(URI.create("https://papers.example/start")));

		assertThat(result.failure()).isEqualTo(LinkVerificationFailure.ADDRESS_NOT_PUBLIC);
		assertThat(result.redirectCount()).isEqualTo(1);
		assertThat(client.requests()).hasSize(1);
	}

	@Test
	void enforcesTheRedirectCapAndRequiresLocation() {
		LinkVerificationPolicy oneRedirect = new LinkVerificationPolicy(
				false, 1, Duration.ofSeconds(1), Duration.ofSeconds(2), 128);
		RecordingClient cappedClient = new RecordingClient(
				response(302, Map.of("Location", List.of("/second"))),
				response(302, Map.of("Location", List.of("/third"))));
		SafeOutboundLinkVerifier cappedVerifier = new SafeOutboundLinkVerifier(
				oneRedirect, host -> List.of(PUBLIC_ADDRESS), cappedClient);

		LinkVerificationResult capped = cappedVerifier.verify(
				OutboundLinkCandidate.providerLandingPage(URI.create("https://papers.example/first")));

		assertThat(capped.failure()).isEqualTo(LinkVerificationFailure.TOO_MANY_REDIRECTS);
		assertThat(capped.redirectCount()).isEqualTo(1);

		SafeOutboundLinkVerifier missingLocationVerifier = verifier(
				host -> List.of(PUBLIC_ADDRESS), new RecordingClient(response(302)));
		LinkVerificationResult missingLocation = missingLocationVerifier.verify(
				OutboundLinkCandidate.providerLandingPage(URI.create("https://papers.example/start")));
		assertThat(missingLocation.failure()).isEqualTo(LinkVerificationFailure.REDIRECT_LOCATION_MISSING);
	}

	@Test
	void verifiesPdfByMediaTypeOrMagicPrefix() {
		RecordingClient mediaTypeClient = new RecordingClient(response(
				206, Map.of("Content-Type", List.of("Application/PDF; charset=binary")), "not-magic".getBytes()));
		SafeOutboundLinkVerifier mediaTypeVerifier = verifier(host -> List.of(PUBLIC_ADDRESS), mediaTypeClient);

		LinkVerificationResult byMediaType = mediaTypeVerifier.verify(OutboundLinkCandidate.providerPdf(PDF_URI));

		assertThat(byMediaType.verified()).isTrue();
		assertThat(mediaTypeClient.requests()).singleElement().satisfies(request -> {
			assertThat(request.method()).isEqualTo(ProbeMethod.RANGE_GET);
			assertThat(request.maxResponseBytes()).isEqualTo(LinkVerificationPolicy.secureDefaults().maxProbeBytes());
			assertThat(request.timeout()).isEqualTo(LinkVerificationPolicy.secureDefaults().requestTimeout());
		});

		RecordingClient magicClient = new RecordingClient(response(
				200, Map.of("Content-Type", List.of("application/octet-stream")), "%PDF-1.7\n".getBytes()));
		SafeOutboundLinkVerifier magicVerifier = verifier(host -> List.of(PUBLIC_ADDRESS), magicClient);
		assertThat(magicVerifier.verify(OutboundLinkCandidate.providerPdf(PDF_URI)).verified()).isTrue();

		RecordingClient invalidClient = new RecordingClient(response(
				200, Map.of("Content-Type", List.of("text/html")), "<html>login</html>".getBytes()));
		SafeOutboundLinkVerifier invalidVerifier = verifier(host -> List.of(PUBLIC_ADDRESS), invalidClient);
		assertThat(invalidVerifier.verify(OutboundLinkCandidate.providerPdf(PDF_URI)).failure())
				.isEqualTo(LinkVerificationFailure.PDF_CONTENT_NOT_VERIFIED);
	}

	@Test
	void fallsBackFromHeadToBoundedRangeGetOnlyWhenHeadIsUnsupported() {
		RecordingClient client = new RecordingClient(response(405), response(200));
		SafeOutboundLinkVerifier verifier = verifier(host -> List.of(PUBLIC_ADDRESS), client);

		LinkVerificationResult result = verifier.verify(
				OutboundLinkCandidate.providerLandingPage(URI.create("https://papers.example/article")));

		assertThat(result.verified()).isTrue();
		assertThat(client.requests()).satisfiesExactly(
				request -> {
					assertThat(request.method()).isEqualTo(ProbeMethod.HEAD);
					assertThat(request.maxResponseBytes()).isZero();
				},
				request -> {
					assertThat(request.method()).isEqualTo(ProbeMethod.RANGE_GET);
					assertThat(request.maxResponseBytes()).isEqualTo(LinkVerificationPolicy.secureDefaults().maxProbeBytes());
				});
	}

	@Test
	void returnsTypedFailuresWithoutLeakingNetworkExceptions() {
		SafeOutboundLinkVerifier dnsFailureVerifier = verifier(host -> {
			throw new UnknownHostException("secret resolver detail");
		}, request -> response(200));

		LinkVerificationResult dnsFailure = dnsFailureVerifier.verify(OutboundLinkCandidate.providerPdf(PDF_URI));

		assertThat(dnsFailure.failure()).isEqualTo(LinkVerificationFailure.DNS_RESOLUTION_FAILED);

		SafeOutboundLinkVerifier networkFailureVerifier = verifier(
				host -> List.of(PUBLIC_ADDRESS), request -> {
					throw new IOException("secret upstream detail");
				});
		LinkVerificationResult networkFailure = networkFailureVerifier.verify(OutboundLinkCandidate.providerPdf(PDF_URI));
		assertThat(networkFailure.failure()).isEqualTo(LinkVerificationFailure.NETWORK_ERROR);

		SafeOutboundLinkVerifier interruptedVerifier = verifier(
				host -> List.of(PUBLIC_ADDRESS), request -> {
					throw new InterruptedException("secret thread detail");
				});
		LinkVerificationResult interrupted = interruptedVerifier.verify(OutboundLinkCandidate.providerPdf(PDF_URI));
		assertThat(interrupted.failure()).isEqualTo(LinkVerificationFailure.REQUEST_INTERRUPTED);
		assertThat(Thread.currentThread().isInterrupted()).isTrue();
	}

	private static SafeOutboundLinkVerifier verifier(DnsResolver resolver, HttpProbeClient client) {
		return new SafeOutboundLinkVerifier(LinkVerificationPolicy.secureDefaults(), resolver, client);
	}

	private static void assertFailure(
			SafeOutboundLinkVerifier verifier, URI uri, LinkVerificationFailure expectedFailure) {
		LinkVerificationResult result = verifier.verify(OutboundLinkCandidate.providerLandingPage(uri));
		assertThat(result.failure()).isEqualTo(expectedFailure);
		assertThat(result.verified()).isFalse();
	}

	private static HttpProbeResponse response(int status) {
		return response(status, Map.of(), new byte[0]);
	}

	private static HttpProbeResponse response(int status, Map<String, List<String>> headers) {
		return response(status, headers, new byte[0]);
	}

	private static HttpProbeResponse response(int status, Map<String, List<String>> headers, byte[] body) {
		return new HttpProbeResponse(status, HttpHeaders.of(headers, (name, value) -> true), body);
	}

	private static InetAddress address(String value) {
		try {
			return InetAddress.getByName(value);
		}
		catch (UnknownHostException exception) {
			throw new IllegalArgumentException("Invalid test address", exception);
		}
	}

	private static final class RecordingClient implements HttpProbeClient {

		private final Deque<HttpProbeResponse> responses = new ArrayDeque<>();
		private final List<HttpProbeRequest> requests = new ArrayList<>();

		private RecordingClient(HttpProbeResponse... responses) {
			this.responses.addAll(List.of(responses));
		}

		@Override
		public HttpProbeResponse exchange(HttpProbeRequest request) {
			requests.add(request);
			if (responses.isEmpty()) {
				throw new IllegalStateException("No response prepared");
			}
			return responses.removeFirst();
		}

		List<HttpProbeRequest> requests() {
			return List.copyOf(requests);
		}
	}
}
