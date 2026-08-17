package com.openscholar.provider.openalex;

import java.net.http.HttpClient;
import java.time.Clock;

import com.openscholar.provider.ResearchProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(OpenAlexProperties.class)
class OpenAlexConfiguration {

	static final String REST_CLIENT_BEAN = "openAlexRestClient";

	@Bean(REST_CLIENT_BEAN)
	RestClient openAlexRestClient(RestClient.Builder builder, OpenAlexProperties properties) {
		HttpClient httpClient = HttpClient.newBuilder()
				.connectTimeout(properties.connectTimeout())
				.followRedirects(HttpClient.Redirect.NORMAL)
				.build();
		JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
		requestFactory.setReadTimeout(properties.readTimeout());

		return builder.clone()
				.baseUrl(properties.baseUrl())
				.requestFactory(requestFactory)
				.build();
	}

	@Bean
	ResearchProvider openAlexResearchProvider(
			@Qualifier(REST_CLIENT_BEAN) RestClient restClient,
			OpenAlexProperties properties,
			Clock clock) {
		return new OpenAlexResearchProvider(restClient, properties, clock);
	}
}
