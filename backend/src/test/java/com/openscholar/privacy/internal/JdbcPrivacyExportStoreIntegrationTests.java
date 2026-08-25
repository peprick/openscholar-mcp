package com.openscholar.privacy.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.openscholar.TestcontainersConfiguration;
import com.openscholar.privacy.PrivacyExport;
import com.openscholar.search.SearchExecutionSource;
import com.openscholar.search.SearchMode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@Transactional
class JdbcPrivacyExportStoreIntegrationTests {

	private static final UUID LOCAL_USER_ID =
			UUID.fromString("00000000-0000-0000-0000-000000000001");

	@Autowired
	private JdbcPrivacyExportStore store;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Test
	void visitsOnlyOwnedRowsInTheStableExportOrder() {
		UUID otherUserId = UUID.randomUUID();
		OffsetDateTime base = OffsetDateTime.of(2026, 8, 25, 12, 0, 0, 0, ZoneOffset.UTC);
		jdbcTemplate.update("""
				INSERT INTO app_user (id, display_name, created_at)
				VALUES (?, 'Other user', ?)
				""", otherUserId, base);

		UUID earlyPaperId = insertPaper("Early saved paper", base);
		UUID latePaperId = insertPaper("Late saved paper", base.plusMinutes(1));
		UUID otherPaperId = insertPaper("Other user paper", base.plusMinutes(2));
		UUID earlyCollectionId = insertCollection(
				LOCAL_USER_ID, "Early collection", base.plusMinutes(3));
		UUID lateCollectionId = insertCollection(
				LOCAL_USER_ID, "Late collection", base.plusMinutes(4));
		UUID otherCollectionId = insertCollection(
				otherUserId, "Other collection", base.plusMinutes(2));

		UUID earlySavedId = insertSavedPaper(
				earlyCollectionId, earlyPaperId, "READING", base.plusMinutes(5));
		insertSavedPaper(lateCollectionId, latePaperId, "COMPLETED", base.plusMinutes(6));
		insertSavedPaper(otherCollectionId, otherPaperId, "UNREAD", base.plusMinutes(4));
		jdbcTemplate.update("""
				INSERT INTO collection_paper_tag (collection_paper_id, tag)
				VALUES (?, 'review'), (?, 'methods')
				""", earlySavedId, earlySavedId);

		insertSearch(LOCAL_USER_ID, "early topic", "AUTO", "PROVIDER", base.plusMinutes(7));
		insertSearch(LOCAL_USER_ID, "late topic", "LOCAL", "LOCAL_CATALOG", base.plusMinutes(8));
		insertSearch(otherUserId, "other topic", "ONLINE", "PROVIDER", base.plusMinutes(6));

		JdbcPrivacyExportStore.UserData user = store.loadUser(LOCAL_USER_ID);
		JdbcPrivacyExportStore.Counts counts = store.preflightCounts(LOCAL_USER_ID);
		List<PrivacyExport.PrivacySearch> searches = new ArrayList<>();
		List<PrivacyExport.PrivacyCollection> collections = new ArrayList<>();
		List<PrivacyExport.PrivacySavedPaper> savedPapers = new ArrayList<>();
		store.forEachSearch(LOCAL_USER_ID, searches::add);
		store.forEachCollection(LOCAL_USER_ID, collections::add);
		store.forEachSavedPaper(LOCAL_USER_ID, savedPapers::add);

		assertThat(user.displayName()).isEqualTo("Local OpenScholar User");
		assertThat(counts).isEqualTo(new JdbcPrivacyExportStore.Counts(2, 2, 2));
		assertThat(counts.total()).isEqualTo(6);
		assertThat(counts.exceedsCombinedLimit()).isFalse();
		assertThat(searches).extracting(PrivacyExport.PrivacySearch::query)
			.containsExactly("early topic", "late topic");
		assertThat(searches.getFirst().requestedMode()).isEqualTo(SearchMode.AUTO);
		assertThat(searches.getFirst().executionSource())
			.isEqualTo(SearchExecutionSource.PROVIDER_FETCH);
		assertThat(searches.getFirst().filters().documentTypes()).containsExactly("THESIS", "ARTICLE");
		assertThat(searches.getFirst().filters().languages()).containsExactly("fr", "en");
		assertThat(searches.getFirst().warnings())
				.containsExactly("CORE_UNAVAILABLE", "OPENALEX_UNAVAILABLE");
		assertThat(searches.getLast().requestedMode()).isEqualTo(SearchMode.LOCAL);
		assertThat(searches.getLast().executionSource())
			.isEqualTo(SearchExecutionSource.LOCAL_CATALOG);
		assertThat(collections).extracting(PrivacyExport.PrivacyCollection::name)
			.containsExactly("Early collection", "Late collection");
		assertThat(savedPapers).extracting(PrivacyExport.PrivacySavedPaper::title)
			.containsExactly("Early saved paper", "Late saved paper");
		assertThat(savedPapers.getFirst().tags()).containsExactly("methods", "review");
	}

	private UUID insertPaper(String title, OffsetDateTime createdAt) {
		UUID id = UUID.randomUUID();
		jdbcTemplate.update("""
				INSERT INTO paper (
				    id, title, normalized_title, document_type, metadata_quality,
				    metadata_updated_at, version, created_at, updated_at
				)
				VALUES (?, ?, lower(?), 'ARTICLE', 0, ?, 0, ?, ?)
				""", id, title, title, createdAt, createdAt, createdAt);
		return id;
	}

	private UUID insertCollection(UUID ownerId, String name, OffsetDateTime createdAt) {
		UUID id = UUID.randomUUID();
		jdbcTemplate.update("""
				INSERT INTO library_collection (
				    id, owner_id, name, description, version, created_at, updated_at
				)
				VALUES (?, ?, ?, null, 0, ?, ?)
				""", id, ownerId, name, createdAt, createdAt);
		return id;
	}

	private UUID insertSavedPaper(
			UUID collectionId, UUID paperId, String status, OffsetDateTime savedAt) {
		UUID id = UUID.randomUUID();
		jdbcTemplate.update("""
				INSERT INTO collection_paper (
				    id, collection_id, paper_id, reading_status, version, saved_at, updated_at
				)
				VALUES (?, ?, ?, ?, 0, ?, ?)
				""", id, collectionId, paperId, status, savedAt, savedAt);
		return id;
	}

	private void insertSearch(
			UUID ownerId, String query, String mode, String origin, OffsetDateTime searchedAt) {
		jdbcTemplate.update("""
				INSERT INTO search_snapshot (
				    id, owner_id, original_query, normalized_query, fingerprint,
				    fingerprint_version, pipeline_version, filters, status,
				    requested_mode, result_origin, searched_at, fresh_until,
				    provider_coverage, warnings, total_provider_matches,
				    result_count, created_at
				)
				VALUES (?, ?, ?, ?, ?, 1, 'privacy-test-v1',
				        '{"yearFrom":2020,"documentTypes":["THESIS","ARTICLE"],"openAccessOnly":true,"minimumCitations":2,"languages":["fr","en"],"pageSize":10,"cursor":"*"}'::jsonb,
				        'COMPLETED', ?, ?, ?, ?, '[]'::jsonb,
				        '["CORE_UNAVAILABLE","OPENALEX_UNAVAILABLE"]'::jsonb, 1, 1, ?)
				""", UUID.randomUUID(), ownerId, query, query,
				UUID.randomUUID().toString().replace("-", "").repeat(2),
				mode, origin, searchedAt, searchedAt.plusHours(1), searchedAt);
	}
}
