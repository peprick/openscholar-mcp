package com.openscholar.search.internal.persistence;

import java.util.Objects;

/** Shared pure filter semantics for holdout intake and scoring. */
final class RelatedTopicReuseHoldoutCandidateFilters {

	private RelatedTopicReuseHoldoutCandidateFilters() {
	}

	static boolean matches(
			RelatedTopicReuseHoldoutBundle.Candidate candidate,
			RelatedTopicReuseHoldoutBundle.Filter filter) {
		Objects.requireNonNull(candidate, "candidate");
		Objects.requireNonNull(filter, "filter");
		return (filter.yearFrom() == null
					|| candidate.publicationYear() != null
					&& candidate.publicationYear() >= filter.yearFrom())
				&& (filter.yearTo() == null
						|| candidate.publicationYear() != null
						&& candidate.publicationYear() <= filter.yearTo())
				&& (filter.documentTypes().isEmpty()
						|| filter.documentTypes().contains(candidate.documentType()))
				&& (!filter.openAccessOnly() || candidate.reportedOpenAccess())
				&& (candidate.citationCount() == null ? 0 : candidate.citationCount())
						>= filter.minimumCitations()
				&& (filter.languages().isEmpty()
						|| filter.languages().contains(candidate.language()));
	}
}
