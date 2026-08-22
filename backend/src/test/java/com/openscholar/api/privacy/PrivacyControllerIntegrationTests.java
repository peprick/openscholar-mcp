package com.openscholar.api.privacy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import com.openscholar.TestcontainersConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class PrivacyControllerIntegrationTests {

	private static final UUID LOCAL_USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	private UUID paperId;

	@BeforeEach
	void createPersonalFixtures() {
		removePersonalFixtures();
		paperId = UUID.randomUUID();
		UUID collectionId = UUID.randomUUID();
		UUID savedPaperId = UUID.randomUUID();
		UUID searchId = UUID.randomUUID();
		OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
		jdbcTemplate.update("""
				INSERT INTO paper (
				    id, title, normalized_title, document_type, metadata_quality,
				    metadata_updated_at, version, created_at, updated_at
				)
				VALUES (?, 'Private library paper', 'private library paper', 'ARTICLE', 0, ?, 0, ?, ?)
				""", paperId, now, now, now);
		jdbcTemplate.update("""
				INSERT INTO library_collection (id, owner_id, name, description, version, created_at, updated_at)
				VALUES (?, ?, 'My reading', 'Personal notes', 0, ?, ?)
				""", collectionId, LOCAL_USER_ID, now, now);
		jdbcTemplate.update("""
				INSERT INTO collection_paper (
				    id, collection_id, paper_id, reading_status, version, saved_at, updated_at
				)
				VALUES (?, ?, ?, 'READING', 0, ?, ?)
				""", savedPaperId, collectionId, paperId, now, now);
		jdbcTemplate.update(
				"INSERT INTO collection_paper_tag (collection_paper_id, tag) VALUES (?, 'methods'), (?, 'review')",
				savedPaperId, savedPaperId);
		jdbcTemplate.update("""
				INSERT INTO search_snapshot (
				    id, owner_id, original_query, normalized_query, fingerprint, fingerprint_version,
				    pipeline_version, filters, status, searched_at, fresh_until,
				    provider_coverage, warnings, total_provider_matches, result_count, created_at
				)
				VALUES (?, ?, 'private doctoral topic', 'private doctoral topic', ?, 1, 'test-v1',
				        '{"yearFrom":2020,"documentTypes":["THESIS"],"openAccessOnly":true,"minimumCitations":2,"languages":["en"],"pageSize":10,"cursor":"*"}'::jsonb,
				        'COMPLETED', ?, ?, '[]'::jsonb, '["CORE_UNAVAILABLE"]'::jsonb, 7, 1, ?)
				""", searchId, LOCAL_USER_ID, "b".repeat(64), now, now.plusHours(1), now);
		jdbcTemplate.update("""
				INSERT INTO research_refresh_job (
				    id, job_type, target_id, trigger_kind, status, attempt_count,
				    max_attempts, available_at, created_at, updated_at
				)
				VALUES (?, 'SEARCH_METADATA', ?, 'MANUAL', 'QUEUED', 0, 3, ?, ?, ?)
				""", UUID.randomUUID(), searchId, now, now, now);
	}

	@AfterEach
	void cleanup() {
		removePersonalFixtures();
		if (paperId != null) {
			jdbcTemplate.update("DELETE FROM paper WHERE id = ?", paperId);
		}
	}

	@Test
	void exportsOwnedSearchAndLibraryDataWithoutCaching() throws Exception {
		mockMvc.perform(get("/api/v1/privacy/export"))
				.andExpect(status().isOk())
				.andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
				.andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION,
						"attachment; filename=\"openscholar-personal-data.json\""))
				.andExpect(jsonPath("$.userId").value(LOCAL_USER_ID.toString()))
				.andExpect(jsonPath("$.displayName").value("Local OpenScholar User"))
				.andExpect(jsonPath("$.searches.length()").value(1))
				.andExpect(jsonPath("$.searches[0].query").value("private doctoral topic"))
				.andExpect(jsonPath("$.searches[0].filters.yearFrom").value(2020))
				.andExpect(jsonPath("$.searches[0].filters.yearTo").doesNotExist())
				.andExpect(jsonPath("$.searches[0].filters.documentTypes[0]").value("THESIS"))
				.andExpect(jsonPath("$.searches[0].filters.openAccessOnly").value(true))
				.andExpect(jsonPath("$.searches[0].filters.languages[0]").value("en"))
				.andExpect(jsonPath("$.searches[0].warnings[0]").value("CORE_UNAVAILABLE"))
				.andExpect(jsonPath("$.collections[0].name").value("My reading"))
				.andExpect(jsonPath("$.savedPapers[0].title").value("Private library paper"))
				.andExpect(jsonPath("$.savedPapers[0].readingStatus").value("READING"))
				.andExpect(jsonPath("$.savedPapers[0].tags[0]").value("methods"))
				.andExpect(jsonPath("$.savedPapers[0].tags[1]").value("review"));
	}

	@Test
	void requiresExactNonNullConfirmationBeforeDeletingAnything() throws Exception {
		for (String body : new String[] {
			"{}",
			"{\"confirmation\":null}",
			"{\"confirmation\":\"delete_my_data\"}"
		}) {
			mockMvc.perform(delete("/api/v1/privacy/account")
						.contentType(MediaType.APPLICATION_JSON)
						.content(body))
					.andExpect(status().isBadRequest());
		}

		assertThat(count("library_collection")).isOne();
		assertThat(count("search_snapshot")).isOne();
	}

	@Test
	void deletesPersonalRelationshipsAndHistoryButRetainsLocalIdentityAndGlobalPaperMetadata() throws Exception {
		mockMvc.perform(delete("/api/v1/privacy/account")
					.contentType(MediaType.APPLICATION_JSON)
					.content("{\"confirmation\":\"DELETE_MY_DATA\"}"))
				.andExpect(status().isNoContent())
				.andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"));

		assertThat(count("library_collection")).isZero();
		assertThat(count("collection_paper")).isZero();
		assertThat(count("collection_paper_tag")).isZero();
		assertThat(count("search_snapshot")).isZero();
		assertThat(count("research_refresh_job")).isZero();
		assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM app_user WHERE id = ?", Integer.class,
				LOCAL_USER_ID)).isOne();
		assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM paper WHERE id = ?", Integer.class,
				paperId)).isOne();
	}

	private int count(String table) {
		return jdbcTemplate.queryForObject("SELECT count(*) FROM " + table, Integer.class);
	}

	private void removePersonalFixtures() {
		jdbcTemplate.update("DELETE FROM research_refresh_job");
		jdbcTemplate.update("DELETE FROM search_snapshot");
		jdbcTemplate.update("DELETE FROM library_collection");
	}
}
