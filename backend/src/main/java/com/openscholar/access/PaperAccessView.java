package com.openscholar.access;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record PaperAccessView(
		UUID paperId,
		AccessStatus status,
		AccessDisposition disposition,
		Instant checkedAt,
		Instant freshUntil,
		List<AccessProviderCoverageView> providerCoverage,
		List<String> warnings,
		List<AccessLocationView> locations) {

	public PaperAccessView {
		providerCoverage = providerCoverage == null ? List.of() : List.copyOf(providerCoverage);
		warnings = warnings == null ? List.of() : List.copyOf(warnings);
		locations = locations == null ? List.of() : List.copyOf(locations);
	}
}
