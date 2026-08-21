package com.openscholar.provider.datacite;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;

import com.openscholar.provider.ResearchProvider;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.web.client.RestClient;

class DataCiteConfigurationTests {

	private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
			.withBean(RestClient.Builder.class, RestClient::builder)
			.withBean(Clock.class, Clock::systemUTC)
			.withUserConfiguration(DataCiteConfiguration.class);

	@Test
	void isEntirelyDisabledByDefault() {
		contextRunner.run(context -> {
			assertThat(context).hasNotFailed();
			assertThat(context).doesNotHaveBean(DataCiteProperties.class);
			assertThat(context).doesNotHaveBean(DataCiteConfiguration.REST_CLIENT_BEAN);
			assertThat(context).doesNotHaveBean(ResearchProvider.class);
		});
	}

	@Test
	void createsTheProviderOnlyWhenExplicitlyEnabled() {
		contextRunner
				.withPropertyValues(DataCiteProperties.PREFIX + ".enabled=true")
				.run(context -> {
					assertThat(context).hasNotFailed();
					assertThat(context).hasSingleBean(DataCiteProperties.class);
					assertThat(context).hasBean(DataCiteConfiguration.REST_CLIENT_BEAN);
					assertThat(context).hasSingleBean(ResearchProvider.class);
					assertThat(context.getBean(ResearchProvider.class))
							.isInstanceOf(DataCiteResearchProvider.class);
				});
	}

	@Test
	void bindsDefaultsAndCustomSafetySettings() {
		contextRunner
				.withPropertyValues(DataCiteProperties.PREFIX + ".enabled=true")
				.run(context -> {
					DataCiteProperties properties = context.getBean(DataCiteProperties.class);
					assertThat(properties.baseUrl()).hasToString("https://api.datacite.org");
					assertThat(properties.connectTimeout()).isEqualTo(Duration.ofSeconds(3));
					assertThat(properties.requestTimeout()).isEqualTo(Duration.ofSeconds(10));
					assertThat(properties.maxResponseBytes()).isEqualTo(8 * 1024 * 1024);
					assertThat(properties.contactEmail()).isNull();
				});

		contextRunner
				.withPropertyValues(
						DataCiteProperties.PREFIX + ".enabled=true",
						DataCiteProperties.PREFIX + ".base-url=https://datacite.test/api",
						DataCiteProperties.PREFIX + ".connect-timeout=2s",
						DataCiteProperties.PREFIX + ".request-timeout=4s",
						DataCiteProperties.PREFIX + ".max-response-bytes=4096",
						DataCiteProperties.PREFIX + ".contact-email=researcher@example.org")
				.run(context -> {
					DataCiteProperties properties = context.getBean(DataCiteProperties.class);
					assertThat(properties.baseUrl()).hasToString("https://datacite.test/api");
					assertThat(properties.connectTimeout()).isEqualTo(Duration.ofSeconds(2));
					assertThat(properties.requestTimeout()).isEqualTo(Duration.ofSeconds(4));
					assertThat(properties.maxResponseBytes()).isEqualTo(4096);
					assertThat(properties.contactEmail()).isEqualTo("researcher@example.org");
				});
	}

	@Test
	void rejectsUnsafeOrNonPositiveSettings() {
		assertConfigurationFailure(
				DataCiteProperties.PREFIX + ".base-url=https://user:secret@datacite.test",
				"DataCite baseUrl must be an absolute HTTP(S) URL without credentials, query, or fragment");
		assertConfigurationFailure(
				DataCiteProperties.PREFIX + ".request-timeout=0s",
				"DataCite requestTimeout must be positive");
		assertConfigurationFailure(
				DataCiteProperties.PREFIX + ".max-response-bytes=0",
				"DataCite maxResponseBytes must be positive");
		assertConfigurationFailure(
				DataCiteProperties.PREFIX + ".contact-email=not-an-email",
				"DataCite contactEmail must be a single valid email address");
	}

	private void assertConfigurationFailure(String property, String rootCauseMessage) {
		contextRunner
				.withPropertyValues(DataCiteProperties.PREFIX + ".enabled=true", property)
				.run(context -> {
					assertThat(context).hasFailed();
					assertThat(context.getStartupFailure()).hasRootCauseMessage(rootCauseMessage);
				});
	}
}
