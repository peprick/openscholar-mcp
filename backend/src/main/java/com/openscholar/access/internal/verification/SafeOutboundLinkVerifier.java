package com.openscholar.access.internal.verification;

import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.net.http.HttpHeaders;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

final class SafeOutboundLinkVerifier implements ProviderLinkVerifier {

	private static final byte[] PDF_SIGNATURE = "%PDF-".getBytes(StandardCharsets.US_ASCII);

	private final LinkVerificationPolicy policy;
	private final DnsResolver dnsResolver;
	private final HttpProbeClient httpClient;

	SafeOutboundLinkVerifier(
			LinkVerificationPolicy policy,
			DnsResolver dnsResolver,
			HttpProbeClient httpClient) {
		this.policy = Objects.requireNonNull(policy, "policy");
		this.dnsResolver = Objects.requireNonNull(dnsResolver, "dnsResolver");
		this.httpClient = Objects.requireNonNull(httpClient, "httpClient");
	}

	@Override
	public LinkVerificationResult verify(URI uri, ProviderLinkType type) {
		return verify(new OutboundLinkCandidate(uri, type, LinkProvenance.PROVIDER));
	}

	LinkVerificationResult verify(OutboundLinkCandidate candidate) {
		if (candidate == null || candidate.provenance() != LinkProvenance.PROVIDER) {
			return LinkVerificationResult.rejected(candidate == null ? null : candidate.uri(),
					LinkVerificationFailure.SOURCE_NOT_PROVIDER);
		}
		if (candidate.kind() == null) {
			return LinkVerificationResult.rejected(candidate.uri(), LinkVerificationFailure.INVALID_URL);
		}

		URI current = candidate.uri();
		int redirects = 0;
		while (true) {
			LinkVerificationFailure uriFailure = validateUri(current);
			if (uriFailure != LinkVerificationFailure.NONE) {
				return LinkVerificationResult.rejected(current, redirects, -1, uriFailure);
			}

			LinkVerificationFailure addressFailure = validateResolvedAddresses(current);
			if (addressFailure != LinkVerificationFailure.NONE) {
				return LinkVerificationResult.rejected(current, redirects, -1, addressFailure);
			}

			HttpProbeResponse response;
			try {
				response = probe(current, candidate.kind());
			}
			catch (InterruptedException exception) {
				Thread.currentThread().interrupt();
				return LinkVerificationResult.rejected(
						current, redirects, -1, LinkVerificationFailure.REQUEST_INTERRUPTED);
			}
			catch (IOException | RuntimeException exception) {
				return LinkVerificationResult.rejected(current, redirects, -1, LinkVerificationFailure.NETWORK_ERROR);
			}

			if (isRedirect(response.statusCode())) {
				if (redirects >= policy.maxRedirects()) {
					return LinkVerificationResult.rejected(
							current, redirects, response.statusCode(), LinkVerificationFailure.TOO_MANY_REDIRECTS);
				}
				Optional<String> location = response.headers().firstValue("Location");
				if (location.isEmpty() || location.orElseThrow().isBlank()) {
					return LinkVerificationResult.rejected(
							current, redirects, response.statusCode(), LinkVerificationFailure.REDIRECT_LOCATION_MISSING);
				}
				try {
					current = resolveRedirect(current, location.orElseThrow());
				}
				catch (IllegalArgumentException | URISyntaxException exception) {
					return LinkVerificationResult.rejected(
							current, redirects, response.statusCode(), LinkVerificationFailure.INVALID_REDIRECT);
				}
				redirects++;
				continue;
			}

			if (!isSuccessful(response.statusCode())) {
				return LinkVerificationResult.rejected(
						current, redirects, response.statusCode(), LinkVerificationFailure.HTTP_STATUS_REJECTED);
			}
			if (candidate.kind() == ProviderLinkType.PDF && !isPdf(response)) {
				return LinkVerificationResult.rejected(
						current, redirects, response.statusCode(), LinkVerificationFailure.PDF_CONTENT_NOT_VERIFIED);
			}
			return LinkVerificationResult.verified(current, redirects, response.statusCode());
		}
	}

	private HttpProbeResponse probe(URI uri, ProviderLinkType kind) throws IOException, InterruptedException {
		if (kind == ProviderLinkType.PDF) {
			return httpClient.exchange(new HttpProbeRequest(
					uri,
					ProbeMethod.RANGE_GET,
					policy.requestTimeout(),
					policy.maxProbeBytes(),
					"application/pdf,application/octet-stream;q=0.8,*/*;q=0.1"));
		}

		HttpProbeResponse head = httpClient.exchange(new HttpProbeRequest(
				uri, ProbeMethod.HEAD, policy.requestTimeout(), 0, "text/html,*/*;q=0.1"));
		if (head.statusCode() != 405 && head.statusCode() != 501) {
			return head;
		}
		return httpClient.exchange(new HttpProbeRequest(
				uri, ProbeMethod.RANGE_GET, policy.requestTimeout(), policy.maxProbeBytes(), "text/html,*/*;q=0.1"));
	}

	private LinkVerificationFailure validateUri(URI uri) {
		if (uri == null || !uri.isAbsolute() || uri.getHost() == null || uri.getHost().isBlank()) {
			return LinkVerificationFailure.INVALID_URL;
		}
		String scheme = uri.getScheme().toLowerCase(Locale.ROOT);
		if (!scheme.equals("https") && !(policy.allowHttp() && scheme.equals("http"))) {
			return LinkVerificationFailure.HTTPS_REQUIRED;
		}
		if (uri.getRawUserInfo() != null) {
			return LinkVerificationFailure.CREDENTIALS_NOT_ALLOWED;
		}
		if (uri.getRawFragment() != null) {
			return LinkVerificationFailure.FRAGMENT_NOT_ALLOWED;
		}
		int port = uri.getPort();
		int defaultPort = scheme.equals("https") ? 443 : 80;
		if (port != -1 && port != defaultPort) {
			return LinkVerificationFailure.NON_DEFAULT_PORT_NOT_ALLOWED;
		}
		return LinkVerificationFailure.NONE;
	}

	private LinkVerificationFailure validateResolvedAddresses(URI uri) {
		List<InetAddress> addresses;
		try {
			addresses = dnsResolver.resolve(stripIpv6Brackets(uri.getHost()));
		}
		catch (UnknownHostException | RuntimeException exception) {
			return LinkVerificationFailure.DNS_RESOLUTION_FAILED;
		}
		if (addresses == null || addresses.isEmpty()) {
			return LinkVerificationFailure.DNS_RESOLUTION_FAILED;
		}
		return addresses.stream().allMatch(PublicAddressPolicy::isPublic)
				? LinkVerificationFailure.NONE
				: LinkVerificationFailure.ADDRESS_NOT_PUBLIC;
	}

	private static String stripIpv6Brackets(String host) {
		return host.startsWith("[") && host.endsWith("]") ? host.substring(1, host.length() - 1) : host;
	}

	private static URI resolveRedirect(URI current, String location) throws URISyntaxException {
		URI redirect = new URI(location.strip());
		return current.resolve(redirect);
	}

	private static boolean isRedirect(int status) {
		return status == 301 || status == 302 || status == 303 || status == 307 || status == 308;
	}

	private static boolean isSuccessful(int status) {
		return status >= 200 && status < 300;
	}

	private static boolean isPdf(HttpProbeResponse response) {
		if (hasPdfContentType(response.headers())) {
			return true;
		}
		byte[] prefix = response.bodyPrefix();
		if (prefix.length < PDF_SIGNATURE.length) {
			return false;
		}
		for (int index = 0; index < PDF_SIGNATURE.length; index++) {
			if (prefix[index] != PDF_SIGNATURE[index]) {
				return false;
			}
		}
		return true;
	}

	private static boolean hasPdfContentType(HttpHeaders headers) {
		return headers.firstValue("Content-Type")
				.map(value -> value.split(";", 2)[0].strip().equalsIgnoreCase("application/pdf"))
				.orElse(false);
	}
}
