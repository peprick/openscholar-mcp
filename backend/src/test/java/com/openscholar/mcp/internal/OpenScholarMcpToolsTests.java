package com.openscholar.mcp.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.IntStream;

import com.openscholar.access.AccessDisposition;
import com.openscholar.access.AccessStatus;
import com.openscholar.access.AccessUnavailableException;
import com.openscholar.access.PaperAccessUseCase;
import com.openscholar.access.PaperAccessView;
import com.openscholar.citation.CitationBatchExportUseCase;
import com.openscholar.citation.CitationExport;
import com.openscholar.citation.CitationFormat;
import com.openscholar.library.CollectionDetailsView;
import com.openscholar.library.CollectionNotFoundException;
import com.openscholar.library.CollectionSummaryView;
import com.openscholar.library.LibraryPage;
import com.openscholar.library.LibraryUseCase;
import com.openscholar.library.ReadingStatus;
import com.openscholar.library.SavedPaperView;
import com.openscholar.paper.DocumentType;
import com.openscholar.paper.InvalidPaperIdentifierException;
import com.openscholar.paper.PaperDetailsUseCase;
import com.openscholar.paper.PaperDetailsView;
import com.openscholar.paper.PaperIdentifierLookupUseCase;
import com.openscholar.paper.PaperIdentifierNotFoundException;
import com.openscholar.paper.PaperIdentifierResolutionView;
import com.openscholar.paper.PaperNotFoundException;
import com.openscholar.paper.PaperView;
import com.openscholar.paper.ResolvablePaperIdentifierType;
import com.openscholar.provider.ProviderId;
import com.openscholar.search.CacheDisposition;
import com.openscholar.search.SearchCommand;
import com.openscholar.search.SearchCoordinationInterruptedException;
import com.openscholar.search.SearchCoordinationTimeoutException;
import com.openscholar.search.SearchDeadlineExceededException;
import com.openscholar.search.SearchExecutionInterruptedException;
import com.openscholar.search.SearchExecutionSource;
import com.openscholar.search.SearchMode;
import com.openscholar.search.SearchResearchUseCase;
import com.openscholar.search.SearchResultView;
import com.openscholar.search.SearchUnavailableException;
import com.openscholar.search.SearchView;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class OpenScholarMcpToolsTests {

	private static final UUID PAPER_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");

	private static final UUID COLLECTION_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");

	private static final Instant NOW = Instant.parse("2026-08-19T10:15:30Z");

	private static final String NESTED_SECRET = "jdbc:postgresql://private-db/research?password=hunter2";

	private RecordingSearchUseCase search;

	private RecordingPaperDetailsUseCase paperDetails;

	private RecordingPaperIdentifierLookupUseCase paperIdentifierLookup;

	private RecordingPaperAccessUseCase paperAccess;

	private RecordingLibraryUseCase library;

	private RecordingCitationExportUseCase citations;

	private OpenScholarMcpTools tools;

	private SearchView searchView;

	private PaperDetailsView paperDetailsView;

	private PaperAccessView paperAccessView;

	private SavedPaperView savedPaperView;

	@BeforeEach
	void setUp() {
		PaperView paper = new PaperView(PAPER_ID, "A stored paper", "Synthetic abstract", LocalDate.of(2026, 8, 19),
				2026, DocumentType.ARTICLE, "en", "Journal of Tests", 12, NOW, List.of(), List.of(),
				"Test Research Press", "Example Test University", "9", "4", "50-72", "e9001", "2nd",
				List.of("978-0-306-40615-7"), List.of("2049-3630", "2049-3649"),
				"Doctor of Philosophy");
		searchView = new SearchView(UUID.fromString("33333333-3333-3333-3333-333333333333"), "agent systems",
				"fingerprint", CacheDisposition.EXACT_HIT, NOW, NOW.plus(Duration.ofHours(1)), null, List.of(),
				List.of(), List.of(new SearchResultView(1, paper, true, null, null, 0.9d, List.of(),
						ProviderId.OPENALEX, "W-MCP-TYPED", NOW)));
		paperDetailsView = new PaperDetailsView(paper, BigDecimal.ONE, NOW, List.of(), null);
		paperAccessView = new PaperAccessView(PAPER_ID, AccessStatus.UNKNOWN, AccessDisposition.NOT_YET_RESOLVED, null,
				null, List.of(), List.of(), List.of());
		savedPaperView = new SavedPaperView(COLLECTION_ID, "Core reading", PAPER_ID, "A stored paper",
				List.of("Ada Researcher"), 2026, DocumentType.ARTICLE, ReadingStatus.READING,
				List.of("agents"), NOW, NOW);

		search = new RecordingSearchUseCase(searchView);
		paperDetails = new RecordingPaperDetailsUseCase(paperDetailsView);
		paperIdentifierLookup = new RecordingPaperIdentifierLookupUseCase(
				new PaperIdentifierResolutionView(PAPER_ID, ResolvablePaperIdentifierType.DOI,
						"10.1000/openscholar.mcp-test"));
		paperAccess = new RecordingPaperAccessUseCase(paperAccessView);
		library = new RecordingLibraryUseCase(new LibraryPage<>(List.of(savedPaperView), 0, 20, 1, 1));
		citations = new RecordingCitationExportUseCase(new CitationExport(CitationFormat.BIBTEX, "batch",
				"openscholar-citations-1.bib", CitationFormat.BIBTEX.mediaType(), "@article{batch}\n"));
		tools = toolsWithResultBudget(1_048_576);
	}

	@Test
	void searchResearchMapsDefaultsIntoTheSharedSearchCommand() {
		OpenScholarMcpTools.SearchResearchToolResult result = tools.searchResearch("  agent systems  ", null, null,
				null, null, null, null, null, null, null, null);

		assertThat(result.searchId()).isEqualTo(searchView.searchId());
		assertThat(result.query()).isEqualTo(searchView.query());
		assertThat(result.cacheDisposition()).isEqualTo(CacheDisposition.EXACT_HIT);
		assertThat(result.requestedMode()).isEqualTo(SearchMode.AUTO);
		assertThat(result.executionSource()).isEqualTo(SearchExecutionSource.EXACT_CACHE);
		assertThat(result.nextCursor()).isNull();
		assertThat(result.providerCoverage()).isEmpty();
		assertThat(result.warnings()).isEmpty();
		assertThat(result.results()).singleElement().satisfies(paper -> {
			assertThat(paper.publisher()).isEqualTo("Test Research Press");
			assertThat(paper.institution()).isEqualTo("Example Test University");
			assertThat(paper.volume()).isEqualTo("9");
			assertThat(paper.issue()).isEqualTo("4");
			assertThat(paper.pages()).isEqualTo("50-72");
			assertThat(paper.articleNumber()).isEqualTo("e9001");
			assertThat(paper.edition()).isEqualTo("2nd");
			assertThat(paper.isbn()).containsExactly("978-0-306-40615-7");
			assertThat(paper.issn()).containsExactly("2049-3630", "2049-3649");
			assertThat(paper.degree()).isEqualTo("Doctor of Philosophy");
			assertThat(paper.provenance()).singleElement().satisfies(provenance -> {
				assertThat(provenance.provider()).isEqualTo(ProviderId.OPENALEX);
				assertThat(provenance.providerRecordId()).isEqualTo("W-MCP-TYPED");
				assertThat(provenance.retrievedAt()).isEqualTo(NOW);
			});
		});
		assertThat(search.calls).isOne();
		assertThat(search.command.query()).isEqualTo("agent systems");
		assertThat(search.command.yearFrom()).isNull();
		assertThat(search.command.yearTo()).isNull();
		assertThat(search.command.documentTypes()).isEmpty();
		assertThat(search.command.openAccessOnly()).isFalse();
		assertThat(search.command.minimumCitations()).isZero();
		assertThat(search.command.languages()).isEmpty();
		assertThat(search.command.pageSize()).isEqualTo(20);
		assertThat(search.command.cursor()).isEqualTo("*");
		assertThat(search.command.forceRefresh()).isFalse();
		assertThat(search.command.mode()).isEqualTo(SearchMode.AUTO);
	}

	@Test
	void searchResearchMapsExplicitValuesAndAcceptsTheInclusivePageCap() {
		OpenScholarMcpTools.SearchResearchToolResult result = tools.searchResearch("graph agents", 2020, 2026,
				Set.of(DocumentType.ARTICLE, DocumentType.THESIS), true, 7, Set.of("EN", "Fr"), 25,
				"opaque-cursor", true, SearchMode.ONLINE);

		assertThat(result.searchId()).isEqualTo(searchView.searchId());
		assertThat(search.command.yearFrom()).isEqualTo(2020);
		assertThat(search.command.yearTo()).isEqualTo(2026);
		assertThat(search.command.documentTypes()).containsExactlyInAnyOrder(DocumentType.ARTICLE, DocumentType.THESIS);
		assertThat(search.command.openAccessOnly()).isTrue();
		assertThat(search.command.minimumCitations()).isEqualTo(7);
		assertThat(search.command.languages()).containsExactlyInAnyOrder("en", "fr");
		assertThat(search.command.pageSize()).isEqualTo(25);
		assertThat(search.command.cursor()).isEqualTo("opaque-cursor");
		assertThat(search.command.forceRefresh()).isTrue();
		assertThat(search.command.mode()).isEqualTo(SearchMode.ONLINE);
	}

	@Test
	void searchResearchRejectsPageSizesOutsideTheMcpBoundsBeforeCallingTheUseCase() {
		for (int invalidSize : List.of(0, 26)) {
			Throwable failure = catchThrowable(() -> tools.searchResearch("agent systems", null, null, null, null, null,
					null, invalidSize, null, null, null));

			assertSafeFailure(failure, "INVALID_REQUEST: MCP page size must be between 1 and 25");
		}
		assertThat(search.calls).isZero();
	}

	@Test
	void searchResearchRejectsAForcedLocalSearchBeforeCallingTheUseCase() {
		Throwable failure = catchThrowable(() -> tools.searchResearch("agent systems", null, null, null, null, null,
				null, null, null, true, SearchMode.LOCAL));

		assertSafeFailure(failure, "INVALID_REQUEST: Local search cannot force a provider refresh");
		assertThat(search.calls).isZero();
	}

	@Test
	void paperDetailsWrapsCanonicalMetadataAndStoredAccessWithoutResolving() {
		OpenScholarMcpTools.PaperDetailsToolResult result = tools.getPaperDetails(PAPER_ID);

		assertThat(result.paper().paperId()).isEqualTo(PAPER_ID);
		assertThat(result.paper().title()).isEqualTo("A stored paper");
		assertThat(result.paper().publisher()).isEqualTo("Test Research Press");
		assertThat(result.paper().institution()).isEqualTo("Example Test University");
		assertThat(result.paper().volume()).isEqualTo("9");
		assertThat(result.paper().issue()).isEqualTo("4");
		assertThat(result.paper().pages()).isEqualTo("50-72");
		assertThat(result.paper().articleNumber()).isEqualTo("e9001");
		assertThat(result.paper().edition()).isEqualTo("2nd");
		assertThat(result.paper().isbn()).containsExactly("978-0-306-40615-7");
		assertThat(result.paper().issn()).containsExactly("2049-3630", "2049-3649");
		assertThat(result.paper().degree()).isEqualTo("Doctor of Philosophy");
		assertThat(result.paper().identifiers()).isEmpty();
		assertThat(result.paper().authors()).isEmpty();
		assertThat(result.metadataCompleteness()).isEqualByComparingTo(BigDecimal.ONE);
		assertThat(result.metadataUpdatedAt()).isEqualTo(NOW);
		assertThat(result.provenance()).isEmpty();
		assertThat(result.authorshipProviderRecordId()).isNull();
		assertThat(result.storedAccess().paperId()).isEqualTo(PAPER_ID);
		assertThat(result.storedAccess().status()).isEqualTo(AccessStatus.UNKNOWN);
		assertThat(result.storedAccess().disposition()).isEqualTo(AccessDisposition.NOT_YET_RESOLVED);
		assertThat(paperDetails.paperId).isEqualTo(PAPER_ID);
		assertThat(paperDetails.calls).isOne();
		assertThat(paperAccess.paperId).isEqualTo(PAPER_ID);
		assertThat(paperAccess.getCalls).isOne();
		assertThat(paperAccess.resolveCalls).isZero();
	}

	@Test
	void paperIdentifierResolutionDelegatesToTheOwnerScopedDatabaseUseCase() {
		OpenScholarMcpTools.PaperIdentifierResolutionToolResult result = tools
			.resolvePaperIdentifier("https://doi.org/10.1000/OpenScholar.MCP-Test");

		assertThat(result.paperId()).isEqualTo(PAPER_ID);
		assertThat(result.identifierType()).isEqualTo(ResolvablePaperIdentifierType.DOI);
		assertThat(result.normalizedValue()).isEqualTo("10.1000/openscholar.mcp-test");
		assertThat(paperIdentifierLookup.identifier)
			.isEqualTo("https://doi.org/10.1000/OpenScholar.MCP-Test");
		assertThat(paperIdentifierLookup.calls).isOne();
		assertThat(search.calls).isZero();
		assertThat(paperDetails.calls).isZero();
		assertThat(paperAccess.getCalls).isZero();
		assertThat(paperAccess.resolveCalls).isZero();
	}

	@Test
	void paperIdentifierResolutionExposesStableSafeFailures() {
		paperIdentifierLookup.failure = new InvalidPaperIdentifierException();
		assertSafeFailure(catchThrowable(() -> tools.resolvePaperIdentifier("not-an-identifier")),
				"INVALID_PAPER_IDENTIFIER: Identifier must be a DOI, arXiv identifier, or OpenAlex work identifier.");

		paperIdentifierLookup.failure = new PaperIdentifierNotFoundException();
		assertSafeFailure(catchThrowable(() -> tools.resolvePaperIdentifier("10.1000/private-paper")),
				"PAPER_IDENTIFIER_NOT_FOUND: No visible paper was found for that identifier.");

		assertThat(paperIdentifierLookup.calls).isEqualTo(2);
		assertThat(search.calls).isZero();
		assertThat(paperDetails.calls).isZero();
		assertThat(paperAccess.getCalls).isZero();
		assertThat(paperAccess.resolveCalls).isZero();
	}

	@Test
	void legalFullTextIsDatabaseOnlyAndNeverResolvesAccess() {
		OpenScholarMcpTools.AccessToolResult result = tools.getLegalFullText(PAPER_ID);

		assertThat(result.paperId()).isEqualTo(PAPER_ID);
		assertThat(result.status()).isEqualTo(AccessStatus.UNKNOWN);
		assertThat(result.disposition()).isEqualTo(AccessDisposition.NOT_YET_RESOLVED);
		assertThat(result.providerCoverage()).isEmpty();
		assertThat(result.warnings()).isEmpty();
		assertThat(result.locations()).isEmpty();
		assertThat(paperAccess.paperId).isEqualTo(PAPER_ID);
		assertThat(paperAccess.getCalls).isOne();
		assertThat(paperAccess.resolveCalls).isZero();
		assertThat(paperDetails.calls).isZero();
	}

	@Test
	void legalFullTextRejectsANullPaperIdBeforeReadingAccess() {
		Throwable failure = catchThrowable(() -> tools.getLegalFullText(null));

		assertSafeFailure(failure, "INVALID_REQUEST: paperId must not be null");
		assertThat(paperAccess.getCalls).isZero();
		assertThat(paperAccess.resolveCalls).isZero();
	}

	@Test
	void savedLibraryMapsDefaultsAndWrapsPageMetadata() {
		OpenScholarMcpTools.SavedLibraryToolResult result = tools.searchSavedLibrary(null, null, null, null, null, null);

		assertThat(library.calls).isOne();
		assertThat(library.query).isNull();
		assertThat(library.collectionId).isNull();
		assertThat(library.readingStatus).isNull();
		assertThat(library.tag).isNull();
		assertThat(library.page).isZero();
		assertThat(library.size).isEqualTo(20);
		assertThat(result.items()).singleElement().satisfies(item -> {
			assertThat(item.collectionId()).isEqualTo(COLLECTION_ID);
			assertThat(item.collectionName()).isEqualTo("Core reading");
			assertThat(item.paperId()).isEqualTo(PAPER_ID);
			assertThat(item.title()).isEqualTo("A stored paper");
			assertThat(item.authors()).containsExactly("Ada Researcher");
			assertThat(item.tags()).containsExactly("agents");
			assertThat(item.readingStatus()).isEqualTo(ReadingStatus.READING);
		});
		assertThat(result.page()).isZero();
		assertThat(result.size()).isEqualTo(20);
		assertThat(result.totalElements()).isOne();
		assertThat(result.totalPages()).isOne();
	}

	@Test
	void savedLibraryAcceptsTwentyFiveAndRejectsLargerPages() {
		tools.searchSavedLibrary("agents", COLLECTION_ID, ReadingStatus.READING, "methods", 2, 25);

		assertThat(library.query).isEqualTo("agents");
		assertThat(library.collectionId).isEqualTo(COLLECTION_ID);
		assertThat(library.readingStatus).isEqualTo(ReadingStatus.READING);
		assertThat(library.tag).isEqualTo("methods");
		assertThat(library.page).isEqualTo(2);
		assertThat(library.size).isEqualTo(25);

		Throwable failure = catchThrowable(
				() -> tools.searchSavedLibrary(null, null, null, null, 0, 26));
		assertSafeFailure(failure, "INVALID_REQUEST: MCP page size must be between 1 and 25");
		assertThat(library.calls).isOne();
	}

	@Test
	void savedLibraryResultDefensivelyCopiesItemsAndCanonicalizesNull() {
		OpenScholarMcpTools.SavedPaperToolItem item = tools.searchSavedLibrary(null, null, null, null, null, null)
			.items()
			.getFirst();
		List<OpenScholarMcpTools.SavedPaperToolItem> mutable = new ArrayList<>(List.of(item));
		OpenScholarMcpTools.SavedLibraryToolResult result = new OpenScholarMcpTools.SavedLibraryToolResult(mutable, 0,
				20, 1, 1);
		mutable.clear();

		assertThat(result.items()).containsExactly(item).isUnmodifiable();
		assertThat(new OpenScholarMcpTools.SavedLibraryToolResult(null, 0, 20, 0, 0).items()).isEmpty();
	}

	@Test
	void citationsDefaultToBibtexAndReturnTheSafeResultWrapper() {
		OpenScholarMcpTools.CitationExportToolResult result = tools.exportCitations(List.of(PAPER_ID), null);

		assertThat(citations.calls).isOne();
		assertThat(citations.paperIds).containsExactly(PAPER_ID);
		assertThat(citations.format).isEqualTo(CitationFormat.BIBTEX);
		assertThat(result.format()).isEqualTo("bibtex");
		assertThat(result.filename()).isEqualTo("openscholar-citations-1.bib");
		assertThat(result.mediaType()).isEqualTo("application/x-bibtex");
		assertThat(result.paperCount()).isOne();
		assertThat(result.content()).isEqualTo("@article{batch}\n");
	}

	@Test
	void citationsSupportCslJsonAndAcceptTheInclusiveTwentyFivePaperCap() {
		citations.result = new CitationExport(CitationFormat.CSL_JSON, "batch", "openscholar-citations-25.csl.json",
				CitationFormat.CSL_JSON.mediaType(), "[]");
		List<UUID> paperIds = paperIds(25);

		OpenScholarMcpTools.CitationExportToolResult result = tools.exportCitations(paperIds, " CSL-JSON ");

		assertThat(citations.paperIds).containsExactlyElementsOf(paperIds);
		assertThat(citations.format).isEqualTo(CitationFormat.CSL_JSON);
		assertThat(result.format()).isEqualTo("csl-json");
		assertThat(result.paperCount()).isEqualTo(25);
		assertThat(result.content()).isEqualTo("[]");
	}

	@Test
	void oversizedToolResultsFailWithAStableSafeCodeWithoutEchoingContent() {
		String oversizedContent = "sensitive-result-" + "x".repeat(1_024);
		citations.result = new CitationExport(CitationFormat.BIBTEX, "batch", "oversized.bib",
				CitationFormat.BIBTEX.mediaType(), oversizedContent);
		tools = toolsWithResultBudget(1_024);

		Throwable failure = catchThrowable(() -> tools.exportCitations(List.of(PAPER_ID), "bibtex"));

		assertSafeFailure(failure,
				"MCP_RESPONSE_TOO_LARGE: The tool result exceeds the configured response budget; retryable=false");
		assertThat(failure.getMessage()).doesNotContain(oversizedContent, "sensitive-result");
	}

	@Test
	void citationsRejectInvalidListsAndFormatsBeforeCallingTheUseCase() {
		assertSafeFailure(catchThrowable(() -> tools.exportCitations(null, null)),
				"INVALID_REQUEST: paperIds must not be null");
		assertSafeFailure(catchThrowable(() -> tools.exportCitations(paperIds(26), "bibtex")),
				"INVALID_REQUEST: MCP citation exports can contain at most 25 papers");
		assertSafeFailure(catchThrowable(() -> tools.exportCitations(Collections.singletonList(null), "bibtex")),
				"INVALID_REQUEST: paperIds must not contain null values");
		assertSafeFailure(catchThrowable(() -> tools.exportCitations(List.of(PAPER_ID), "ris")),
				"UNSUPPORTED_CITATION_FORMAT: Citation format must be one of: bibtex, csl-json");

		assertThat(citations.calls).isZero();
	}

	@Test
	void expectedDomainFailuresExposeStableCodesWithoutNestedCauseDetails() {
		search.failure = new SearchUnavailableException("Provider temporarily unavailable", true,
				Duration.ofSeconds(7), new IllegalStateException(NESTED_SECRET));
		Throwable searchFailure = catchThrowable(() -> tools.searchResearch("agent systems", null, null, null, null,
				null, null, null, null, null, null));
		assertSafeFailure(searchFailure,
				"SEARCH_PROVIDER_UNAVAILABLE: Provider temporarily unavailable; retryable=true; retryAfterSeconds=7");
		assertThat(searchFailure.getMessage()).doesNotContain(NESTED_SECRET, "IllegalStateException");

		paperDetails.failure = new PaperNotFoundException(PAPER_ID);
		assertSafeFailure(catchThrowable(() -> tools.getPaperDetails(PAPER_ID)),
				"PAPER_NOT_FOUND: Paper not found: " + PAPER_ID);

		library.failure = new CollectionNotFoundException(COLLECTION_ID);
		assertSafeFailure(catchThrowable(
				() -> tools.searchSavedLibrary(null, COLLECTION_ID, null, null, null, null)),
				"COLLECTION_NOT_FOUND: Collection not found: " + COLLECTION_ID);
	}

	@Test
	void coordinationFailuresExposeSafeRetryablePrefixesWithoutNestedCauseDetails() {
		SearchCoordinationTimeoutException timeout = new SearchCoordinationTimeoutException();
		timeout.initCause(new IllegalStateException(NESTED_SECRET));
		search.failure = timeout;

		Throwable timeoutFailure = catchThrowable(() -> tools.searchResearch("agent systems", null, null, null, null,
				null, null, null, null, null, null));

		assertSafeFailure(timeoutFailure,
				"SEARCH_COORDINATION_TIMEOUT: Search coordination wait timed out; retryable=true");
		assertThat(timeoutFailure.getMessage()).doesNotContain(NESTED_SECRET, "IllegalStateException");

		search.failure = new SearchCoordinationInterruptedException(new InterruptedException(NESTED_SECRET));
		Throwable interruptedFailure = catchThrowable(() -> tools.searchResearch("agent systems", null, null, null, null,
				null, null, null, null, null, null));

		assertSafeFailure(interruptedFailure,
				"SEARCH_COORDINATION_INTERRUPTED: Search coordination wait was interrupted; retryable=true");
		assertThat(interruptedFailure.getMessage()).doesNotContain(NESTED_SECRET, "InterruptedException");
	}

	@Test
	void executionFailuresExposeSafeRetryablePrefixesWithoutNestedCauseDetails() {
		SearchDeadlineExceededException deadline = new SearchDeadlineExceededException();
		deadline.initCause(new IllegalStateException(NESTED_SECRET));
		search.failure = deadline;

		Throwable deadlineFailure = catchThrowable(() -> tools.searchResearch("agent systems", null, null, null, null,
				null, null, null, null, null, null));

		assertSafeFailure(deadlineFailure,
				"SEARCH_DEADLINE_EXCEEDED: Search execution deadline exceeded; retryable=true");
		assertThat(deadlineFailure.getMessage()).doesNotContain(NESTED_SECRET, "IllegalStateException");

		search.failure = new SearchExecutionInterruptedException(new InterruptedException(NESTED_SECRET));
		Throwable interruptedFailure = catchThrowable(() -> tools.searchResearch("agent systems", null, null, null, null,
				null, null, null, null, null, null));

		assertSafeFailure(interruptedFailure,
				"SEARCH_EXECUTION_INTERRUPTED: Search execution was interrupted; retryable=true");
		assertThat(interruptedFailure.getMessage()).doesNotContain(NESTED_SECRET, "InterruptedException");
	}

	@Test
	void accessFailuresExposeRetryMetadataWithoutNestedCauseDetails() {
		paperAccess.failure = new AccessUnavailableException("Access providers unavailable", false,
				Duration.ofSeconds(9), new IllegalStateException(NESTED_SECRET));

		Throwable failure = catchThrowable(() -> tools.getLegalFullText(PAPER_ID));

		assertSafeFailure(failure,
				"ACCESS_PROVIDERS_UNAVAILABLE: Access providers unavailable; retryable=false; retryAfterSeconds=9");
		assertThat(failure.getMessage()).doesNotContain(NESTED_SECRET, "IllegalStateException");
		assertThat(paperAccess.resolveCalls).isZero();
	}

	@Test
	void unexpectedFailuresReturnOnlyTheGenericSafeMessage() {
		paperDetails.failure = new IllegalStateException("raw outer failure", new IllegalArgumentException(NESTED_SECRET));

		Throwable failure = catchThrowable(() -> tools.getPaperDetails(PAPER_ID));

		assertSafeFailure(failure, "MCP_TOOL_FAILED: The tool could not complete safely.");
		assertThat(failure.getMessage()).doesNotContain("raw outer failure", NESTED_SECRET, "IllegalArgumentException");
		assertThat(paperAccess.getCalls).isZero();
	}

	private static List<UUID> paperIds(int size) {
		return IntStream.range(0, size)
			.mapToObj(index -> UUID.nameUUIDFromBytes(("paper-" + index).getBytes(StandardCharsets.UTF_8)))
			.toList();
	}

	private OpenScholarMcpTools toolsWithResultBudget(long maximumBytes) {
		McpToolResultBudget budget = new McpToolResultBudget(JsonMapper.builder().build(),
				new McpPayloadProperties(null, maximumBytes));
		return new OpenScholarMcpTools(search, paperDetails, paperIdentifierLookup, paperAccess, library, citations,
				budget);
	}

	private static void assertSafeFailure(Throwable failure, String message) {
		assertThat(failure).isInstanceOf(RuntimeException.class).hasMessage(message).hasNoCause();
	}

	private static final class RecordingSearchUseCase implements SearchResearchUseCase {

		private final SearchView result;

		private SearchCommand command;

		private RuntimeException failure;

		private int calls;

		private RecordingSearchUseCase(SearchView result) {
			this.result = result;
		}

		@Override
		public SearchView search(SearchCommand command) {
			calls++;
			this.command = command;
			if (failure != null) {
				throw failure;
			}
			return result;
		}

		@Override
		public SearchView next(UUID searchId) {
			throw new AssertionError("The MCP search tool must call search, not next");
		}

		@Override
		public SearchView get(UUID searchId) {
			throw new AssertionError("The MCP search tool must call search, not get");
		}
	}

	private static final class RecordingPaperDetailsUseCase implements PaperDetailsUseCase {

		private final PaperDetailsView result;

		private UUID paperId;

		private RuntimeException failure;

		private int calls;

		private RecordingPaperDetailsUseCase(PaperDetailsView result) {
			this.result = result;
		}

		@Override
		public PaperDetailsView get(UUID paperId) {
			calls++;
			this.paperId = paperId;
			if (failure != null) {
				throw failure;
			}
			return result;
		}
	}

	private static final class RecordingPaperIdentifierLookupUseCase implements PaperIdentifierLookupUseCase {

		private final PaperIdentifierResolutionView result;

		private String identifier;

		private RuntimeException failure;

		private int calls;

		private RecordingPaperIdentifierLookupUseCase(PaperIdentifierResolutionView result) {
			this.result = result;
		}

		@Override
		public PaperIdentifierResolutionView resolve(String identifier) {
			calls++;
			this.identifier = identifier;
			if (failure != null) {
				throw failure;
			}
			return result;
		}
	}

	private static final class RecordingPaperAccessUseCase implements PaperAccessUseCase {

		private final PaperAccessView result;

		private UUID paperId;

		private RuntimeException failure;

		private int getCalls;

		private int resolveCalls;

		private RecordingPaperAccessUseCase(PaperAccessView result) {
			this.result = result;
		}

		@Override
		public PaperAccessView get(UUID paperId) {
			getCalls++;
			this.paperId = paperId;
			if (failure != null) {
				throw failure;
			}
			return result;
		}

		@Override
		public PaperAccessView resolve(UUID paperId, boolean forceRefresh) {
			resolveCalls++;
			throw new AssertionError("Read-only MCP tools must never resolve legal access");
		}
	}

	private static final class RecordingLibraryUseCase implements LibraryUseCase {

		private final LibraryPage<SavedPaperView> result;

		private String query;

		private UUID collectionId;

		private ReadingStatus readingStatus;

		private String tag;

		private int page;

		private int size;

		private int calls;

		private RuntimeException failure;

		private RecordingLibraryUseCase(LibraryPage<SavedPaperView> result) {
			this.result = result;
		}

		@Override
		public LibraryPage<SavedPaperView> searchSavedPapers(String query, UUID collectionId,
				ReadingStatus readingStatus, String tag, int page, int size) {
			calls++;
			this.query = query;
			this.collectionId = collectionId;
			this.readingStatus = readingStatus;
			this.tag = tag;
			this.page = page;
			this.size = size;
			if (failure != null) {
				throw failure;
			}
			return result;
		}

		@Override
		public LibraryPage<CollectionSummaryView> listCollections(int page, int size) {
			throw unsupported();
		}

		@Override
		public CollectionSummaryView createCollection(String name, String description) {
			throw unsupported();
		}

		@Override
		public CollectionDetailsView getCollection(UUID collectionId, int page, int size) {
			throw unsupported();
		}

		@Override
		public CollectionSummaryView updateCollection(UUID collectionId, String name, String description) {
			throw unsupported();
		}

		@Override
		public void deleteCollection(UUID collectionId) {
			throw unsupported();
		}

		@Override
		public SavedPaperView addPaper(UUID collectionId, UUID paperId, ReadingStatus readingStatus,
				Collection<String> tags) {
			throw unsupported();
		}

		@Override
		public SavedPaperView updatePaper(UUID collectionId, UUID paperId, ReadingStatus readingStatus,
				Collection<String> tags) {
			throw unsupported();
		}

		@Override
		public void removePaper(UUID collectionId, UUID paperId) {
			throw unsupported();
		}

		private static AssertionError unsupported() {
			return new AssertionError("The MCP saved-library tool called an unrelated library operation");
		}
	}

	private static final class RecordingCitationExportUseCase implements CitationBatchExportUseCase {

		private CitationExport result;

		private List<UUID> paperIds;

		private CitationFormat format;

		private int calls;

		private RecordingCitationExportUseCase(CitationExport result) {
			this.result = result;
		}

		@Override
		public CitationExport exportBatch(List<UUID> paperIds, CitationFormat format) {
			calls++;
			this.paperIds = List.copyOf(paperIds);
			this.format = format;
			return result;
		}
	}
}
