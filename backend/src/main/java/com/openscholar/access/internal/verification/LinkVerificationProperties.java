package com.openscholar.access.internal.verification;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("openscholar.access.link-verification")
record LinkVerificationProperties(
		@DefaultValue("false") boolean allowHttp,
		@DefaultValue("4") int maxRedirects,
		@DefaultValue("3s") Duration connectTimeout,
		@DefaultValue("10s") Duration requestTimeout,
		@DefaultValue("4096") int maxProbeBytes) {

	LinkVerificationProperties {
		new LinkVerificationPolicy(allowHttp, maxRedirects, connectTimeout, requestTimeout, maxProbeBytes);
	}

	LinkVerificationPolicy toPolicy() {
		return new LinkVerificationPolicy(allowHttp, maxRedirects, connectTimeout, requestTimeout, maxProbeBytes);
	}
}
