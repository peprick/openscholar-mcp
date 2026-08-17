package com.openscholar.access;

public enum AccessDisposition {
	CACHE_HIT,
	RESOLVED,
	REFRESHED,
	FORCED_REFRESH,
	STALE_FALLBACK,
	NO_SUPPORTED_IDENTIFIER,
	NOT_YET_RESOLVED
}
