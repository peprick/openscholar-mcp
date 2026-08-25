package com.openscholar.privacy.internal;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(PrivacyExportProperties.class)
class PrivacyConfiguration {
}
