package com.openscholar.jobs.internal;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnProperty(prefix = "openscholar.jobs.refresh", name = "worker-enabled", havingValue = "true")
class ResearchRefreshSchedulingConfiguration {
}
