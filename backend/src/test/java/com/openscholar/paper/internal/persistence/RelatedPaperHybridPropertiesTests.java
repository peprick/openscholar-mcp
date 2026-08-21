package com.openscholar.paper.internal.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class RelatedPaperHybridPropertiesTests {

	@Test
	void acceptsOnlyTheBoundedCandidatePool() {
		RelatedPaperHybridProperties properties = new RelatedPaperHybridProperties(true, 100);

		assertThat(properties.enabled()).isTrue();
		assertThat(properties.candidatePoolSize()).isEqualTo(100);
		assertThatThrownBy(() -> new RelatedPaperHybridProperties(true, 24))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("between 25 and 100");
		assertThatThrownBy(() -> new RelatedPaperHybridProperties(true, 101))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("between 25 and 100");
	}
}
