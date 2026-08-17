package com.openscholar.access.internal.provider.unpaywall;

import java.net.http.HttpClient;
import java.time.Clock;

import com.openscholar.access.internal.provider.AccessEvidenceProvider;
import com.openscholar.access.internal.provider.AccessProviderHttpSupport;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(UnpaywallProperties.class)
class UnpaywallConfiguration {

	static final String REST_CLIENT_BEAN = "unpaywallRestClient";
	static final int MAX_RESPONSE_BYTES = 2 * 1024 * 1024;

	@Bean(REST_CLIENT_BEAN)
	RestClient unpaywallRestClient(RestClient.Builder builder, UnpaywallProperties properties) {
		HttpClient httpClient = HttpClient.newBuilder()
				.connectTimeout(properties.connectTimeout())
				.followRedirects(HttpClient.Redirect.NEVER)
				.build();
		JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
		requestFactory.setReadTimeout(properties.readTimeout());

		return builder.clone()
				.baseUrl(properties.baseUrl())
				.requestFactory(requestFactory)
				.requestInterceptor(AccessProviderHttpSupport.boundedResponseBody(MAX_RESPONSE_BYTES))
				.build();
	}

	@Bean
	AccessEvidenceProvider unpaywallAccessEvidenceProvider(
			@Qualifier(REST_CLIENT_BEAN) RestClient restClient,
			UnpaywallProperties properties,
			Clock clock) {
		return new UnpaywallAccessEvidenceProvider(restClient, properties, clock);
	}
}
