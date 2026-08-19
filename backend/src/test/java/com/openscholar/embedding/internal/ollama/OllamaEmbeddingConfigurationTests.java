package com.openscholar.embedding.internal.ollama;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpClient;
import java.time.Clock;

import com.openscholar.embedding.EmbeddingGenerator;
import org.junit.jupiter.api.Test;
import org.springframework.ai.ollama.api.OllamaApi;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class OllamaEmbeddingConfigurationTests {

	private static final String DIGEST = "a".repeat(64);

	private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
			.withBean(Clock.class, Clock::systemUTC)
			.withUserConfiguration(OllamaEmbeddingConfiguration.class);

	@Test
	void isEntirelyDisabledByDefaultEvenWithoutADigest() {
		contextRunner.run(context -> {
			assertThat(context).doesNotHaveBean(OllamaEmbeddingProperties.class);
			assertThat(context).doesNotHaveBean(OllamaApi.class);
			assertThat(context).doesNotHaveBean(OllamaRuntimeClient.class);
			assertThat(context).doesNotHaveBean(HttpClient.class);
			assertThat(context).doesNotHaveBean(EmbeddingGenerator.class);
			assertThat(context).doesNotHaveBean(
					OllamaEmbeddingConfiguration.REST_CLIENT_BUILDER_BEAN);
		});
	}

	@Test
	void createsThePinnedGeneratorOnlyWhenExplicitlyEnabled() {
		contextRunner
				.withPropertyValues(
						OllamaEmbeddingProperties.PREFIX + ".enabled=true",
						OllamaEmbeddingProperties.PREFIX + ".expected-digest=" + DIGEST,
						OllamaEmbeddingProperties.PREFIX + ".local-only-confirmed=true")
				.run(context -> {
					assertThat(context).hasNotFailed();
					assertThat(context).hasSingleBean(OllamaEmbeddingProperties.class);
					assertThat(context).hasSingleBean(OllamaApi.class);
					assertThat(context).hasSingleBean(OllamaRuntimeClient.class);
					assertThat(context).hasSingleBean(HttpClient.class);
					assertThat(context).hasSingleBean(EmbeddingGenerator.class);
					assertThat(context).hasBean(
							OllamaEmbeddingConfiguration.REST_CLIENT_BUILDER_BEAN);

					EmbeddingGenerator generator = context.getBean(EmbeddingGenerator.class);
					assertThat(generator.profile().profileKey())
							.isEqualTo(PinnedOllamaEmbeddingGenerator.profileKey(DIGEST));
					assertThat(generator.profile().modelRevision())
							.isEqualTo("sha256:" + DIGEST + ";ollama:0.31.1");
					assertThat(context.getBean(HttpClient.class).proxy())
							.containsSame(HttpClient.Builder.NO_PROXY);
				});
	}

	@Test
	void failsClosedWhenEnabledWithoutAFullDigest() {
		contextRunner
				.withPropertyValues(
						OllamaEmbeddingProperties.PREFIX + ".enabled=true",
						OllamaEmbeddingProperties.PREFIX + ".local-only-confirmed=true")
				.run(context -> assertThat(context).hasFailed());
	}
}
