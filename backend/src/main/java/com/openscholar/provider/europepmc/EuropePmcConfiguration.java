package com.openscholar.provider.europepmc;

import java.net.http.HttpClient;
import java.time.Clock;

import com.openscholar.common.ProviderResponseBodyLimit;
import com.openscholar.provider.ResearchProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = EuropePmcProperties.PREFIX, name = "enabled", havingValue = "true")
@EnableConfigurationProperties(EuropePmcProperties.class)
class EuropePmcConfiguration {

	static final String REST_CLIENT_BEAN = "europePmcRestClient";

	@Bean(REST_CLIENT_BEAN)
	RestClient europePmcRestClient(RestClient.Builder builder, EuropePmcProperties properties) {
		HttpClient httpClient = buildHttpClient(properties);
		JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
		requestFactory.setReadTimeout(properties.requestTimeout());

		return configure(builder.clone(), properties)
				.requestInterceptor(new EuropePmcRequestDeadline(properties.requestTimeout()))
				.requestFactory(requestFactory)
				.build();
	}

	static HttpClient buildHttpClient(EuropePmcProperties properties) {
		return HttpClient.newBuilder()
				// Europe PMC's edge can negotiate HTTP/2 with the JDK client and then
				// close the stream before response headers. Its supported REST API is
				// reliable over HTTP/1.1, so keep this provider client pinned to it.
				.version(HttpClient.Version.HTTP_1_1)
				.connectTimeout(properties.connectTimeout())
				.followRedirects(HttpClient.Redirect.NORMAL)
				.build();
	}

	static RestClient.Builder configure(RestClient.Builder builder, EuropePmcProperties properties) {
		return builder
				.baseUrl(properties.baseUrl())
				.defaultHeader(HttpHeaders.USER_AGENT, properties.userAgent())
				.requestInterceptor(ProviderResponseBodyLimit.boundedResponseBody(properties.maxResponseBytes()));
	}

	@Bean
	ResearchProvider europePmcResearchProvider(
			@Qualifier(REST_CLIENT_BEAN) RestClient restClient,
			Clock clock) {
		return new EuropePmcResearchProvider(restClient, clock);
	}
}
