package com.openscholar.jobs.internal;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ResearchRefreshJobProperties.class)
class ResearchRefreshJobConfiguration {
}
