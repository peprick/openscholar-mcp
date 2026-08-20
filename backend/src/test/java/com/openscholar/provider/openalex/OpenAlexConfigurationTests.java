package com.openscholar.provider.openalex;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.web.client.RestClient;

class OpenAlexConfigurationTests {

	private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
			.withBean(RestClient.Builder.class, RestClient::builder)
			.withBean(Clock.class, Clock::systemUTC)
			.withUserConfiguration(OpenAlexConfiguration.class);

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
