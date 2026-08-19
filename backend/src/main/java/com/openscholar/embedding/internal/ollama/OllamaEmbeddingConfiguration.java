package com.openscholar.embedding.internal.ollama;

import java.net.http.HttpClient;
import java.time.Clock;

import com.openscholar.embedding.EmbeddingGenerator;
import org.springframework.ai.ollama.api.OllamaApi;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(
		prefix = OllamaEmbeddingProperties.PREFIX,
		name = "enabled",
		havingValue = "true")
@EnableConfigurationProperties(OllamaEmbeddingProperties.class)
class OllamaEmbeddingConfiguration {

	static final String REST_CLIENT_BUILDER_BEAN = "ollamaEmbeddingRestClientBuilder";
	static final int MAXIMUM_RESPONSE_BYTES = 2 * 1024 * 1024;

	@Bean
	HttpClient ollamaEmbeddingHttpClient(OllamaEmbeddingProperties properties) {
		return HttpClient.newBuilder()
				.connectTimeout(properties.connectTimeout())
				.followRedirects(HttpClient.Redirect.NEVER)
				.proxy(HttpClient.Builder.NO_PROXY)
				.build();
	}

	@Bean(REST_CLIENT_BUILDER_BEAN)
	RestClient.Builder ollamaEmbeddingRestClientBuilder(
			HttpClient ollamaEmbeddingHttpClient,
			OllamaEmbeddingProperties properties) {
		JdkClientHttpRequestFactory requestFactory =
				new JdkClientHttpRequestFactory(ollamaEmbeddingHttpClient);
		requestFactory.setReadTimeout(properties.readTimeout());
		return RestClient.builder()
				.requestFactory(requestFactory)
				.requestInterceptor(
						new OllamaResponseSizeLimitInterceptor(MAXIMUM_RESPONSE_BYTES));
	}

	@Bean
	OllamaApi ollamaEmbeddingApi(
			@Qualifier(REST_CLIENT_BUILDER_BEAN) RestClient.Builder restClientBuilder,
			OllamaEmbeddingProperties properties) {
		return OllamaApi.builder()
				.baseUrl(properties.baseUrl().toString())
				.restClientBuilder(restClientBuilder.clone())
				.build();
	}

	@Bean
	OllamaRuntimeClient ollamaRuntimeClient(
			@Qualifier(REST_CLIENT_BUILDER_BEAN) RestClient.Builder restClientBuilder,
			OllamaEmbeddingProperties properties) {
		return new OllamaRuntimeClient(restClientBuilder.clone()
				.baseUrl(properties.baseUrl())
				.build());
	}

	@Bean
	EmbeddingGenerator ollamaEmbeddingGenerator(
			OllamaApi ollamaApi,
			OllamaRuntimeClient runtimeClient,
			OllamaEmbeddingProperties properties,
			Clock clock) {
		return new PinnedOllamaEmbeddingGenerator(ollamaApi, runtimeClient, properties, clock);
	}
}
