package com.openscholar.search.internal.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;
import java.util.regex.MatchResult;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class RelatedTopicReuseScaleFixtureContractTests {

	private static final Pattern URL = Pattern.compile("https?://[^'\\s]+");

	@Test
	void paperIdentifiersRoundTripAtEveryCorpusAndIntegerBoundary() {
		List<Integer> ordinals = List.of(
				0,
				1,
				39_999,
				40_000,
				49_999,
				50_000,
				74_999,
				75_000,
				99_999,
				0x7ffffffe);

		assertThat(ordinals).allSatisfy(ordinal -> {
			UUID paperId = RelatedTopicReuseScaleFixture.paperId(ordinal);

			assertThat(RelatedTopicReuseScaleFixture.ordinal(paperId))
					.as("paper ordinal %s", ordinal)
					.isEqualTo(ordinal);
		});
		assertThat(RelatedTopicReuseScaleFixture.ordinal(
				UUID.fromString("42000000-0000-0000-0000-000000000001")))
				.isEqualTo(-1);
		assertThat(RelatedTopicReuseScaleFixture.ordinal(
				UUID.fromString("41000000-0000-0000-0000-000000000000")))
				.isEqualTo(-1);
		assertThat(RelatedTopicReuseScaleFixture.ordinal(
				UUID.fromString("41000000-0000-0000-0000-ffffffffffff")))
				.isEqualTo(-1);
		assertThatThrownBy(() -> RelatedTopicReuseScaleFixture.paperId(-1))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("out of range");
		assertThatThrownBy(() -> RelatedTopicReuseScaleFixture.paperId(0x7fffffff))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("out of range");
	}

	@Test
	void targetVisibilityStopsExactlyBeforeOtherOwnerAndCatalogPartitions()
			throws Exception {
		var corpus = corpus();

		assertThat(List.of(0, 39_999, 40_000, 49_999))
				.allSatisfy(ordinal -> assertThat(RelatedTopicReuseScaleFixture.targetVisible(
						RelatedTopicReuseScaleFixture.paperId(ordinal), corpus))
						.as("target-visible ordinal %s", ordinal)
						.isTrue());
		assertThat(List.of(50_000, 74_999, 75_000, 99_999))
				.allSatisfy(ordinal -> assertThat(RelatedTopicReuseScaleFixture.targetVisible(
						RelatedTopicReuseScaleFixture.paperId(ordinal), corpus))
						.as("target-invisible ordinal %s", ordinal)
						.isFalse());
		assertThat(RelatedTopicReuseScaleFixture.targetVisible(
				UUID.fromString("42000000-0000-0000-0000-000000000001"), corpus))
				.isFalse();
		assertThat(corpus.targetSearchVisibleCount() + corpus.targetCollectionVisibleCount())
				.isEqualTo(50_000);
	}

	@Test
	void fixtureUsesBoundedSetBasedSqlForEveryBulkInsert() {
		List<String> statements = RelatedTopicReuseScaleFixture.sqlContract();
		String sql = String.join("\n", statements);
		List<String> bulkStatements = statements.stream()
				.filter(statement -> !statement.contains("INSERT INTO library_collection"))
				.toList();

		assertThat(statements).hasSize(8).allMatch(statement -> !statement.isBlank());
		assertThat(bulkStatements)
				.hasSize(7)
				.allMatch(statement -> statement.contains("generate_series("));
		assertThat(Pattern.compile("\\bgenerate_series\\(").matcher(sql).results().count())
				.isEqualTo(7L);
		assertThat(sql).contains(
				"INSERT INTO paper",
				"INSERT INTO provider_record",
				"INSERT INTO search_snapshot",
				"INSERT INTO search_result",
				"INSERT INTO library_collection",
				"INSERT INTO collection_paper",
				"scale mechanics seed '",
				"|| ? || ' ordinal '",
				"'documentTypes', '[]'::jsonb",
				"'openAccessOnly', FALSE",
				"'pageSize', 50",
				"'cursor', '*'",
				"lpad(to_hex(ordinal + 1), 12, '0'))::uuid",
				"lpad(to_hex(snapshot_ordinal + 1), 12, '0'))::uuid",
				"lpad(to_hex(local_ordinal + 1), 12, '0'))::uuid",
				"jsonb_build_array(jsonb_build_object(",
				"'returnedCount', 50",
				"jsonb_build_object(",
				"'publicationYear', snapshot_paper.publication_year",
				"'documentType', snapshot_paper.document_type",
				"'identifiers', '[]'::jsonb",
				"'authors', '[]'::jsonb");
	}

	@Test
	void frozenPolicyBindsEveryGeneratorStatementByDigest() throws Exception {
		var corpus = corpus();

		assertThat(corpus.generatorVersion())
				.isEqualTo(RelatedTopicReuseScaleFixture.GENERATOR_VERSION);
		assertThat(corpus.generatorSeed())
				.isEqualTo(RelatedTopicReuseScaleFixture.GENERATOR_SEED);
		assertThat(corpus.generatorSqlSha256())
				.isEqualTo(RelatedTopicReuseScaleFixture.generatorSqlSha256())
				.matches("[0-9a-f]{64}");
	}

	@Test
	void fixtureNeverStoresOrSynthesizesAPdfUrl() {
		List<String> statements = RelatedTopicReuseScaleFixture.sqlContract();
		String providerInsert = normalized(onlyStatementContaining(
				statements, "INSERT INTO provider_record"));
		List<String> resultInserts = statements.stream()
				.filter(statement -> statement.contains("INSERT INTO search_result"))
				.map(RelatedTopicReuseScaleFixtureContractTests::normalized)
				.toList();

		assertThat(providerInsert)
				.contains(
						"source_url, reported_open_access, landing_page_url, pdf_url, "
								+ "metadata_fragment",
						"'https://fixtures.openscholar.test/papers/' || ordinal, NULL, "
								+ "'{\"synthetic\":true,\"benchmark\":"
								+ "\"related-topic-reuse-scale-v1\"}'::jsonb");
		assertThat(resultInserts)
				.hasSize(2)
				.allSatisfy(resultInsert -> assertThat(resultInsert).contains(
						"reported_open_access, landing_page_url, pdf_url, ranking_reasons",
						"'https://fixtures.openscholar.test/papers/' || paper_ordinal, "
								+ "NULL, '[]'::jsonb"));
	}

	@Test
	void fixtureSqlCannotReachAnExternalDatabaseFilesystemOrNetwork() {
		String sql = String.join("\n", RelatedTopicReuseScaleFixture.sqlContract());
		String lower = sql.toLowerCase();
		List<String> urls = URL.matcher(lower).results().map(MatchResult::group).toList();

		assertThat(lower).doesNotContain(
				"create extension",
				"create server",
				"create foreign table",
				"foreign data wrapper",
				"postgres_fdw",
				"file_fdw",
				"dblink(",
				"http_get(",
				"http_post(",
				"lo_import(",
				"pg_read_file(",
				"pg_write_file(",
				"copy ",
				" program ",
				"jdbc:",
				"s3://",
				"curl ",
				"wget ",
				"do $$",
				"execute ");
		assertThat(urls)
				.isNotEmpty()
				.allMatch(url -> url.startsWith("https://fixtures.openscholar.test/"));
		assertThat(URL.matcher(lower).replaceAll(""))
				.doesNotContain("://");
	}

	private static RelatedTopicReuseScalePolicy.Corpus corpus() throws Exception {
		return RelatedTopicReuseScalePolicy.loadFrozen(new ObjectMapper()).policy().corpus();
	}

	private static String onlyStatementContaining(List<String> statements, String fragment) {
		return statements.stream()
				.filter(statement -> statement.contains(fragment))
				.findFirst()
				.orElseThrow();
	}

	private static String normalized(String sql) {
		return sql.replaceAll("\\s+", " ").strip();
	}
}
