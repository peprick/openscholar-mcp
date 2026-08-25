package com.openscholar.mcp.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import com.openscholar.library.CollectionDetailsView;
import com.openscholar.library.CollectionNotFoundException;
import com.openscholar.library.LibraryPage;
import com.openscholar.library.LibraryUseCase;
import com.openscholar.library.ReadingStatus;
import com.openscholar.library.SavedPaperView;
import com.openscholar.paper.DocumentType;
import com.openscholar.paper.PaperAuthorView;
import com.openscholar.paper.PaperDetailsUseCase;
import com.openscholar.paper.PaperDetailsView;
import com.openscholar.paper.PaperIdentifier;
import com.openscholar.paper.PaperIdentifierType;
import com.openscholar.paper.PaperNotFoundException;
import com.openscholar.paper.PaperProviderRecordView;
import com.openscholar.paper.PaperView;
import com.openscholar.provider.ProviderId;
import com.openscholar.search.CacheDisposition;
import com.openscholar.search.ProviderCoverageView;
import com.openscholar.search.RankingReason;
import com.openscholar.search.SearchExecutionSource;
import com.openscholar.search.SearchMode;
import com.openscholar.search.SearchNotFoundException;
import com.openscholar.search.SearchResearchUseCase;
import com.openscholar.search.SearchResultView;
import com.openscholar.search.SearchView;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.server.McpStatelessServerFeatures;
import io.modelcontextprotocol.spec.McpError;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

@ExtendWith(MockitoExtension.class)
class OpenScholarMcpResourcesTests {

	private static final UUID PAPER_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");

	private static final UUID COLLECTION_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");

	private static final UUID SEARCH_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");

	private static final UUID ALTERNATE_ID = UUID.fromString("44444444-4444-4444-4444-444444444444");

	private static final String PAPER_TEMPLATE = "openscholar://papers/{paperId}";

	private static final String COLLECTION_TEMPLATE = "openscholar://collections/{collectionId}";

	private static final String SEARCH_TEMPLATE = "openscholar://searches/{searchId}";

	private static final Instant NOW = Instant.parse("2026-08-25T10:15:30Z");

	private static final String SOURCE_URL = "https://provider.example/private-source?token=do-not-expose";

	private static final String LANDING_URL = "https://publisher.example/paper/111";

	private static final String PDF_URL = "https://publisher.example/paper/111.pdf";

	private final ObjectMapper objectMapper = JsonMapper.builder().build();

	@Mock
	private PaperDetailsUseCase paperDetailsUseCase;

	@Mock
	private LibraryUseCase libraryUseCase;

	@Mock
	private SearchResearchUseCase searchUseCase;

	private OpenScholarMcpResources resources;

	@BeforeEach
	void setUp() {
		resources = resourcesWithBudget(1_048_576L);
	}

	@Test
	void exposesExactlyThreeJsonResourceTemplatesInStableOrder() {
		List<McpStatelessServerFeatures.SyncResourceTemplateSpecification> specifications =
				resources.resourceTemplateSpecifications();

		assertThat(specifications).hasSize(3);
		assertThat(specifications).extracting(specification -> specification.resourceTemplate().uriTemplate())
			.containsExactly(PAPER_TEMPLATE, COLLECTION_TEMPLATE, SEARCH_TEMPLATE);
		assertThat(specifications).extracting(specification -> specification.resourceTemplate().name())
			.containsExactly("openscholar-paper", "openscholar-collection", "openscholar-search");
		assertThat(specifications).extracting(specification -> specification.resourceTemplate().mimeType())
			.containsOnly("application/json");
	}

	@Test
	void readsPaperMetadataWithoutExposingSourceUrls() throws Exception {
		when(paperDetailsUseCase.get(PAPER_ID)).thenReturn(paperDetails("A stored paper"));

		JsonNode result = readJson(PAPER_TEMPLATE, "openscholar://papers/" + PAPER_ID);

		assertFields(result, "schemaVersion", "paper", "metadataCompleteness", "metadataUpdatedAt", "provenance",
				"authorshipProviderRecordId");
		assertThat(result.required("schemaVersion").asInt()).isOne();
		JsonNode paper = result.required("paper");
		assertPaperMetadataFields(paper);
		assertThat(paper.required("paperId").asString()).isEqualTo(PAPER_ID.toString());
		assertThat(paper.required("title").asString()).isEqualTo("A stored paper");
		assertThat(paper.required("identifiers")).singleElement()
			.satisfies(identifier -> assertFields(identifier, "type", "namespace", "value"));
		assertThat(paper.required("authors")).singleElement()
			.satisfies(author -> assertFields(author, "id", "displayName", "orcid", "openAlexId", "position",
					"corresponding"));
		assertThat(result.required("metadataCompleteness").asDouble()).isEqualTo(1.0d);
		assertThat(result.required("provenance")).singleElement().satisfies(provenance -> {
			assertFields(provenance, "providerRecordEntryId", "provider", "providerRecordId", "providerUpdatedAt",
					"retrievedAt", "reportedOpenAccess");
			assertThat(provenance.required("provider").asString()).isEqualTo("OPENALEX");
			assertThat(provenance.has("sourceUrl")).isFalse();
		});
		assertThat(result.toString()).doesNotContain(SOURCE_URL, "private-source", "token");
		verify(paperDetailsUseCase).get(PAPER_ID);
		verifyNoMoreInteractions(paperDetailsUseCase);
		verifyNoInteractions(libraryUseCase, searchUseCase);
	}

	@Test
	void readsAnOwnerScopedBoundedCollectionPage() throws Exception {
		when(libraryUseCase.getCollection(COLLECTION_ID, 0, 25)).thenReturn(collectionDetails(COLLECTION_ID));

		JsonNode result = readJson(COLLECTION_TEMPLATE, "openscholar://collections/" + COLLECTION_ID);

		assertFields(result, "schemaVersion", "collectionId", "name", "description", "paperCount", "createdAt",
				"updatedAt", "papers");
		assertThat(result.required("schemaVersion").asInt()).isOne();
		assertThat(result.required("collectionId").asString()).isEqualTo(COLLECTION_ID.toString());
		assertThat(result.required("name").asString()).isEqualTo("Core reading");
		JsonNode papers = result.required("papers");
		assertFields(papers, "items", "page", "size", "totalElements", "totalPages");
		assertThat(papers.required("page").asInt()).isZero();
		assertThat(papers.required("size").asInt()).isEqualTo(25);
		assertThat(papers.required("items")).singleElement().satisfies(paper -> {
			assertFields(paper, "collectionId", "collectionName", "paperId", "title", "authors",
					"publicationYear", "documentType", "readingStatus", "tags", "savedAt", "updatedAt");
			assertThat(paper.required("paperId").asString()).isEqualTo(PAPER_ID.toString());
			assertThat(paper.required("readingStatus").asString()).isEqualTo("READING");
		});
		verify(libraryUseCase).getCollection(COLLECTION_ID, 0, 25);
		verifyNoMoreInteractions(libraryUseCase);
		verifyNoInteractions(paperDetailsUseCase, searchUseCase);
	}

	@Test
	void readsOnlyTheStoredOwnerScopedSearchAndOmitsDocumentUrls() throws Exception {
		when(searchUseCase.get(SEARCH_ID)).thenReturn(searchView(SEARCH_ID));

		JsonNode result = readJson(SEARCH_TEMPLATE, "openscholar://searches/" + SEARCH_ID);

		assertFields(result, "schemaVersion", "searchId", "query", "queryFingerprint", "cacheDisposition",
				"requestedMode", "executionSource", "searchedAt", "freshUntil", "nextCursor", "providerCoverage",
				"warnings", "results");
		assertThat(result.required("schemaVersion").asInt()).isOne();
		assertThat(result.required("searchId").asString()).isEqualTo(SEARCH_ID.toString());
		assertThat(result.required("executionSource").asString()).isEqualTo("EXACT_CACHE");
		assertThat(result.required("providerCoverage")).singleElement()
			.satisfies(coverage -> assertFields(coverage, "provider", "status", "returnedCount", "totalMatches"));
		assertThat(result.required("results")).singleElement().satisfies(searchResult -> {
			assertFields(searchResult, "rank", "paper", "reportedOpenAccess", "score", "rankingReasons", "provider",
					"providerRecordId", "retrievedAt", "providerContributions");
			JsonNode paper = searchResult.required("paper");
			assertPaperMetadataFields(paper);
			assertThat(paper.required("paperId").asString()).isEqualTo(PAPER_ID.toString());
			assertThat(searchResult.required("rankingReasons")).singleElement()
				.satisfies(reason -> assertFields(reason, "feature", "value"));
			assertThat(searchResult.required("providerContributions")).singleElement()
				.satisfies(contribution -> assertFields(contribution, "provider", "providerRecordId", "retrievedAt"));
			assertThat(searchResult.has("landingPageUrl")).isFalse();
			assertThat(searchResult.has("pdfUrl")).isFalse();
		});
		assertThat(result.toString()).doesNotContain(LANDING_URL, PDF_URL, ".pdf");
		verify(searchUseCase).get(SEARCH_ID);
		verifyNoMoreInteractions(searchUseCase);
		verifyNoInteractions(paperDetailsUseCase, libraryUseCase);
	}

	@ParameterizedTest
	@ValueSource(strings = {
		"https://papers/11111111-1111-1111-1111-111111111111",
		"OpenScholar://papers/11111111-1111-1111-1111-111111111111",
		"openscholar://PAPERS/11111111-1111-1111-1111-111111111111",
		"openscholar://papers@attacker/11111111-1111-1111-1111-111111111111",
		"openscholar://papers/11111111-1111-1111-1111-111111111111/extra",
		"openscholar://papers/11111111-1111-1111-1111-111111111111?download=true",
		"openscholar://papers/11111111-1111-1111-1111-111111111111#fragment",
		"openscholar://papers/11111111-1111-1111-1111-11111111111g",
		"openscholar://papers/11111111111111111111111111111111",
		"openscholar://papers/11111111-1111-1111-1111-111111111111/",
		"openscholar://papers/%31%31%31%31%31%31%31%31-1111-1111-1111-111111111111"
	})
	void rejectsNonCanonicalPaperUrisBeforeReadingAnything(String uri) {
		Throwable failure = catchThrowable(() -> read(PAPER_TEMPLATE, uri));

		assertMcpError(failure, McpSchema.ErrorCodes.INVALID_PARAMS, "Invalid OpenScholar resource URI");
		verifyNoInteractions(paperDetailsUseCase, libraryUseCase, searchUseCase);
	}

	@Test
	void rejectsNullAndCrossTemplateRequestsBeforeReadingAnything() {
		Throwable nullRequest = catchThrowable(() -> specification(PAPER_TEMPLATE).readHandler()
			.apply(McpTransportContext.EMPTY, null));
		Throwable collectionWithPaperUri = catchThrowable(
				() -> read(COLLECTION_TEMPLATE, "openscholar://papers/" + PAPER_ID));
		Throwable searchWithCollectionUri = catchThrowable(
				() -> read(SEARCH_TEMPLATE, "openscholar://collections/" + COLLECTION_ID));

		assertMcpError(nullRequest, McpSchema.ErrorCodes.INVALID_PARAMS, "Invalid OpenScholar resource URI");
		assertMcpError(collectionWithPaperUri, McpSchema.ErrorCodes.INVALID_PARAMS,
				"Invalid OpenScholar resource URI");
		assertMcpError(searchWithCollectionUri, McpSchema.ErrorCodes.INVALID_PARAMS,
				"Invalid OpenScholar resource URI");
		verifyNoInteractions(paperDetailsUseCase, libraryUseCase, searchUseCase);
	}

	@Test
	void mapsEveryDomainNotFoundFailureToTheSameSafeWireError() {
		when(paperDetailsUseCase.get(PAPER_ID)).thenThrow(new PaperNotFoundException(PAPER_ID));
		when(libraryUseCase.getCollection(COLLECTION_ID, 0, 25))
			.thenThrow(new CollectionNotFoundException(COLLECTION_ID));
		when(searchUseCase.get(SEARCH_ID)).thenThrow(new SearchNotFoundException(SEARCH_ID));

		McpError paperFailure = assertMcpError(
				catchThrowable(() -> read(PAPER_TEMPLATE, "openscholar://papers/" + PAPER_ID)),
				McpSchema.ErrorCodes.RESOURCE_NOT_FOUND, "Resource not found");
		McpError collectionFailure = assertMcpError(
				catchThrowable(() -> read(COLLECTION_TEMPLATE, "openscholar://collections/" + COLLECTION_ID)),
				McpSchema.ErrorCodes.RESOURCE_NOT_FOUND, "Resource not found");
		McpError searchFailure = assertMcpError(
				catchThrowable(() -> read(SEARCH_TEMPLATE, "openscholar://searches/" + SEARCH_ID)),
				McpSchema.ErrorCodes.RESOURCE_NOT_FOUND, "Resource not found");

		assertThat(paperFailure.getJsonRpcError()).isEqualTo(collectionFailure.getJsonRpcError())
			.isEqualTo(searchFailure.getJsonRpcError());
		assertThat(paperFailure.toString()).doesNotContain(PAPER_ID.toString(), COLLECTION_ID.toString(),
				SEARCH_ID.toString());
	}

	@Test
	void hiddenAndMissingCollectionAndSearchFailuresAreIndistinguishable() {
		when(libraryUseCase.getCollection(any(UUID.class), eq(0), eq(25)))
			.thenAnswer(invocation -> {
				throw new CollectionNotFoundException(invocation.getArgument(0));
			});
		when(searchUseCase.get(any(UUID.class)))
			.thenAnswer(invocation -> {
				throw new SearchNotFoundException(invocation.getArgument(0));
			});

		McpError hiddenCollection = resourceFailure(COLLECTION_TEMPLATE,
				"openscholar://collections/" + COLLECTION_ID);
		McpError missingCollection = resourceFailure(COLLECTION_TEMPLATE,
				"openscholar://collections/" + ALTERNATE_ID);
		McpError hiddenSearch = resourceFailure(SEARCH_TEMPLATE, "openscholar://searches/" + SEARCH_ID);
		McpError missingSearch = resourceFailure(SEARCH_TEMPLATE, "openscholar://searches/" + ALTERNATE_ID);

		assertThat(hiddenCollection.getJsonRpcError()).isEqualTo(missingCollection.getJsonRpcError())
			.isEqualTo(hiddenSearch.getJsonRpcError())
			.isEqualTo(missingSearch.getJsonRpcError());
		assertThat(hiddenCollection.getJsonRpcError().data()).isNull();
	}

	@Test
	void mapsOversizedAndUnexpectedFailuresWithoutLeakingContentOrNestedDetails() {
		String oversizedTitle = "private-title-" + "x".repeat(2_048);
		resources = resourcesWithBudget(1_024L);
		when(paperDetailsUseCase.get(PAPER_ID)).thenReturn(paperDetails(oversizedTitle));

		Throwable oversized = catchThrowable(() -> read(PAPER_TEMPLATE, "openscholar://papers/" + PAPER_ID));
		McpError oversizedError = assertMcpError(oversized, McpSchema.ErrorCodes.INTERNAL_ERROR,
				"MCP resource response too large");
		assertThat(oversizedError.toString()).doesNotContain(oversizedTitle, PAPER_ID.toString(), "2048");

		String nestedSecret = "jdbc:postgresql://private-db/research?password=hunter2";
		when(searchUseCase.get(SEARCH_ID))
			.thenThrow(new IllegalStateException("outer secret", new IllegalArgumentException(nestedSecret)));
		Throwable unexpected = catchThrowable(() -> read(SEARCH_TEMPLATE, "openscholar://searches/" + SEARCH_ID));
		McpError unexpectedError = assertMcpError(unexpected, McpSchema.ErrorCodes.INTERNAL_ERROR,
				"MCP resource read failed");
		assertThat(unexpectedError.toString()).doesNotContain("outer secret", nestedSecret, SEARCH_ID.toString());
	}

	private OpenScholarMcpResources resourcesWithBudget(long maximumBytes) {
		McpResourceResultBudget resultBudget = new McpResourceResultBudget(objectMapper,
				new McpPayloadProperties(null, null, maximumBytes));
		return new OpenScholarMcpResources(paperDetailsUseCase, libraryUseCase, searchUseCase, resultBudget);
	}

	private McpStatelessServerFeatures.SyncResourceTemplateSpecification specification(String uriTemplate) {
		return resources.resourceTemplateSpecifications().stream()
			.filter(specification -> specification.resourceTemplate().uriTemplate().equals(uriTemplate))
			.findFirst()
			.orElseThrow();
	}

	private McpSchema.ReadResourceResult read(String uriTemplate, String uri) {
		return specification(uriTemplate).readHandler()
			.apply(McpTransportContext.EMPTY, new McpSchema.ReadResourceRequest(uri));
	}

	private JsonNode readJson(String uriTemplate, String uri) throws Exception {
		McpSchema.ReadResourceResult result = read(uriTemplate, uri);
		assertThat(result.contents()).singleElement().isInstanceOf(McpSchema.TextResourceContents.class);
		McpSchema.TextResourceContents content = (McpSchema.TextResourceContents) result.contents().getFirst();
		assertThat(content.uri()).isEqualTo(uri);
		assertThat(content.mimeType()).isEqualTo("application/json");
		return objectMapper.readTree(content.text());
	}

	private McpError resourceFailure(String uriTemplate, String uri) {
		return assertMcpError(catchThrowable(() -> read(uriTemplate, uri)),
				McpSchema.ErrorCodes.RESOURCE_NOT_FOUND, "Resource not found");
	}

	private static McpError assertMcpError(Throwable failure, int code, String message) {
		assertThat(failure).isInstanceOf(McpError.class).hasMessage(message).hasNoCause();
		McpError mcpError = (McpError) failure;
		assertThat(mcpError.getJsonRpcError().code()).isEqualTo(code);
		assertThat(mcpError.getJsonRpcError().message()).isEqualTo(message);
		assertThat(mcpError.getJsonRpcError().data()).isNull();
		return mcpError;
	}

	private static void assertPaperMetadataFields(JsonNode paper) {
		assertFields(paper, "paperId", "title", "abstractText", "publicationDate", "publicationYear",
				"documentType", "language", "venueName", "citationCount", "citationCountAsOf", "identifiers",
				"authors", "publisher", "institution", "volume", "issue", "pages", "articleNumber", "edition",
				"isbn", "issn", "degree");
	}

	private static void assertFields(JsonNode object, String... fields) {
		assertThat(object.propertyNames()).containsExactlyInAnyOrder(fields);
	}

	private static PaperDetailsView paperDetails(String title) {
		PaperProviderRecordView provenance = new PaperProviderRecordView(
				UUID.fromString("55555555-5555-5555-5555-555555555555"), "OPENALEX", "W-RESOURCE-1",
				URI.create(SOURCE_URL), NOW.minus(Duration.ofHours(2)), NOW.minus(Duration.ofHours(1)), true);
		return new PaperDetailsView(paper(title), new BigDecimal("1.00"), NOW, List.of(provenance), null);
	}

	private static PaperView paper(String title) {
		return new PaperView(PAPER_ID, title, "Stored abstract", LocalDate.of(2026, 8, 25), 2026,
				DocumentType.ARTICLE, "en", "Journal of Resource Tests", 12, NOW,
				List.of(new PaperIdentifier(PaperIdentifierType.DOI, "", "10.1000/resource-test")),
				List.of(new PaperAuthorView(UUID.fromString("66666666-6666-6666-6666-666666666666"),
						"Ada Researcher", "0000-0001-2345-6789", "A123", 0, true)));
	}

	private static CollectionDetailsView collectionDetails(UUID collectionId) {
		SavedPaperView saved = new SavedPaperView(collectionId, "Core reading", PAPER_ID, "A stored paper",
				List.of("Ada Researcher"), 2026, DocumentType.ARTICLE, ReadingStatus.READING,
				List.of("agents"), NOW.minus(Duration.ofDays(1)), NOW);
		return new CollectionDetailsView(collectionId, "Core reading", "Owner-scoped metadata", 1,
				NOW.minus(Duration.ofDays(2)), NOW, new LibraryPage<>(List.of(saved), 0, 25, 1, 1));
	}

	private static SearchView searchView(UUID searchId) {
		SearchResultView result = new SearchResultView(1, paper("A stored paper"), true, URI.create(LANDING_URL),
				URI.create(PDF_URL), 0.95d, List.of(new RankingReason("title", 1.0d)), ProviderId.OPENALEX,
				"W-RESOURCE-1", NOW);
		return new SearchView(searchId, "agent systems", "fingerprint", CacheDisposition.EXACT_HIT,
				SearchMode.AUTO, SearchExecutionSource.EXACT_CACHE, NOW, NOW.plus(Duration.ofHours(1)), null,
				List.of(new ProviderCoverageView(ProviderId.OPENALEX, "SUCCESS", 1, 1)), List.of(),
				List.of(result));
	}
}
