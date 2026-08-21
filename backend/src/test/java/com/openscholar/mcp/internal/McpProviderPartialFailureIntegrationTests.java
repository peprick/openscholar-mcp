package com.openscholar.mcp.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.openscholar.TestcontainersConfiguration;
import com.openscholar.paper.DocumentType;
import com.openscholar.provider.ProviderAuthor;
import com.openscholar.provider.ProviderException;
import com.openscholar.provider.ProviderId;
import com.openscholar.provider.ProviderPaperRecord;
import com.openscholar.provider.ProviderSearchQuery;
import com.openscholar.provider.ProviderSearchResult;
import com.openscholar.provider.ResearchProvider;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, McpProviderPartialFailureIntegrationTests.ProviderConfiguration.class})
@SpringBootTest(properties = {
		"openscholar.mcp.security.local-api-key=mcp-partial-wire-test-key",
		"openscholar.mcp.security.allowed-origins=http://mcp-client.test",
		"openscholar.search.cache-ttl=1h"
})
class McpProviderPartialFailureIntegrationTests {

	private static final String API_KEY = "mcp-partial-wire-test-key";
	private static final String PROTOCOL_VERSION = "2025-11-25";
	private static final Instant RETRIEVED_AT = Instant.parse("2026-08-19T08:00:00Z");

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ObjectMapper objectMapper;

	@Test
	void exposesAProviderPartialFailureWithoutDiscardingSuccessfulResults() throws Exception {
		String topic = "partial provider MCP result " + UUID.randomUUID();
		Map<String, Object> message = Map.of(
				"jsonrpc", "2.0",
				"id", 1,
				"method", "tools/call",
				"params", Map.of(
						"name", "search_research",
						"arguments", Map.of("topic", topic, "limit", 1)));

		String body = mockMvc.perform(post("/mcp")
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + API_KEY)
				.header("MCP-Protocol-Version", PROTOCOL_VERSION)
				.contentType(MediaType.APPLICATION_JSON)
				.accept(MediaType.APPLICATION_JSON, MediaType.TEXT_EVENT_STREAM)
				.content(objectMapper.writeValueAsString(message)))
			.andExpect(status().isOk())
			.andReturn()
			.getResponse()
			.getContentAsString();

		JsonNode result = objectMapper.readTree(body).required("result");
		assertThat(result.path("isError").asBoolean(false)).isFalse();
		JsonNode content = result.required("structuredContent");
		Map<String, JsonNode> coverage = new LinkedHashMap<>();
		content.required("providerCoverage").forEach(item ->
				coverage.put(item.required("provider").asString(), item));

		assertThat(content.required("results")).hasSize(1);
		assertThat(coverage.keySet()).containsExactlyInAnyOrder("CORE", "OPENALEX");
		assertThat(coverage.get("CORE").required("status").asString()).isEqualTo("FAILED");
		assertThat(coverage.get("OPENALEX").required("status").asString()).isEqualTo("SUCCESS");
		assertThat(content.required("warnings").required(0).asString()).isEqualTo("CORE_SYNTHETIC_FAILURE");
	}

	@TestConfiguration(proxyBeanMethods = false)
	static class ProviderConfiguration {

		@Bean
		@Primary
		ResearchProvider fakeOpenAlexProvider() {
			return new ResearchProvider() {
				@Override
				public ProviderId id() {
					return ProviderId.OPENALEX;
				}

				@Override
				public ProviderSearchResult search(ProviderSearchQuery query) {
					ProviderPaperRecord paper = new ProviderPaperRecord(
							ProviderId.OPENALEX,
							"W-MCP-PARTIAL-WIRE",
							"10.1000/openscholar.mcp-partial-wire",
							null,
							"Successful result beside a failed provider",
							null,
							null,
							2026,
							DocumentType.ARTICLE,
							"en",
							null,
							null,
							List.of(new ProviderAuthor(null, "Ada Protocol", null, 0, true)),
							true,
							null,
							null,
							null,
							RETRIEVED_AT,
							Map.of("fixture", "mcp-partial-wire"));
					return new ProviderSearchResult(ProviderId.OPENALEX, List.of(paper), 1, null, RETRIEVED_AT);
				}
			};
		}

		@Bean
		ResearchProvider failingCoreProvider() {
			return new ResearchProvider() {
				@Override
				public ProviderId id() {
					return ProviderId.CORE;
				}

				@Override
				public ProviderSearchResult search(ProviderSearchQuery query) {
					throw new ProviderException(
							ProviderId.CORE,
							"CORE_SYNTHETIC_FAILURE",
							"Synthetic CORE provider failure",
							true,
							Duration.ofSeconds(1),
							null);
				}
			};
		}
	}
}
