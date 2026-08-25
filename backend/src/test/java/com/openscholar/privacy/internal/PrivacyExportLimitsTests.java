package com.openscholar.privacy.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class PrivacyExportLimitsTests {

	@Test
	void freezesTheExportResourceLimits() {
		assertThat(PrivacyExportLimits.MAX_COMBINED_RECORDS).isEqualTo(100_000);
		assertThat(PrivacyExportLimits.MAX_SERIALIZED_BYTES).isEqualTo(134_217_728L);
		assertThat(PrivacyExportLimits.TIMEOUT_SECONDS).isEqualTo(120);
		assertThat(PrivacyExportLimits.FETCH_SIZE).isEqualTo(256);
		assertThat(PrivacyExportLimits.PER_BRANCH_PREFLIGHT_LIMIT).isEqualTo(100_001);
	}

	@Test
	void combinesBoundedBranchCountsWithoutWeakeningTheOverallLimit() {
		JdbcPrivacyExportStore.Counts exact =
				new JdbcPrivacyExportStore.Counts(99_998, 1, 1);
		JdbcPrivacyExportStore.Counts over =
				new JdbcPrivacyExportStore.Counts(99_999, 1, 1);

		assertThat(exact.total()).isEqualTo(100_000);
		assertThat(exact.exceedsCombinedLimit()).isFalse();
		assertThat(over.total()).isEqualTo(100_001);
		assertThat(over.exceedsCombinedLimit()).isTrue();
		assertThatThrownBy(() -> new JdbcPrivacyExportStore.Counts(100_002, 0, 0))
			.isInstanceOf(IllegalArgumentException.class);
	}
}
