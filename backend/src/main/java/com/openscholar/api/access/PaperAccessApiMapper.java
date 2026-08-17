package com.openscholar.api.access;

import com.openscholar.access.PaperAccessView;

final class PaperAccessApiMapper {

	private PaperAccessApiMapper() {
	}

	static PaperAccessResponse toResponse(PaperAccessView view) {
		var locations = view.locations().stream()
				.map(location -> new PaperAccessResponse.Location(
						location.id(),
						location.source(),
						location.best(),
						location.accessStatus(),
						location.versionType(),
						location.hostType(),
						location.landingPageUrl(),
						location.pdfUrl(),
						location.hostDomain(),
						location.license(),
						location.evidence(),
						location.contentHandling(),
						location.verificationStatus(),
						location.verificationHttpStatus(),
						location.verificationContentType(),
						location.verificationFailureCode(),
						location.providerUpdatedAt(),
						location.retrievedAt(),
						location.lastSeenAt(),
						location.verifiedAt()))
				.toList();
		var coverage = view.providerCoverage().stream()
				.map(item -> new PaperAccessResponse.ProviderCoverage(
						item.provider(), item.status(), item.candidateCount()))
				.toList();
		return new PaperAccessResponse(
				view.paperId(),
				view.status(),
				view.disposition(),
				view.checkedAt(),
				view.freshUntil(),
				locations.isEmpty() ? null : locations.getFirst().id(),
				coverage,
				view.warnings(),
				locations);
	}
}
