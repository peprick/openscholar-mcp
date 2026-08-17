package com.openscholar.access.internal.verification;

import java.io.IOException;
import java.io.InputStream;
import java.net.http.HttpHeaders;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.CloseableHttpResponse;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.client5.http.protocol.HttpClientContext;
import org.apache.hc.core5.http.ClassicHttpRequest;
import org.apache.hc.core5.http.Header;
import org.apache.hc.core5.http.HttpEntity;
import org.apache.hc.core5.http.impl.NoConnectionReuseStrategy;
import org.apache.hc.core5.http.io.support.ClassicRequestBuilder;
import org.apache.hc.core5.io.CloseMode;
import org.apache.hc.core5.util.Timeout;

final class ApacheHttpProbeClient implements HttpProbeClient, AutoCloseable {

	private static final List<String> RETAINED_RESPONSE_HEADERS = List.of("Location", "Content-Type");

	private final CloseableHttpClient httpClient;

	private ApacheHttpProbeClient(CloseableHttpClient httpClient) {
		this.httpClient = Objects.requireNonNull(httpClient, "httpClient");
	}

	static ApacheHttpProbeClient create(LinkVerificationPolicy policy, DnsResolver dnsResolver) {
		Objects.requireNonNull(policy, "policy");
		var connectionConfig = ConnectionConfig.custom()
				.setConnectTimeout(timeout(policy.connectTimeout()))
				.setSocketTimeout(timeout(policy.requestTimeout()))
				.build();
		var connectionManager = PoolingHttpClientConnectionManagerBuilder.create()
				.setDnsResolver(new ValidatingApacheDnsResolver(dnsResolver))
				.setDefaultConnectionConfig(connectionConfig)
				.setMaxConnTotal(20)
				.setMaxConnPerRoute(5)
				.build();
		var requestConfig = secureRequestConfig(policy.requestTimeout());

		CloseableHttpClient client = HttpClients.custom()
				.setConnectionManager(connectionManager)
				.setConnectionReuseStrategy(NoConnectionReuseStrategy.INSTANCE)
				.setDefaultRequestConfig(requestConfig)
				.disableRedirectHandling()
				.disableCookieManagement()
				.disableAuthCaching()
				.disableAutomaticRetries()
				.disableConnectionState()
				.disableContentCompression()
				.build();
		return new ApacheHttpProbeClient(client);
	}

	@Override
	public HttpProbeResponse exchange(HttpProbeRequest probe) throws IOException {
		ClassicRequestBuilder builder = probe.method() == ProbeMethod.HEAD
				? ClassicRequestBuilder.head(probe.uri())
				: ClassicRequestBuilder.get(probe.uri());
		builder.setHeader("Accept", probe.accept());
		builder.setHeader("Accept-Encoding", "identity");
		if (probe.method() == ProbeMethod.RANGE_GET) {
			builder.setHeader("Range", "bytes=0-" + (probe.maxResponseBytes() - 1));
		}

		ClassicHttpRequest request = builder.build();
		HttpClientContext context = HttpClientContext.create();
		context.setRequestConfig(secureRequestConfig(probe.timeout()));
		@SuppressWarnings("deprecation")
		CloseableHttpResponse response = httpClient.execute(request, context);
		try {
			return new HttpProbeResponse(
					response.getCode(),
					retainedHeaders(response.getHeaders()),
					readPrefix(response.getEntity(), probe.maxResponseBytes()));
		}
		finally {
			response.close(CloseMode.IMMEDIATE);
		}
	}

	@Override
	public void close() {
		httpClient.close(CloseMode.GRACEFUL);
	}

	private static RequestConfig secureRequestConfig(java.time.Duration timeout) {
		return RequestConfig.custom()
				.setRedirectsEnabled(false)
				.setAuthenticationEnabled(false)
				.setContentCompressionEnabled(false)
				.setConnectionRequestTimeout(timeout(timeout))
				.setResponseTimeout(timeout(timeout))
				.build();
	}

	private static byte[] readPrefix(HttpEntity entity, int maximumBytes) throws IOException {
		if (entity == null || maximumBytes == 0) {
			return new byte[0];
		}
		try (InputStream body = entity.getContent()) {
			return body.readNBytes(maximumBytes);
		}
	}

	private static HttpHeaders retainedHeaders(Header[] headers) {
		Map<String, List<String>> retained = new LinkedHashMap<>();
		for (Header header : headers) {
			if (RETAINED_RESPONSE_HEADERS.stream().anyMatch(name -> name.equalsIgnoreCase(header.getName()))) {
				retained.computeIfAbsent(header.getName(), ignored -> new ArrayList<>()).add(header.getValue());
			}
		}
		return HttpHeaders.of(retained, (name, value) -> true);
	}

	private static Timeout timeout(java.time.Duration duration) {
		return Timeout.ofMilliseconds(duration.toMillis());
	}
}
