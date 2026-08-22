package com.openscholar.access.internal;

import java.time.Clock;
import java.time.Instant;
import java.util.Locale;

import com.openscholar.access.AccessHostType;
import com.openscholar.access.AccessStatus;
import com.openscholar.access.AccessVerificationStatus;
import com.openscholar.access.AccessVersionType;
import com.openscholar.access.internal.provider.AccessCandidate;
import com.openscholar.access.internal.provider.AccessSource;
import com.openscholar.access.internal.verification.LinkVerificationResult;
import com.openscholar.access.internal.verification.ProviderLinkType;
import com.openscholar.access.internal.verification.ProviderLinkVerifier;
import org.springframework.stereotype.Component;

@Component
class ProviderAccessCandidateVerifier implements AccessCandidateVerifier {

	private final ProviderLinkVerifier linkVerifier;
	private final Clock clock;

	ProviderAccessCandidateVerifier(ProviderLinkVerifier linkVerifier, Clock clock) {
		this.linkVerifier = linkVerifier;
		this.clock = clock;
	}

	@Override
	public CandidateVerificationOutcome verify(AccessCandidate candidate, Instant retrievedAt) {
		LinkVerificationResult pdfResult = null;
		if (candidate.pdfUrl() != null) {
			pdfResult = linkVerifier.verify(candidate.pdfUrl(), ProviderLinkType.PDF);
			if (pdfResult.verified()) {
				java.net.URI verifiedLandingPage = null;
				String warning = null;
				if (candidate.landingPageUrl() != null) {
					LinkVerificationResult landingResult = linkVerifier.verify(
							candidate.landingPageUrl(), ProviderLinkType.LANDING_PAGE);
					if (landingResult.verified()) {
						verifiedLandingPage = landingResult.finalUri();
					}
					else {
						warning = warning(candidate, "LANDING_" + landingResult.failure().name());
					}
				}
				return new CandidateVerificationOutcome(
						java.util.Optional.of(location(
								candidate,
								pdfStatus(candidate),
								verifiedLandingPage,
								pdfResult.finalUri(),
								pdfResult,
								retrievedAt)),
						warning);
			}
		}

		if (candidate.landingPageUrl() != null) {
			LinkVerificationResult landingResult = linkVerifier.verify(
					candidate.landingPageUrl(), ProviderLinkType.LANDING_PAGE);
			if (landingResult.verified()) {
				String warning = pdfResult == null
						? null
						: warning(candidate, "PDF_" + pdfResult.failure().name());
				return new CandidateVerificationOutcome(
						java.util.Optional.of(location(
								candidate,
								landingStatus(candidate),
								landingResult.finalUri(),
								null,
								landingResult,
								retrievedAt)),
						warning);
			}
			return CandidateVerificationOutcome.rejected(
					warning(candidate, "LANDING_" + landingResult.failure().name()));
		}

		return CandidateVerificationOutcome.rejected(
				warning(candidate, "PDF_" + pdfResult.failure().name()));
	}

	private ResolvedAccessLocation location(
			AccessCandidate candidate,
			AccessStatus status,
			java.net.URI landingPageUrl,
			java.net.URI pdfUrl,
			LinkVerificationResult verification,
			Instant retrievedAt) {
		Instant verifiedAt = clock.instant();
		return new ResolvedAccessLocation(
				candidate.source().name(),
				candidate.sourceKey(),
				candidate.best(),
				status,
				versionType(candidate),
				hostType(candidate),
				landingPageUrl,
				pdfUrl,
				candidate.license(),
				candidate.source().name() + "_REPORTED_ACCESS",
				AccessVerificationStatus.VERIFIED,
				verification.httpStatus(),
				null,
				null,
				candidate.providerUpdatedAt(),
				retrievedAt,
				verifiedAt);
	}

	private static AccessStatus landingStatus(AccessCandidate candidate) {
		return classifiedLocationStatus(candidate, AccessStatus.OPEN_LANDING_PAGE);
	}

	private static AccessStatus pdfStatus(AccessCandidate candidate) {
		return classifiedLocationStatus(candidate, AccessStatus.OPEN_PDF);
	}

	private static AccessStatus classifiedLocationStatus(AccessCandidate candidate, AccessStatus fallback) {
		if (candidate.source() == AccessSource.ARXIV) {
			return AccessStatus.PREPRINT;
		}
		return hostType(candidate) == AccessHostType.REPOSITORY
				? AccessStatus.REPOSITORY_COPY
				: fallback;
	}

	private static AccessHostType hostType(AccessCandidate candidate) {
		if (candidate.source() == AccessSource.ARXIV) {
			return AccessHostType.PREPRINT_SERVER;
		}
		String value = lower(candidate.hostType());
		return switch (value) {
			case "publisher" -> AccessHostType.PUBLISHER;
			case "repository" -> AccessHostType.REPOSITORY;
			default -> AccessHostType.UNKNOWN;
		};
	}

	private static AccessVersionType versionType(AccessCandidate candidate) {
		if (candidate.source() == AccessSource.ARXIV) {
			return AccessVersionType.PREPRINT;
		}
		return switch (lower(candidate.version())) {
			case "publishedversion", "published" -> AccessVersionType.PUBLISHED;
			case "acceptedversion", "accepted" -> AccessVersionType.ACCEPTED_MANUSCRIPT;
			case "submittedversion", "submitted" -> AccessVersionType.SUBMITTED_MANUSCRIPT;
			default -> AccessVersionType.UNKNOWN;
		};
	}

	private static String lower(String value) {
		return value == null ? "" : value.strip().toLowerCase(Locale.ROOT);
	}

	private static String warning(AccessCandidate candidate, String suffix) {
		return candidate.source().name() + "_" + suffix;
	}
}
