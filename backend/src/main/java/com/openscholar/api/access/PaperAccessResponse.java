package com.openscholar.api.access;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.openscholar.access.AccessDisposition;
import com.openscholar.access.AccessHostType;
import com.openscholar.access.AccessStatus;
import com.openscholar.access.AccessVerificationStatus;
import com.openscholar.access.AccessVersionType;
import com.openscholar.access.ContentHandlingMode;

public record PaperAccessResponse(
		UUID paperId,
		AccessStatus status,
		AccessDisposition cacheDisposition,
		Instant checkedAt,
		Instant freshUntil,
		UUID bestLocationId,
		List<ProviderCoverage> providerCoverage,
		List<String> warnings,
		List<Location> locations) {

	public record ProviderCoverage(String provider, String status, int candidateCount) {
	}

	public record Location(
			UUID id,
			String source,
			boolean best,
			AccessStatus accessStatus,
			AccessVersionType versionType,
			AccessHostType hostType,
			URI landingPageUrl,
			URI pdfUrl,
			String hostDomain,
			String license,
			String evidence,
			ContentHandlingMode contentHandling,
			AccessVerificationStatus verificationStatus,
			Integer verificationHttpStatus,
			String verificationContentType,
			String verificationFailureCode,
			Instant providerUpdatedAt,
			Instant retrievedAt,
			Instant lastSeenAt,
			Instant verifiedAt) {
	}
}
