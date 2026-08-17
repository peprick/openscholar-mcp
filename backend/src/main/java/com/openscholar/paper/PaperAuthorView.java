package com.openscholar.paper;

import java.util.UUID;

public record PaperAuthorView(
		UUID id,
		String displayName,
		String orcid,
		String openAlexId,
		int position,
		boolean corresponding) {
}
