package com.openscholar.embedding.internal.ollama;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.http.HttpMethod.GET;
import static org.springframework.http.HttpMethod.POST;
import static org.springframework.test.json.JsonCompareMode.STRICT;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.net.URI;
import java.net.SocketTimeoutException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import com.openscholar.embedding.EmbeddingFailureScope;
import com.openscholar.embedding.EmbeddingGenerationException;
import com.openscholar.embedding.GeneratedEmbedding;
import com.openscholar.paper.EmbeddingContentKind;
import com.openscholar.paper.EmbeddingDistanceMetric;
import org.junit.jupiter.api.Test;
import org.springframework.ai.ollama.api.OllamaApi;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class PinnedOllamaEmbeddingGeneratorTests {

	private static final String BASE_URL = "http://127.0.0.1:11434";
	private static final String DIGEST = "a".repeat(64);
	private static final String OTHER_DIGEST = "b".repeat(64);
	private static final Instant NOW = Instant.parse("2026-08-19T12:00:00Z");
	private static final String INPUT = "Title: Test paper\nAbstract: Provider-free contract test";

	@Test
	void generatesWithTheExactPinnedRequestAndRechecksTheDigest() {
		Harness harness = harness();
		expectRuntimeVersion(harness.server(), PinnedOllamaEmbeddingGenerator.OLLAMA_VERSION);
		expectTags(harness.server(), DIGEST);
		expectCompatibleShow(harness.server());
		harness.server().expect(requestTo(BASE_URL + "/api/embed"))
				.andExpect(method(POST))
				.andExpect(content().json("""
						{
						  "model": "qwen3-embedding:0.6b",
						  "input": ["Title: Test paper\\nAbstract: Provider-free contract test"],
						  "keep_alive": "5m",
						  "options": {"num_ctx": 8192},
						  "truncate": false,
						  "dimensions": 1024
						}
				""", STRICT))
				.andRespond(withSuccess(embeddingResponse(
						PinnedOllamaEmbeddingGenerator.MODEL,
						vectorJson(PinnedOllamaEmbeddingGenerator.DIMENSIONS, "1.0")),
						MediaType.APPLICATION_JSON));
		expectRuntimeVersion(harness.server(), PinnedOllamaEmbeddingGenerator.OLLAMA_VERSION);
		expectTags(harness.server(), DIGEST);

		GeneratedEmbedding generated = harness.generator().generate(INPUT);

		assertThat(generated.generatedAt()).isEqualTo(NOW);
		assertThat(generated.vector()).hasSize(PinnedOllamaEmbeddingGenerator.DIMENSIONS);
		assertThat(generated.vector().getFirst()).isEqualTo(1.0f);
		assertThat(harness.generator().profile())
				.satisfies(profile -> {
					assertThat(profile.profileKey()).isEqualTo(
							PinnedOllamaEmbeddingGenerator.profileKey(DIGEST));
					assertThat(profile.provider()).isEqualTo("ollama");
					assertThat(profile.model()).isEqualTo("qwen3-embedding:0.6b");
					assertThat(profile.modelRevision()).isEqualTo(
							"sha256:" + DIGEST + ";ollama:0.31.1");
					assertThat(profile.contentKind()).isEqualTo(EmbeddingContentKind.TITLE_ABSTRACT);
					assertThat(profile.inputPolicyVersion()).isEqualTo(1);
					assertThat(profile.dimensions()).isEqualTo(1024);
					assertThat(profile.distanceMetric()).isEqualTo(EmbeddingDistanceMetric.COSINE);
				});
		harness.server().verify();
	}

	@Test
	void rejectsAMissingExactTagBeforeShowingOrEmbeddingAModel() {
		Harness harness = harness();
		expectRuntimeVersion(harness.server(), PinnedOllamaEmbeddingGenerator.OLLAMA_VERSION);
		harness.server().expect(requestTo(BASE_URL + "/api/tags"))
				.andExpect(method(GET))
				.andRespond(withSuccess(tagsResponse("qwen3-embedding:latest", DIGEST),
						MediaType.APPLICATION_JSON));

		assertFailure(harness.generator()::verify,
				PinnedOllamaEmbeddingGenerator.MODEL_NOT_INSTALLED, false);
		harness.server().verify();
	}

	@Test
	void rejectsDigestMismatchWithoutAcceptingAPrefix() {
		Harness mismatch = harness();
		expectRuntimeVersion(mismatch.server(), PinnedOllamaEmbeddingGenerator.OLLAMA_VERSION);
		expectTags(mismatch.server(), OTHER_DIGEST);
		assertFailure(mismatch.generator()::verify,
				PinnedOllamaEmbeddingGenerator.DIGEST_MISMATCH, false);
		mismatch.server().verify();

		Harness prefix = harness();
		expectRuntimeVersion(prefix.server(), PinnedOllamaEmbeddingGenerator.OLLAMA_VERSION);
		expectTags(prefix.server(), DIGEST.substring(0, 12));
		assertFailure(prefix.generator()::verify,
				PinnedOllamaEmbeddingGenerator.DIGEST_MISMATCH, false);
		prefix.server().verify();
	}

	@Test
	void rejectsMissingCapabilityOrIncompatibleModelMetadata() {
		assertIncompatible(showResponse("completion", 32768, 1024));
		assertIncompatible(showResponse("embedding", 4096, 1024));
		assertIncompatible(showResponse("embedding", 32768, 768));
		assertIncompatible("""
				{
				  "capabilities": ["embedding"],
				  "model_info": {}
				}
				""");
	}

	@Test
	void rejectsWrongModelWrongDimensionsMultipleOrZeroVectors() {
		assertInvalidEmbeddingResponse(embeddingResponse(
				"qwen3-embedding:latest",
				vectorJson(PinnedOllamaEmbeddingGenerator.DIMENSIONS, "1.0")));
		assertInvalidEmbeddingResponse(embeddingResponse(
				PinnedOllamaEmbeddingGenerator.MODEL,
				vectorJson(3, "1.0")));
		assertInvalidEmbeddingResponse("""
				{
				  "model": "qwen3-embedding:0.6b",
				  "embeddings": [[1.0], [1.0]]
				}
				""");
		assertInvalidEmbeddingResponse(embeddingResponse(
				PinnedOllamaEmbeddingGenerator.MODEL,
				vectorJson(PinnedOllamaEmbeddingGenerator.DIMENSIONS, "0.0")));
	}

	@Test
	void discardsAnEmbeddingWhenTheDigestChangesDuringInference() {
		Harness harness = harness();
		expectRuntimeVersion(harness.server(), PinnedOllamaEmbeddingGenerator.OLLAMA_VERSION);
		expectTags(harness.server(), DIGEST);
		expectCompatibleShow(harness.server());
		expectEmbedding(harness.server(), embeddingResponse(
				PinnedOllamaEmbeddingGenerator.MODEL,
				vectorJson(PinnedOllamaEmbeddingGenerator.DIMENSIONS, "1.0")));
		expectRuntimeVersion(harness.server(), PinnedOllamaEmbeddingGenerator.OLLAMA_VERSION);
		expectTags(harness.server(), OTHER_DIGEST);

		assertFailure(() -> harness.generator().generate(INPUT),
				PinnedOllamaEmbeddingGenerator.DIGEST_MISMATCH, false);
		harness.server().verify();
	}

	@Test
	void discardsAnEmbeddingWhenTheRuntimeChangesDuringInference() {
		Harness harness = harness();
		expectRuntimeVersion(harness.server(), PinnedOllamaEmbeddingGenerator.OLLAMA_VERSION);
		expectTags(harness.server(), DIGEST);
		expectCompatibleShow(harness.server());
		expectEmbedding(harness.server(), embeddingResponse(
				PinnedOllamaEmbeddingGenerator.MODEL,
				vectorJson(PinnedOllamaEmbeddingGenerator.DIMENSIONS, "1.0")));
		expectRuntimeVersion(harness.server(), "0.32.0");

		assertFailure(() -> harness.generator().generate(INPUT),
				PinnedOllamaEmbeddingGenerator.RUNTIME_MISMATCH, false);
		harness.server().verify();
	}

	@Test
	void translatesHttpFailuresWithoutLeakingTheResponseBodyOrInput() {
		Harness harness = harness();
		harness.server().expect(requestTo(BASE_URL + "/api/version"))
				.andExpect(method(GET))
				.andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE)
						.contentType(MediaType.APPLICATION_JSON)
						.body("{\"error\":\"SECRET_RESPONSE_BODY\"}"));

		assertThatThrownBy(harness.generator()::verify)
				.isInstanceOfSatisfying(EmbeddingGenerationException.class, exception -> {
					assertThat(exception.errorCode())
							.isEqualTo(PinnedOllamaEmbeddingGenerator.UPSTREAM_ERROR);
					assertThat(exception.retryable()).isTrue();
					assertThat(exception.scope()).isEqualTo(EmbeddingFailureScope.SYSTEM);
					assertThat(exception.getMessage())
							.doesNotContain("SECRET_RESPONSE_BODY")
							.doesNotContain(INPUT);
					assertThat(exception.getCause()).isNull();
				});
		harness.server().verify();
	}

	@Test
	void translatesConnectionFailuresAsSafeRetryableErrors() {
		Harness harness = harness();
		harness.server().expect(requestTo(BASE_URL + "/api/version"))
				.andExpect(method(GET))
				.andRespond(request -> {
					throw new SocketTimeoutException("SECRET_CONNECTION_DETAIL");
				});

		assertThatThrownBy(harness.generator()::verify)
				.isInstanceOfSatisfying(EmbeddingGenerationException.class, exception -> {
					assertThat(exception.errorCode())
							.isEqualTo(PinnedOllamaEmbeddingGenerator.UNAVAILABLE);
					assertThat(exception.retryable()).isTrue();
					assertThat(exception.scope()).isEqualTo(EmbeddingFailureScope.SYSTEM);
					assertThat(exception.getMessage()).doesNotContain("SECRET_CONNECTION_DETAIL");
					assertThat(exception.getCause()).isNull();
				});
		harness.server().verify();
	}

	@Test
	void rejectsAnUnpinnedOllamaRuntimeBeforeInspectingTheModel() {
		Harness harness = harness();
		expectRuntimeVersion(harness.server(), "0.32.0");

		assertFailure(harness.generator()::verify,
				PinnedOllamaEmbeddingGenerator.RUNTIME_MISMATCH, false);
		harness.server().verify();
	}

	@Test
	void classifiesAnEmbeddingRequestRejectionAsSystemic() {
		Harness harness = harness();
		expectRuntimeVersion(harness.server(), PinnedOllamaEmbeddingGenerator.OLLAMA_VERSION);
		expectTags(harness.server(), DIGEST);
		expectCompatibleShow(harness.server());
		harness.server().expect(requestTo(BASE_URL + "/api/embed"))
				.andExpect(method(POST))
				.andRespond(withStatus(HttpStatus.BAD_REQUEST));

		assertFailure(() -> harness.generator().generate(INPUT),
				PinnedOllamaEmbeddingGenerator.REQUEST_REJECTED, false);
		harness.server().verify();
	}

	@Test
	void classifiesMissingEmbeddingEndpointAsSystemic() {
		Harness harness = harness();
		expectRuntimeVersion(harness.server(), PinnedOllamaEmbeddingGenerator.OLLAMA_VERSION);
		expectTags(harness.server(), DIGEST);
		expectCompatibleShow(harness.server());
		harness.server().expect(requestTo(BASE_URL + "/api/embed"))
				.andExpect(method(POST))
				.andRespond(withStatus(HttpStatus.NOT_FOUND));

		assertFailure(() -> harness.generator().generate(INPUT),
				PinnedOllamaEmbeddingGenerator.REQUEST_REJECTED, false);
		harness.server().verify();
	}

	@Test
	void rejectsBlankInputWithoutMakingARequest() {
		Harness harness = harness();
		assertThatThrownBy(() -> harness.generator().generate("  "))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessage("Embedding input must not be blank");
		harness.server().verify();
	}

	private static Harness harness() {
		RestClient.Builder builder = RestClient.builder();
		MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
		OllamaApi ollamaApi = OllamaApi.builder()
				.baseUrl(BASE_URL)
				.restClientBuilder(builder)
				.build();
		OllamaEmbeddingProperties properties = new OllamaEmbeddingProperties(
				true,
				URI.create(BASE_URL),
				DIGEST,
				true,
				Duration.ofSeconds(2),
				Duration.ofSeconds(30),
				"5m");
		Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
		return new Harness(
				new PinnedOllamaEmbeddingGenerator(
						ollamaApi,
						new OllamaRuntimeClient(builder.clone().baseUrl(BASE_URL).build()),
						properties,
						clock),
				server);
	}

	private static void expectRuntimeVersion(MockRestServiceServer server, String version) {
		server.expect(requestTo(BASE_URL + "/api/version"))
				.andExpect(method(GET))
				.andRespond(withSuccess("{\"version\":\"" + version + "\"}",
						MediaType.APPLICATION_JSON));
	}

	private static void expectTags(MockRestServiceServer server, String digest) {
		server.expect(requestTo(BASE_URL + "/api/tags"))
				.andExpect(method(GET))
				.andRespond(withSuccess(tagsResponse(PinnedOllamaEmbeddingGenerator.MODEL, digest),
						MediaType.APPLICATION_JSON));
	}

	private static void expectCompatibleShow(MockRestServiceServer server) {
		server.expect(requestTo(BASE_URL + "/api/show"))
				.andExpect(method(POST))
				.andExpect(content().json("""
						{"model": "qwen3-embedding:0.6b"}
						""", STRICT))
				.andRespond(withSuccess(showResponse("embedding", 32768, 1024),
						MediaType.APPLICATION_JSON));
	}

	private static void expectEmbedding(MockRestServiceServer server, String response) {
		server.expect(requestTo(BASE_URL + "/api/embed"))
				.andExpect(method(POST))
				.andRespond(withSuccess(response, MediaType.APPLICATION_JSON));
	}

	private static void assertIncompatible(String showResponse) {
		Harness harness = harness();
		expectRuntimeVersion(harness.server(), PinnedOllamaEmbeddingGenerator.OLLAMA_VERSION);
		expectTags(harness.server(), DIGEST);
		harness.server().expect(requestTo(BASE_URL + "/api/show"))
				.andExpect(method(POST))
				.andRespond(withSuccess(showResponse, MediaType.APPLICATION_JSON));
		assertFailure(harness.generator()::verify,
				PinnedOllamaEmbeddingGenerator.MODEL_INCOMPATIBLE, false);
		harness.server().verify();
	}

	private static void assertInvalidEmbeddingResponse(String response) {
		Harness harness = harness();
		expectRuntimeVersion(harness.server(), PinnedOllamaEmbeddingGenerator.OLLAMA_VERSION);
		expectTags(harness.server(), DIGEST);
		expectCompatibleShow(harness.server());
		expectEmbedding(harness.server(), response);
		assertFailure(() -> harness.generator().generate(INPUT),
				PinnedOllamaEmbeddingGenerator.INVALID_RESPONSE, false);
		harness.server().verify();
	}

	private static void assertFailure(Runnable operation, String errorCode, boolean retryable) {
		assertThatThrownBy(operation::run)
				.isInstanceOfSatisfying(EmbeddingGenerationException.class, exception -> {
					assertThat(exception.errorCode()).isEqualTo(errorCode);
					assertThat(exception.retryable()).isEqualTo(retryable);
					assertThat(exception.scope()).isEqualTo(EmbeddingFailureScope.SYSTEM);
				});
	}

	private static String tagsResponse(String model, String digest) {
		return """
				{
				  "models": [{
				    "name": "%s",
				    "model": "%s",
				    "modified_at": "2026-08-19T12:00:00Z",
				    "size": 639000000,
				    "digest": "%s",
				    "details": {
				      "format": "gguf",
				      "family": "qwen3",
				      "families": ["qwen3"],
				      "parameter_size": "596M",
				      "quantization_level": "Q8_0"
				    }
				  }]
				}
				""".formatted(model, model, digest);
	}

	private static String showResponse(String capability, int contextLength, int dimensions) {
		return """
				{
				  "capabilities": ["%s"],
				  "details": {
				    "format": "gguf",
				    "family": "qwen3",
				    "parameter_size": "596M",
				    "quantization_level": "Q8_0"
				  },
				  "model_info": {
				    "qwen3.context_length": %d,
				    "qwen3.embedding_length": %d
				  }
				}
				""".formatted(capability, contextLength, dimensions);
	}

	private static String embeddingResponse(String model, String vector) {
		return """
				{
				  "model": "%s",
				  "embeddings": [%s],
				  "total_duration": 1000,
				  "load_duration": 100,
				  "prompt_eval_count": 12
				}
				""".formatted(model, vector);
	}

	private static String vectorJson(int dimensions, String firstValue) {
		return IntStream.range(0, dimensions)
				.mapToObj(index -> index == 0 ? firstValue : "0.0")
				.collect(Collectors.joining(",", "[", "]"));
	}

	private record Harness(
			PinnedOllamaEmbeddingGenerator generator,
			MockRestServiceServer server) {
	}
}
