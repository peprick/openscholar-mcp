package com.openscholar.jobs.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import com.openscholar.jobs.EmbeddingBackfillDisposition;
import com.openscholar.jobs.EmbeddingBackfillResult;
import com.openscholar.jobs.EmbeddingBackfillUseCase;
import org.junit.jupiter.api.Test;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;

class EmbeddingBackfillRunnerConfigurationTests {

	private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
			.withUserConfiguration(EmbeddingBackfillRunnerConfiguration.class)
			.withBean(EmbeddingBackfillUseCase.class, () -> command -> new EmbeddingBackfillResult(
					command.profileKey(),
					EmbeddingBackfillDisposition.COMPLETED,
					0,
					0,
					0,
					0,
					List.of(),
					null));

	@Test
	void createsNoRunnerByDefault() {
		contextRunner.run(context -> assertThat(context).doesNotHaveBean(ApplicationRunner.class));
	}

	@Test
	void createsOneRunnerOnlyWhenExplicitlyEnabled() {
		contextRunner
				.withPropertyValues("openscholar.embedding.backfill.enabled=true")
				.run(context -> {
					assertThat(context).hasSingleBean(ApplicationRunner.class);
					assertThat(context).hasSingleBean(EmbeddingBackfillProperties.class);
				});
	}

	@Test
	void failsClosedWhenBackfillIsEnabledForAWebApplication() {
		new WebApplicationContextRunner()
				.withUserConfiguration(EmbeddingBackfillWebApplicationGuardConfiguration.class)
				.withPropertyValues("openscholar.embedding.backfill.enabled=true")
				.run(context -> {
					assertThat(context).hasFailed();
					assertThat(context.getStartupFailure())
							.hasRootCauseMessage(
									"Embedding backfill requires spring.main.web-application-type=none");
				});
	}
}
