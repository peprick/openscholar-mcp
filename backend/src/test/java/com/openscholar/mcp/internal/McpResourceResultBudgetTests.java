package com.openscholar.mcp.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class McpResourceResultBudgetTests {

	@Test
	void serializesJsonWhoseUtf8RepresentationFitsTheLimit() {
		McpResourceResultBudget budget = budget(1_024);

		String json = budget.toJson(new ResourceResult("bounded output"));

		assertThat(json).isEqualTo("{\"content\":\"bounded output\"}");
		assertThat(json.getBytes(StandardCharsets.UTF_8)).hasSizeLessThanOrEqualTo(1_024);
	}

	@Test
	void rejectsSerializedJsonThatExceedsTheLimitWithoutEchoingIt() {
		McpResourceResultBudget budget = budget(1_024);
		ResourceResult result = new ResourceResult("x".repeat(1_024));

		assertThatThrownBy(() -> budget.toJson(result))
				.isInstanceOf(McpResourceResultTooLargeException.class)
				.hasMessageContaining("1024-byte")
				.satisfies(exception -> assertThat(exception.getMessage()).doesNotContain(result.content()));
	}

	@Test
	void measuresUtf8BytesRatherThanJavaCharacters() {
		McpResourceResultBudget budget = budget(1_024);
		ResourceResult result = new ResourceResult("\u20ac".repeat(340));

		assertThatThrownBy(() -> budget.toJson(result))
				.isInstanceOf(McpResourceResultTooLargeException.class);
	}

	private static McpResourceResultBudget budget(long maximumBytes) {
		return new McpResourceResultBudget(
				JsonMapper.builder().build(),
				new McpPayloadProperties(1_024L, 1_024L, maximumBytes));
	}

	private record ResourceResult(String content) {
	}
}
