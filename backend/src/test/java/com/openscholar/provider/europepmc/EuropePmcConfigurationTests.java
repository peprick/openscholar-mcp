package com.openscholar.provider.europepmc;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;

import com.openscholar.provider.ResearchProvider;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.web.client.RestClient;

class EuropePmcConfigurationTests {

	private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
			.withBean(RestClient.Builder.class, RestClient::builder)
			.withBean(Clock.class, Clock::systemUTC)
			.withUserConfiguration(EuropePmcConfiguration.class);

	@Test
	void isEntirelyDisabledByDefault() {
		contextRunner.run(context -> {
			assertThat(context).hasNotFailed();
			assertThat(context).doesNotHaveBean(EuropePmcProperties.class);
			assertThat(context).doesNotHaveBean(EuropePmcConfiguration.REST_CLIENT_BEAN);
			assertThat(context).doesNotHaveBean(ResearchProvider.class);
		});
	}

	@Test
	void createsTheProviderOnlyWhenExplicitlyEnabled() {
		contextRunner
				.withPropertyValues(EuropePmcProperties.PREFIX + ".enabled=true")
				.run(context -> {
					assertThat(context).hasNotFailed();
					assertThat(context).hasSingleBean(EuropePmcProperties.class);
					assertThat(context).hasBean(EuropePmcConfiguration.REST_CLIENT_BEAN);
					assertThat(context).hasSingleBean(ResearchProvider.class);
					assertThat(context.getBean(ResearchProvider.class))
							.isInstanceOf(EuropePmcResearchProvider.class);
				});
	}

	@Test
	void bindsDefaultsAndCustomSafetySettings() {
		contextRunner
				.withPropertyValues(EuropePmcProperties.PREFIX + ".enabled=true")
				.run(context -> {
					EuropePmcProperties properties = context.getBean(EuropePmcProperties.class);
					assertThat(properties.baseUrl())
							.hasToString("https://www.ebi.ac.uk/europepmc/webservices/rest");
					assertThat(properties.connectTimeout()).isEqualTo(Duration.ofSeconds(3));
					assertThat(properties.requestTimeout()).isEqualTo(Duration.ofSeconds(10));
					assertThat(properties.maxResponseBytes()).isEqualTo(8 * 1024 * 1024);
				});

		contextRunner
				.withPropertyValues(
						EuropePmcProperties.PREFIX + ".enabled=true",
						EuropePmcProperties.PREFIX + ".base-url=https://europe-pmc.test/rest",
						EuropePmcProperties.PREFIX + ".connect-timeout=2s",
						EuropePmcProperties.PREFIX + ".request-timeout=4s",
						EuropePmcProperties.PREFIX + ".max-response-bytes=4096")
				.run(context -> {
					EuropePmcProperties properties = context.getBean(EuropePmcProperties.class);
					assertThat(properties.baseUrl()).hasToString("https://europe-pmc.test/rest");
					assertThat(properties.connectTimeout()).isEqualTo(Duration.ofSeconds(2));
					assertThat(properties.requestTimeout()).isEqualTo(Duration.ofSeconds(4));
					assertThat(properties.maxResponseBytes()).isEqualTo(4096);
				});
	}

	@Test
	void pinsTheProviderClientToHttpOneForEuropePmcEdgeCompatibility() {
		EuropePmcProperties properties = new EuropePmcProperties(
				true,
				URI.create("https://www.ebi.ac.uk/europepmc/webservices/rest"),
				Duration.ofSeconds(3),
				Duration.ofSeconds(10),
				8 * 1024 * 1024);

		assertThat(EuropePmcConfiguration.buildHttpClient(properties).version())
				.isEqualTo(HttpClient.Version.HTTP_1_1);
	}
}
