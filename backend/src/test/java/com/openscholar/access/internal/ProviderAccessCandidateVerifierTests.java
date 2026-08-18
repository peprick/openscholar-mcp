package com.openscholar.access.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Map;

import com.openscholar.access.AccessHostType;
import com.openscholar.access.AccessStatus;
import com.openscholar.access.AccessVersionType;
import com.openscholar.access.internal.provider.AccessCandidate;
import com.openscholar.access.internal.provider.AccessSource;
import com.openscholar.access.internal.verification.LinkVerificationFailure;
import com.openscholar.access.internal.verification.LinkVerificationResult;
import com.openscholar.access.internal.verification.ProviderLinkType;
import com.openscholar.access.internal.verification.ProviderLinkVerifier;
import org.junit.jupiter.api.Test;

class ProviderAccessCandidateVerifierTests {

	private static final Instant RETRIEVED_AT = Instant.parse("2026-08-17T10:00:00Z");
	private static final Instant VERIFIED_AT = Instant.parse("2026-08-17T10:05:00Z");

	@Test
	void persistsOnlyVerifiedFinalPdfAndLandingUris() {
		URI reportedPdf = URI.create("https://repository.example/reported.pdf");
		URI reportedLanding = URI.create("https://repository.example/reported");
		URI finalPdf = URI.create("https://cdn.example/paper.pdf");
		URI finalLanding = URI.create("https://repository.example/paper");
		StubLinkVerifier links = new StubLinkVerifier()
				.respond(reportedPdf, verified(finalPdf, 206))
				.respond(reportedLanding, verified(finalLanding, 200));
		ProviderAccessCandidateVerifier verifier = verifier(links);

		var outcome = verifier.verify(candidate(
				AccessSource.UNPAYWALL,
				"publisher",
				"publishedVersion",
				reportedLanding,
				reportedPdf), RETRIEVED_AT);

		assertThat(outcome.warningCode()).isNull();
		assertThat(outcome.location()).hasValueSatisfying(location -> {
			assertThat(location.accessStatus()).isEqualTo(AccessStatus.OPEN_PDF);
			assertThat(location.hostType()).isEqualTo(AccessHostType.PUBLISHER);
			assertThat(location.versionType()).isEqualTo(AccessVersionType.PUBLISHED);
			assertThat(location.pdfUrl()).isEqualTo(finalPdf);
			assertThat(location.landingPageUrl()).isEqualTo(finalLanding);
			assertThat(location.retrievedAt()).isEqualTo(RETRIEVED_AT);
			assertThat(location.verifiedAt()).isEqualTo(VERIFIED_AT);
		});
		assertThat(links.calls).containsExactly(
				new Call(reportedPdf, ProviderLinkType.PDF),
				new Call(reportedLanding, ProviderLinkType.LANDING_PAGE));
	}

	@Test
	void fallsBackToVerifiedOpenLandingPageWhenPdfProbeFails() {
		URI pdf = URI.create("https://repository.example/not-a-pdf");
		URI landing = URI.create("https://repository.example/paper");
		StubLinkVerifier links = new StubLinkVerifier()
				.respond(pdf, rejected(pdf, LinkVerificationFailure.PDF_CONTENT_NOT_VERIFIED))
				.respond(landing, verified(landing, 200));

		var outcome = verifier(links).verify(candidate(
				AccessSource.UNPAYWALL,
				"repository",
				"acceptedVersion",
				landing,
				pdf), RETRIEVED_AT);

		assertThat(outcome.warningCode()).isEqualTo("UNPAYWALL_PDF_PDF_CONTENT_NOT_VERIFIED");
		assertThat(outcome.location()).hasValueSatisfying(location -> {
			assertThat(location.accessStatus()).isEqualTo(AccessStatus.OPEN_LANDING_PAGE);
			assertThat(location.hostType()).isEqualTo(AccessHostType.REPOSITORY);
			assertThat(location.versionType()).isEqualTo(AccessVersionType.ACCEPTED_MANUSCRIPT);
			assertThat(location.pdfUrl()).isNull();
			assertThat(location.landingPageUrl()).isEqualTo(landing);
		});
	}

	@Test
	void keepsArxivClassificationSeparateFromLandingPageAccessStatus() {
		URI landing = URI.create("https://arxiv.org/abs/2608.12345");
		StubLinkVerifier links = new StubLinkVerifier().respond(landing, verified(landing, 200));

		var outcome = verifier(links).verify(candidate(
				AccessSource.ARXIV,
				null,
				null,
				landing,
				null), RETRIEVED_AT);

		assertThat(outcome.location()).hasValueSatisfying(location -> {
			assertThat(location.accessStatus()).isEqualTo(AccessStatus.OPEN_LANDING_PAGE);
			assertThat(location.hostType()).isEqualTo(AccessHostType.PREPRINT_SERVER);
			assertThat(location.versionType()).isEqualTo(AccessVersionType.PREPRINT);
		});
	}

	@Test
	void keepsVerifiedPdfWhenOptionalLandingPageFails() {
		URI pdf = URI.create("https://example.org/paper.pdf");
		URI landing = URI.create("https://example.org/paper");
		StubLinkVerifier links = new StubLinkVerifier()
				.respond(pdf, verified(pdf, 200))
				.respond(landing, rejected(landing, LinkVerificationFailure.NETWORK_ERROR));

		var outcome = verifier(links).verify(candidate(
				AccessSource.UNPAYWALL,
				"publisher",
				"published",
				landing,
				pdf), RETRIEVED_AT);

		assertThat(outcome.warningCode()).isEqualTo("UNPAYWALL_LANDING_NETWORK_ERROR");
		assertThat(outcome.location()).hasValueSatisfying(location -> {
			assertThat(location.accessStatus()).isEqualTo(AccessStatus.OPEN_PDF);
			assertThat(location.pdfUrl()).isEqualTo(pdf);
			assertThat(location.landingPageUrl()).isNull();
		});
	}

	@Test
	void rejectsCandidateWhenNoReportedUrlCanBeVerified() {
		URI pdf = URI.create("https://example.org/paper.pdf");
		StubLinkVerifier links = new StubLinkVerifier()
				.respond(pdf, rejected(pdf, LinkVerificationFailure.ADDRESS_NOT_PUBLIC));

		var outcome = verifier(links).verify(candidate(
				AccessSource.UNPAYWALL,
				"repository",
				"submitted",
				null,
				pdf), RETRIEVED_AT);

		assertThat(outcome.location()).isEmpty();
		assertThat(outcome.warningCode()).isEqualTo("UNPAYWALL_PDF_ADDRESS_NOT_PUBLIC");
	}

	private static ProviderAccessCandidateVerifier verifier(ProviderLinkVerifier links) {
		return new ProviderAccessCandidateVerifier(links, Clock.fixed(VERIFIED_AT, ZoneOffset.UTC));
	}

	private static AccessCandidate candidate(
			AccessSource source,
			String hostType,
			String version,
			URI landingPage,
			URI pdf) {
		return new AccessCandidate(
				source,
				"source-key",
				true,
				hostType,
				version,
				"cc-by",
				landingPage,
				pdf,
				Instant.parse("2026-08-16T00:00:00Z"),
				Map.of());
	}

	private static LinkVerificationResult verified(URI uri, int status) {
		return new LinkVerificationResult(
				LinkVerificationResult.Status.VERIFIED,
				uri,
				0,
				status,
				LinkVerificationFailure.NONE);
	}

	private static LinkVerificationResult rejected(URI uri, LinkVerificationFailure failure) {
		return new LinkVerificationResult(
				LinkVerificationResult.Status.REJECTED,
				uri,
				0,
				-1,
				failure);
	}

	private record Call(URI uri, ProviderLinkType type) {
	}

	private static final class StubLinkVerifier implements ProviderLinkVerifier {

		private final Map<URI, LinkVerificationResult> responses = new java.util.HashMap<>();
		private final ArrayList<Call> calls = new ArrayList<>();

		StubLinkVerifier respond(URI uri, LinkVerificationResult result) {
			responses.put(uri, result);
			return this;
		}

		@Override
		public LinkVerificationResult verify(URI uri, ProviderLinkType type) {
			calls.add(new Call(uri, type));
			return responses.get(uri);
		}
	}
}
