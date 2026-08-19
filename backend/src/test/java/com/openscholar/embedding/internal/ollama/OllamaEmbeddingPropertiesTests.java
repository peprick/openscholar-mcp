package com.openscholar.embedding.internal.ollama;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.time.Duration;

import org.junit.jupiter.api.Test;

class OllamaEmbeddingPropertiesTests {

	private static final String DIGEST = "a".repeat(64);

	@Test
	void acceptsLoopbackRootUrlsAndSafeDurations() {
		assertThat(propertiesWithBaseUrl("http://127.42.7.9:11434/", "1h30m").keepAlive())
				.isEqualTo("1h30m");
		assertThat(propertiesWithBaseUrl("http://[::1]:11434", "500ms").baseUrl().getHost())
				.contains("::1");
	}

	@Test
	void rejectsNonLoopbackOrNonRootBaseUrls() {
		assertInvalidBaseUrl("http://localhost:11434");
		assertInvalidBaseUrl("https://127.0.0.1:11434");
		assertInvalidBaseUrl("http://ollama:11434");
		assertInvalidBaseUrl("http://127.0.0.1:11434/api");
		assertInvalidBaseUrl("http://user@127.0.0.1:11434");
		assertInvalidBaseUrl("http://127.0.0.1:11434?model=qwen");
		assertInvalidBaseUrl("http://127.0.0.1:11434#fragment");
	}

	@Test
	void requiresABareLowercaseFullDigest() {
		assertThatThrownBy(() -> propertiesWithDigest(null, "5m"))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("bare lowercase");
		assertThatThrownBy(() -> propertiesWithDigest("A".repeat(64), "5m"))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> propertiesWithDigest("sha256:" + DIGEST, "5m"))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> propertiesWithDigest("a".repeat(63), "5m"))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> propertiesWithDigest(" " + DIGEST, "5m"))
				.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void requiresPositiveTimeouts() {
		assertThatThrownBy(() -> new OllamaEmbeddingProperties(
				true,
				URI.create("http://127.0.0.1:11434"),
				DIGEST,
				true,
				Duration.ZERO,
				Duration.ofSeconds(30),
				"5m"))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("connectTimeout");
		assertThatThrownBy(() -> new OllamaEmbeddingProperties(
				true,
				URI.create("http://127.0.0.1:11434"),
				DIGEST,
				true,
				Duration.ofSeconds(2),
				Duration.ofSeconds(-1),
				"5m"))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("readTimeout");
	}

	@Test
	void requiresAnExplicitLocalOnlyServerConfirmation() {
		assertThatThrownBy(() -> new OllamaEmbeddingProperties(
				true,
				URI.create("http://127.0.0.1:11434"),
				DIGEST,
				false,
				Duration.ofSeconds(2),
				Duration.ofSeconds(30),
				"5m"))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("localOnlyConfirmed");
	}

	@Test
	void rejectsUnsafeOrNonPositiveKeepAliveValues() {
		for (String value : new String[] {null, "", "0s", "-1s", "1.5h", "5 minutes", "5m\n"}) {
			assertThatThrownBy(() -> propertiesWithDigest(DIGEST, value))
					.isInstanceOf(IllegalArgumentException.class)
					.hasMessageContaining("keepAlive");
		}
	}

	private static OllamaEmbeddingProperties propertiesWithDigest(String digest, String keepAlive) {
		return new OllamaEmbeddingProperties(
				true,
				URI.create("http://127.0.0.1:11434"),
				digest,
				true,
				Duration.ofSeconds(2),
				Duration.ofSeconds(30),
				keepAlive);
	}

	private static OllamaEmbeddingProperties propertiesWithBaseUrl(String baseUrl, String keepAlive) {
		return new OllamaEmbeddingProperties(
				true,
				URI.create(baseUrl),
				DIGEST,
				true,
				Duration.ofSeconds(2),
				Duration.ofSeconds(30),
				keepAlive);
	}

	private static void assertInvalidBaseUrl(String value) {
		assertThatThrownBy(() -> propertiesWithBaseUrl(value, "5m"))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("loopback");
	}
}
