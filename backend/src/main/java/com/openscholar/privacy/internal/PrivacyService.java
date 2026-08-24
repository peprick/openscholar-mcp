package com.openscholar.privacy.internal;

import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import com.openscholar.library.ReadingStatus;
import com.openscholar.privacy.PrivacyExport;
import com.openscholar.privacy.PrivacyUseCase;
import com.openscholar.security.CurrentUserIdProvider;
import com.openscholar.security.OidcSecurityProperties;
import com.openscholar.search.SearchExecutionSource;
import com.openscholar.search.SearchMode;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class PrivacyService implements PrivacyUseCase {

	private final JdbcClient jdbcClient;
	private final CurrentUserIdProvider currentUser;
	private final OidcSecurityProperties securityProperties;
	private final Clock clock;

	PrivacyService(
			JdbcClient jdbcClient,
			CurrentUserIdProvider currentUser,
			OidcSecurityProperties securityProperties,
			Clock clock) {
		this.jdbcClient = jdbcClient;
		this.currentUser = currentUser;
		this.securityProperties = securityProperties;
		this.clock = clock;
	}

	@Override
	@Transactional(readOnly = true)
	public PrivacyExport exportPersonalData() {
		UUID userId = currentUser.currentUserId();
		UserData user = jdbcClient.sql("""
				SELECT display_name, created_at
				FROM app_user
				WHERE id = :userId
				""")
				.param("userId", userId)
				.query((resultSet, rowNumber) -> new UserData(
						resultSet.getString("display_name"), instant(resultSet, "created_at")))
				.single();
		List<PrivacyExport.PrivacyCollection> collections = jdbcClient.sql("""
				SELECT id, name, description, created_at, updated_at
				FROM library_collection
				WHERE owner_id = :userId
				ORDER BY created_at, id
				""")
				.param("userId", userId)
				.query((resultSet, rowNumber) -> new PrivacyExport.PrivacyCollection(
						resultSet.getObject("id", UUID.class),
						resultSet.getString("name"),
						resultSet.getString("description"),
						instant(resultSet, "created_at"),
						instant(resultSet, "updated_at")))
				.list();
		List<PrivacyExport.PrivacySearch> searches = jdbcClient.sql("""
				SELECT id, original_query, requested_mode, result_origin,
				       searched_at, fresh_until, result_count,
				       (filters ->> 'yearFrom')::integer AS year_from,
				       (filters ->> 'yearTo')::integer AS year_to,
				       (filters ->> 'openAccessOnly')::boolean AS open_access_only,
				       (filters ->> 'minimumCitations')::integer AS minimum_citations,
				       (filters ->> 'pageSize')::integer AS page_size,
				       ARRAY(SELECT jsonb_array_elements_text(filters -> 'documentTypes')) AS document_types,
				       ARRAY(SELECT jsonb_array_elements_text(filters -> 'languages')) AS languages,
				       ARRAY(SELECT jsonb_array_elements_text(warnings)) AS warning_codes
				FROM search_snapshot
				WHERE owner_id = :userId
				ORDER BY created_at, id
				""")
				.param("userId", userId)
				.query(PrivacyService::mapSearch)
				.list();
		List<PrivacyExport.PrivacySavedPaper> savedPapers = jdbcClient.sql("""
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
				""")
				.param("userId", userId)
				.query(PrivacyService::mapSavedPaper)
				.list();
		return new PrivacyExport(
				userId,
				user.displayName(),
				user.createdAt(),
				clock.instant(),
				searches,
				collections,
				savedPapers);
	}

	@Override
	@Transactional
	public void deletePersonalData() {
		UUID userId = currentUser.currentUserId();
		jdbcClient.sql("""
				DELETE FROM research_refresh_job
				WHERE job_type = 'SEARCH_METADATA'
				  AND target_id IN (
				      SELECT id FROM search_snapshot WHERE owner_id = :userId
				  )
				""")
				.param("userId", userId)
				.update();
		jdbcClient.sql("DELETE FROM search_snapshot WHERE owner_id = :userId")
				.param("userId", userId)
				.update();
		jdbcClient.sql("DELETE FROM library_collection WHERE owner_id = :userId")
				.param("userId", userId)
				.update();
		if (securityProperties.enabled()) {
			jdbcClient.sql("DELETE FROM app_user WHERE id = :userId")
					.param("userId", userId)
					.update();
		}
	}

	private static PrivacyExport.PrivacySearch mapSearch(ResultSet resultSet, int rowNumber) throws SQLException {
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
						tags(resultSet.getArray("document_types")),
						resultSet.getBoolean("open_access_only"),
						resultSet.getInt("minimum_citations"),
						tags(resultSet.getArray("languages")),
						resultSet.getInt("page_size")),
				instant(resultSet, "searched_at"),
				instant(resultSet, "fresh_until"),
				resultSet.getInt("result_count"),
				tags(resultSet.getArray("warning_codes")));
	}

	private static PrivacyExport.PrivacySavedPaper mapSavedPaper(ResultSet resultSet, int rowNumber)
			throws SQLException {
		return new PrivacyExport.PrivacySavedPaper(
				resultSet.getObject("collection_id", UUID.class),
				resultSet.getObject("paper_id", UUID.class),
				resultSet.getString("title"),
				ReadingStatus.valueOf(resultSet.getString("reading_status")),
				tags(resultSet.getArray("tags")),
				instant(resultSet, "saved_at"),
				instant(resultSet, "updated_at"));
	}

	private static List<String> tags(Array array) throws SQLException {
		if (array == null) {
			return List.of();
		}
		try {
			Object value = array.getArray();
			return value instanceof String[] strings ? List.copyOf(Arrays.asList(strings)) : List.of();
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

	private record UserData(String displayName, Instant createdAt) {
	}
}
