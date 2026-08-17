package com.openscholar.paper;

public record PaperAuthorCandidate(
		String openAlexId,
		String displayName,
		String orcid,
		int position,
		boolean corresponding) {
}
