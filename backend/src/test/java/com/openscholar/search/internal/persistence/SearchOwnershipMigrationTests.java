package com.openscholar.search.internal.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
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
class SearchOwnershipMigrationTests {

	private static final UUID LOCAL_USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

	@Autowired
	private DataSource dataSource;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Test
	void v15BackfillsExistingSearchesAndEnforcesOwnerCascades() {
		String schema = "search_owner_" + UUID.randomUUID().toString().replace("-", "");
		UUID legacySearch = UUID.randomUUID();
		UUID hostedUser = UUID.randomUUID();
		UUID hostedSearch = UUID.randomUUID();
		try {
			migrate(schema, "14");
			insertV14Search(schema, legacySearch);
			migrate(schema, null);

			assertThat(jdbcTemplate.queryForObject(
					"SELECT owner_id FROM %s.search_snapshot WHERE id = ?".formatted(schema),
					UUID.class,
					legacySearch)).isEqualTo(LOCAL_USER_ID);
			jdbcTemplate.update("""
					INSERT INTO %s.app_user (id, display_name, created_at, identity_issuer, identity_subject)
					VALUES (?, 'Hosted user', now(), 'https://issuer.example', 'subject-1')
					""".formatted(schema), hostedUser);
			insertV15Search(schema, hostedSearch, hostedUser);

			assertThat(jdbcTemplate.queryForObject(
					"SELECT count(*) FROM %s.search_snapshot WHERE fingerprint = ?".formatted(schema),
					Integer.class,
					"a".repeat(64))).isEqualTo(2);
			jdbcTemplate.update("DELETE FROM %s.app_user WHERE id = ?".formatted(schema), hostedUser);
			assertThat(jdbcTemplate.queryForObject(
					"SELECT count(*) FROM %s.search_snapshot WHERE id = ?".formatted(schema),
					Integer.class,
					hostedSearch)).isZero();

			assertThatThrownBy(() -> insertV15Search(schema, UUID.randomUUID(), UUID.randomUUID()))
					.isInstanceOf(RuntimeException.class);
			assertThat(jdbcTemplate.queryForObject("""
					SELECT count(*)
					FROM %s.flyway_schema_history
					WHERE version = '15' AND success
					""".formatted(schema), Integer.class)).isEqualTo(1);
		}
		finally {
			jdbcTemplate.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
		}
	}

	private void insertV14Search(String schema, UUID id) {
		jdbcTemplate.update("""
				INSERT INTO %s.search_snapshot (
				    id, original_query, normalized_query, fingerprint, fingerprint_version,
				    pipeline_version, filters, status, searched_at, fresh_until,
				    provider_coverage, warnings, total_provider_matches, result_count, created_at
				)
				VALUES (?, 'private topic', 'private topic', ?, 1, 'test-v1',
				        '{"documentTypes":[],"openAccessOnly":false,"minimumCitations":0,"languages":[],"pageSize":20,"cursor":"*"}'::jsonb,
				        'COMPLETED', now(), now() + interval '1 hour', '[]'::jsonb, '[]'::jsonb, 0, 0, now())
				""".formatted(schema), id, "a".repeat(64));
	}

	private void insertV15Search(String schema, UUID id, UUID ownerId) {
		OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
		jdbcTemplate.update("""
				INSERT INTO %s.search_snapshot (
				    id, owner_id, original_query, normalized_query, fingerprint, fingerprint_version,
				    pipeline_version, filters, status, searched_at, fresh_until,
				    provider_coverage, warnings, total_provider_matches, result_count, created_at
				)
				VALUES (?, ?, 'private topic', 'private topic', ?, 1, 'test-v1',
				        '{"documentTypes":[],"openAccessOnly":false,"minimumCitations":0,"languages":[],"pageSize":20,"cursor":"*"}'::jsonb,
				        'COMPLETED', ?, ?, '[]'::jsonb, '[]'::jsonb, 0, 0, ?)
				""".formatted(schema), id, ownerId, "a".repeat(64), now, now.plusHours(1), now);
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
