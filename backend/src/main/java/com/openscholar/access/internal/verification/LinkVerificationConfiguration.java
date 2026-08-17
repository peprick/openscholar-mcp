package com.openscholar.access.internal.verification;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(LinkVerificationProperties.class)
class LinkVerificationConfiguration {

	@Bean
	DnsResolver providerLinkDnsResolver() {
		return DnsResolver.system();
	}

	@Bean(destroyMethod = "close")
	ApacheHttpProbeClient providerLinkHttpProbeClient(
			LinkVerificationProperties properties,
			DnsResolver dnsResolver) {
		return ApacheHttpProbeClient.create(properties.toPolicy(), dnsResolver);
	}

	@Bean
	ProviderLinkVerifier providerLinkVerifier(
			LinkVerificationProperties properties,
			DnsResolver dnsResolver,
			ApacheHttpProbeClient httpProbeClient) {
		return new SafeOutboundLinkVerifier(properties.toPolicy(), dnsResolver, httpProbeClient);
	}
}
