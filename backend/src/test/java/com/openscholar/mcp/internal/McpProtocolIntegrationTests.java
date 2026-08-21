package com.openscholar.mcp.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import com.openscholar.TestcontainersConfiguration;
import com.openscholar.access.AccessDisposition;
import com.openscholar.access.AccessStatus;
import com.openscholar.access.internal.persistence.PaperAccessStore;
import com.openscholar.paper.DocumentType;
import com.openscholar.provider.ProviderAuthor;
import com.openscholar.provider.ProviderId;
import com.openscholar.provider.ProviderPaperRecord;
import com.openscholar.provider.ProviderSearchQuery;
import com.openscholar.provider.ProviderSearchResult;
import com.openscholar.provider.ResearchProvider;
import org.junit.jupiter.api.BeforeEach;
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
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, McpProtocolIntegrationTests.FakeProviderConfiguration.class})
@SpringBootTest(properties = {
		"openscholar.mcp.security.local-api-key=mcp-wire-test-key",
		"openscholar.mcp.security.allowed-origins=http://mcp-client.test",
		"openscholar.mcp.payload.max-request-bytes=4096",
		"openscholar.mcp.payload.max-tool-result-bytes=4096",
		"openscholar.search.cache-ttl=1h"
})
class McpProtocolIntegrationTests {

	private static final String API_KEY = "mcp-wire-test-key";
	private static final String PROTOCOL_VERSION = "2025-11-25";
	private static final Instant RETRIEVED_AT = Instant.parse("2026-08-19T08:00:00Z");

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ObjectMapper objectMapper;

	@Autowired
	private FakeResearchProvider researchProvider;

	@Autowired
	private PaperAccessStore paperAccessStore;

	@BeforeEach
	void resetProvider() {
		researchProvider.reset();
	}

	@Test
	void negotiatesAuthenticatedStatelessJsonRpc() throws Exception {
		JsonNode response = initialize(1);

		assertThat(response.required("jsonrpc").asString()).isEqualTo("2.0");
		assertThat(response.required("id").asInt()).isEqualTo(1);
		JsonNode result = response.required("result");
		assertThat(result.required("protocolVersion").asString()).isEqualTo(PROTOCOL_VERSION);
		assertThat(result.required("serverInfo").required("name").asString()).isEqualTo("openscholar-mcp");
		assertThat(result.required("serverInfo").required("version").asString()).isEqualTo("0.0.1");
		assertThat(result.required("capabilities").required("tools").isObject()).isTrue();
		assertThat(result.required("capabilities").hasNonNull("resources")).isFalse();
		assertThat(result.required("capabilities").hasNonNull("prompts")).isFalse();

		postNotification("notifications/initialized", Map.of()).andExpect(status().isAccepted())
			.andExpect(content().string(""));
	}

	@Test
	void publishesExactlyTheSupportedToolsWithSafeSchemasAndAnnotations() throws Exception {
		JsonNode response = postRequest(2, "tools/list", Map.of());
		assertThat(response.required("jsonrpc").asString()).isEqualTo("2.0");
		assertThat(response.required("id").asInt()).isEqualTo(2);

		Map<String, JsonNode> tools = toolsByName(response.required("result").required("tools"));
		assertThat(tools.keySet()).containsExactlyInAnyOrder(
				"search_research",
				"get_paper_details",
				"get_legal_full_text",
				"search_saved_library",
				"export_citations");

		assertToolContract(tools.get("search_research"),
				Set.of("topic", "yearFrom", "yearTo", "documentTypes", "openAccessOnly", "minimumCitations",
						"languages", "limit", "cursor", "forceRefresh"),
				Set.of("topic"), false, false, true);
		assertToolContract(tools.get("get_paper_details"), Set.of("paperId"), Set.of("paperId"), true, true, false);
		assertToolContract(tools.get("get_legal_full_text"), Set.of("paperId"), Set.of("paperId"), true, true, false);
		assertToolContract(tools.get("search_saved_library"),
				Set.of("query", "collectionId", "readingStatus", "tag", "page", "size"), Set.of(), true, true,
				false);
		assertToolContract(tools.get("export_citations"), Set.of("paperIds", "format"), Set.of("paperIds"), true,
				true, false);

		assertThat(inputProperty(tools.get("search_research"), "topic").required("type").asString())
			.isEqualTo("string");
		assertThat(inputProperty(tools.get("search_research"), "documentTypes").required("type").asString())
			.isEqualTo("array");
		assertThat(inputProperty(tools.get("get_paper_details"), "paperId").required("type").asString())
			.isEqualTo("string");
		assertThat(inputProperty(tools.get("get_legal_full_text"), "paperId").required("type").asString())
			.isEqualTo("string");
		assertThat(inputProperty(tools.get("export_citations"), "paperIds").required("type").asString())
			.isEqualTo("array");

		JsonNode searchOutput = tools.get("search_research").required("outputSchema");
		assertThat(arrayValues(searchOutput.required("required"))).doesNotContain("nextCursor");
		JsonNode searchItem = searchOutput.required("properties").required("results").required("items");
		assertThat(arrayValues(searchItem.required("required"))).doesNotContain(
				"abstractText", "publicationDate", "publicationYear", "language", "venueName", "citationCount",
				"publisher", "institution", "volume", "issue", "pages", "articleNumber", "edition", "isbn",
				"issn", "degree", "citationCountAsOf", "providerLandingPageUrl", "providerReportedPdfUrl", "score");
		assertThat(searchItem.required("properties").required("publisher").required("type").asString())
				.isEqualTo("string");
		assertThat(searchItem.required("properties").required("isbn").required("type").asString())
				.isEqualTo("array");
		assertThat(searchItem.required("properties").required("issn").required("items").required("type").asString())
				.isEqualTo("string");
		JsonNode detailPaper = tools.get("get_paper_details").required("outputSchema")
				.required("properties").required("paper");
		assertThat(arrayValues(detailPaper.required("required"))).doesNotContain(
				"publisher", "institution", "volume", "issue", "pages", "articleNumber", "edition", "isbn",
				"issn", "degree");
		JsonNode authorItem = searchItem.required("properties").required("authors").required("items");
		assertThat(arrayValues(authorItem.required("required"))).doesNotContain("orcid", "openAlexId");
	}

	@Test
	void callsSearchAndRejectsInvalidArgumentsBeforeInvokingTheProvider() throws Exception {
		String topic = "wire protocol research " + UUID.randomUUID();
		Map<String, Object> arguments = new LinkedHashMap<>();
		arguments.put("topic", topic);
		arguments.put("documentTypes", List.of("ARTICLE"));
		arguments.put("openAccessOnly", true);
		arguments.put("languages", List.of("en"));
		arguments.put("limit", 1);

		JsonNode successful = postRequest(3, "tools/call",
				Map.of("name", "search_research", "arguments", arguments));
		JsonNode successfulResult = successful.required("result");
		assertThat(successfulResult.path("isError").asBoolean(false)).isFalse();
		assertThat(successfulResult.required("structuredContent").required("query").asString()).isEqualTo(topic);
		JsonNode searchResult = successfulResult.required("structuredContent").required("results").required(0);
		assertThat(searchResult.required("title").asString()).isEqualTo("Deterministic MCP paper");
		assertThat(successfulResult.required("structuredContent").has("nextCursor")).isFalse();
		assertThat(searchResult.has("abstractText")).isFalse();
		assertThat(searchResult.has("publicationDate")).isFalse();
		assertThat(searchResult.has("publicationYear")).isFalse();
		assertThat(searchResult.has("language")).isFalse();
		assertThat(searchResult.has("venueName")).isFalse();
		assertThat(searchResult.has("publisher")).isFalse();
		assertThat(searchResult.has("institution")).isFalse();
		assertThat(searchResult.has("volume")).isFalse();
		assertThat(searchResult.has("issue")).isFalse();
		assertThat(searchResult.has("pages")).isFalse();
		assertThat(searchResult.has("articleNumber")).isFalse();
		assertThat(searchResult.has("edition")).isFalse();
		assertThat(searchResult.has("isbn")).isFalse();
		assertThat(searchResult.has("issn")).isFalse();
		assertThat(searchResult.has("degree")).isFalse();
		assertThat(searchResult.has("citationCount")).isFalse();
		assertThat(searchResult.has("citationCountAsOf")).isFalse();
		assertThat(searchResult.has("providerLandingPageUrl")).isFalse();
		assertThat(searchResult.has("providerReportedPdfUrl")).isFalse();
		assertThat(searchResult.has("score")).isFalse();
		assertThat(searchResult.required("authors").required(0).has("orcid")).isFalse();
		assertThat(searchResult.required("authors").required(0).has("openAlexId")).isFalse();
		assertThat(researchProvider.calls()).isEqualTo(1);

		JsonNode invalid = postRequest(4, "tools/call",
				Map.of("name", "search_research", "arguments", Map.of("limit", 1)));
		JsonNode invalidResult = invalid.required("result");
		assertThat(invalidResult.required("isError").asBoolean()).isTrue();
		assertThat(invalidResult.required("content").required(0).required("type").asString()).isEqualTo("text");
		assertThat(invalidResult.required("content").required(0).required("text").asString())
			.containsIgnoringCase("topic");
		assertThat(researchProvider.calls()).isEqualTo(1);
	}

	@Test
	void successfullyCallsEveryDatabaseOnlyToolOverTheWire() throws Exception {
		String topic = "database-only MCP tools " + UUID.randomUUID();
		JsonNode search = callTool(10, "search_research", Map.of("topic", topic, "limit", 1));
		JsonNode searchContent = successfulStructuredContent(search);
		String paperId = searchContent.required("results").required(0).required("paperId").asString();

		JsonNode details = successfulStructuredContent(
				callTool(11, "get_paper_details", Map.of("paperId", paperId)));
		JsonNode detailsPaper = details.required("paper");
		assertThat(detailsPaper.required("paperId").asString()).isEqualTo(paperId);
		assertThat(detailsPaper.has("publisher")).isFalse();
		assertThat(detailsPaper.has("institution")).isFalse();
		assertThat(detailsPaper.has("volume")).isFalse();
		assertThat(detailsPaper.has("issue")).isFalse();
		assertThat(detailsPaper.has("pages")).isFalse();
		assertThat(detailsPaper.has("articleNumber")).isFalse();
		assertThat(detailsPaper.has("edition")).isFalse();
		assertThat(detailsPaper.has("isbn")).isFalse();
		assertThat(detailsPaper.has("issn")).isFalse();
		assertThat(detailsPaper.has("degree")).isFalse();
		assertThat(details.required("storedAccess").required("disposition").asString())
			.isEqualTo("NOT_YET_RESOLVED");

		JsonNode access = successfulStructuredContent(
				callTool(12, "get_legal_full_text", Map.of("paperId", paperId)));
		assertThat(access.required("paperId").asString()).isEqualTo(paperId);
		assertThat(access.required("disposition").asString()).isEqualTo("NOT_YET_RESOLVED");
		assertThat(access.required("locations")).isEmpty();

		JsonNode library = successfulStructuredContent(callTool(13, "search_saved_library", Map.of()));
		assertThat(library.required("items")).isEmpty();
		assertThat(library.required("totalElements").asLong()).isZero();

		JsonNode citations = successfulStructuredContent(callTool(14, "export_citations",
				Map.of("paperIds", List.of(paperId), "format", "bibtex")));
		assertThat(citations.required("format").asString()).isEqualTo("bibtex");
		assertThat(citations.required("paperCount").asInt()).isEqualTo(1);
		assertThat(citations.required("content").asString()).contains("Deterministic MCP paper");
	}

	@Test
	void rejectsOversizedHttpBodiesBeforeToolDispatch() throws Exception {
		String oversizedMessage = objectMapper.writeValueAsString(Map.of(
				"jsonrpc", "2.0",
				"id", 20,
				"method", "tools/call",
				"params", Map.of(
						"name", "search_research",
						"arguments", Map.of("topic", "x".repeat(5000)))));

		MvcResult result = mockMvc.perform(post("/mcp")
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + API_KEY)
				.header("MCP-Protocol-Version", PROTOCOL_VERSION)
				.contentType(MediaType.APPLICATION_JSON)
				.accept(MediaType.APPLICATION_JSON)
				.content(oversizedMessage))
			.andExpect(status().isPayloadTooLarge())
			.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
			.andReturn();

		JsonNode problem = objectMapper.readTree(result.getResponse().getContentAsString());
		assertThat(problem.required("code").asString()).isEqualTo("MCP_REQUEST_TOO_LARGE");
		assertThat(researchProvider.calls()).isZero();
	}

	@Test
	void returnsOnlyASafeToolErrorWhenTheStructuredResultExceedsItsBudget() throws Exception {
		researchProvider.returnOversizedResult();
		String topic = "oversized MCP result " + UUID.randomUUID();

		JsonNode response = callTool(21, "search_research", Map.of("topic", topic, "limit", 1));
		JsonNode result = response.required("result");
		assertThat(result.required("isError").asBoolean()).isTrue();
		String error = result.required("content").required(0).required("text").asString();
		assertThat(error).contains("MCP_RESPONSE_TOO_LARGE", "retryable=false")
			.doesNotContain(FakeResearchProvider.LEAK_MARKER);
	}

	@Test
	void returnsStoredRestrictedAccessWithoutInventingAFullTextLocation() throws Exception {
		researchProvider.returnRestrictedRecord();
		String topic = "restricted access MCP result " + UUID.randomUUID();
		JsonNode search = successfulStructuredContent(
				callTool(23, "search_research", Map.of("topic", topic, "limit", 1)));
		UUID paperId = UUID.fromString(search.required("results").required(0).required("paperId").asString());
		Instant checkedAt = RETRIEVED_AT.plus(Duration.ofHours(1));
		paperAccessStore.store(
				paperId,
				AccessStatus.RESTRICTED,
				AccessDisposition.RESOLVED,
				checkedAt,
				checkedAt.plus(Duration.ofDays(1)),
				accessLookupFingerprint("10.1000/openscholar.mcp-wire-restricted", false),
				List.of(),
				List.of("NO_LEGAL_FULL_TEXT_FOUND"),
				Set.of(),
				List.of());

		JsonNode access = successfulStructuredContent(
				callTool(24, "get_legal_full_text", Map.of("paperId", paperId.toString())));
		assertThat(access.required("status").asString()).isEqualTo("RESTRICTED");
		assertThat(access.required("disposition").asString()).isEqualTo("CACHE_HIT");
		assertThat(arrayValues(access.required("warnings"))).containsExactly("NO_LEGAL_FULL_TEXT_FOUND");
		assertThat(access.required("locations")).isEmpty();
	}

	private JsonNode initialize(int id) throws Exception {
		Map<String, Object> params = Map.of(
				"protocolVersion", PROTOCOL_VERSION,
				"capabilities", Map.of(),
				"clientInfo", Map.of("name", "openscholar-wire-test", "version", "1.0.0"));
		return postRequest(id, "initialize", params, false);
	}

	private JsonNode postRequest(int id, String method, Map<String, ?> params) throws Exception {
		return postRequest(id, method, params, true);
	}

	private JsonNode callTool(int id, String name, Map<String, ?> arguments) throws Exception {
		return postRequest(id, "tools/call", Map.of("name", name, "arguments", arguments));
	}

	private static JsonNode successfulStructuredContent(JsonNode response) {
		JsonNode result = response.required("result");
		assertThat(result.path("isError").asBoolean(false)).isFalse();
		return result.required("structuredContent");
	}

	private JsonNode postRequest(int id, String method, Map<String, ?> params, boolean sendProtocolVersion)
			throws Exception {
		Map<String, Object> message = new LinkedHashMap<>();
		message.put("jsonrpc", "2.0");
		message.put("id", id);
		message.put("method", method);
		message.put("params", params);

		var request = post("/mcp")
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + API_KEY)
			.contentType(MediaType.APPLICATION_JSON)
			.accept(MediaType.APPLICATION_JSON, MediaType.TEXT_EVENT_STREAM)
			.content(objectMapper.writeValueAsString(message));
		if (sendProtocolVersion) {
			request.header("MCP-Protocol-Version", PROTOCOL_VERSION);
		}

		MvcResult mvcResult = mockMvc.perform(request)
			.andExpect(status().isOk())
			.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
			.andReturn();
		return objectMapper.readTree(mvcResult.getResponse().getContentAsString());
	}

	private org.springframework.test.web.servlet.ResultActions postNotification(String method, Map<String, ?> params)
			throws Exception {
		Map<String, Object> message = new LinkedHashMap<>();
		message.put("jsonrpc", "2.0");
		message.put("method", method);
		message.put("params", params);
		return mockMvc.perform(post("/mcp")
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + API_KEY)
			.header("MCP-Protocol-Version", PROTOCOL_VERSION)
			.contentType(MediaType.APPLICATION_JSON)
			.accept(MediaType.APPLICATION_JSON, MediaType.TEXT_EVENT_STREAM)
			.content(objectMapper.writeValueAsString(message)));
	}

	private static Map<String, JsonNode> toolsByName(JsonNode toolsNode) {
		Map<String, JsonNode> tools = new LinkedHashMap<>();
		IntStream.range(0, toolsNode.size()).forEach(index -> {
			JsonNode tool = toolsNode.required(index);
			tools.put(tool.required("name").asString(), tool);
		});
		return tools;
	}

	private static void assertToolContract(JsonNode tool, Set<String> properties, Set<String> required,
			boolean readOnly, boolean idempotent, boolean openWorld) {
		assertThat(tool).isNotNull();
		JsonNode inputSchema = tool.required("inputSchema");
		assertThat(inputSchema.required("type").asString()).isEqualTo("object");
		assertThat(inputSchema.required("properties").propertyNames()).containsExactlyInAnyOrderElementsOf(properties);
		assertThat(arrayValues(inputSchema.path("required"))).containsExactlyInAnyOrderElementsOf(required);

		assertThat(tool.required("outputSchema").required("type").asString()).isEqualTo("object");
		JsonNode annotations = tool.required("annotations");
		assertThat(annotations.required("readOnlyHint").asBoolean()).isEqualTo(readOnly);
		assertThat(annotations.required("destructiveHint").asBoolean()).isFalse();
		assertThat(annotations.required("idempotentHint").asBoolean()).isEqualTo(idempotent);
		assertThat(annotations.required("openWorldHint").asBoolean()).isEqualTo(openWorld);
	}

	private static JsonNode inputProperty(JsonNode tool, String name) {
		return tool.required("inputSchema").required("properties").required(name);
	}

	private static List<String> arrayValues(JsonNode array) {
		return IntStream.range(0, array.size()).mapToObj(index -> array.required(index).asString()).toList();
	}

	private static String accessLookupFingerprint(String normalizedDoi, boolean hasAbstract) {
		String input = normalizedDoi + "\n\n" + hasAbstract;
		try {
			return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
					.digest(input.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
		}
		catch (java.security.NoSuchAlgorithmException exception) {
			throw new IllegalStateException(exception);
		}
	}

	@TestConfiguration(proxyBeanMethods = false)
	static class FakeProviderConfiguration {

		@Bean
		@Primary
		FakeResearchProvider fakeResearchProvider() {
			return new FakeResearchProvider();
		}

	}

	static final class FakeResearchProvider implements ResearchProvider {

		private static final String LEAK_MARKER = "MCP_RESULT_MUST_NOT_LEAK";

		private final AtomicInteger calls = new AtomicInteger();
		private final AtomicBoolean oversized = new AtomicBoolean();
		private final AtomicBoolean restricted = new AtomicBoolean();

		@Override
		public ProviderId id() {
			return ProviderId.OPENALEX;
		}

		@Override
		public ProviderSearchResult search(ProviderSearchQuery query) {
			calls.incrementAndGet();
			String providerRecordId = oversized.get()
					? "W-MCP-WIRE-OVERSIZED"
					: restricted.get() ? "W-MCP-WIRE-RESTRICTED" : "W-MCP-WIRE-TEST";
			String doi = oversized.get()
					? "10.1000/openscholar.mcp-wire-oversized"
					: restricted.get()
							? "10.1000/openscholar.mcp-wire-restricted"
							: "10.1000/openscholar.mcp-wire-test";
			ProviderPaperRecord paper = new ProviderPaperRecord(
					ProviderId.OPENALEX,
					providerRecordId,
					doi,
					null,
					"Deterministic MCP paper",
					oversized.get() ? LEAK_MARKER.repeat(512) : null,
					null,
					null,
					DocumentType.ARTICLE,
					null,
					null,
					null,
					List.of(new ProviderAuthor(null, "Ada Protocol", null, 0, true)),
					true,
					null,
					null,
					null,
					RETRIEVED_AT.minus(Duration.ofDays(1)),
					Map.of("fixture", "mcp-wire"));
			return new ProviderSearchResult(ProviderId.OPENALEX, List.of(paper), 1, null, RETRIEVED_AT);
		}

		void reset() {
			calls.set(0);
			oversized.set(false);
			restricted.set(false);
		}

		void returnOversizedResult() {
			oversized.set(true);
		}

		void returnRestrictedRecord() {
			restricted.set(true);
		}

		int calls() {
			return calls.get();
		}
	}
}
