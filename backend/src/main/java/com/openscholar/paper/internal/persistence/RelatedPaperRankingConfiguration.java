package com.openscholar.paper.internal.persistence;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(RelatedPaperHybridProperties.class)
class RelatedPaperRankingConfiguration {
}
