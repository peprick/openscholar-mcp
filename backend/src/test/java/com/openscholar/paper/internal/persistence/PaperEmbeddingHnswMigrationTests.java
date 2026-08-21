package com.openscholar.paper.internal.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.StringJoiner;
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
class PaperEmbeddingHnswMigrationTests {

	@Autowired
	private DataSource dataSource;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Test
	void v11AddsOnlyThePinnedProfileCosineHnswIndexWithoutChangingStoredVectors() {
		String schema = "paper_embedding_hnsw_" + UUID.randomUUID().toString().replace("-", "");
		UUID paperId = UUID.randomUUID();
		try {
			migrate(schema, "10");
			jdbcTemplate.update("""
					insert into %s.paper
					    (id, title, normalized_title, abstract_text, document_type,
					     metadata_quality, metadata_updated_at, version, created_at, updated_at)
					values (?, 'Pinned ANN source', 'pinned ann source', 'A retained abstract',
					        'ARTICLE', 0, now(), 0, now(), now())
					""".formatted(schema), paperId);
			jdbcTemplate.update("""
					insert into %s.embedding_profile
					    (profile_key, provider, model, model_revision, content_kind,
					     input_policy_version, dimensions, distance_metric, created_at)
					values (?, ?, ?, ?, 'TITLE_ABSTRACT',
					        1, ?, 'COSINE', now())
					""".formatted(schema),
					PaperEmbeddingAnnPolicy.PROFILE_KEY,
					PaperEmbeddingAnnPolicy.PROVIDER,
					PaperEmbeddingAnnPolicy.MODEL,
					PaperEmbeddingAnnPolicy.MODEL_REVISION,
					PaperEmbeddingAnnPolicy.DIMENSIONS);
			jdbcTemplate.update("""
					insert into %s.paper_embedding
					    (paper_id, profile_key, dimensions, content_checksum,
					     embedding, embedded_at)
					values (?, ?, ?, repeat('a', 64), cast(? as public.vector), now())
					""".formatted(schema),
					paperId,
					PaperEmbeddingAnnPolicy.PROFILE_KEY,
					PaperEmbeddingAnnPolicy.DIMENSIONS,
					unitVectorLiteral());

			migrate(schema, null);

			String indexDefinition = jdbcTemplate.queryForObject("""
					select indexdef
					from pg_indexes
					where schemaname = ? and indexname = ?
					""", String.class, schema, PaperEmbeddingAnnPolicy.INDEX_NAME);
			assertThat(indexDefinition)
					.containsIgnoringCase("using hnsw")
					.containsIgnoringCase("vector(1024)")
					.containsIgnoringCase("vector_cosine_ops")
					.contains(PaperEmbeddingAnnPolicy.PROFILE_KEY);

			String indexOptions = jdbcTemplate.queryForObject("""
					select array_to_string(index_relation.reloptions, ',')
					from pg_class index_relation
					join pg_namespace namespace on namespace.oid = index_relation.relnamespace
					where namespace.nspname = ? and index_relation.relname = ?
					""", String.class, schema, PaperEmbeddingAnnPolicy.INDEX_NAME);
			assertThat(indexOptions)
					.contains("m=" + PaperEmbeddingAnnPolicy.INDEX_M)
					.contains("ef_construction=" + PaperEmbeddingAnnPolicy.INDEX_EF_CONSTRUCTION);

			assertThat(jdbcTemplate.queryForObject(
					"select count(*) from %s.paper_embedding where paper_id = ?".formatted(schema),
					Integer.class,
					paperId)).isEqualTo(1);
			assertThat(jdbcTemplate.queryForObject("""
					select public.vector_dims(embedding)
					from %s.paper_embedding
					where paper_id = ? and profile_key = ?
					""".formatted(schema),
					Integer.class,
					paperId,
					PaperEmbeddingAnnPolicy.PROFILE_KEY))
					.isEqualTo(PaperEmbeddingAnnPolicy.DIMENSIONS);
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

	private String unitVectorLiteral() {
		StringJoiner vector = new StringJoiner(",", "[", "]");
		vector.add("1");
		for (int dimension = 1; dimension < PaperEmbeddingAnnPolicy.DIMENSIONS; dimension++) {
			vector.add("0");
		}
		return vector.toString();
	}
}
