package com.openscholar.embedding.internal.ollama;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

import com.openscholar.embedding.EmbeddingFailureScope;
import com.openscholar.embedding.EmbeddingGenerationException;
import com.openscholar.embedding.EmbeddingGenerator;
import com.openscholar.embedding.GeneratedEmbedding;
import com.openscholar.paper.EmbeddingContentKind;
import com.openscholar.paper.EmbeddingDistanceMetric;
import com.openscholar.paper.EmbeddingProfile;
import org.springframework.ai.ollama.api.OllamaApi;
import org.springframework.ai.retry.NonTransientAiException;
import org.springframework.ai.retry.TransientAiException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

final class PinnedOllamaEmbeddingGenerator implements EmbeddingGenerator {

	static final String PROFILE_KEY_PREFIX = "paper-semantic-v1-";
	static final String MODEL = "qwen3-embedding:0.6b";
	static final String OLLAMA_VERSION = "0.31.1";
	static final int DIMENSIONS = 1024;
	static final int NUM_CTX = 8192;

	static final String MODEL_NOT_INSTALLED = "OLLAMA_MODEL_NOT_INSTALLED";
	static final String DIGEST_MISMATCH = "OLLAMA_DIGEST_MISMATCH";
	static final String MODEL_INCOMPATIBLE = "OLLAMA_MODEL_INCOMPATIBLE";
	static final String RUNTIME_MISMATCH = "OLLAMA_RUNTIME_MISMATCH";
	static final String INVALID_RESPONSE = "OLLAMA_INVALID_RESPONSE";
	static final String UNAVAILABLE = "OLLAMA_UNAVAILABLE";
	static final String REQUEST_REJECTED = "OLLAMA_REQUEST_REJECTED";
	static final String UPSTREAM_ERROR = "OLLAMA_UPSTREAM_ERROR";

	private static final String EMBEDDING_CAPABILITY = "embedding";
	private static final String CONTEXT_LENGTH = "qwen3.context_length";
	private static final String EMBEDDING_LENGTH = "qwen3.embedding_length";

	private final OllamaApi ollamaApi;
	private final OllamaRuntimeClient runtimeClient;
	private final OllamaEmbeddingProperties properties;
	private final Clock clock;
	private final EmbeddingProfile profile;

	PinnedOllamaEmbeddingGenerator(
			OllamaApi ollamaApi,
			OllamaRuntimeClient runtimeClient,
			OllamaEmbeddingProperties properties,
			Clock clock) {
		this.ollamaApi = Objects.requireNonNull(ollamaApi, "ollamaApi");
		this.runtimeClient = Objects.requireNonNull(runtimeClient, "runtimeClient");
		this.properties = Objects.requireNonNull(properties, "properties");
		this.clock = Objects.requireNonNull(clock, "clock");
		this.profile = new EmbeddingProfile(
				profileKey(properties.expectedDigest()),
				"ollama",
				MODEL,
				"sha256:" + properties.expectedDigest() + ";ollama:" + OLLAMA_VERSION,
				EmbeddingContentKind.TITLE_ABSTRACT,
				1,
				DIMENSIONS,
				EmbeddingDistanceMetric.COSINE);
	}

	@Override
	public EmbeddingProfile profile() {
		return profile;
	}

	@Override
	public void verify() {
		requireRuntimeVersion();
		requirePinnedModel();
		OllamaApi.ShowModelResponse response = invoke(
				() -> ollamaApi.showModel(new OllamaApi.ShowModelRequest(MODEL)));
		if (response == null
				|| response.capabilities() == null
				|| !response.capabilities().contains(EMBEDDING_CAPABILITY)
				|| metadataInteger(response.modelInfo(), CONTEXT_LENGTH) < NUM_CTX
				|| metadataInteger(response.modelInfo(), EMBEDDING_LENGTH) != DIMENSIONS) {
			throw failure(
					MODEL_INCOMPATIBLE,
					false,
					EmbeddingFailureScope.SYSTEM,
					"The installed Ollama model is not compatible with the configured embedding profile");
		}
	}

	@Override
	public GeneratedEmbedding generate(String input) {
		if (input == null || input.isBlank()) {
			throw new IllegalArgumentException("Embedding input must not be blank");
		}
		verify();
		OllamaApi.EmbeddingsResponse response = invoke(() -> ollamaApi.embed(
				new OllamaApi.EmbeddingsRequest(
						MODEL,
						List.of(input),
						properties.keepAlive(),
						Map.of("num_ctx", NUM_CTX),
						false,
						DIMENSIONS)));

		List<Float> vector = requireSingleVector(response);
		requireRuntimeVersion();
		requirePinnedModel();
		return new GeneratedEmbedding(vector, clock.instant());
	}

	private void requireRuntimeVersion() {
		String version = invoke(runtimeClient::version);
		if (!OLLAMA_VERSION.equals(version)) {
			throw failure(
					RUNTIME_MISMATCH,
					false,
					EmbeddingFailureScope.SYSTEM,
					"The Ollama server version does not match the configured embedding profile");
		}
	}

	private OllamaApi.Model requirePinnedModel() {
		OllamaApi.ListModelResponse response = invoke(ollamaApi::listModels);
		if (response == null || response.models() == null) {
			throw invalidResponse();
		}
		List<OllamaApi.Model> matches = response.models().stream()
				.filter(Objects::nonNull)
				.filter(model -> MODEL.equals(model.name()) && MODEL.equals(model.model()))
				.toList();
		if (matches.isEmpty()) {
			throw failure(
					MODEL_NOT_INSTALLED,
					false,
					EmbeddingFailureScope.SYSTEM,
					"The required Ollama embedding model is not installed");
		}
		if (matches.size() != 1) {
			throw invalidResponse();
		}
		OllamaApi.Model model = matches.getFirst();
		if (!properties.expectedDigest().equals(model.digest())) {
			throw failure(
					DIGEST_MISMATCH,
					false,
					EmbeddingFailureScope.SYSTEM,
					"The installed Ollama model does not match the configured digest");
		}
		return model;
	}

	private List<Float> requireSingleVector(OllamaApi.EmbeddingsResponse response) {
		if (response == null
				|| !MODEL.equals(response.model())
				|| response.embeddings() == null
				|| response.embeddings().size() != 1
				|| response.embeddings().getFirst() == null
				|| response.embeddings().getFirst().length != DIMENSIONS) {
			throw invalidResponse();
		}
		float[] values = response.embeddings().getFirst();
		List<Float> vector = new ArrayList<>(values.length);
		boolean hasNonZeroComponent = false;
		for (float value : values) {
			if (!Float.isFinite(value)) {
				throw invalidResponse();
			}
			hasNonZeroComponent |= value != 0.0f;
			vector.add(value);
		}
		if (!hasNonZeroComponent) {
			throw invalidResponse();
		}
		return List.copyOf(vector);
	}

	private int metadataInteger(Map<String, Object> metadata, String key) {
		if (metadata == null || !(metadata.get(key) instanceof Number number)) {
			return -1;
		}
		double value = number.doubleValue();
		if (!Double.isFinite(value) || value != Math.rint(value)
				|| value < 0 || value > Integer.MAX_VALUE) {
			return -1;
		}
		return (int) value;
	}

	private <T> T invoke(Supplier<T> request) {
		try {
			return request.get();
		}
		catch (RestClientResponseException exception) {
			int status = exception.getStatusCode().value();
			if (status == 429 || status >= 500) {
				throw failure(
						UPSTREAM_ERROR,
						true,
						EmbeddingFailureScope.SYSTEM,
						"Ollama is temporarily unavailable");
			}
			throw failure(
					REQUEST_REJECTED,
					false,
					EmbeddingFailureScope.SYSTEM,
					"Ollama rejected the embedding request");
		}
		catch (ResourceAccessException exception) {
			if (causedByResponseLimit(exception)) {
				throw invalidResponse();
			}
			throw failure(
					UNAVAILABLE,
					true,
					EmbeddingFailureScope.SYSTEM,
					"Ollama could not be reached");
		}
		catch (RestClientException exception) {
			throw invalidResponse();
		}
		catch (TransientAiException exception) {
			throw failure(
					UPSTREAM_ERROR,
					true,
					EmbeddingFailureScope.SYSTEM,
					"Ollama is temporarily unavailable");
		}
		catch (NonTransientAiException exception) {
			throw failure(
					REQUEST_REJECTED,
					false,
					EmbeddingFailureScope.SYSTEM,
					"Ollama rejected the embedding request");
		}
		catch (NullPointerException emptyResponse) {
			throw invalidResponse();
		}
	}

	static String profileKey(String digest) {
		return PROFILE_KEY_PREFIX + digest + "-ollama-" + OLLAMA_VERSION.replace('.', '-');
	}

	private static boolean causedByResponseLimit(Throwable failure) {
		for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
			if (cause instanceof OllamaResponseTooLargeException) {
				return true;
			}
		}
		return false;
	}

	private EmbeddingGenerationException invalidResponse() {
		return failure(
				INVALID_RESPONSE,
				false,
				EmbeddingFailureScope.SYSTEM,
				"Ollama returned an unreadable or invalid response");
	}

	private EmbeddingGenerationException failure(
			String errorCode,
			boolean retryable,
			EmbeddingFailureScope scope,
			String message) {
		return new EmbeddingGenerationException(errorCode, retryable, scope, message);
	}
}
