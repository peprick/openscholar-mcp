package com.openscholar.privacy.internal;

final class PrivacyExportLimits {

	static final int MAX_COMBINED_RECORDS = 100_000;

	static final long MAX_SERIALIZED_BYTES = 134_217_728L;

	static final int TIMEOUT_SECONDS = 120;

	static final int FETCH_SIZE = 256;

	static final int PER_BRANCH_PREFLIGHT_LIMIT = MAX_COMBINED_RECORDS + 1;

	private PrivacyExportLimits() {
	}
}
