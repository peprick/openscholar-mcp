package com.openscholar.access.internal.provider.arxiv;

import java.net.http.HttpClient;
import java.time.Clock;

import com.openscholar.access.internal.provider.AccessEvidenceProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ArxivProperties.class)
class ArxivConfiguration {

	static final String REST_CLIENT_BEAN = "arxivRestClient";

	@Bean(REST_CLIENT_BEAN)
	RestClient arxivRestClient(RestClient.Builder builder, ArxivProperties properties) {
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
	AccessEvidenceProvider arxivAccessEvidenceProvider(
			@Qualifier(REST_CLIENT_BEAN) RestClient restClient,
			ArxivProperties properties,
			Clock clock) {
		return new ArxivAccessEvidenceProvider(restClient, properties, clock);
	}
}
