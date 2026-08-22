package com.openscholar.provider.core;

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
@ConditionalOnProperty(prefix = CoreProperties.PREFIX, name = "enabled", havingValue = "true")
@EnableConfigurationProperties(CoreProperties.class)
class CoreConfiguration {

	static final String REST_CLIENT_BEAN = "coreRestClient";

	@Bean(REST_CLIENT_BEAN)
	RestClient coreRestClient(RestClient.Builder builder, CoreProperties properties) {
		HttpClient httpClient = HttpClient.newBuilder()
				.connectTimeout(properties.connectTimeout())
				.followRedirects(HttpClient.Redirect.NORMAL)
				.build();
		JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
		requestFactory.setReadTimeout(properties.requestTimeout());

		return configure(builder.clone(), properties)
				.requestInterceptor(new CoreRequestDeadline(properties.requestTimeout()))
				.requestFactory(requestFactory)
				.build();
	}

	static RestClient.Builder configure(RestClient.Builder builder, CoreProperties properties) {
		RestClient.Builder configured = builder
				.baseUrl(properties.baseUrl())
				.defaultHeader(HttpHeaders.USER_AGENT, "OpenScholar/0.0.1")
				.requestInterceptor(ProviderResponseBodyLimit.boundedResponseBody(properties.maxResponseBytes()));
		if (properties.hasApiKey()) {
			configured.defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + properties.apiKey());
		}
		return configured;
	}

	@Bean
	ResearchProvider coreResearchProvider(
			@Qualifier(REST_CLIENT_BEAN) RestClient restClient,
			CoreProperties properties,
			Clock clock) {
		return new CoreResearchProvider(restClient, properties, clock);
	}
}
