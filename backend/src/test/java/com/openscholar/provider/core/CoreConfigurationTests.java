package com.openscholar.provider.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;

import com.openscholar.provider.ResearchProvider;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.web.client.RestClient;

class CoreConfigurationTests {

	private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
			.withBean(RestClient.Builder.class, RestClient::builder)
			.withBean(Clock.class, Clock::systemUTC)
			.withUserConfiguration(CoreConfiguration.class);

	@Test
	void isEntirelyDisabledByDefault() {
		contextRunner.run(context -> {
			assertThat(context).hasNotFailed();
			assertThat(context).doesNotHaveBean(CoreProperties.class);
			assertThat(context).doesNotHaveBean(CoreConfiguration.REST_CLIENT_BEAN);
			assertThat(context).doesNotHaveBean(ResearchProvider.class);
		});
	}

	@Test
	void refusesEnablementWithoutAnOperatorLicenceConfirmation() {
		contextRunner
				.withPropertyValues(CoreProperties.PREFIX + ".enabled=true")
				.run(context -> {
					assertThat(context).hasFailed();
					assertThat(context.getStartupFailure()).hasRootCauseMessage(
							"CORE cannot be enabled until the operator confirms an applicable licence and terms");
				});
	}

	@Test
	void createsTheProviderOnlyAfterBothExplicitOptIns() {
		contextRunner
				.withPropertyValues(
						CoreProperties.PREFIX + ".enabled=true",
						CoreProperties.PREFIX + ".license-confirmed=true")
				.run(context -> {
					assertThat(context).hasNotFailed();
					assertThat(context).hasSingleBean(CoreProperties.class);
					assertThat(context).hasBean(CoreConfiguration.REST_CLIENT_BEAN);
					assertThat(context).hasSingleBean(ResearchProvider.class);
					assertThat(context.getBean(ResearchProvider.class)).isInstanceOf(CoreResearchProvider.class);
				});
	}

	@Test
	void bindsDefaultsAndCustomSafetySettings() {
		contextRunner
				.withPropertyValues(
						CoreProperties.PREFIX + ".enabled=true",
						CoreProperties.PREFIX + ".license-confirmed=true")
				.run(context -> {
					CoreProperties properties = context.getBean(CoreProperties.class);
					assertThat(properties.baseUrl()).hasToString("https://api.core.ac.uk");
					assertThat(properties.connectTimeout()).isEqualTo(Duration.ofSeconds(3));
					assertThat(properties.requestTimeout()).isEqualTo(Duration.ofSeconds(10));
					assertThat(properties.maxResponseBytes()).isEqualTo(8 * 1024 * 1024);
					assertThat(properties.apiKey()).isNull();
				});

		contextRunner
				.withPropertyValues(
						CoreProperties.PREFIX + ".enabled=true",
						CoreProperties.PREFIX + ".license-confirmed=true",
						CoreProperties.PREFIX + ".base-url=https://core.test/api",
						CoreProperties.PREFIX + ".connect-timeout=2s",
						CoreProperties.PREFIX + ".request-timeout=4s",
						CoreProperties.PREFIX + ".max-response-bytes=4096",
						CoreProperties.PREFIX + ".api-key=secret")
				.run(context -> {
					CoreProperties properties = context.getBean(CoreProperties.class);
					assertThat(properties.baseUrl()).hasToString("https://core.test/api");
					assertThat(properties.connectTimeout()).isEqualTo(Duration.ofSeconds(2));
					assertThat(properties.requestTimeout()).isEqualTo(Duration.ofSeconds(4));
					assertThat(properties.maxResponseBytes()).isEqualTo(4096);
					assertThat(properties.apiKey()).isEqualTo("secret");
				});
	}

	@Test
	void rejectsUnsafeOrNonPositiveSettings() {
		assertConfigurationFailure(
				CoreProperties.PREFIX + ".base-url=https://user:secret@core.test",
				"CORE baseUrl must be an absolute HTTP(S) URL without credentials, query, or fragment");
		assertConfigurationFailure(
				CoreProperties.PREFIX + ".request-timeout=0s",
				"CORE requestTimeout must be positive");
		assertConfigurationFailure(
				CoreProperties.PREFIX + ".max-response-bytes=0",
				"CORE maxResponseBytes must be positive");
		assertThatThrownBy(() -> new CoreProperties(
				true,
				true,
				java.net.URI.create("https://api.core.ac.uk"),
				Duration.ofSeconds(1),
				Duration.ofSeconds(1),
				1024,
				"line1\nline2"))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessage("CORE apiKey must not contain line breaks");
	}

	private void assertConfigurationFailure(String property, String rootCauseMessage) {
		contextRunner
				.withPropertyValues(
						CoreProperties.PREFIX + ".enabled=true",
						CoreProperties.PREFIX + ".license-confirmed=true",
						property)
				.run(context -> {
					assertThat(context).hasFailed();
					assertThat(context.getStartupFailure()).hasRootCauseMessage(rootCauseMessage);
				});
	}
}
