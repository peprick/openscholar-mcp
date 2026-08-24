package com.openscholar.search.internal.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;

import javax.sql.DataSource;

import com.openscholar.TestcontainersConfiguration;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
class SearchExecutionMetadataMigrationTests {

	@Autowired
	private DataSource dataSource;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Test
	void v16BackfillsProviderExecutionMetadataAndConstrainsFutureSnapshots() {
		String schema = "search_execution_" + UUID.randomUUID().toString().replace("-", "");
		UUID legacySearchId = UUID.randomUUID();
		try {
			migrate(schema, "15");
			insertV15Search(schema, legacySearchId);
			migrate(schema, null);

			assertThat(jdbcTemplate.queryForMap(
					"SELECT requested_mode, result_origin FROM %s.search_snapshot WHERE id = ?"
							.formatted(schema),
					legacySearchId))
					.containsEntry("requested_mode", "AUTO")
					.containsEntry("result_origin", "PROVIDER");

			jdbcTemplate.update("""
					UPDATE %s.search_snapshot
					SET requested_mode = 'LOCAL', result_origin = 'LOCAL_CATALOG'
					WHERE id = ?
					""".formatted(schema), legacySearchId);
			assertThat(jdbcTemplate.queryForMap(
					"SELECT requested_mode, result_origin FROM %s.search_snapshot WHERE id = ?"
							.formatted(schema),
					legacySearchId))
					.containsEntry("requested_mode", "LOCAL")
					.containsEntry("result_origin", "LOCAL_CATALOG");

			assertThatThrownBy(() -> jdbcTemplate.update(
					"UPDATE %s.search_snapshot SET requested_mode = 'INVALID' WHERE id = ?".formatted(schema),
					legacySearchId))
					.isInstanceOf(RuntimeException.class);
			assertThatThrownBy(() -> jdbcTemplate.update(
					"UPDATE %s.search_snapshot SET result_origin = 'INVALID' WHERE id = ?".formatted(schema),
					legacySearchId))
					.isInstanceOf(RuntimeException.class);

			assertThat(jdbcTemplate.queryForObject("""
					SELECT indexdef
					FROM pg_indexes
					WHERE schemaname = ?
					  AND indexname = 'idx_search_snapshot_owner_fingerprint_origin_freshness'
					""", String.class, schema))
					.contains("owner_id, fingerprint, result_origin, fresh_until DESC, searched_at DESC")
					.contains("WHERE")
					.contains("COMPLETED");
		}
		finally {
			jdbcTemplate.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
		}
	}

	private void insertV15Search(String schema, UUID id) {
		jdbcTemplate.update("""
				INSERT INTO %s.search_snapshot (
				    id, owner_id, original_query, normalized_query, fingerprint, fingerprint_version,
				    pipeline_version, filters, status, searched_at, fresh_until,
				    provider_coverage, warnings, total_provider_matches, result_count, created_at
				)
				VALUES (?, '00000000-0000-0000-0000-000000000001', 'offline agents', 'offline agents', ?, 1,
				        'test-v1',
				        '{"documentTypes":[],"openAccessOnly":false,"minimumCitations":0,"languages":[],"pageSize":20,"cursor":"*"}'::jsonb,
				        'COMPLETED', now(), now() + interval '1 hour', '[]'::jsonb, '[]'::jsonb, 0, 0, now())
				""".formatted(schema), id, "a".repeat(64));
	}

	private void migrate(String schema, String target) {
		var configuration = Flyway.configure()
				.dataSource(dataSource)
				.schemas(schema)
				.defaultSchema(schema)
				.locations("classpath:db/migration");
		if (target != null) {
			configuration.target(target);
		}
		configuration.load().migrate();
	}
}
