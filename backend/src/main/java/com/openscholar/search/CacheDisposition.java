package com.openscholar.search;

public enum CacheDisposition {
	EXACT_HIT,
	MISS_FETCHED,
	STALE_REFRESHED,
	FORCED_REFRESH,
	STALE_FALLBACK
}
