package com.openscholar.mcp.internal;

import java.math.BigDecimal;
import java.net.URI;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.regex.Pattern;

import com.openscholar.access.AccessRefreshTooSoonException;
import com.openscholar.access.AccessDisposition;
import com.openscholar.access.AccessHostType;
import com.openscholar.access.AccessLocationView;
import com.openscholar.access.AccessProviderCoverageView;
import com.openscholar.access.AccessStatus;
import com.openscholar.access.AccessUnavailableException;
import com.openscholar.access.AccessVerificationStatus;
import com.openscholar.access.AccessVersionType;
import com.openscholar.access.ContentHandlingMode;
import com.openscholar.access.PaperAccessUseCase;
import com.openscholar.access.PaperAccessView;
import com.openscholar.citation.CitationBatchExportUseCase;
import com.openscholar.citation.CitationExport;
import com.openscholar.citation.CitationFormat;
import com.openscholar.citation.UnsupportedCitationFormatException;
import com.openscholar.library.CollectionNotFoundException;
import com.openscholar.library.LibraryPage;
import com.openscholar.library.LibraryUseCase;
import com.openscholar.library.ReadingStatus;
import com.openscholar.library.SavedPaperView;
import com.openscholar.paper.DocumentType;
import com.openscholar.paper.InvalidPaperIdentifierException;
import com.openscholar.paper.PaperAuthorView;
import com.openscholar.paper.PaperDetailsUseCase;
import com.openscholar.paper.PaperDetailsView;
import com.openscholar.paper.PaperIdentifier;
import com.openscholar.paper.PaperIdentifierLookupUseCase;
import com.openscholar.paper.PaperIdentifierNotFoundException;
import com.openscholar.paper.PaperIdentifierResolutionView;
import com.openscholar.paper.PaperNotFoundException;
import com.openscholar.paper.PaperProviderRecordView;
import com.openscholar.paper.PaperView;
import com.openscholar.paper.ResolvablePaperIdentifierType;
import com.openscholar.provider.ProviderId;
import com.openscholar.search.CacheDisposition;
import com.openscholar.search.ProviderContributionView;
import com.openscholar.search.ProviderCoverageView;
import com.openscholar.search.RankingReason;
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
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

@Component
public class OpenScholarMcpTools {

	private static final Logger LOGGER = LoggerFactory.getLogger(OpenScholarMcpTools.class);

	private static final Pattern LANGUAGE_CODE = Pattern.compile("(?i)[a-z]{2,3}");

	private static final int MAX_MCP_PAGE_SIZE = 25;

	private static final int MAX_MCP_CITATION_COUNT = 25;

	private final SearchResearchUseCase searchUseCase;

	private final PaperDetailsUseCase paperDetailsUseCase;

	private final PaperIdentifierLookupUseCase paperIdentifierLookupUseCase;

	private final PaperAccessUseCase paperAccessUseCase;

	private final LibraryUseCase libraryUseCase;

	private final CitationBatchExportUseCase citationExportUseCase;

	private final McpToolResultBudget toolResultBudget;

	public OpenScholarMcpTools(SearchResearchUseCase searchUseCase, PaperDetailsUseCase paperDetailsUseCase,
			PaperIdentifierLookupUseCase paperIdentifierLookupUseCase, PaperAccessUseCase paperAccessUseCase,
			LibraryUseCase libraryUseCase,
			CitationBatchExportUseCase citationExportUseCase, McpToolResultBudget toolResultBudget) {
		this.searchUseCase = searchUseCase;
		this.paperDetailsUseCase = paperDetailsUseCase;
		this.paperIdentifierLookupUseCase = paperIdentifierLookupUseCase;
		this.paperAccessUseCase = paperAccessUseCase;
		this.libraryUseCase = libraryUseCase;
		this.citationExportUseCase = citationExportUseCase;
		this.toolResultBudget = toolResultBudget;
	}

	@McpTool(name = "search_research", title = "Search research",
			description = "Search scholarly metadata through OpenScholar's bounded provider/cache/local pipeline. "
					+ "Provider-reported access links are discovery hints; use get_legal_full_text for verified legal locations.",
			generateOutputSchema = true,
			annotations = @McpTool.McpAnnotations(readOnlyHint = false, destructiveHint = false,
					idempotentHint = false, openWorldHint = true))
	public SearchResearchToolResult searchResearch(
			@McpToolParam(description = "Research topic, between 3 and 500 characters") String topic,
			@McpToolParam(description = "Earliest publication year, between 1000 and 9999",
					required = false) Integer yearFrom,
			@McpToolParam(description = "Latest publication year, between 1000 and 9999",
					required = false) Integer yearTo,
			@McpToolParam(description = "Optional publication types such as ARTICLE, PREPRINT, or THESIS",
					required = false) Set<DocumentType> documentTypes,
			@McpToolParam(description = "Return only records reported open access by the discovery provider",
					required = false) Boolean openAccessOnly,
			@McpToolParam(description = "Return only records for which a discovery provider reports a PDF link. "
					+ "The link is still verified separately before reading",
					required = false) Boolean pdfAvailableOnly,
			@McpToolParam(description = "Minimum citation count; defaults to zero",
					required = false) Integer minimumCitations,
			@McpToolParam(description = "Optional two- or three-letter language codes",
					required = false) Set<String> languages,
			@McpToolParam(description = "Maximum results for this page, between 1 and 25; defaults to 20",
					required = false) Integer limit,
			@McpToolParam(description = "Opaque cursor from an earlier search_research result",
					required = false) String cursor,
			@McpToolParam(description = "Bypass an exact fresh cache hit; defaults to false",
					required = false) Boolean forceRefresh,
			@McpToolParam(description = "AUTO, ONLINE, or LOCAL execution mode; defaults to AUTO. "
					+ "LOCAL never contacts a provider and is incompatible with forceRefresh=true",
					required = false) SearchMode mode) {
		return execute("search_research",
				() -> SearchResearchToolResult.from(searchUseCase.search(new SearchCommand(topic, yearFrom, yearTo,
						validateDocumentTypes(documentTypes),
						Boolean.TRUE.equals(openAccessOnly), Boolean.TRUE.equals(pdfAvailableOnly),
						defaulted(minimumCitations, 0),
						validateLanguages(languages), boundedPageSize(limit, 20), cursor,
						Boolean.TRUE.equals(forceRefresh), mode == null ? SearchMode.AUTO : mode))));
	}

	public SearchResearchToolResult searchResearch(
			String topic,
			Integer yearFrom,
			Integer yearTo,
			Set<DocumentType> documentTypes,
			Boolean openAccessOnly,
			Integer minimumCitations,
			Set<String> languages,
			Integer limit,
			String cursor,
			Boolean forceRefresh,
			SearchMode mode) {
		return searchResearch(topic, yearFrom, yearTo, documentTypes, openAccessOnly, false,
				minimumCitations, languages, limit, cursor, forceRefresh, mode);
	}

	@McpTool(name = "get_paper_details", title = "Get paper details",
			description = "Read canonical stored metadata, identifiers, authorship, provenance, freshness, and the stored "
					+ "legal-access resolution, including coverage, warnings, and locations, for one OpenScholar paper UUID. "
					+ "This tool does not contact providers.",
			generateOutputSchema = true,
			annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false,
					idempotentHint = true, openWorldHint = false))
	public PaperDetailsToolResult getPaperDetails(
			@McpToolParam(description = "Canonical OpenScholar paper UUID") UUID paperId) {
		return execute("get_paper_details", () -> {
			UUID requiredPaperId = requirePaperId(paperId);
			PaperDetailsView paper = paperDetailsUseCase.get(requiredPaperId);
			return PaperDetailsToolResult.from(paper, paperAccessUseCase.get(requiredPaperId));
		});
	}

	@McpTool(name = "resolve_paper_identifier", title = "Resolve paper identifier",
			description = "Resolve one DOI, arXiv, or OpenAlex work identifier to a canonical OpenScholar paper UUID. "
					+ "This owner-scoped database lookup only returns papers already visible in the caller's research history "
					+ "or saved library and never contacts a provider.",
			generateOutputSchema = true,
			annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false,
					idempotentHint = true, openWorldHint = false))
	public PaperIdentifierResolutionToolResult resolvePaperIdentifier(
			@McpToolParam(description = "DOI, arXiv identifier, OpenAlex work identifier, or canonical URL") String identifier) {
		return execute("resolve_paper_identifier",
				() -> PaperIdentifierResolutionToolResult.from(paperIdentifierLookupUseCase.resolve(identifier)));
	}

	@McpTool(name = "get_legal_full_text", title = "Find legal full text",
			description = "Read the stored legal full-text or landing-page resolution for one canonical OpenScholar paper. "
					+ "This database-only tool never fetches bytes or contacts a provider; NOT_YET_RESOLVED means verification "
					+ "has not been run through the REST/UI workflow yet.",
			generateOutputSchema = true,
			annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false,
					idempotentHint = true, openWorldHint = false))
	public AccessToolResult getLegalFullText(
			@McpToolParam(description = "Canonical OpenScholar paper UUID") UUID paperId) {
		return execute("get_legal_full_text",
				() -> AccessToolResult.from(paperAccessUseCase.get(requirePaperId(paperId))));
	}

	@McpTool(name = "search_saved_library", title = "Search saved library",
			description = "Search the owner-scoped local research library by title, author, abstract, venue, collection, "
					+ "reading status, or normalized tag. Returns one item per collection membership.",
			generateOutputSchema = true,
			annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false,
					idempotentHint = true, openWorldHint = false))
	public SavedLibraryToolResult searchSavedLibrary(
			@McpToolParam(description = "Optional lexical query, at most 200 characters",
					required = false) String query,
			@McpToolParam(description = "Optional owner-scoped collection UUID",
					required = false) UUID collectionId,
			@McpToolParam(description = "Optional UNREAD, READING, or COMPLETED status",
					required = false) ReadingStatus readingStatus,
			@McpToolParam(description = "Optional normalized tag, at most 40 characters",
					required = false) String tag,
			@McpToolParam(description = "Zero-based page number; defaults to zero",
					required = false) Integer page,
			@McpToolParam(description = "Page size between 1 and 25; defaults to 20",
					required = false) Integer size) {
		return execute("search_saved_library", () -> {
			LibraryPage<SavedPaperView> result = libraryUseCase.searchSavedPapers(query, collectionId, readingStatus,
					tag, defaulted(page, 0), boundedPageSize(size, 20));
			return new SavedLibraryToolResult(result.items().stream().map(SavedPaperToolItem::from).toList(),
					result.page(), result.size(), result.totalElements(), result.totalPages());
		});
	}

	@McpTool(name = "export_citations", title = "Export citations",
			description = "Export one to 25 distinct canonical OpenScholar paper UUIDs in caller order as BibTeX "
					+ "or CSL-JSON using stored metadata only. The whole call fails if any paper is unknown.",
			generateOutputSchema = true,
			annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false,
					idempotentHint = true, openWorldHint = false))
	public CitationExportToolResult exportCitations(
			@McpToolParam(description = "One to 25 distinct canonical OpenScholar paper UUIDs") List<UUID> paperIds,
			@McpToolParam(description = "bibtex or csl-json; defaults to bibtex",
					required = false) String format) {
		return execute("export_citations", () -> {
			if (paperIds == null) {
				throw new IllegalArgumentException("paperIds must not be null");
			}
			if (paperIds.size() > MAX_MCP_CITATION_COUNT) {
				throw new IllegalArgumentException("MCP citation exports can contain at most 25 papers");
			}
			if (paperIds.stream().anyMatch(java.util.Objects::isNull)) {
				throw new IllegalArgumentException("paperIds must not contain null values");
			}
			CitationExport export = citationExportUseCase.exportBatch(paperIds,
					format == null ? CitationFormat.BIBTEX : CitationFormat.fromApiValue(format));
			return new CitationExportToolResult(export.format().apiValue(), export.filename(), export.mediaType(),
					paperIds.size(), export.body());
		});
	}

	private static int defaulted(Integer value, int defaultValue) {
		return value == null ? defaultValue : value;
	}

	private static <T> List<T> nonEmptyOrNull(List<T> values) {
		return values == null || values.isEmpty() ? null : List.copyOf(values);
	}

	private static int boundedPageSize(Integer value, int defaultValue) {
		int size = defaulted(value, defaultValue);
		if (size < 1 || size > MAX_MCP_PAGE_SIZE) {
			throw new IllegalArgumentException("MCP page size must be between 1 and 25");
		}
		return size;
	}

	private static <T> Set<T> safeSet(Set<T> values) {
		if (values == null) {
			return Set.of();
		}
		if (values.stream().anyMatch(java.util.Objects::isNull)) {
			throw new IllegalArgumentException("Set values must not contain null");
		}
		return Set.copyOf(values);
	}

	private static Set<DocumentType> validateDocumentTypes(Set<DocumentType> documentTypes) {
		Set<DocumentType> values = safeSet(documentTypes);
		if (values.size() > 12) {
			throw new IllegalArgumentException("documentTypes must contain at most 12 values");
		}
		return values;
	}

	private static UUID requirePaperId(UUID paperId) {
		if (paperId == null) {
			throw new IllegalArgumentException("paperId must not be null");
		}
		return paperId;
	}

	private static Set<String> validateLanguages(Set<String> languages) {
		Set<String> values = safeSet(languages);
		if (values.size() > 20) {
			throw new IllegalArgumentException("languages must contain at most 20 values");
		}
		for (String language : values) {
			if (language == null || !LANGUAGE_CODE.matcher(language).matches()) {
				throw new IllegalArgumentException("languages must contain two- or three-letter codes");
			}
		}
		return values.stream().map(value -> value.toLowerCase(Locale.ROOT)).collect(java.util.stream.Collectors.toSet());
	}

	private <T> T execute(String toolName, Supplier<T> action) {
		try {
			return toolResultBudget.requireWithinLimit(action.get());
		}
		catch (InvalidPaperIdentifierException exception) {
			throw failure(McpToolErrorCode.INVALID_PAPER_IDENTIFIER);
		}
		catch (PaperIdentifierNotFoundException exception) {
			throw failure(McpToolErrorCode.PAPER_IDENTIFIER_NOT_FOUND);
		}
		catch (PaperNotFoundException exception) {
			throw failure(McpToolErrorCode.PAPER_NOT_FOUND);
		}
		catch (CollectionNotFoundException exception) {
			throw failure(McpToolErrorCode.COLLECTION_NOT_FOUND);
		}
		catch (UnsupportedCitationFormatException exception) {
			throw failure(McpToolErrorCode.UNSUPPORTED_CITATION_FORMAT);
		}
		catch (SearchCoordinationTimeoutException exception) {
			throw retryableFailure(McpToolErrorCode.SEARCH_COORDINATION_TIMEOUT);
		}
		catch (SearchCoordinationInterruptedException exception) {
			throw retryableFailure(McpToolErrorCode.SEARCH_COORDINATION_INTERRUPTED);
		}
		catch (SearchDeadlineExceededException exception) {
			throw retryableFailure(McpToolErrorCode.SEARCH_DEADLINE_EXCEEDED);
		}
		catch (SearchExecutionInterruptedException exception) {
			throw retryableFailure(McpToolErrorCode.SEARCH_EXECUTION_INTERRUPTED);
		}
		catch (SearchUnavailableException exception) {
			throw failure(McpToolErrorCode.SEARCH_PROVIDER_UNAVAILABLE, exception.retryable(),
					exception.retryAfter());
		}
		catch (AccessRefreshTooSoonException exception) {
			throw failure(McpToolErrorCode.ACCESS_REFRESH_RATE_LIMITED, true, exception.retryAfter());
		}
		catch (AccessUnavailableException exception) {
			throw failure(McpToolErrorCode.ACCESS_PROVIDERS_UNAVAILABLE, exception.retryable(),
					exception.retryAfter());
		}
		catch (McpToolResultTooLargeException exception) {
			throw failure(McpToolErrorCode.MCP_RESPONSE_TOO_LARGE);
		}
		catch (IllegalArgumentException exception) {
			throw failure(McpToolErrorCode.INVALID_REQUEST);
		}
		catch (RuntimeException exception) {
			LOGGER.error("Unexpected MCP tool failure: tool={}, exceptionType={}", toolName,
					exception.getClass().getName());
			throw failure(McpToolErrorCode.MCP_TOOL_FAILED);
		}
	}

	private static McpToolExecutionException failure(McpToolErrorCode code) {
		return new McpToolExecutionException(McpToolError.nonRetryable(code));
	}

	private static McpToolExecutionException retryableFailure(McpToolErrorCode code) {
		return new McpToolExecutionException(McpToolError.retryable(code));
	}

	private static McpToolExecutionException failure(McpToolErrorCode code, boolean retryable,
			java.time.Duration retryAfter) {
		return new McpToolExecutionException(McpToolError.from(code, retryable, retryAfter));
	}

	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record SearchResearchToolResult(UUID searchId, String query, String queryFingerprint,
			CacheDisposition cacheDisposition, SearchMode requestedMode, SearchExecutionSource executionSource,
			Instant searchedAt, Instant freshUntil,
			@JsonProperty(required = false) String nextCursor,
			List<SearchProviderCoverage> providerCoverage, List<String> warnings, List<SearchPaperToolResult> results) {

		private static SearchResearchToolResult from(SearchView view) {
			return new SearchResearchToolResult(view.searchId(), view.query(), view.queryFingerprint(),
					view.cacheDisposition(), view.requestedMode(), view.executionSource(),
					view.searchedAt(), view.freshUntil(), view.nextCursor(),
					view.providerCoverage().stream().map(SearchProviderCoverage::from).toList(), view.warnings(),
					view.results().stream().map(SearchPaperToolResult::from).toList());
		}

		public SearchResearchToolResult {
			providerCoverage = providerCoverage == null ? List.of() : List.copyOf(providerCoverage);
			warnings = warnings == null ? List.of() : List.copyOf(warnings);
			results = results == null ? List.of() : List.copyOf(results);
		}
	}

	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record SearchProviderCoverage(ProviderId provider, String status, int returnedCount, long totalMatches) {

		private static SearchProviderCoverage from(ProviderCoverageView coverage) {
			return new SearchProviderCoverage(coverage.provider(), coverage.status(), coverage.returnedCount(),
					coverage.totalMatches());
		}
	}

	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record SearchPaperToolResult(int rank, UUID paperId, String title,
			@JsonProperty(required = false) String abstractText,
			@JsonProperty(required = false) LocalDate publicationDate,
			@JsonProperty(required = false) Integer publicationYear, DocumentType documentType,
			@JsonProperty(required = false) String language,
			@JsonProperty(required = false) String venueName,
			@JsonProperty(required = false) String publisher,
			@JsonProperty(required = false) String institution,
			@JsonProperty(required = false) String volume,
			@JsonProperty(required = false) String issue,
			@JsonProperty(required = false) String pages,
			@JsonProperty(required = false) String articleNumber,
			@JsonProperty(required = false) String edition,
			@JsonProperty(required = false) List<String> isbn,
			@JsonProperty(required = false) List<String> issn,
			@JsonProperty(required = false) String degree,
			@JsonProperty(required = false) Integer citationCount,
			@JsonProperty(required = false) Instant citationCountAsOf, List<PaperIdentifier> identifiers,
			List<PaperAuthorToolResult> authors, boolean providerReportedOpenAccess,
			@JsonProperty(required = false) URI providerLandingPageUrl,
			@JsonProperty(required = false) URI providerReportedPdfUrl,
			@JsonProperty(required = false) Double score, List<RankingReasonToolResult> rankingReasons, ProviderId provider,
			String providerRecordId, Instant retrievedAt, List<SearchProvenanceToolResult> provenance) {

		private static SearchPaperToolResult from(SearchResultView result) {
			PaperView paper = result.paper();
			return new SearchPaperToolResult(result.rank(), paper.id(), paper.title(), paper.abstractText(),
					paper.publicationDate(), paper.publicationYear(), paper.documentType(), paper.language(),
					paper.venueName(), paper.publisher(), paper.institution(), paper.volume(), paper.issue(),
					paper.pages(), paper.articleNumber(), paper.edition(), nonEmptyOrNull(paper.isbn()),
					nonEmptyOrNull(paper.issn()), paper.degree(), paper.citationCount(), paper.citationCountAsOf(),
					paper.identifiers(),
					paper.authors().stream().map(PaperAuthorToolResult::from).toList(), result.reportedOpenAccess(),
					result.landingPageUrl(), result.pdfUrl(), result.score(),
					result.rankingReasons().stream().map(RankingReasonToolResult::from).toList(), result.provider(),
					result.providerRecordId(), result.retrievedAt(),
					result.providerContributions().stream().map(SearchProvenanceToolResult::from).toList());
		}

		public SearchPaperToolResult {
			identifiers = identifiers == null ? List.of() : List.copyOf(identifiers);
			authors = authors == null ? List.of() : List.copyOf(authors);
			isbn = isbn == null ? null : List.copyOf(isbn);
			issn = issn == null ? null : List.copyOf(issn);
			rankingReasons = rankingReasons == null ? List.of() : List.copyOf(rankingReasons);
			provenance = provenance == null ? List.of() : List.copyOf(provenance);
		}
	}

	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record SearchProvenanceToolResult(ProviderId provider, String providerRecordId, Instant retrievedAt) {

		private static SearchProvenanceToolResult from(ProviderContributionView contribution) {
			return new SearchProvenanceToolResult(contribution.provider(), contribution.providerRecordId(),
					contribution.retrievedAt());
		}
	}

	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record PaperAuthorToolResult(UUID id, String displayName,
			@JsonProperty(required = false) String orcid,
			@JsonProperty(required = false) String openAlexId, int position, boolean corresponding) {

		private static PaperAuthorToolResult from(PaperAuthorView author) {
			return new PaperAuthorToolResult(author.id(), author.displayName(), author.orcid(), author.openAlexId(),
					author.position(), author.corresponding());
		}
	}

	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record RankingReasonToolResult(String feature, @JsonProperty(required = false) Double value) {

		private static RankingReasonToolResult from(RankingReason reason) {
			return new RankingReasonToolResult(reason.feature(), reason.value());
		}
	}

	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record PaperIdentifierResolutionToolResult(UUID paperId, ResolvablePaperIdentifierType identifierType,
			String normalizedValue) {

		private static PaperIdentifierResolutionToolResult from(PaperIdentifierResolutionView resolution) {
			return new PaperIdentifierResolutionToolResult(resolution.paperId(), resolution.identifierType(),
					resolution.normalizedValue());
		}
	}

	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record PaperDetailsToolResult(PaperToolResult paper, BigDecimal metadataCompleteness,
			Instant metadataUpdatedAt, List<PaperProvenanceToolResult> provenance,
			@JsonProperty(required = false) UUID authorshipProviderRecordId,
			AccessToolResult storedAccess) {

		private static PaperDetailsToolResult from(PaperDetailsView details, PaperAccessView access) {
			return new PaperDetailsToolResult(PaperToolResult.from(details.paper()), details.metadataCompleteness(),
					details.metadataUpdatedAt(),
					details.provenance().stream().map(PaperProvenanceToolResult::from).toList(),
					details.authorshipProviderRecordId(), AccessToolResult.from(access));
		}

		public PaperDetailsToolResult {
			provenance = provenance == null ? List.of() : List.copyOf(provenance);
		}
	}

	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record PaperToolResult(UUID paperId, String title,
			@JsonProperty(required = false) String abstractText,
			@JsonProperty(required = false) LocalDate publicationDate,
			@JsonProperty(required = false) Integer publicationYear, DocumentType documentType,
			@JsonProperty(required = false) String language,
			@JsonProperty(required = false) String venueName,
			@JsonProperty(required = false) String publisher,
			@JsonProperty(required = false) String institution,
			@JsonProperty(required = false) String volume,
			@JsonProperty(required = false) String issue,
			@JsonProperty(required = false) String pages,
			@JsonProperty(required = false) String articleNumber,
			@JsonProperty(required = false) String edition,
			@JsonProperty(required = false) List<String> isbn,
			@JsonProperty(required = false) List<String> issn,
			@JsonProperty(required = false) String degree,
			@JsonProperty(required = false) Integer citationCount,
			@JsonProperty(required = false) Instant citationCountAsOf, List<PaperIdentifier> identifiers,
			List<PaperAuthorToolResult> authors) {

		private static PaperToolResult from(PaperView paper) {
			return new PaperToolResult(paper.id(), paper.title(), paper.abstractText(), paper.publicationDate(),
					paper.publicationYear(), paper.documentType(), paper.language(), paper.venueName(),
					paper.publisher(), paper.institution(), paper.volume(), paper.issue(), paper.pages(),
					paper.articleNumber(), paper.edition(), nonEmptyOrNull(paper.isbn()),
					nonEmptyOrNull(paper.issn()), paper.degree(), paper.citationCount(), paper.citationCountAsOf(),
					paper.identifiers(),
					paper.authors().stream().map(PaperAuthorToolResult::from).toList());
		}

		public PaperToolResult {
			identifiers = identifiers == null ? List.of() : List.copyOf(identifiers);
			authors = authors == null ? List.of() : List.copyOf(authors);
			isbn = isbn == null ? null : List.copyOf(isbn);
			issn = issn == null ? null : List.copyOf(issn);
		}
	}

	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record PaperProvenanceToolResult(UUID id, String provider, String providerRecordId,
			@JsonProperty(required = false) URI sourceUrl,
			@JsonProperty(required = false) Instant providerUpdatedAt, Instant retrievedAt,
			boolean providerReportedOpenAccess) {

		private static PaperProvenanceToolResult from(PaperProviderRecordView record) {
			return new PaperProvenanceToolResult(record.id(), record.provider(), record.providerRecordId(),
					record.sourceUrl(), record.providerUpdatedAt(), record.retrievedAt(), record.reportedOpenAccess());
		}
	}

	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record AccessToolResult(UUID paperId, AccessStatus status, AccessDisposition disposition,
			@JsonProperty(required = false) Instant checkedAt,
			@JsonProperty(required = false) Instant freshUntil,
			List<AccessProviderCoverageToolResult> providerCoverage, List<String> warnings,
			List<AccessLocationToolResult> locations) {

		private static AccessToolResult from(PaperAccessView access) {
			return new AccessToolResult(access.paperId(), access.status(), access.disposition(), access.checkedAt(),
					access.freshUntil(),
					access.providerCoverage().stream().map(AccessProviderCoverageToolResult::from).toList(),
					access.warnings(), access.locations().stream().map(AccessLocationToolResult::from).toList());
		}

		public AccessToolResult {
			providerCoverage = providerCoverage == null ? List.of() : List.copyOf(providerCoverage);
			warnings = warnings == null ? List.of() : List.copyOf(warnings);
			locations = locations == null ? List.of() : List.copyOf(locations);
		}
	}

	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record AccessProviderCoverageToolResult(String provider, String status, int candidateCount) {

		private static AccessProviderCoverageToolResult from(AccessProviderCoverageView coverage) {
			return new AccessProviderCoverageToolResult(coverage.provider(), coverage.status(), coverage.candidateCount());
		}
	}

	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record AccessLocationToolResult(UUID id, String source, boolean best, AccessStatus accessStatus,
			@JsonProperty(required = false) AccessVersionType versionType,
			@JsonProperty(required = false) AccessHostType hostType,
			@JsonProperty(required = false) URI landingPageUrl,
			@JsonProperty(required = false) URI pdfUrl,
			@JsonProperty(required = false) String hostDomain,
			@JsonProperty(required = false) String license,
			@JsonProperty(required = false) String evidence, ContentHandlingMode contentHandling,
			AccessVerificationStatus verificationStatus,
			@JsonProperty(required = false) Integer verificationHttpStatus,
			@JsonProperty(required = false) String verificationContentType,
			@JsonProperty(required = false) String verificationFailureCode,
			@JsonProperty(required = false) Instant providerUpdatedAt, Instant retrievedAt,
			Instant lastSeenAt, @JsonProperty(required = false) Instant verifiedAt) {

		private static AccessLocationToolResult from(AccessLocationView location) {
			return new AccessLocationToolResult(location.id(), location.source(), location.best(), location.accessStatus(),
					location.versionType(), location.hostType(), location.landingPageUrl(), location.pdfUrl(),
					location.hostDomain(), location.license(), location.evidence(), location.contentHandling(),
					location.verificationStatus(), location.verificationHttpStatus(),
					location.verificationContentType(), location.verificationFailureCode(),
					location.providerUpdatedAt(), location.retrievedAt(), location.lastSeenAt(), location.verifiedAt());
		}
	}

	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record SavedLibraryToolResult(List<SavedPaperToolItem> items, int page, int size, long totalElements,
			int totalPages) {

		public SavedLibraryToolResult {
			items = items == null ? List.of() : List.copyOf(items);
		}
	}

	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record SavedPaperToolItem(UUID collectionId, String collectionName, UUID paperId, String title,
			List<String> authors, @JsonProperty(required = false) Integer publicationYear,
			@JsonProperty(required = false) DocumentType documentType, ReadingStatus readingStatus,
			List<String> tags, Instant savedAt, Instant updatedAt) {

		private static SavedPaperToolItem from(SavedPaperView paper) {
			return new SavedPaperToolItem(paper.collectionId(), paper.collectionName(), paper.paperId(), paper.title(),
					paper.authors(), paper.publicationYear(), paper.documentType(), paper.readingStatus(), paper.tags(),
					paper.savedAt(), paper.updatedAt());
		}

		public SavedPaperToolItem {
			authors = authors == null ? List.of() : List.copyOf(authors);
			tags = tags == null ? List.of() : List.copyOf(tags);
		}
	}

	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record CitationExportToolResult(String format, String filename, String mediaType, int paperCount,
			String content) {
	}
}
