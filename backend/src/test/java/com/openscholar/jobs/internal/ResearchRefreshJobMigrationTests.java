package com.openscholar.jobs.internal;

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
class ResearchRefreshJobMigrationTests {

	@Autowired
	private DataSource dataSource;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Test
	void v13UpgradesAnExistingSchemaAndEnforcesOneActiveJobPerTarget() {
		String schema = "refresh_jobs_" + UUID.randomUUID().toString().replace("-", "");
		UUID targetId = UUID.randomUUID();
		try {
			migrate(schema, "12");
			migrate(schema, null);

			jdbcTemplate.update("""
					INSERT INTO %s.research_refresh_job (
					    id, job_type, target_id, trigger_kind, status, attempt_count,
					    max_attempts, available_at, created_at, updated_at
					)
					VALUES (?, 'PAPER_ACCESS', ?, 'MANUAL', 'QUEUED', 0, 3, now(), now(), now())
					""".formatted(schema), UUID.randomUUID(), targetId);

			assertThatThrownBy(() -> jdbcTemplate.update("""
					INSERT INTO %s.research_refresh_job (
					    id, job_type, target_id, trigger_kind, status, attempt_count,
					    max_attempts, available_at, created_at, updated_at
					)
					VALUES (?, 'PAPER_ACCESS', ?, 'SCHEDULED', 'QUEUED', 0, 3, now(), now(), now())
					""".formatted(schema), UUID.randomUUID(), targetId))
					.isInstanceOf(RuntimeException.class);
			assertThat(jdbcTemplate.queryForObject("""
					SELECT count(*)
					FROM %s.flyway_schema_history
					WHERE version = '13' AND success
					""".formatted(schema), Integer.class)).isEqualTo(1);
		}
		finally {
			jdbcTemplate.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
		}
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
