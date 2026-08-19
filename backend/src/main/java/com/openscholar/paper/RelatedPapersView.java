package com.openscholar.paper;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

public record RelatedPapersView(UUID sourcePaperId, List<RelatedPaperMatch> results) {

	public RelatedPapersView {
		Objects.requireNonNull(sourcePaperId, "sourcePaperId");
		results = results == null ? List.of() : List.copyOf(results);
	}
}
