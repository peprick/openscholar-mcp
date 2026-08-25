package com.openscholar.mcp.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class McpToolResultBudgetTests {

	@Test
	void returnsAResultWhoseSerializedUtf8FitsTheLimit() {
		McpToolResultBudget budget = budget(1_024);
		ToolResult result = new ToolResult("bounded output");

		assertThat(budget.requireWithinLimit(result)).isSameAs(result);
	}

	@Test
	void rejectsAResultWhoseSerializedUtf8ExceedsTheLimitWithoutEchoingIt() {
		McpToolResultBudget budget = budget(1_024);
		ToolResult result = new ToolResult("x".repeat(1_024));

		assertThatThrownBy(() -> budget.requireWithinLimit(result))
				.isInstanceOf(McpToolResultTooLargeException.class)
				.hasMessageContaining("1024-byte")
				.satisfies(exception -> assertThat(exception.getMessage()).doesNotContain(result.content()));
	}

	private static McpToolResultBudget budget(long maximumBytes) {
		return new McpToolResultBudget(
				JsonMapper.builder().build(),
				new McpPayloadProperties(1_024L, maximumBytes, null));
	}

	private record ToolResult(String content) {
	}
}
