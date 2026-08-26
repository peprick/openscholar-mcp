package com.openscholar.provider.europepmc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.time.Duration;

import org.junit.jupiter.api.Test;

class EuropePmcPropertiesTests {

	@Test
	void acceptsExplicitSafeSettings() {
		EuropePmcProperties properties = new EuropePmcProperties(
				true,
				URI.create("https://europe-pmc.test/webservices/rest"),
				Duration.ofSeconds(2),
				Duration.ofSeconds(4),
				4096);

		assertThat(properties.enabled()).isTrue();
		assertThat(properties.baseUrl()).hasToString("https://europe-pmc.test/webservices/rest");
		assertThat(properties.connectTimeout()).isEqualTo(Duration.ofSeconds(2));
		assertThat(properties.requestTimeout()).isEqualTo(Duration.ofSeconds(4));
		assertThat(properties.maxResponseBytes()).isEqualTo(4096);
		assertThat(properties.userAgent()).isEqualTo("OpenScholar/0.0.1");
	}

	@Test
	void rejectsUnsafeBaseUrls() {
		assertThatThrownBy(() -> properties(URI.create("/webservices/rest")))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessage("Europe PMC baseUrl must be an absolute HTTP(S) URL");
		assertThatThrownBy(() -> properties(URI.create("ftp://europe-pmc.test/rest")))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("without credentials, query, or fragment");
		assertThatThrownBy(() -> properties(URI.create("https://user:secret@europe-pmc.test/rest")))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("without credentials, query, or fragment");
		assertThatThrownBy(() -> properties(URI.create("https://europe-pmc.test/rest?query=owned")))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("without credentials, query, or fragment");
		assertThatThrownBy(() -> properties(URI.create("https://europe-pmc.test/rest#fragment")))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("without credentials, query, or fragment");
	}

	@Test
	void rejectsNonPositiveSafetyBounds() {
		assertThatThrownBy(() -> new EuropePmcProperties(
				true, URI.create("https://europe-pmc.test/rest"), Duration.ZERO, Duration.ofSeconds(1), 1))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessage("Europe PMC connectTimeout must be positive");
		assertThatThrownBy(() -> new EuropePmcProperties(
				true, URI.create("https://europe-pmc.test/rest"), Duration.ofSeconds(1), Duration.ZERO, 1))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessage("Europe PMC requestTimeout must be positive");
		assertThatThrownBy(() -> new EuropePmcProperties(
				true, URI.create("https://europe-pmc.test/rest"), Duration.ofSeconds(1), Duration.ofSeconds(1), 0))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessage("Europe PMC maxResponseBytes must be positive");
	}

	private static EuropePmcProperties properties(URI baseUrl) {
		return new EuropePmcProperties(
				true, baseUrl, Duration.ofSeconds(1), Duration.ofSeconds(1), 1024);
	}
}
