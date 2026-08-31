package com.openscholar.privacy.internal;

import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;

import com.openscholar.library.ReadingStatus;
import com.openscholar.privacy.PrivacyExport;
import com.openscholar.search.SearchExecutionSource;
import com.openscholar.search.SearchMode;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

@Component
final class JdbcPrivacyExportStore {

	private static final String LOAD_USER_SQL = """
			SELECT display_name, created_at
			FROM app_user
			WHERE id = :userId
			""";

	private static final String PREFLIGHT_COUNTS_SQL = """
			WITH bounded_searches AS (
			    SELECT 1
			    FROM search_snapshot
			    WHERE owner_id = :userId
			    LIMIT :branchLimit
			), bounded_collections AS (
			    SELECT 1
			    FROM library_collection
			    WHERE owner_id = :userId
			    LIMIT :branchLimit
			), bounded_saved_papers AS (
			    SELECT 1
			    FROM collection_paper saved
			    JOIN library_collection collection ON collection.id = saved.collection_id
			    WHERE collection.owner_id = :userId
			    LIMIT :branchLimit
			)
			SELECT
			    (SELECT count(*) FROM bounded_searches) AS search_count,
			    (SELECT count(*) FROM bounded_collections) AS collection_count,
			    (SELECT count(*) FROM bounded_saved_papers) AS saved_paper_count
			""";

	private static final String SEARCHES_SQL = """
			SELECT id, original_query, requested_mode, result_origin,
			       searched_at, fresh_until, result_count,
			       (filters ->> 'yearFrom')::integer AS year_from,
			       (filters ->> 'yearTo')::integer AS year_to,
			       (filters ->> 'openAccessOnly')::boolean AS open_access_only,
			       coalesce((filters ->> 'pdfAvailableOnly')::boolean, false) AS pdf_available_only,
			       (filters ->> 'minimumCitations')::integer AS minimum_citations,
			       (filters ->> 'pageSize')::integer AS page_size,
			       ARRAY(
			           SELECT item.value
			           FROM jsonb_array_elements_text(filters -> 'documentTypes')
			               WITH ORDINALITY AS item(value, position)
			           ORDER BY item.position
			       ) AS document_types,
			       ARRAY(
			           SELECT item.value
			           FROM jsonb_array_elements_text(filters -> 'languages')
			               WITH ORDINALITY AS item(value, position)
			           ORDER BY item.position
			       ) AS languages,
			       ARRAY(
			           SELECT item.value
			           FROM jsonb_array_elements_text(warnings)
			               WITH ORDINALITY AS item(value, position)
			           ORDER BY item.position
			       ) AS warning_codes
			FROM search_snapshot
			WHERE owner_id = :userId
			ORDER BY created_at, id
			""";

	private static final String COLLECTIONS_SQL = """
			SELECT id, name, description, created_at, updated_at
			FROM library_collection
			WHERE owner_id = :userId
			ORDER BY created_at, id
			""";

	private static final String SAVED_PAPERS_SQL = """
			SELECT collection.id AS collection_id, saved.paper_id, paper.title,
			       saved.reading_status, saved.saved_at, saved.updated_at,
			       ARRAY(
			           SELECT tag.tag
			           FROM collection_paper_tag tag
			           WHERE tag.collection_paper_id = saved.id
			           ORDER BY tag.tag
			       ) AS tags
			FROM collection_paper saved
			JOIN library_collection collection ON collection.id = saved.collection_id
			JOIN paper ON paper.id = saved.paper_id
			WHERE collection.owner_id = :userId
			ORDER BY saved.saved_at, saved.id
			""";

	private final JdbcClient jdbcClient;

	JdbcPrivacyExportStore(JdbcClient jdbcClient) {
		this.jdbcClient = Objects.requireNonNull(jdbcClient, "jdbcClient");
	}

	UserData loadUser(UUID userId) {
		Objects.requireNonNull(userId, "userId");
		return jdbcClient.sql(LOAD_USER_SQL)
			.param("userId", userId)
			.withFetchSize(1)
			.withMaxRows(2)
			.withQueryTimeout(PrivacyExportLimits.TIMEOUT_SECONDS)
			.query((resultSet, rowNumber) -> new UserData(
					resultSet.getString("display_name"),
					instant(resultSet, "created_at")))
			.single();
	}

	Counts preflightCounts(UUID userId) {
		Objects.requireNonNull(userId, "userId");
		return jdbcClient.sql(PREFLIGHT_COUNTS_SQL)
			.param("userId", userId)
			.param("branchLimit", PrivacyExportLimits.PER_BRANCH_PREFLIGHT_LIMIT)
			.withFetchSize(1)
			.withMaxRows(1)
			.withQueryTimeout(PrivacyExportLimits.TIMEOUT_SECONDS)
			.query((resultSet, rowNumber) -> new Counts(
					resultSet.getLong("search_count"),
					resultSet.getLong("collection_count"),
					resultSet.getLong("saved_paper_count")))
			.single();
	}

	void forEachSearch(
			UUID userId, Consumer<? super PrivacyExport.PrivacySearch> visitor) {
		visit(SEARCHES_SQL, userId, visitor, JdbcPrivacyExportStore::mapSearch);
	}

	void forEachCollection(
			UUID userId, Consumer<? super PrivacyExport.PrivacyCollection> visitor) {
		visit(COLLECTIONS_SQL, userId, visitor, JdbcPrivacyExportStore::mapCollection);
	}

	void forEachSavedPaper(
			UUID userId, Consumer<? super PrivacyExport.PrivacySavedPaper> visitor) {
		visit(SAVED_PAPERS_SQL, userId, visitor, JdbcPrivacyExportStore::mapSavedPaper);
	}

	private <T> void visit(
			String sql, UUID userId, Consumer<? super T> visitor, SqlRowMapper<T> mapper) {
		Objects.requireNonNull(userId, "userId");
		Objects.requireNonNull(visitor, "visitor");
		RowCallbackHandler handler = resultSet -> visitor.accept(mapper.map(resultSet));
		jdbcClient.sql(sql)
			.param("userId", userId)
			.withFetchSize(PrivacyExportLimits.FETCH_SIZE)
			.withMaxRows(PrivacyExportLimits.PER_BRANCH_PREFLIGHT_LIMIT)
			.withQueryTimeout(PrivacyExportLimits.TIMEOUT_SECONDS)
			.query(handler);
	}

	private static PrivacyExport.PrivacySearch mapSearch(ResultSet resultSet) throws SQLException {
		return new PrivacyExport.PrivacySearch(
				resultSet.getObject("id", UUID.class),
				resultSet.getString("original_query"),
				SearchMode.valueOf(resultSet.getString("requested_mode")),
				"LOCAL_CATALOG".equals(resultSet.getString("result_origin"))
						? SearchExecutionSource.LOCAL_CATALOG
						: SearchExecutionSource.PROVIDER_FETCH,
				new PrivacyExport.PrivacySearchFilters(
						nullableInteger(resultSet, "year_from"),
						nullableInteger(resultSet, "year_to"),
						strings(resultSet.getArray("document_types")),
						resultSet.getBoolean("open_access_only"),
						resultSet.getBoolean("pdf_available_only"),
						resultSet.getInt("minimum_citations"),
						strings(resultSet.getArray("languages")),
						resultSet.getInt("page_size")),
				instant(resultSet, "searched_at"),
				instant(resultSet, "fresh_until"),
				resultSet.getInt("result_count"),
				strings(resultSet.getArray("warning_codes")));
	}

	private static PrivacyExport.PrivacyCollection mapCollection(ResultSet resultSet) throws SQLException {
		return new PrivacyExport.PrivacyCollection(
				resultSet.getObject("id", UUID.class),
				resultSet.getString("name"),
				resultSet.getString("description"),
				instant(resultSet, "created_at"),
				instant(resultSet, "updated_at"));
	}

	private static PrivacyExport.PrivacySavedPaper mapSavedPaper(ResultSet resultSet) throws SQLException {
		return new PrivacyExport.PrivacySavedPaper(
				resultSet.getObject("collection_id", UUID.class),
				resultSet.getObject("paper_id", UUID.class),
				resultSet.getString("title"),
				ReadingStatus.valueOf(resultSet.getString("reading_status")),
				strings(resultSet.getArray("tags")),
				instant(resultSet, "saved_at"),
				instant(resultSet, "updated_at"));
	}

	private static List<String> strings(Array array) throws SQLException {
		if (array == null) {
			return List.of();
		}
		try {
			Object value = array.getArray();
			return value instanceof String[] strings
					? List.copyOf(Arrays.asList(strings))
					: List.of();
		}
		finally {
			array.free();
		}
	}

	private static Instant instant(ResultSet resultSet, String column) throws SQLException {
		var value = resultSet.getTimestamp(column);
		return value == null ? null : value.toInstant();
	}

	private static Integer nullableInteger(ResultSet resultSet, String column) throws SQLException {
		int value = resultSet.getInt(column);
		return resultSet.wasNull() ? null : value;
	}

	@FunctionalInterface
	private interface SqlRowMapper<T> {

		T map(ResultSet resultSet) throws SQLException;
	}

	record UserData(String displayName, Instant createdAt) {
	}

	record Counts(long searches, long collections, long savedPapers) {

		Counts {
			if (searches < 0 || searches > PrivacyExportLimits.PER_BRANCH_PREFLIGHT_LIMIT
					|| collections < 0 || collections > PrivacyExportLimits.PER_BRANCH_PREFLIGHT_LIMIT
					|| savedPapers < 0 || savedPapers > PrivacyExportLimits.PER_BRANCH_PREFLIGHT_LIMIT) {
				throw new IllegalArgumentException("Privacy export preflight counts are outside their bounds");
			}
		}

		long total() {
			return Math.addExact(Math.addExact(searches, collections), savedPapers);
		}

		boolean exceedsCombinedLimit() {
			return total() > PrivacyExportLimits.MAX_COMBINED_RECORDS;
		}
	}
}
