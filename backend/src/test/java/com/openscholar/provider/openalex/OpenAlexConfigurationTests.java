package com.openscholar.provider.openalex;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.web.client.RestClient;

class OpenAlexConfigurationTests {

	private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
			.withBean(RestClient.Builder.class, RestClient::builder)
			.withBean(Clock.class, Clock::systemUTC)
			.withUserConfiguration(OpenAlexConfiguration.class);
	private final ApplicationContextRunner applicationConfigurationRunner = contextRunner
			.withInitializer(new ConfigDataApplicationContextInitializer());

	@Test
	void bindsTheTenSecondDefaultAndACustomRequestTimeout() {
		contextRunner.run(context -> {
			assertThat(context).hasNotFailed();
			assertThat(context.getBean(OpenAlexProperties.class).requestTimeout())
					.isEqualTo(Duration.ofSeconds(10));
		});

		contextRunner
				.withPropertyValues(OpenAlexProperties.PREFIX + ".request-timeout=4s")
				.run(context -> {
					assertThat(context).hasNotFailed();
					assertThat(context.getBean(OpenAlexProperties.class).requestTimeout())
							.isEqualTo(Duration.ofSeconds(4));
				});
	}

	@Test
	void bindsTheLegacyReadTimeoutFallbackButPrefersTheNewEnvironmentVariable() {
		applicationConfigurationRunner
				.withPropertyValues("OPENALEX_READ_TIMEOUT=7s")
				.run(context -> {
					assertThat(context).hasNotFailed();
					assertThat(context.getBean(OpenAlexProperties.class).requestTimeout())
							.isEqualTo(Duration.ofSeconds(7));
				});

		applicationConfigurationRunner
				.withPropertyValues("OPENALEX_REQUEST_TIMEOUT=4s", "OPENALEX_READ_TIMEOUT=7s")
				.run(context -> {
					assertThat(context).hasNotFailed();
					assertThat(context.getBean(OpenAlexProperties.class).requestTimeout())
							.isEqualTo(Duration.ofSeconds(4));
				});
	}

	@Test
	void bindsTheLegacyCanonicalPropertyButPrefersTheNewCanonicalProperty() {
		applicationConfigurationRunner
				.withPropertyValues(OpenAlexProperties.PREFIX + ".read-timeout=6s")
				.run(context -> {
					assertThat(context).hasNotFailed();
					assertThat(context.getBean(OpenAlexProperties.class).requestTimeout())
							.isEqualTo(Duration.ofSeconds(6));
				});

		applicationConfigurationRunner
				.withPropertyValues(
						OpenAlexProperties.PREFIX + ".request-timeout=4s",
						OpenAlexProperties.PREFIX + ".read-timeout=6s")
				.run(context -> {
					assertThat(context).hasNotFailed();
					assertThat(context.getBean(OpenAlexProperties.class).requestTimeout())
							.isEqualTo(Duration.ofSeconds(4));
				});
	}

	@Test
	void rejectsANonPositiveRequestTimeout() {
		contextRunner
				.withPropertyValues(OpenAlexProperties.PREFIX + ".request-timeout=0s")
				.run(context -> {
					assertThat(context).hasFailed();
					assertThat(context.getStartupFailure())
							.hasRootCauseMessage("OpenAlex requestTimeout must be positive");
				});
	}

	@Test
	void bindsTheEightMebibyteDefaultAndACustomResponseLimit() {
		contextRunner.run(context -> {
			assertThat(context).hasNotFailed();
			assertThat(context.getBean(OpenAlexProperties.class).maxResponseBytes())
					.isEqualTo(8 * 1024 * 1024);
		});

		contextRunner
				.withPropertyValues(OpenAlexProperties.PREFIX + ".max-response-bytes=4096")
				.run(context -> {
					assertThat(context).hasNotFailed();
					assertThat(context.getBean(OpenAlexProperties.class).maxResponseBytes()).isEqualTo(4096);
				});
	}

	@Test
	void rejectsANonPositiveResponseLimit() {
		contextRunner
				.withPropertyValues(OpenAlexProperties.PREFIX + ".max-response-bytes=0")
				.run(context -> {
					assertThat(context).hasFailed();
					assertThat(context.getStartupFailure())
							.hasRootCauseMessage("OpenAlex maxResponseBytes must be positive");
				});
	}
}
