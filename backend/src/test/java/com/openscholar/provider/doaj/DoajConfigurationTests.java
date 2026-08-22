package com.openscholar.provider.doaj;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;

import com.openscholar.provider.ResearchProvider;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.web.client.RestClient;

class DoajConfigurationTests {

	private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
			.withBean(RestClient.Builder.class, RestClient::builder)
			.withBean(Clock.class, Clock::systemUTC)
			.withUserConfiguration(DoajConfiguration.class);

	@Test
	void isEntirelyDisabledByDefault() {
		contextRunner.run(context -> {
			assertThat(context).hasNotFailed();
			assertThat(context).doesNotHaveBean(DoajProperties.class);
			assertThat(context).doesNotHaveBean(DoajConfiguration.REST_CLIENT_BEAN);
			assertThat(context).doesNotHaveBean(ResearchProvider.class);
		});
	}

	@Test
	void createsTheProviderOnlyWhenExplicitlyEnabled() {
		contextRunner
				.withPropertyValues(DoajProperties.PREFIX + ".enabled=true")
				.run(context -> {
					assertThat(context).hasNotFailed();
					assertThat(context).hasSingleBean(DoajProperties.class);
					assertThat(context).hasBean(DoajConfiguration.REST_CLIENT_BEAN);
					assertThat(context).hasSingleBean(ResearchProvider.class);
					assertThat(context.getBean(ResearchProvider.class))
							.isInstanceOf(DoajResearchProvider.class);
				});
	}

	@Test
	void bindsDefaultsAndCustomSafetySettings() {
		contextRunner
				.withPropertyValues(DoajProperties.PREFIX + ".enabled=true")
				.run(context -> {
					DoajProperties properties = context.getBean(DoajProperties.class);
					assertThat(properties.baseUrl()).hasToString("https://doaj.org/api/v4");
					assertThat(properties.connectTimeout()).isEqualTo(Duration.ofSeconds(3));
					assertThat(properties.requestTimeout()).isEqualTo(Duration.ofSeconds(10));
					assertThat(properties.maxResponseBytes()).isEqualTo(8 * 1024 * 1024);
					assertThat(properties.contactEmail()).isNull();
				});

		contextRunner
				.withPropertyValues(
						DoajProperties.PREFIX + ".enabled=true",
						DoajProperties.PREFIX + ".base-url=https://doaj.test/api/v4",
						DoajProperties.PREFIX + ".connect-timeout=2s",
						DoajProperties.PREFIX + ".request-timeout=4s",
						DoajProperties.PREFIX + ".max-response-bytes=4096",
						DoajProperties.PREFIX + ".contact-email=researcher@example.org")
				.run(context -> {
					DoajProperties properties = context.getBean(DoajProperties.class);
					assertThat(properties.baseUrl()).hasToString("https://doaj.test/api/v4");
					assertThat(properties.connectTimeout()).isEqualTo(Duration.ofSeconds(2));
					assertThat(properties.requestTimeout()).isEqualTo(Duration.ofSeconds(4));
					assertThat(properties.maxResponseBytes()).isEqualTo(4096);
					assertThat(properties.contactEmail()).isEqualTo("researcher@example.org");
				});
	}

	@Test
	void rejectsUnsafeOrNonPositiveSettings() {
		assertConfigurationFailure(
				DoajProperties.PREFIX + ".base-url=https://user:secret@doaj.test/api/v4",
				"DOAJ baseUrl must be an absolute HTTP(S) URL without credentials, query, or fragment");
		assertConfigurationFailure(
				DoajProperties.PREFIX + ".request-timeout=0s",
				"DOAJ requestTimeout must be positive");
		assertConfigurationFailure(
				DoajProperties.PREFIX + ".max-response-bytes=0",
				"DOAJ maxResponseBytes must be positive");
		assertConfigurationFailure(
				DoajProperties.PREFIX + ".contact-email=not-an-email",
				"DOAJ contactEmail must be a single valid email address");
	}

	private void assertConfigurationFailure(String property, String rootCauseMessage) {
		contextRunner
				.withPropertyValues(DoajProperties.PREFIX + ".enabled=true", property)
				.run(context -> {
					assertThat(context).hasFailed();
					assertThat(context.getStartupFailure()).hasRootCauseMessage(rootCauseMessage);
				});
	}
}
