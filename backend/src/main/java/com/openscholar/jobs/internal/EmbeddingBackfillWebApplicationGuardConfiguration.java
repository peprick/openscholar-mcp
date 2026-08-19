package com.openscholar.jobs.internal;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@ConditionalOnWebApplication
@ConditionalOnProperty(
		prefix = EmbeddingBackfillProperties.PREFIX,
		name = "enabled",
		havingValue = "true")
class EmbeddingBackfillWebApplicationGuardConfiguration {

	@Bean
	InitializingBean rejectEmbeddingBackfillInWebApplication() {
		return () -> {
			throw new IllegalStateException(
					"Embedding backfill requires spring.main.web-application-type=none");
		};
	}
}
