package com.openscholar.mcp.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
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
import com.openscholar.library.LibraryUseCase;
import com.openscholar.library.ReadingStatus;
import com.openscholar.paper.DocumentType;
import com.openscholar.provider.ProviderAuthor;
import com.openscholar.provider.ProviderId;
import com.openscholar.provider.ProviderException;
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
import org.springframework.jdbc.core.JdbcTemplate;
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

	@Autowired
	private LibraryUseCase libraryUseCase;

	@Autowired
	private JdbcTemplate jdbcTemplate;

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
		JsonNode resourceCapabilities = result.required("capabilities").required("resources");
		assertThat(resourceCapabilities.isObject()).isTrue();
		assertThat(resourceCapabilities.has("subscribe")).isFalse();
		assertThat(resourceCapabilities.has("listChanged")).isFalse();
		assertThat(result.required("capabilities").hasNonNull("prompts")).isFalse();

		postNotification("notifications/initialized", Map.of()).andExpect(status().isAccepted())
			.andExpect(content().string(""));
	}

	@Test
	void publishesOnlyTheThreeBoundedResourceTemplatesAndNoConcreteResourceList() throws Exception {
		JsonNode response = postRequest(32, "resources/templates/list", Map.of());
		JsonNode publishedTemplates = response.required("result").required("resourceTemplates");
		assertThat(publishedTemplates).hasSize(3);

		Map<String, JsonNode> templates = resourceTemplatesByUri(publishedTemplates);
		assertThat(templates.keySet()).containsExactlyInAnyOrder(
				"openscholar://papers/{paperId}",
				"openscholar://collections/{collectionId}",
				"openscholar://searches/{searchId}");
		for (JsonNode template : templates.values()) {
			assertThat(template.required("name").asString()).startsWith("openscholar-");
			assertThat(template.required("title").asString()).isNotBlank();
			assertThat(template.required("description").asString()).isNotBlank();
			assertThat(template.required("mimeType").asString()).isEqualTo(MediaType.APPLICATION_JSON_VALUE);
		}

		JsonNode resources = postRequest(33, "resources/list", Map.of());
		assertThat(resources.required("result").required("resources")).isEmpty();
	}

	@Test
	void publishesExactlyTheSupportedToolsWithSafeSchemasAndAnnotations() throws Exception {
		JsonNode response = postRequest(2, "tools/list", Map.of());
		assertThat(response.required("jsonrpc").asString()).isEqualTo("2.0");
		assertThat(response.required("id").asInt()).isEqualTo(2);

		JsonNode publishedTools = response.required("result").required("tools");
		assertThat(publishedTools).hasSize(6);
		Map<String, JsonNode> tools = toolsByName(publishedTools);
		assertThat(tools.keySet()).containsExactlyInAnyOrder(
				"search_research",
				"get_paper_details",
				"resolve_paper_identifier",
				"get_legal_full_text",
				"search_saved_library",
				"export_citations");

		assertToolContract(tools.get("search_research"),
				Set.of("topic", "yearFrom", "yearTo", "documentTypes", "openAccessOnly", "pdfAvailableOnly",
						"minimumCitations",
						"languages", "limit", "cursor", "forceRefresh", "mode"),
				Set.of("topic"), false, false, true);
		assertToolContract(tools.get("get_paper_details"), Set.of("paperId"), Set.of("paperId"), true, true, false);
		assertToolContract(tools.get("resolve_paper_identifier"), Set.of("identifier"), Set.of("identifier"), true,
				true, false);
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
		assertThat(inputProperty(tools.get("search_research"), "mode").required("type").asString())
			.isEqualTo("string");
		assertThat(inputProperty(tools.get("get_paper_details"), "paperId").required("type").asString())
			.isEqualTo("string");
		assertThat(inputProperty(tools.get("resolve_paper_identifier"), "identifier").required("type").asString())
			.isEqualTo("string");
		assertThat(inputProperty(tools.get("get_legal_full_text"), "paperId").required("type").asString())
			.isEqualTo("string");
		assertThat(inputProperty(tools.get("export_citations"), "paperIds").required("type").asString())
			.isEqualTo("array");

		JsonNode searchOutput = tools.get("search_research").required("outputSchema");
		assertThat(arrayValues(searchOutput.required("required"))).doesNotContain("nextCursor");
		assertThat(arrayValues(searchOutput.required("required")))
			.contains("requestedMode", "executionSource");
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
		JsonNode identifierResolution = tools.get("resolve_paper_identifier").required("outputSchema");
		assertThat(arrayValues(identifierResolution.required("required")))
			.containsExactlyInAnyOrder("paperId", "identifierType", "normalizedValue");
		assertThat(arrayValues(identifierResolution.required("properties").required("identifierType")
			.required("enum")))
			.containsExactly("DOI", "ARXIV", "OPENALEX");
		assertThat(arrayValues(detailPaper.required("required"))).doesNotContain(
				"publisher", "institution", "volume", "issue", "pages", "articleNumber", "edition", "isbn",
				"issn", "degree");
		JsonNode authorItem = searchItem.required("properties").required("authors").required("items");
		assertThat(arrayValues(authorItem.required("required"))).doesNotContain("orcid", "openAlexId");
		JsonNode provenanceItem = searchItem.required("properties").required("provenance").required("items");
		assertThat(arrayValues(provenanceItem.required("required")))
			.containsExactlyInAnyOrder("provider", "providerRecordId", "retrievedAt");
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
		assertThat(successfulResult.required("structuredContent").required("requestedMode").asString())
			.isEqualTo("AUTO");
		assertThat(successfulResult.required("structuredContent").required("executionSource").asString())
			.isEqualTo("PROVIDER_FETCH");
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
		assertThat(searchResult.required("provenance")).hasSize(1);
		assertThat(searchResult.required("provenance").required(0).required("provider").asString())
			.isEqualTo("OPENALEX");
		assertThat(searchResult.required("authors").required(0).has("orcid")).isFalse();
		assertThat(searchResult.required("authors").required(0).has("openAlexId")).isFalse();
		assertThat(researchProvider.calls()).isEqualTo(1);

		JsonNode invalid = postRequest(4, "tools/call",
				Map.of("name", "search_research", "arguments", Map.of("limit", 1)));
		assertToolError(invalid, McpToolErrorCode.INVALID_REQUEST, false, null);

		String unknownArgument = "private-unknown-argument-" + UUID.randomUUID();
		JsonNode additionalProperty = callTool(5, "search_research",
				Map.of("topic", topic, "unknownArgument", unknownArgument));
		assertToolError(additionalProperty, McpToolErrorCode.INVALID_REQUEST, false, null);
		assertThat(additionalProperty.toString()).doesNotContain(unknownArgument, "unknownArgument");

		JsonNode wrongType = callTool(6, "search_research", Map.of("topic", List.of("not-a-string")));
		assertToolError(wrongType, McpToolErrorCode.INVALID_REQUEST, false, null);
		assertThat(researchProvider.calls()).isEqualTo(1);
	}

	@Test
	void successfullyCallsEveryDatabaseOnlyToolOverTheWire() throws Exception {
		String topic = "database-only MCP tools " + UUID.randomUUID();
		JsonNode search = callTool(10, "search_research", Map.of("topic", topic, "limit", 1));
		JsonNode searchContent = successfulStructuredContent(search);
		String paperId = searchContent.required("results").required(0).required("paperId").asString();
		String searchId = searchContent.required("searchId").asString();
		assertThat(researchProvider.calls()).isOne();

		JsonNode resolution = successfulStructuredContent(callTool(11, "resolve_paper_identifier",
				Map.of("identifier", "https://doi.org/10.1000/OpenScholar.MCP-Wire-Test")));
		assertThat(resolution.required("paperId").asString()).isEqualTo(paperId);
		assertThat(resolution.required("identifierType").asString()).isEqualTo("DOI");
		assertThat(resolution.required("normalizedValue").asString())
			.isEqualTo("10.1000/openscholar.mcp-wire-test");
		assertThat(researchProvider.calls()).isOne();

		JsonNode details = successfulStructuredContent(
				callTool(12, "get_paper_details", Map.of("paperId", paperId)));
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
				callTool(13, "get_legal_full_text", Map.of("paperId", paperId)));
		assertThat(access.required("paperId").asString()).isEqualTo(paperId);
		assertThat(access.required("disposition").asString()).isEqualTo("NOT_YET_RESOLVED");
		assertThat(access.required("locations")).isEmpty();

		JsonNode library = successfulStructuredContent(callTool(14, "search_saved_library", Map.of()));
		assertThat(library.required("items")).isEmpty();
		assertThat(library.required("totalElements").asLong()).isZero();

		JsonNode citations = successfulStructuredContent(callTool(15, "export_citations",
				Map.of("paperIds", List.of(paperId), "format", "bibtex")));
		assertThat(citations.required("format").asString()).isEqualTo("bibtex");
		assertThat(citations.required("paperCount").asInt()).isEqualTo(1);
		assertThat(citations.required("content").asString()).contains("Deterministic MCP paper");

		UUID collectionId = libraryUseCase.createCollection("MCP wire resources", "Database-only resource test")
			.collectionId();
		try {
			libraryUseCase.addPaper(collectionId, UUID.fromString(paperId), ReadingStatus.READING,
					List.of("mcp-wire"));

			JsonNode paperResource = readResource(34, "openscholar://papers/" + paperId);
			JsonNode paperPayload = resourcePayload(paperResource);
			assertThat(paperPayload.required("paper").required("paperId").asString()).isEqualTo(paperId);
			assertThat(paperPayload.required("paper").required("title").asString())
				.isEqualTo("Deterministic MCP paper");

			JsonNode searchResource = readResource(35, "openscholar://searches/" + searchId);
			JsonNode searchPayload = resourcePayload(searchResource);
			assertThat(searchPayload.required("searchId").asString()).isEqualTo(searchId);
			assertThat(searchPayload.required("query").asString()).isEqualTo(topic);
			assertThat(searchPayload.required("results")).hasSize(1);

			JsonNode collectionResource = readResource(36, "openscholar://collections/" + collectionId);
			JsonNode collectionPayload = resourcePayload(collectionResource);
			assertThat(collectionPayload.required("collectionId").asString()).isEqualTo(collectionId.toString());
			assertThat(collectionPayload.required("papers").required("items")).hasSize(1);
			assertThat(collectionPayload.required("papers").required("items").required(0)
					.required("paperId").asString()).isEqualTo(paperId);
			assertThat(researchProvider.calls()).isOne();
		}
		finally {
			libraryUseCase.deleteCollection(collectionId);
		}
	}

	@Test
	void distinguishesSafeTemplateErrorsFromStandardUnmatchedResourceErrors() throws Exception {
		String invalidIdentifier = "private-invalid-resource-" + UUID.randomUUID();
		JsonNode invalid = readResource(37, "openscholar://papers/" + invalidIdentifier);
		assertResourceError(invalid, -32602, "Invalid OpenScholar resource URI");
		assertThat(invalid.toString()).doesNotContain(invalidIdentifier, "Exception", "jdbc:");

		UUID missingId = UUID.randomUUID();
		JsonNode missingCollection = readResource(38, "openscholar://collections/" + missingId);
		assertResourceError(missingCollection, -32002, "Resource not found");
		assertThat(missingCollection.toString()).doesNotContain(missingId.toString(), "Exception", "jdbc:");

		String queriedUri = "openscholar://papers/" + UUID.randomUUID() + "?download=true";
		JsonNode queried = readResource(39, queriedUri);
		assertResourceError(queried, -32602, "Invalid OpenScholar resource URI");
		assertThat(queried.toString()).doesNotContain(queriedUri, "download=true", "Exception", "jdbc:");

		for (String unmatchedUri : List.of(
				"https://papers.example/" + UUID.randomUUID(),
				"openscholar://papers/" + UUID.randomUUID() + "/extra")) {
			JsonNode unmatched = readResource(40, unmatchedUri);
			assertResourceError(unmatched, -32002, "Resource not found");
			assertThat(unmatched.required("error").required("data").required("uri").asString())
				.isEqualTo(unmatchedUri);
			assertThat(unmatched.toString()).doesNotContain("Exception", "jdbc:");
		}
		assertThat(researchProvider.calls()).isZero();
	}

	@Test
	void returnsSafeIdentifierResolutionFailuresWithoutCallingAProvider() throws Exception {
		JsonNode invalid = callTool(16, "resolve_paper_identifier", Map.of("identifier", "not-an-identifier"));
		assertToolError(invalid, McpToolErrorCode.INVALID_PAPER_IDENTIFIER, false, null);
		assertThat(invalid.toString()).doesNotContain("not-an-identifier", "Exception", "jdbc:");

		String absentDoi = "10.1000/not-visible-" + UUID.randomUUID();
		JsonNode absent = callTool(17, "resolve_paper_identifier", Map.of("identifier", absentDoi));
		assertToolError(absent, McpToolErrorCode.PAPER_IDENTIFIER_NOT_FOUND, false, null);
		assertThat(absent.toString()).doesNotContain(absentDoi, "Exception", "jdbc:");
		assertThat(researchProvider.calls()).isZero();
	}

	@Test
	void makesMissingAndOtherOwnerObjectsIndistinguishableOverTheWire() throws Exception {
		UUID otherOwnerId = UUID.randomUUID();
		UUID hiddenPaperId = UUID.randomUUID();
		UUID hiddenCollectionId = UUID.randomUUID();
		String hiddenDoi = "10.1000/private-" + UUID.randomUUID();
		String absentDoi = "10.1000/absent-" + UUID.randomUUID();
		OffsetDateTime now = OffsetDateTime.ofInstant(RETRIEVED_AT, ZoneOffset.UTC);

		try {
			jdbcTemplate.update(
					"INSERT INTO app_user (id, display_name, created_at) VALUES (?, 'Other MCP owner', ?)",
					otherOwnerId, now);
			jdbcTemplate.update("""
					INSERT INTO paper (
					    id, title, normalized_title, document_type, metadata_quality,
					    metadata_updated_at, version, created_at, updated_at
					)
					VALUES (?, 'Private MCP paper', 'private mcp paper', 'ARTICLE', 0, ?, 0, ?, ?)
					""", hiddenPaperId, now, now, now);
			jdbcTemplate.update("""
					INSERT INTO paper_external_id (
					    id, paper_id, id_type, namespace, normalized_value, raw_value, created_at
					)
					VALUES (?, ?, 'DOI', '', ?, ?, ?)
					""", UUID.randomUUID(), hiddenPaperId, hiddenDoi, hiddenDoi, now);
			jdbcTemplate.update("""
					INSERT INTO library_collection (
					    id, owner_id, name, version, created_at, updated_at
					)
					VALUES (?, ?, 'Private MCP collection', 0, ?, ?)
					""", hiddenCollectionId, otherOwnerId, now, now);
			jdbcTemplate.update("""
					INSERT INTO collection_paper (
					    id, collection_id, paper_id, reading_status, version, saved_at, updated_at
					)
					VALUES (?, ?, ?, 'UNREAD', 0, ?, ?)
					""", UUID.randomUUID(), hiddenCollectionId, hiddenPaperId, now, now);

			JsonNode hiddenIdentifier = callTool(18, "resolve_paper_identifier",
					Map.of("identifier", hiddenDoi));
			JsonNode missingIdentifier = callTool(19, "resolve_paper_identifier",
					Map.of("identifier", absentDoi));
			assertToolError(hiddenIdentifier, McpToolErrorCode.PAPER_IDENTIFIER_NOT_FOUND, false, null);
			assertToolError(missingIdentifier, McpToolErrorCode.PAPER_IDENTIFIER_NOT_FOUND, false, null);
			assertThat(hiddenIdentifier.required("result")).isEqualTo(missingIdentifier.required("result"));

			JsonNode hiddenCollection = callTool(30, "search_saved_library",
					Map.of("collectionId", hiddenCollectionId.toString()));
			JsonNode missingCollection = callTool(31, "search_saved_library",
					Map.of("collectionId", UUID.randomUUID().toString()));
			assertToolError(hiddenCollection, McpToolErrorCode.COLLECTION_NOT_FOUND, false, null);
			assertToolError(missingCollection, McpToolErrorCode.COLLECTION_NOT_FOUND, false, null);
			assertThat(hiddenCollection.required("result")).isEqualTo(missingCollection.required("result"));
		}
		finally {
			jdbcTemplate.update("DELETE FROM app_user WHERE id = ?", otherOwnerId);
			jdbcTemplate.update("DELETE FROM paper WHERE id = ?", hiddenPaperId);
		}
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
		assertToolError(response, McpToolErrorCode.MCP_RESPONSE_TOO_LARGE, false, null);
		assertThat(response.toString()).doesNotContain(FakeResearchProvider.LEAK_MARKER);
	}

	@Test
	void returnsRetryGuidanceWithoutExposingProviderFailures() throws Exception {
		researchProvider.failRetryably();
		String topic = "provider outage MCP result " + UUID.randomUUID();

		JsonNode response = callTool(22, "search_research",
				Map.of("topic", topic, "limit", 1, "mode", "ONLINE", "forceRefresh", true));

		assertToolError(response, McpToolErrorCode.SEARCH_PROVIDER_UNAVAILABLE, true, 7L);
		assertThat(response.toString()).doesNotContain(FakeResearchProvider.FAILURE_LEAK_MARKER,
				"ProviderException", "IllegalStateException", "api-key");
	}

	@Test
	void normalizesMalformedArgumentsButLeavesUnknownToolsAsProtocolErrors() throws Exception {
		String invalidUuid = "private-invalid-uuid-" + UUID.randomUUID();
		JsonNode malformedUuid = callTool(27, "get_paper_details", Map.of("paperId", invalidUuid));
		assertToolError(malformedUuid, McpToolErrorCode.INVALID_REQUEST, false, null);
		assertThat(malformedUuid.toString()).doesNotContain(invalidUuid, "Exception", "Failed to invoke");

		String invalidStatus = "PRIVATE_READING_STATUS";
		JsonNode malformedEnum = callTool(28, "search_saved_library",
				Map.of("readingStatus", invalidStatus));
		assertToolError(malformedEnum, McpToolErrorCode.INVALID_REQUEST, false, null);
		assertThat(malformedEnum.toString()).doesNotContain(invalidStatus, "Exception", "Failed to invoke");

		JsonNode unknown = callTool(29, "not_an_openscholar_tool", Map.of());
		assertThat(unknown.has("result")).isFalse();
		assertThat(unknown.required("error").required("code").asInt()).isEqualTo(-32602);
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

	@Test
	void repeatedJsonRpcIdsDoNotActAsIdempotencyKeysAndOrdinarySearchCachingStillApplies() throws Exception {
		String topic = "duplicate JSON-RPC identifier " + UUID.randomUUID();
		Map<String, Object> arguments = Map.of("topic", topic, "limit", 1);

		JsonNode first = callTool(25, "search_research", arguments);
		JsonNode second = callTool(25, "search_research", arguments);

		assertThat(first.required("id").asInt()).isEqualTo(25);
		assertThat(second.required("id").asInt()).isEqualTo(25);
		assertThat(successfulStructuredContent(first).required("cacheDisposition").asString())
				.isEqualTo("MISS_FETCHED");
		assertThat(successfulStructuredContent(second).required("cacheDisposition").asString())
				.isEqualTo("EXACT_HIT");
		assertThat(researchProvider.calls()).isEqualTo(1);
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
		JsonNode response = postRequest(id, "tools/call", Map.of("name", name, "arguments", arguments));
		assertThat(response.required("id").asInt()).isEqualTo(id);
		return response;
	}

	private JsonNode readResource(int id, String uri) throws Exception {
		JsonNode response = postRequest(id, "resources/read", Map.of("uri", uri));
		assertThat(response.required("id").asInt()).isEqualTo(id);
		return response;
	}

	private JsonNode resourcePayload(JsonNode response) throws Exception {
		assertThat(response.has("error")).isFalse();
		JsonNode contents = response.required("result").required("contents");
		assertThat(contents).hasSize(1);
		JsonNode contentNode = contents.required(0);
		assertThat(contentNode.required("mimeType").asString()).isEqualTo(MediaType.APPLICATION_JSON_VALUE);
		return objectMapper.readTree(contentNode.required("text").asString());
	}

	private static void assertResourceError(JsonNode response, int code, String message) {
		assertThat(response.has("result")).isFalse();
		JsonNode error = response.required("error");
		assertThat(error.required("code").asInt()).isEqualTo(code);
		assertThat(error.required("message").asString()).isEqualTo(message);
	}

	private static JsonNode successfulStructuredContent(JsonNode response) {
		JsonNode result = response.required("result");
		assertThat(result.path("isError").asBoolean(false)).isFalse();
		return result.required("structuredContent");
	}

	private static JsonNode assertToolError(JsonNode response, McpToolErrorCode code, boolean retryable,
			Long retryAfterSeconds) {
		assertThat(response.required("jsonrpc").asString()).isEqualTo("2.0");
		assertThat(response.has("error")).isFalse();
		JsonNode result = response.required("result");
		assertThat(result.propertyNames()).containsExactlyInAnyOrder("content", "isError", "_meta");
		assertThat(result.required("isError").asBoolean()).isTrue();
		assertThat(result.has("structuredContent")).isFalse();
		assertThat(result.required("content")).hasSize(1);
		assertThat(result.required("content").required(0).required("type").asString()).isEqualTo("text");

		McpToolError expected = new McpToolError(code, retryable, retryAfterSeconds);
		assertThat(result.required("content").required(0).required("text").asString())
			.isEqualTo(expected.toText());

		JsonNode metadata = result.required("_meta");
		assertThat(metadata.propertyNames()).containsExactly(SafeMcpToolMethodCallback.ERROR_META_KEY);
		JsonNode descriptor = metadata.required(SafeMcpToolMethodCallback.ERROR_META_KEY);
		if (retryAfterSeconds == null) {
			assertThat(descriptor.propertyNames()).containsExactlyInAnyOrder(
					"schemaVersion", "code", "category", "message", "retryable", "action");
			assertThat(descriptor.has("retryAfterSeconds")).isFalse();
		}
		else {
			assertThat(descriptor.propertyNames()).containsExactlyInAnyOrder(
					"schemaVersion", "code", "category", "message", "retryable", "action",
					"retryAfterSeconds");
			assertThat(descriptor.required("retryAfterSeconds").asLong()).isEqualTo(retryAfterSeconds);
		}
		assertThat(descriptor.required("schemaVersion").asInt()).isEqualTo(McpToolError.SCHEMA_VERSION);
		assertThat(descriptor.required("code").asString()).isEqualTo(code.name());
		assertThat(descriptor.required("category").asString()).isEqualTo(expected.category());
		assertThat(descriptor.required("message").asString()).isEqualTo(expected.message());
		assertThat(descriptor.required("retryable").asBoolean()).isEqualTo(retryable);
		assertThat(descriptor.required("action").asString()).isEqualTo(expected.action());
		return descriptor;
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

	private static Map<String, JsonNode> resourceTemplatesByUri(JsonNode templatesNode) {
		Map<String, JsonNode> templates = new LinkedHashMap<>();
		IntStream.range(0, templatesNode.size()).forEach(index -> {
			JsonNode template = templatesNode.required(index);
			templates.put(template.required("uriTemplate").asString(), template);
		});
		return templates;
	}

	private static void assertToolContract(JsonNode tool, Set<String> properties, Set<String> required,
			boolean readOnly, boolean idempotent, boolean openWorld) {
		assertThat(tool).isNotNull();
		JsonNode inputSchema = tool.required("inputSchema");
		assertThat(inputSchema.required("type").asString()).isEqualTo("object");
		assertThat(inputSchema.required("additionalProperties").asBoolean()).isFalse();
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

		@Bean
		@Primary
		Clock fixedClock() {
			return Clock.fixed(RETRIEVED_AT, ZoneOffset.UTC);
		}

	}

	static final class FakeResearchProvider implements ResearchProvider {

		private static final String LEAK_MARKER = "MCP_RESULT_MUST_NOT_LEAK";
		private static final String FAILURE_LEAK_MARKER = "MCP_PROVIDER_FAILURE_MUST_NOT_LEAK";

		private final AtomicInteger calls = new AtomicInteger();
		private final AtomicBoolean oversized = new AtomicBoolean();
		private final AtomicBoolean restricted = new AtomicBoolean();
		private final AtomicBoolean unavailable = new AtomicBoolean();

		@Override
		public ProviderId id() {
			return ProviderId.OPENALEX;
		}

		@Override
		public ProviderSearchResult search(ProviderSearchQuery query) {
			calls.incrementAndGet();
			if (unavailable.get()) {
				throw new ProviderException(ProviderId.OPENALEX, "PRIVATE_PROVIDER_CODE",
						FAILURE_LEAK_MARKER + " api-key=private", true, Duration.ofMillis(6_001),
						new IllegalStateException(FAILURE_LEAK_MARKER));
			}
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
			unavailable.set(false);
		}

		void returnOversizedResult() {
			oversized.set(true);
		}

		void returnRestrictedRecord() {
			restricted.set(true);
		}

		void failRetryably() {
			unavailable.set(true);
		}

		int calls() {
			return calls.get();
		}
	}
}
