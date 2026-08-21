package com.openscholar.paper.internal.persistence;

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
class PaperEmbeddingMigrationTests {

	@Autowired
	private DataSource dataSource;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Test
	void v10AddsVersionedVectorStorageWithoutBackfillingExistingPapers() {
		String schema = "paper_embedding_upgrade_" + UUID.randomUUID().toString().replace("-", "");
		UUID paperId = UUID.randomUUID();
		try {
			Flyway.configure()
					.dataSource(dataSource)
					.schemas(schema)
					.defaultSchema(schema)
					.locations("classpath:db/migration")
					.target("9")
					.load()
					.migrate();
			jdbcTemplate.update("""
					insert into %s.paper
					    (id, title, normalized_title, abstract_text, document_type,
					     venue_name, metadata_quality, metadata_updated_at, version,
					     created_at, updated_at)
					values (?, 'Legacy semantic retrieval', 'legacy semantic retrieval',
					        'A retained abstract', 'ARTICLE', 'Journal of Retrieval',
					        0, now(), 0, now(), now())
					""".formatted(schema), paperId);

			Flyway.configure()
					.dataSource(dataSource)
					.schemas(schema)
					.defaultSchema(schema)
					.locations("classpath:db/migration")
					.target("10")
					.load()
					.migrate();

			assertThat(jdbcTemplate.queryForObject(
					"select count(*) from %s.paper where id = ?".formatted(schema),
					Integer.class,
					paperId)).isEqualTo(1);
			assertThat(jdbcTemplate.queryForObject(
					"select count(*) from %s.paper_embedding".formatted(schema),
					Integer.class)).isZero();
			assertThat(jdbcTemplate.queryForObject("""
					select namespace.nspname
					from pg_extension extension
					join pg_namespace namespace on namespace.oid = extension.extnamespace
					where extension.extname = 'vector'
					""", String.class)).isEqualTo("public");
			assertThat(jdbcTemplate.queryForObject("""
					select count(*)
					from pg_indexes
					where schemaname = ?
					  and lower(indexdef) like '%%using hnsw%%'
					""", Integer.class, schema)).isZero();

			insertProfile(schema, "fixture-title-abstract-v1", 3, "revision-1");
			insertEmbedding(schema, paperId, "fixture-title-abstract-v1", 3, "[1,0,0]");
			assertThat(embeddingCount(schema, paperId)).isEqualTo(1);

			assertThatThrownBy(() -> jdbcTemplate.update("""
					update %s.paper_embedding
					set dimensions = 2,
					    embedding = cast('[1,0]' as public.vector)
					where paper_id = ? and profile_key = 'fixture-title-abstract-v1'
					""".formatted(schema), paperId))
					.isInstanceOf(RuntimeException.class);
			assertThatThrownBy(() -> jdbcTemplate.update("""
					update %s.paper_embedding
					set embedding = cast('[1,0]' as public.vector)
					where paper_id = ? and profile_key = 'fixture-title-abstract-v1'
					""".formatted(schema), paperId))
					.isInstanceOf(RuntimeException.class);
			assertThatThrownBy(() -> jdbcTemplate.update("""
					update %s.paper_embedding
					set content_checksum = 'not-a-sha256'
					where paper_id = ? and profile_key = 'fixture-title-abstract-v1'
					""".formatted(schema), paperId))
					.isInstanceOf(RuntimeException.class);
			assertThatThrownBy(() -> jdbcTemplate.update("""
					update %s.paper_embedding
					set embedding = cast('[0,0,0]' as public.vector)
					where paper_id = ? and profile_key = 'fixture-title-abstract-v1'
					""".formatted(schema), paperId))
					.isInstanceOf(RuntimeException.class);

			assertThatThrownBy(() -> jdbcTemplate.update(
					"update %s.embedding_profile set model = 'changed' where profile_key = ?"
							.formatted(schema),
					"fixture-title-abstract-v1"))
					.isInstanceOf(RuntimeException.class)
					.hasMessageContaining("Embedding profiles are immutable");
			insertProfile(schema, "unused-title-abstract-v1", 3, "revision-2");
			assertThatThrownBy(() -> jdbcTemplate.update(
					"delete from %s.embedding_profile where profile_key = ?".formatted(schema),
					"unused-title-abstract-v1"))
					.isInstanceOf(RuntimeException.class)
					.hasMessageContaining("Embedding profiles are immutable");

			jdbcTemplate.update(
					"update %s.paper set venue_name = 'Changed venue' where id = ?".formatted(schema),
					paperId);
			assertThat(embeddingCount(schema, paperId)).isEqualTo(1);

			jdbcTemplate.update(
					"update %s.paper set abstract_text = 'Changed abstract' where id = ?".formatted(schema),
					paperId);
			assertThat(embeddingCount(schema, paperId)).isZero();

			insertEmbedding(schema, paperId, "fixture-title-abstract-v1", 3, "[1,0,0]");
			jdbcTemplate.update(
					"update %s.paper set title = 'Changed title' where id = ?".formatted(schema),
					paperId);
			assertThat(embeddingCount(schema, paperId)).isZero();

			insertEmbedding(schema, paperId, "fixture-title-abstract-v1", 3, "[1,0,0]");
			jdbcTemplate.update("delete from %s.paper where id = ?".formatted(schema), paperId);
			assertThat(embeddingCount(schema, paperId)).isZero();
		}
		finally {
			jdbcTemplate.execute("drop schema if exists " + schema + " cascade");
		}
	}

	private void insertProfile(String schema, String profileKey, int dimensions, String revision) {
		jdbcTemplate.update("""
				insert into %s.embedding_profile
				    (profile_key, provider, model, model_revision, content_kind,
				     input_policy_version, dimensions, distance_metric, created_at)
				values (?, 'TEST', 'fixture-model', ?, 'TITLE_ABSTRACT',
				        1, ?, 'COSINE', now())
				""".formatted(schema), profileKey, revision, dimensions);
	}

	private void insertEmbedding(
			String schema, UUID paperId, String profileKey, int dimensions, String vector) {
		jdbcTemplate.update("""
				insert into %s.paper_embedding
				    (paper_id, profile_key, dimensions, content_checksum,
				     embedding, embedded_at)
				values (?, ?, ?, repeat('a', 64), cast(? as public.vector), now())
				""".formatted(schema), paperId, profileKey, dimensions, vector);
	}

	private int embeddingCount(String schema, UUID paperId) {
		return jdbcTemplate.queryForObject(
				"select count(*) from %s.paper_embedding where paper_id = ?".formatted(schema),
				Integer.class,
				paperId);
	}
}
