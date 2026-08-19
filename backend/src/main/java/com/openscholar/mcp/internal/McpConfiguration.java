package com.openscholar.mcp.internal;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({McpSecurityProperties.class, McpRateLimitProperties.class})
class McpConfiguration {
}
