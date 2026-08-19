package com.openscholar.paper.internal.persistence;

import static org.assertj.core.api.Assertions.assertThat;

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
class PaperFullTextMigrationTests {

	@Autowired
	private DataSource dataSource;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Test
	void v9BackfillsExistingPaperVectorsAndCreatesTheGinIndex() {
		String schema = "paper_fts_upgrade_" + UUID.randomUUID().toString().replace("-", "");
		UUID paperId = UUID.randomUUID();
		try {
			Flyway.configure()
					.dataSource(dataSource)
					.schemas(schema)
					.defaultSchema(schema)
					.locations("classpath:db/migration")
					.target("8")
					.load()
					.migrate();
			jdbcTemplate.update("""
					insert into %s.paper
					    (id, title, normalized_title, abstract_text, document_type, venue_name,
					     metadata_quality, metadata_updated_at, version, created_at, updated_at)
					values (?, 'Legacy quantum sensor networks', 'legacy quantum sensor networks',
					        'Adaptive optics for precision measurement', 'ARTICLE',
					        'Journal of Quantum Sensing', 0, now(), 0, now(), now())
					""".formatted(schema), paperId);

			Flyway.configure()
					.dataSource(dataSource)
					.schemas(schema)
					.defaultSchema(schema)
					.locations("classpath:db/migration")
					.load()
					.migrate();

			Boolean matches = jdbcTemplate.queryForObject("""
					select search_vector @@ plainto_tsquery(
					    'english'::regconfig,
					    'quantum sensor optics'
					)
					from %s.paper
					where id = ?
					""".formatted(schema), Boolean.class, paperId);
			assertThat(matches).isTrue();
			String indexDefinition = jdbcTemplate.queryForObject(
					"select indexdef from pg_indexes where schemaname = ? and indexname = ?",
					String.class,
					schema,
					"idx_paper_search_vector_fts");
			assertThat(indexDefinition).containsIgnoringCase("using gin");
		}
		finally {
			jdbcTemplate.execute("drop schema if exists " + schema + " cascade");
		}
	}
}
