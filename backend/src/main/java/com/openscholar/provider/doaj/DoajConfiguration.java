package com.openscholar.provider.doaj;

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
@ConditionalOnProperty(prefix = DoajProperties.PREFIX, name = "enabled", havingValue = "true")
@EnableConfigurationProperties(DoajProperties.class)
class DoajConfiguration {

	static final String REST_CLIENT_BEAN = "doajRestClient";

	@Bean(REST_CLIENT_BEAN)
	RestClient doajRestClient(RestClient.Builder builder, DoajProperties properties) {
		HttpClient httpClient = HttpClient.newBuilder()
				.connectTimeout(properties.connectTimeout())
				.followRedirects(HttpClient.Redirect.NORMAL)
				.build();
		JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
		requestFactory.setReadTimeout(properties.requestTimeout());

		return configure(builder.clone(), properties)
				.requestInterceptor(new DoajRequestDeadline(properties.requestTimeout()))
				.requestFactory(requestFactory)
				.build();
	}

	static RestClient.Builder configure(RestClient.Builder builder, DoajProperties properties) {
		return builder
				.baseUrl(properties.baseUrl())
				.defaultHeader(HttpHeaders.USER_AGENT, properties.userAgent())
				.requestInterceptor(ProviderResponseBodyLimit.boundedResponseBody(properties.maxResponseBytes()));
	}

	@Bean
	ResearchProvider doajResearchProvider(
			@Qualifier(REST_CLIENT_BEAN) RestClient restClient,
			Clock clock) {
		return new DoajResearchProvider(restClient, clock);
	}
}
