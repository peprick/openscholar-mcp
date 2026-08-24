package com.openscholar.search.internal;

import java.net.URI;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import com.openscholar.paper.PaperCatalog;
import com.openscholar.paper.PaperView;
import com.openscholar.provider.ProviderId;
import com.openscholar.search.ProviderContributionView;
import com.openscholar.search.RankingReason;
import com.openscholar.search.SearchCommand;
import com.openscholar.search.SearchResultView;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
class LocalCatalogSearch {

	private static final String BASE_QUERY = """
			WITH eligible_paper AS (
			    SELECT result.paper_id
			    FROM search_result result
			    JOIN search_snapshot snapshot ON snapshot.id = result.search_id
			    WHERE snapshot.owner_id = :ownerId
			    UNION
			    SELECT saved.paper_id
			    FROM collection_paper saved
			    JOIN library_collection collection ON collection.id = saved.collection_id
			    WHERE collection.owner_id = :ownerId
			), scored AS (
			    SELECT paper.id AS paper_id,
			           paper.citation_count,
			           paper.normalized_title = :normalizedQuery AS title_exact,
			           starts_with(paper.normalized_title, :normalizedQuery) AS title_prefix,
			           position(:normalizedQuery IN paper.normalized_title) > 0 AS title_contains,
			           ts_rank_cd(
			               paper.search_vector,
			               websearch_to_tsquery('english'::regconfig, :query),
			               32
			           )::double precision AS text_score,
			           EXISTS (
			               SELECT 1
			               FROM paper_author credited_author
			               WHERE credited_author.paper_id = paper.id
			                 AND position(:normalizedQuery IN lower(credited_author.credited_name)) > 0
			           ) AS author_match,
			           EXISTS (
			               SELECT 1
			               FROM provider_record open_record
			               WHERE open_record.paper_id = paper.id
			                 AND open_record.reported_open_access
			           ) AS reported_open_access
			    FROM eligible_paper eligible
			    JOIN paper ON paper.id = eligible.paper_id
			    WHERE (
			        paper.search_vector @@ websearch_to_tsquery('english'::regconfig, :query)
			        OR position(:normalizedQuery IN paper.normalized_title) > 0
			        OR EXISTS (
			            SELECT 1
			            FROM paper_author credited_author
			            WHERE credited_author.paper_id = paper.id
			              AND position(:normalizedQuery IN lower(credited_author.credited_name)) > 0
			        )
			    )
			    %s
			), ranked AS (
			    SELECT scored.*,
			           (
			               CASE WHEN title_exact THEN 8.0 ELSE 0.0 END
			               + CASE WHEN title_prefix AND NOT title_exact THEN 5.0 ELSE 0.0 END
			               + CASE WHEN title_contains AND NOT title_prefix THEN 2.5 ELSE 0.0 END
			               + (text_score * 3.0)
			               + CASE WHEN author_match THEN 1.5 ELSE 0.0 END
			               + (ln(1.0 + coalesce(citation_count, 0)) * 0.01)
			           )::double precision AS total_score,
			           count(*) OVER () AS total_matches
			    FROM scored
			)
			SELECT ranked.*,
			       provenance.provider,
			       provenance.provider_record_id,
			       provenance.retrieved_at,
			       provenance.landing_page_url,
			       provenance.pdf_url
			FROM ranked
			JOIN LATERAL (
			    SELECT record.provider,
			           record.provider_record_id,
			           record.retrieved_at,
			           record.landing_page_url,
			           record.pdf_url
			    FROM provider_record record
			    WHERE record.paper_id = ranked.paper_id
			    ORDER BY record.retrieved_at DESC, record.provider, record.provider_record_id
			    LIMIT 1
			) provenance ON TRUE
			ORDER BY ranked.total_score DESC,
			         ranked.citation_count DESC NULLS LAST,
			         ranked.paper_id
			LIMIT :limit
			""";

	private static final String HYDRATE_QUERY = """
			WITH eligible_paper AS (
			    SELECT result.paper_id
			    FROM search_result result
			    JOIN search_snapshot snapshot ON snapshot.id = result.search_id
			    WHERE snapshot.owner_id = :ownerId
			    UNION
			    SELECT saved.paper_id
			    FROM collection_paper saved
			    JOIN library_collection collection ON collection.id = saved.collection_id
			    WHERE collection.owner_id = :ownerId
			), scored AS (
			    SELECT paper.id AS paper_id,
			           paper.citation_count,
			           paper.normalized_title = :normalizedQuery AS title_exact,
			           starts_with(paper.normalized_title, :normalizedQuery) AS title_prefix,
			           position(:normalizedQuery IN paper.normalized_title) > 0 AS title_contains,
			           ts_rank_cd(
			               paper.search_vector,
			               websearch_to_tsquery('english'::regconfig, :query),
			               32
			           )::double precision AS text_score,
			           EXISTS (
			               SELECT 1
			               FROM paper_author credited_author
			               WHERE credited_author.paper_id = paper.id
			                 AND position(:normalizedQuery IN lower(credited_author.credited_name)) > 0
			           ) AS author_match,
			           EXISTS (
			               SELECT 1
			               FROM provider_record open_record
			               WHERE open_record.paper_id = paper.id
			                 AND open_record.reported_open_access
			           ) AS reported_open_access
			    FROM eligible_paper eligible
			    JOIN paper ON paper.id = eligible.paper_id
			    WHERE paper.id IN (:paperIds)
			), ranked AS (
			    SELECT scored.*,
			           (
			               CASE WHEN title_exact THEN 8.0 ELSE 0.0 END
			               + CASE WHEN title_prefix AND NOT title_exact THEN 5.0 ELSE 0.0 END
			               + CASE WHEN title_contains AND NOT title_prefix THEN 2.5 ELSE 0.0 END
			               + (text_score * 3.0)
			               + CASE WHEN author_match THEN 1.5 ELSE 0.0 END
			               + (ln(1.0 + coalesce(citation_count, 0)) * 0.01)
			           )::double precision AS total_score,
			           count(*) OVER () AS total_matches
			    FROM scored
			)
			SELECT ranked.*,
			       provenance.provider,
			       provenance.provider_record_id,
			       provenance.retrieved_at,
			       provenance.landing_page_url,
			       provenance.pdf_url
			FROM ranked
			JOIN LATERAL (
			    SELECT record.provider,
			           record.provider_record_id,
			           record.retrieved_at,
			           record.landing_page_url,
			           record.pdf_url
			    FROM provider_record record
			    WHERE record.paper_id = ranked.paper_id
			    ORDER BY record.retrieved_at DESC, record.provider, record.provider_record_id
			    LIMIT 1
			) provenance ON TRUE
			""";

	private final JdbcClient jdbcClient;
	private final PaperCatalog paperCatalog;
	private final LocalCatalogCursorCodec cursorCodec;

	LocalCatalogSearch(
			JdbcClient jdbcClient,
			PaperCatalog paperCatalog,
			LocalCatalogCursorCodec cursorCodec) {
		this.jdbcClient = jdbcClient;
		this.paperCatalog = paperCatalog;
		this.cursorCodec = cursorCodec;
	}

	@Transactional(readOnly = true)
	LocalCatalogPage search(
			UUID ownerId,
			SearchCommand command,
			String normalizedQuery,
			String queryScopeFingerprint) {
		Objects.requireNonNull(ownerId, "ownerId");
		var cursor = cursorCodec.decode(command.cursor(), queryScopeFingerprint);
		return cursor.initial()
				? firstPage(ownerId, command, normalizedQuery, queryScopeFingerprint)
				: continuationPage(
						ownerId,
						command,
						normalizedQuery,
						queryScopeFingerprint,
						cursor.remainingPaperIds());
	}

	private LocalCatalogPage firstPage(
			UUID ownerId,
			SearchCommand command,
			String normalizedQuery,
			String queryScopeFingerprint) {
		StringBuilder filters = new StringBuilder();
		if (command.yearFrom() != null) {
			filters.append(" AND paper.publication_year >= :yearFrom\n");
		}
		if (command.yearTo() != null) {
			filters.append(" AND paper.publication_year <= :yearTo\n");
		}
		if (!command.documentTypes().isEmpty()) {
			filters.append(" AND paper.document_type IN (:documentTypes)\n");
		}
		if (!command.languages().isEmpty()) {
			filters.append(" AND lower(paper.language) IN (:languages)\n");
		}
		if (command.minimumCitations() > 0) {
			filters.append(" AND coalesce(paper.citation_count, 0) >= :minimumCitations\n");
		}
		if (command.openAccessOnly()) {
			filters.append("""
					 AND EXISTS (
					     SELECT 1 FROM provider_record open_filter
					     WHERE open_filter.paper_id = paper.id
					       AND open_filter.reported_open_access
					 )
					""");
		}

		JdbcClient.StatementSpec statement = jdbcClient.sql(BASE_QUERY.formatted(filters));
		statement = statement
				.param("ownerId", ownerId)
				.param("query", command.query())
				.param("normalizedQuery", normalizedQuery)
				.param("limit", command.pageSize() + LocalCatalogCursorCodec.MAX_REMAINING_PAPERS);
		if (command.yearFrom() != null) {
			statement = statement.param("yearFrom", command.yearFrom());
		}
		if (command.yearTo() != null) {
			statement = statement.param("yearTo", command.yearTo());
		}
		if (!command.documentTypes().isEmpty()) {
			statement = statement.param(
					"documentTypes", command.documentTypes().stream().map(Enum::name).toList());
		}
		if (!command.languages().isEmpty()) {
			statement = statement.param("languages", command.languages());
		}
		if (command.minimumCitations() > 0) {
			statement = statement.param("minimumCitations", command.minimumCitations());
		}

		List<LocalRow> rows = statement.query(LocalCatalogSearch::mapRow).list();
		int pageEnd = Math.min(command.pageSize(), rows.size());
		List<LocalRow> pageRows = rows.subList(0, pageEnd);
		List<UUID> remainingPaperIds = rows.subList(pageEnd, rows.size()).stream()
				.map(LocalRow::paperId)
				.toList();
		String nextCursor = remainingPaperIds.isEmpty()
				? null
				: cursorCodec.encode(remainingPaperIds, queryScopeFingerprint);
		long totalMatches = rows.isEmpty() ? 0L : rows.getFirst().totalMatches();
		return page(totalMatches, nextCursor, pageRows);
	}

	private LocalCatalogPage continuationPage(
			UUID ownerId,
			SearchCommand command,
			String normalizedQuery,
			String queryScopeFingerprint,
			List<UUID> remainingPaperIds) {
		int pageEnd = Math.min(command.pageSize(), remainingPaperIds.size());
		List<UUID> selectedPaperIds = remainingPaperIds.subList(0, pageEnd);
		List<UUID> nextPaperIds = remainingPaperIds.subList(pageEnd, remainingPaperIds.size());
		List<LocalRow> hydrated = jdbcClient.sql(HYDRATE_QUERY)
				.param("ownerId", ownerId)
				.param("query", command.query())
				.param("normalizedQuery", normalizedQuery)
				.param("paperIds", selectedPaperIds)
				.query(LocalCatalogSearch::mapRow)
				.list();
		Map<UUID, LocalRow> rowsByPaperId = new LinkedHashMap<>();
		hydrated.forEach(row -> rowsByPaperId.put(row.paperId(), row));
		List<LocalRow> pageRows = selectedPaperIds.stream()
				.map(rowsByPaperId::get)
				.filter(Objects::nonNull)
				.toList();
		String nextCursor = nextPaperIds.isEmpty()
				? null
				: cursorCodec.encode(nextPaperIds, queryScopeFingerprint);
		return page(remainingPaperIds.size(), nextCursor, pageRows);
	}

	private LocalCatalogPage page(long totalMatches, String nextCursor, List<LocalRow> pageRows) {
		Map<UUID, PaperView> papers = paperCatalog.findAllByIds(
				pageRows.stream().map(LocalRow::paperId).toList());
		List<SearchResultView> results = new ArrayList<>(pageRows.size());
		int rank = 1;
		for (LocalRow row : pageRows) {
			PaperView paper = papers.get(row.paperId());
			if (paper == null) {
				throw new IllegalStateException("A locally ranked paper disappeared: " + row.paperId());
			}
			var contribution = new ProviderContributionView(
					row.provider(), row.providerRecordId(), row.retrievedAt());
			results.add(new SearchResultView(
					rank++,
					paper,
					row.reportedOpenAccess(),
					httpUri(row.landingPageUrl()),
					httpUri(row.pdfUrl()),
					row.score(),
					reasons(row),
					row.provider(),
					row.providerRecordId(),
					row.retrievedAt(),
					List.of(contribution)));
		}
		return new LocalCatalogPage(totalMatches, nextCursor, results);
	}

	boolean isLocalCursor(String cursor) {
		return cursorCodec.isLocalCursor(cursor);
	}

	private static LocalRow mapRow(ResultSet resultSet, int rowNumber) throws SQLException {
		return new LocalRow(
				resultSet.getObject("paper_id", UUID.class),
				resultSet.getDouble("total_score"),
				resultSet.getBoolean("title_exact"),
				resultSet.getBoolean("title_prefix"),
				resultSet.getBoolean("title_contains"),
				resultSet.getDouble("text_score"),
				resultSet.getBoolean("author_match"),
				resultSet.getBoolean("reported_open_access"),
				ProviderId.valueOf(resultSet.getString("provider").toUpperCase(Locale.ROOT)),
				resultSet.getString("provider_record_id"),
				resultSet.getTimestamp("retrieved_at").toInstant(),
				resultSet.getString("landing_page_url"),
				resultSet.getString("pdf_url"),
				resultSet.getLong("total_matches"));
	}

	private static List<RankingReason> reasons(LocalRow row) {
		Map<String, Double> reasons = new LinkedHashMap<>();
		if (row.titleExact()) {
			reasons.put("TITLE_EXACT", 1.0);
		}
		else if (row.titlePrefix()) {
			reasons.put("TITLE_PREFIX", 1.0);
		}
		else if (row.titleContains()) {
			reasons.put("TITLE_CONTAINS", 1.0);
		}
		if (row.textScore() > 0) {
			reasons.put("POSTGRES_FULL_TEXT", row.textScore());
		}
		if (row.authorMatch()) {
			reasons.put("AUTHOR_MATCH", 1.0);
		}
		return reasons.entrySet().stream()
				.map(entry -> new RankingReason(entry.getKey(), entry.getValue()))
				.toList();
	}

	private static URI httpUri(String value) {
		if (value == null || value.isBlank()) {
			return null;
		}
		try {
			URI uri = URI.create(value);
			return "http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme())
					? uri
					: null;
		}
		catch (IllegalArgumentException ignored) {
			return null;
		}
	}

	record LocalCatalogPage(long totalMatches, String nextCursor, List<SearchResultView> results) {

		LocalCatalogPage {
			results = List.copyOf(results);
		}
	}

	private record LocalRow(
			UUID paperId,
			double score,
			boolean titleExact,
			boolean titlePrefix,
			boolean titleContains,
			double textScore,
			boolean authorMatch,
			boolean reportedOpenAccess,
			ProviderId provider,
			String providerRecordId,
			Instant retrievedAt,
			String landingPageUrl,
			String pdfUrl,
			long totalMatches) {
	}
}
