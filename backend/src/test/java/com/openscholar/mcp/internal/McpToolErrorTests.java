package com.openscholar.mcp.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.time.Duration;
import java.util.Map;

import org.junit.jupiter.api.Test;

class McpToolErrorTests {

	@Test
	void exposesOnlyTheVersionedAllowlistedDescriptor() {
		McpToolError error = McpToolError.from(McpToolErrorCode.SEARCH_PROVIDER_UNAVAILABLE, true,
				Duration.ofMillis(6_001));

		assertThat(error.asMeta()).containsExactly(
				Map.entry("schemaVersion", 1),
				Map.entry("code", "SEARCH_PROVIDER_UNAVAILABLE"),
				Map.entry("category", "UPSTREAM_UNAVAILABLE"),
				Map.entry("message", "Research sources could not complete the search."),
				Map.entry("retryable", true),
				Map.entry("action", "RETRY_OR_USE_LOCAL_SEARCH"),
				Map.entry("retryAfterSeconds", 7L));
		assertThat(error.toText()).isEqualTo(
				"SEARCH_PROVIDER_UNAVAILABLE: Research sources could not complete the search. "
						+ "[category=UPSTREAM_UNAVAILABLE; retryable=true; "
						+ "action=RETRY_OR_USE_LOCAL_SEARCH; retryAfterSeconds=7]");
	}

	@Test
	void roundsTrustedPositiveRetryDelaysUpAndOmitsUnsafeValues() {
		assertThat(McpToolError.from(McpToolErrorCode.SEARCH_PROVIDER_UNAVAILABLE, true,
				Duration.ofMillis(1)).retryAfterSeconds()).isEqualTo(1L);
		assertThat(McpToolError.from(McpToolErrorCode.SEARCH_PROVIDER_UNAVAILABLE, true,
				Duration.ofMillis(1_001)).retryAfterSeconds()).isEqualTo(2L);
		assertThat(McpToolError.from(McpToolErrorCode.SEARCH_PROVIDER_UNAVAILABLE, true,
				Duration.ZERO).retryAfterSeconds()).isNull();
		assertThat(McpToolError.from(McpToolErrorCode.SEARCH_PROVIDER_UNAVAILABLE, true,
				Duration.ofMillis(-1)).retryAfterSeconds()).isNull();
		assertThat(McpToolError.from(McpToolErrorCode.SEARCH_PROVIDER_UNAVAILABLE, true,
				Duration.ofSeconds(McpToolError.MAX_RETRY_AFTER_SECONDS)).retryAfterSeconds())
			.isEqualTo(McpToolError.MAX_RETRY_AFTER_SECONDS);
		assertThat(McpToolError.from(McpToolErrorCode.SEARCH_PROVIDER_UNAVAILABLE, true,
				Duration.ofSeconds(McpToolError.MAX_RETRY_AFTER_SECONDS + 1)).retryAfterSeconds()).isNull();
	}

	@Test
	void neverPublishesRetryTimingForANonRetryableError() {
		McpToolError error = McpToolError.from(McpToolErrorCode.ACCESS_PROVIDERS_UNAVAILABLE, false,
				Duration.ofSeconds(9));

		assertThat(error.retryAfterSeconds()).isNull();
		assertThat(error.action()).isEqualTo("CONTACT_OPERATOR");
		assertThat(error.toText()).doesNotContain("action=RETRY", "action=WAIT_AND_RETRY");
		assertThat(error.asMeta()).doesNotContainKey("retryAfterSeconds");
		assertThat(error.toText()).doesNotContain("retryAfterSeconds");
		assertThatIllegalArgumentException()
			.isThrownBy(() -> new McpToolError(McpToolErrorCode.INVALID_REQUEST, false, 1L));
	}

	@Test
	void nonRetryableSearchProviderFailuresRecommendTheLocalFallbackInsteadOfRetrying() {
		McpToolError error = McpToolError.from(McpToolErrorCode.SEARCH_PROVIDER_UNAVAILABLE, false,
				Duration.ofSeconds(9));

		assertThat(error.retryable()).isFalse();
		assertThat(error.retryAfterSeconds()).isNull();
		assertThat(error.action()).isEqualTo("USE_LOCAL_SEARCH");
		assertThat(error.toText()).doesNotContain("action=RETRY", "retryAfterSeconds");
	}
}
