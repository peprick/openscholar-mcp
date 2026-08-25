package com.openscholar.mcp.internal;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;

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
import com.openscholar.search.ProviderContributionView;
import com.openscholar.search.ProviderCoverageView;
import com.openscholar.search.RankingReason;
import com.openscholar.search.SearchExecutionSource;
import com.openscholar.search.SearchMode;
import com.openscholar.search.SearchNotFoundException;
import com.openscholar.search.SearchResearchUseCase;
import com.openscholar.search.SearchResultView;
import com.openscholar.search.SearchView;
import io.modelcontextprotocol.server.McpStatelessServerFeatures;
import io.modelcontextprotocol.spec.McpError;
import io.modelcontextprotocol.spec.McpSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class OpenScholarMcpResources {

	private static final Logger LOGGER = LoggerFactory.getLogger(OpenScholarMcpResources.class);

	private static final String JSON_MIME_TYPE = "application/json";

	private static final String PAPER_URI_TEMPLATE = "openscholar://papers/{paperId}";

	private static final String COLLECTION_URI_TEMPLATE = "openscholar://collections/{collectionId}";

	private static final String SEARCH_URI_TEMPLATE = "openscholar://searches/{searchId}";

	private static final String PAPER_URI_PREFIX = "openscholar://papers/";

	private static final String COLLECTION_URI_PREFIX = "openscholar://collections/";

	private static final String SEARCH_URI_PREFIX = "openscholar://searches/";

	private static final int COLLECTION_PAGE_SIZE = 25;

	private static final int RESOURCE_SCHEMA_VERSION = 1;

	private final PaperDetailsUseCase paperDetailsUseCase;

	private final LibraryUseCase libraryUseCase;

	private final SearchResearchUseCase searchUseCase;

	private final McpResourceResultBudget resultBudget;

	OpenScholarMcpResources(PaperDetailsUseCase paperDetailsUseCase, LibraryUseCase libraryUseCase,
			SearchResearchUseCase searchUseCase, McpResourceResultBudget resultBudget) {
		this.paperDetailsUseCase = paperDetailsUseCase;
		this.libraryUseCase = libraryUseCase;
		this.searchUseCase = searchUseCase;
		this.resultBudget = resultBudget;
	}

	public List<McpStatelessServerFeatures.SyncResourceTemplateSpecification> resourceTemplateSpecifications() {
		return List.of(
				template(PAPER_URI_TEMPLATE, "openscholar-paper", "OpenScholar paper metadata",
						"Read stored canonical paper metadata and provenance without access URLs or provider calls.",
						this::readPaper),
				template(COLLECTION_URI_TEMPLATE, "openscholar-collection", "OpenScholar collection",
						"Read an owner-scoped collection and a bounded page of saved-paper metadata.",
						this::readCollection),
				template(SEARCH_URI_TEMPLATE, "openscholar-search", "OpenScholar search snapshot",
						"Read an owner-scoped stored search snapshot without document URLs or provider calls.",
						this::readSearch));
	}

	private McpSchema.ReadResourceResult readPaper(McpSchema.ReadResourceRequest request) {
		return read("paper", () -> {
			UUID paperId = parseId(request, PAPER_URI_PREFIX);
			return jsonResult(request.uri(), PaperResource.from(paperDetailsUseCase.get(paperId)));
		});
	}

	private McpSchema.ReadResourceResult readCollection(McpSchema.ReadResourceRequest request) {
		return read("collection", () -> {
			UUID collectionId = parseId(request, COLLECTION_URI_PREFIX);
			CollectionDetailsView collection = libraryUseCase.getCollection(collectionId, 0, COLLECTION_PAGE_SIZE);
			return jsonResult(request.uri(), CollectionResource.from(collection));
		});
	}

	private McpSchema.ReadResourceResult readSearch(McpSchema.ReadResourceRequest request) {
		return read("search", () -> {
			UUID searchId = parseId(request, SEARCH_URI_PREFIX);
			return jsonResult(request.uri(), SearchResource.from(searchUseCase.get(searchId)));
		});
	}

	private McpSchema.ReadResourceResult jsonResult(String uri, Object value) {
		String json = resultBudget.toJson(value);
		return new McpSchema.ReadResourceResult(
				List.of(new McpSchema.TextResourceContents(uri, JSON_MIME_TYPE, json)));
	}

	private static McpStatelessServerFeatures.SyncResourceTemplateSpecification template(
			String uriTemplate, String name, String title, String description,
			Function<McpSchema.ReadResourceRequest, McpSchema.ReadResourceResult> readHandler) {
		McpSchema.ResourceTemplate template = McpSchema.ResourceTemplate.builder()
			.uriTemplate(uriTemplate)
			.name(name)
			.title(title)
			.description(description)
			.mimeType(JSON_MIME_TYPE)
			.build();
		return new McpStatelessServerFeatures.SyncResourceTemplateSpecification(
				template, (context, request) -> readHandler.apply(request));
	}

	private static UUID parseId(McpSchema.ReadResourceRequest request, String requiredPrefix) {
		if (request == null || request.uri() == null) {
			throw invalidResourceUri();
		}
		String uri = request.uri();
		if (uri.length() != requiredPrefix.length() + 36 || !uri.startsWith(requiredPrefix)) {
			throw invalidResourceUri();
		}
		String candidate = uri.substring(requiredPrefix.length());
		try {
			UUID id = UUID.fromString(candidate);
			if (!id.toString().equals(candidate)) {
				throw invalidResourceUri();
			}
			return id;
		}
		catch (IllegalArgumentException exception) {
			throw invalidResourceUri();
		}
	}

	private <T> T read(String resourceKind, Supplier<T> action) {
		try {
			return action.get();
		}
		catch (InvalidResourceUriException exception) {
			throw mcpError(McpSchema.ErrorCodes.INVALID_PARAMS, "Invalid OpenScholar resource URI");
		}
		catch (PaperNotFoundException | CollectionNotFoundException | SearchNotFoundException exception) {
			throw mcpError(McpSchema.ErrorCodes.RESOURCE_NOT_FOUND, "Resource not found");
		}
		catch (McpResourceResultTooLargeException exception) {
			throw mcpError(McpSchema.ErrorCodes.INTERNAL_ERROR, "MCP resource response too large");
		}
		catch (RuntimeException exception) {
			LOGGER.error("Unexpected MCP resource failure: resourceKind={}, exceptionType={}", resourceKind,
					exception.getClass().getName());
			throw mcpError(McpSchema.ErrorCodes.INTERNAL_ERROR, "MCP resource read failed");
		}
	}

	private static McpError mcpError(int code, String message) {
		return McpError.builder(code).message(message).build();
	}

	private static InvalidResourceUriException invalidResourceUri() {
		return new InvalidResourceUriException();
	}

	private record PaperResource(int schemaVersion, PaperMetadata paper, BigDecimal metadataCompleteness,
			Instant metadataUpdatedAt, List<PaperProvenance> provenance, UUID authorshipProviderRecordId) {

		private static PaperResource from(PaperDetailsView view) {
			return new PaperResource(RESOURCE_SCHEMA_VERSION, PaperMetadata.from(view.paper()),
					view.metadataCompleteness(), view.metadataUpdatedAt(),
					view.provenance().stream().map(PaperProvenance::from).toList(), view.authorshipProviderRecordId());
		}
	}

	private record PaperMetadata(UUID paperId, String title, String abstractText, LocalDate publicationDate,
			Integer publicationYear, DocumentType documentType, String language, String venueName,
			Integer citationCount, Instant citationCountAsOf, List<PaperIdentifierResource> identifiers,
			List<PaperAuthorResource> authors, String publisher, String institution, String volume, String issue,
			String pages, String articleNumber, String edition, List<String> isbn, List<String> issn, String degree) {

		private static PaperMetadata from(PaperView paper) {
			return new PaperMetadata(paper.id(), paper.title(), paper.abstractText(), paper.publicationDate(),
					paper.publicationYear(), paper.documentType(), paper.language(), paper.venueName(),
					paper.citationCount(), paper.citationCountAsOf(),
					paper.identifiers().stream().map(PaperIdentifierResource::from).toList(),
					paper.authors().stream().map(PaperAuthorResource::from).toList(),
					paper.publisher(), paper.institution(), paper.volume(), paper.issue(), paper.pages(),
					paper.articleNumber(), paper.edition(), paper.isbn(), paper.issn(), paper.degree());
		}
	}

	private record PaperIdentifierResource(PaperIdentifierType type, String namespace, String value) {

		private static PaperIdentifierResource from(PaperIdentifier identifier) {
			return new PaperIdentifierResource(identifier.type(), identifier.namespace(), identifier.value());
		}
	}

	private record PaperAuthorResource(UUID id, String displayName, String orcid, String openAlexId, int position,
			boolean corresponding) {

		private static PaperAuthorResource from(PaperAuthorView author) {
			return new PaperAuthorResource(author.id(), author.displayName(), author.orcid(), author.openAlexId(),
					author.position(), author.corresponding());
		}
	}

	private record PaperProvenance(UUID providerRecordEntryId, String provider, String providerRecordId,
			Instant providerUpdatedAt, Instant retrievedAt, boolean reportedOpenAccess) {

		private static PaperProvenance from(PaperProviderRecordView provenance) {
			return new PaperProvenance(provenance.id(), provenance.provider(), provenance.providerRecordId(),
					provenance.providerUpdatedAt(), provenance.retrievedAt(), provenance.reportedOpenAccess());
		}
	}

	private record CollectionResource(int schemaVersion, UUID collectionId, String name, String description,
			long paperCount, Instant createdAt, Instant updatedAt, SavedPaperPageResource papers) {

		private static CollectionResource from(CollectionDetailsView view) {
			return new CollectionResource(RESOURCE_SCHEMA_VERSION, view.collectionId(), view.name(), view.description(),
					view.paperCount(), view.createdAt(), view.updatedAt(), SavedPaperPageResource.from(view.papers()));
		}
	}

	private record SavedPaperPageResource(List<SavedPaperResource> items, int page, int size, long totalElements,
			int totalPages) {

		private static SavedPaperPageResource from(LibraryPage<SavedPaperView> page) {
			return new SavedPaperPageResource(page.items().stream().map(SavedPaperResource::from).toList(), page.page(),
					page.size(), page.totalElements(), page.totalPages());
		}
	}

	private record SavedPaperResource(UUID collectionId, String collectionName, UUID paperId, String title,
			List<String> authors, Integer publicationYear, DocumentType documentType,
			ReadingStatus readingStatus, List<String> tags, Instant savedAt, Instant updatedAt) {

		private static SavedPaperResource from(SavedPaperView paper) {
			return new SavedPaperResource(paper.collectionId(), paper.collectionName(), paper.paperId(), paper.title(),
					paper.authors(), paper.publicationYear(), paper.documentType(), paper.readingStatus(), paper.tags(),
					paper.savedAt(), paper.updatedAt());
		}
	}

	private record SearchResource(int schemaVersion, UUID searchId, String query, String queryFingerprint,
			CacheDisposition cacheDisposition, SearchMode requestedMode, SearchExecutionSource executionSource,
			Instant searchedAt, Instant freshUntil, String nextCursor,
			List<ProviderCoverageResource> providerCoverage, List<String> warnings, List<SearchResultResource> results) {

		private static SearchResource from(SearchView view) {
			return new SearchResource(RESOURCE_SCHEMA_VERSION, view.searchId(), view.query(), view.queryFingerprint(),
					view.cacheDisposition(), view.requestedMode(), view.executionSource(), view.searchedAt(),
					view.freshUntil(), view.nextCursor(),
					view.providerCoverage().stream().map(ProviderCoverageResource::from).toList(), view.warnings(),
					view.results().stream().map(SearchResultResource::from).toList());
		}
	}

	private record ProviderCoverageResource(ProviderId provider, String status, int returnedCount, long totalMatches) {

		private static ProviderCoverageResource from(ProviderCoverageView coverage) {
			return new ProviderCoverageResource(coverage.provider(), coverage.status(), coverage.returnedCount(),
					coverage.totalMatches());
		}
	}

	private record SearchResultResource(int rank, PaperMetadata paper, boolean reportedOpenAccess, Double score,
			List<RankingReasonResource> rankingReasons, ProviderId provider, String providerRecordId, Instant retrievedAt,
			List<ProviderContributionResource> providerContributions) {

		private static SearchResultResource from(SearchResultView result) {
			return new SearchResultResource(result.rank(), PaperMetadata.from(result.paper()),
					result.reportedOpenAccess(), result.score(),
					result.rankingReasons().stream().map(RankingReasonResource::from).toList(), result.provider(),
					result.providerRecordId(), result.retrievedAt(),
					result.providerContributions().stream().map(ProviderContributionResource::from).toList());
		}
	}

	private record RankingReasonResource(String feature, Double value) {

		private static RankingReasonResource from(RankingReason reason) {
			return new RankingReasonResource(reason.feature(), reason.value());
		}
	}

	private record ProviderContributionResource(ProviderId provider, String providerRecordId, Instant retrievedAt) {

		private static ProviderContributionResource from(ProviderContributionView contribution) {
			return new ProviderContributionResource(contribution.provider(), contribution.providerRecordId(),
					contribution.retrievedAt());
		}
	}

	private static final class InvalidResourceUriException extends IllegalArgumentException {
	}
}
