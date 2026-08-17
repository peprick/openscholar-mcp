package com.openscholar.access.internal;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("openscholar.access")
class AccessProperties {

	private Duration cacheTtl = Duration.ofHours(24);
	private Duration forceRefreshCooldown = Duration.ofMinutes(5);
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

	public Duration getForceRefreshCooldown() {
		return forceRefreshCooldown;
	}

	public void setForceRefreshCooldown(Duration forceRefreshCooldown) {
		if (forceRefreshCooldown == null
				|| forceRefreshCooldown.isZero()
				|| forceRefreshCooldown.isNegative()
				|| forceRefreshCooldown.compareTo(Duration.ofHours(24)) > 0) {
			throw new IllegalArgumentException("Access force refresh cooldown must be between 1 nanosecond and 24 hours");
		}
		this.forceRefreshCooldown = forceRefreshCooldown;
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
