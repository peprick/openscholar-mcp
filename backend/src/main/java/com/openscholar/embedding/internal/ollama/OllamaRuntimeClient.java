package com.openscholar.embedding.internal.ollama;

import java.util.Objects;

import org.springframework.web.client.RestClient;

final class OllamaRuntimeClient {

	private final RestClient restClient;

	OllamaRuntimeClient(RestClient restClient) {
		this.restClient = Objects.requireNonNull(restClient, "restClient");
	}

	String version() {
		VersionResponse response = restClient.get()
				.uri("/api/version")
				.retrieve()
				.body(VersionResponse.class);
		return response == null ? null : response.version();
	}

	private record VersionResponse(String version) {
	}
}
