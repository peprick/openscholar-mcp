package com.openscholar.paper.internal.persistence;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(RelatedPaperHybridProperties.PREFIX)
record RelatedPaperHybridProperties(
		@DefaultValue("false") boolean enabled,
		@DefaultValue("100") int candidatePoolSize) {

	static final String PREFIX = "openscholar.related-papers.hybrid";
	static final int MINIMUM_CANDIDATE_POOL_SIZE = 25;
	static final int MAXIMUM_CANDIDATE_POOL_SIZE = 100;

	RelatedPaperHybridProperties {
		if (candidatePoolSize < MINIMUM_CANDIDATE_POOL_SIZE
				|| candidatePoolSize > MAXIMUM_CANDIDATE_POOL_SIZE) {
			throw new IllegalArgumentException(
					"Related-paper hybrid candidate pool size must be between "
							+ MINIMUM_CANDIDATE_POOL_SIZE + " and "
							+ MAXIMUM_CANDIDATE_POOL_SIZE);
		}
	}
}
