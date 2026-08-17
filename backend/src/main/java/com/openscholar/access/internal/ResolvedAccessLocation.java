package com.openscholar.access.internal;

import java.net.URI;
import java.time.Instant;

import com.openscholar.access.AccessHostType;
import com.openscholar.access.AccessStatus;
import com.openscholar.access.AccessVerificationStatus;
import com.openscholar.access.AccessVersionType;

public record ResolvedAccessLocation(
		String source,
		String sourceKey,
		boolean best,
		AccessStatus accessStatus,
		AccessVersionType versionType,
		AccessHostType hostType,
		URI landingPageUrl,
		URI pdfUrl,
		String license,
		String evidence,
		AccessVerificationStatus verificationStatus,
		Integer verificationHttpStatus,
		String verificationContentType,
		String verificationFailureCode,
		Instant providerUpdatedAt,
		Instant retrievedAt,
		Instant verifiedAt) {
}
