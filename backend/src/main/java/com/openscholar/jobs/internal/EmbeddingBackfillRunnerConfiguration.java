package com.openscholar.jobs.internal;

import java.util.List;

import com.openscholar.embedding.EmbeddingGenerator;
import com.openscholar.jobs.EmbeddingBackfillUseCase;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnNotWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(
		prefix = EmbeddingBackfillProperties.PREFIX,
		name = "enabled",
		havingValue = "true")
@ConditionalOnNotWebApplication
@EnableConfigurationProperties(EmbeddingBackfillProperties.class)
class EmbeddingBackfillRunnerConfiguration {

	@Bean
	ApplicationRunner embeddingBackfillRunner(
			EmbeddingBackfillUseCase useCase,
			EmbeddingBackfillProperties properties,
			List<EmbeddingGenerator> generators) {
		return new EmbeddingBackfillRunner(useCase, properties, generators);
	}
}
