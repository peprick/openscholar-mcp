package com.openscholar.paper.internal.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
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
class TypedPublicationMetadataMigrationTests {

	private static final List<String> TYPED_COLUMNS = List.of(
			"article_number",
			"degree",
			"edition",
			"institution",
			"isbn",
			"issn",
			"issue",
			"pages",
			"publisher",
			"volume");

	@Autowired
	private DataSource dataSource;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Test
	void freshMigrationCreatesNullableTypedColumnsAndArrayConstrainedJsonDefaults() {
		String schema = schemaName("typed_metadata_fresh");
		UUID paperId = UUID.randomUUID();
		try {
			migrate(schema, null);

			assertThat(jdbcTemplate.queryForList("""
					select column_name
					from information_schema.columns
					where table_schema = ? and table_name = 'paper'
					  and column_name in (
					      'publisher', 'institution', 'volume', 'issue', 'pages',
					      'article_number', 'edition', 'isbn', 'issn', 'degree'
					  )
					order by column_name
					""", String.class, schema)).containsExactlyElementsOf(TYPED_COLUMNS);
			assertThat(jdbcTemplate.queryForList("""
					select data_type
					from information_schema.columns
					where table_schema = ? and table_name = 'paper'
					  and column_name in ('isbn', 'issn')
					order by column_name
					""", String.class, schema)).containsExactly("jsonb", "jsonb");

			insertLegacyShapedPaper(schema, paperId, "Fresh typed metadata paper");
			assertThat(jdbcTemplate.queryForMap("""
					select publisher, institution, volume, issue, pages, article_number,
					       edition, isbn::text as isbn, issn::text as issn, degree
					from %s.paper
					where id = ?
					""".formatted(schema), paperId))
					.containsEntry("publisher", null)
					.containsEntry("institution", null)
					.containsEntry("volume", null)
					.containsEntry("issue", null)
					.containsEntry("pages", null)
					.containsEntry("article_number", null)
					.containsEntry("edition", null)
					.containsEntry("isbn", "[]")
					.containsEntry("issn", "[]")
					.containsEntry("degree", null);

			assertThatThrownBy(() -> jdbcTemplate.update(
					"update %s.paper set isbn = '{}'::jsonb where id = ?".formatted(schema),
					paperId)).isInstanceOf(RuntimeException.class);
			assertThatThrownBy(() -> jdbcTemplate.update(
					"update %s.paper set issn = '{\"value\":\"2049-3630\"}'::jsonb where id = ?"
							.formatted(schema),
					paperId)).isInstanceOf(RuntimeException.class);
		}
		finally {
			jdbcTemplate.execute("drop schema if exists " + schema + " cascade");
		}
	}

	@Test
	void v12UpgradesExistingPapersWithoutDataLossAndBackfillsEmptyIdentifierLists() {
		String schema = schemaName("typed_metadata_upgrade");
		UUID paperId = UUID.randomUUID();
		try {
			migrate(schema, "11");
			insertLegacyShapedPaper(schema, paperId, "Retained legacy paper");

			migrate(schema, null);

			assertThat(jdbcTemplate.queryForMap("""
					select title, isbn::text as isbn, issn::text as issn
					from %s.paper
					where id = ?
					""".formatted(schema), paperId))
					.containsEntry("title", "Retained legacy paper")
					.containsEntry("isbn", "[]")
					.containsEntry("issn", "[]");
			assertThat(jdbcTemplate.queryForObject("""
					select count(*)
					from %s.flyway_schema_history
					where version = '12' and success
					""".formatted(schema), Integer.class)).isEqualTo(1);
		}
		finally {
			jdbcTemplate.execute("drop schema if exists " + schema + " cascade");
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

	private void insertLegacyShapedPaper(String schema, UUID paperId, String title) {
		jdbcTemplate.update("""
				insert into %s.paper
				    (id, title, normalized_title, document_type, metadata_quality,
				     metadata_updated_at, version, created_at, updated_at)
				values (?, ?, lower(?), 'ARTICLE', 0, now(), 0, now(), now())
				""".formatted(schema), paperId, title, title);
	}

	private static String schemaName(String prefix) {
		return prefix + "_" + UUID.randomUUID().toString().replace("-", "");
	}
}
