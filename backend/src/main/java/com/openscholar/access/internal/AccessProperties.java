package com.openscholar.access.internal;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("openscholar.access")
class AccessProperties {

	private Duration cacheTtl = Duration.ofHours(24);
	private int maxLocationsToVerify = 3;

	public Duration getCacheTtl() {
		return cacheTtl;
	}

	public void setCacheTtl(Duration cacheTtl) {
		if (cacheTtl == null || cacheTtl.isZero() || cacheTtl.isNegative()) {
			throw new IllegalArgumentException("Access cache TTL must be positive");
		}
		this.cacheTtl = cacheTtl;
	}

	public int getMaxLocationsToVerify() {
		return maxLocationsToVerify;
	}

	public void setMaxLocationsToVerify(int maxLocationsToVerify) {
		if (maxLocationsToVerify < 1 || maxLocationsToVerify > 10) {
			throw new IllegalArgumentException("Access maxLocationsToVerify must be between 1 and 10");
		}
		this.maxLocationsToVerify = maxLocationsToVerify;
	}
}
