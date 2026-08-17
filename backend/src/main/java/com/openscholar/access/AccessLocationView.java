package com.openscholar.access;

import java.net.URI;
import java.time.Instant;
import java.util.UUID;

public record AccessLocationView(
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
