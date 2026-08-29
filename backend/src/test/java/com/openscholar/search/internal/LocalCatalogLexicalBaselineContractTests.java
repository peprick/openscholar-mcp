package com.openscholar.search.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

class LocalCatalogLexicalBaselineContractTests {

	private static final String ENGLISH_QUERY =
			"websearch_to_tsquery('english'::regconfig, :query)";

	@Test
	void productionQueriesKeepTheFrozenEnglishConfigurationAndScoringFormula()
			throws Exception {
		String baseQuery = staticStringField("BASE_QUERY");
		String hydrateQuery = staticStringField("HYDRATE_QUERY");

		assertThat(occurrences(baseQuery, ENGLISH_QUERY)).isEqualTo(2);
		assertThat(occurrences(hydrateQuery, ENGLISH_QUERY)).isEqualTo(1);
		assertThat(occurrences(baseQuery, "websearch_to_tsquery(")).isEqualTo(2);
		assertThat(occurrences(hydrateQuery, "websearch_to_tsquery(")).isEqualTo(1);
		assertThat(occurrences(baseQuery, "::regconfig")).isEqualTo(2);
		assertThat(occurrences(hydrateQuery, "::regconfig")).isEqualTo(1);
		assertNoAlternativeConfiguration(baseQuery);
		assertNoAlternativeConfiguration(hydrateQuery);
		assertRankNormalization(baseQuery);
		assertRankNormalization(hydrateQuery);
		assertThat(baseQuery)
				.contains(
						"CASE WHEN title_exact THEN 8.0 ELSE 0.0 END",
						"CASE WHEN title_prefix AND NOT title_exact THEN 5.0 ELSE 0.0 END",
						"CASE WHEN title_contains AND NOT title_prefix THEN 2.5 ELSE 0.0 END",
						"(text_score * 3.0)",
						"CASE WHEN author_match THEN 1.5 ELSE 0.0 END",
						"ln(1.0 + coalesce(citation_count, 0)) * 0.01",
						"ORDER BY ranked.total_score DESC",
						"ranked.citation_count DESC NULLS LAST",
						"ranked.paper_id");
		assertThat(hydrateQuery)
				.contains(
						"CASE WHEN title_exact THEN 8.0 ELSE 0.0 END",
						"CASE WHEN title_prefix AND NOT title_exact THEN 5.0 ELSE 0.0 END",
						"CASE WHEN title_contains AND NOT title_prefix THEN 2.5 ELSE 0.0 END",
						"(text_score * 3.0)",
						"CASE WHEN author_match THEN 1.5 ELSE 0.0 END",
						"ln(1.0 + coalesce(citation_count, 0)) * 0.01");
	}

	@Test
	void generatedSearchVectorKeepsEnglishTitleAbstractAndVenueWeights() throws Exception {
		String migration = new ClassPathResource("db/migration/V9__add_paper_full_text_search.sql")
				.getContentAsString(StandardCharsets.UTF_8);

		assertThat(occurrences(migration, "to_tsvector('english'::regconfig")).isEqualTo(3);
		assertThat(occurrences(migration, "to_tsvector(")).isEqualTo(3);
		assertThat(occurrences(migration, "::regconfig")).isEqualTo(3);
		assertThat(migration)
				.contains(
						"coalesce(title, '')), 'A'",
						"coalesce(abstract_text, '')), 'B'",
						"coalesce(venue_name, '')), 'C'")
				.doesNotContain(
						"to_tsvector('simple'::regconfig",
						"to_tsvector('german'::regconfig",
						"to_tsvector('french'::regconfig",
						"to_tsvector('spanish'::regconfig");
	}

	private static String staticStringField(String name) throws Exception {
		Field field = LocalCatalogSearch.class.getDeclaredField(name);
		field.setAccessible(true);
		return (String) field.get(null);
	}

	private static int occurrences(String value, String token) {
		return (value.length() - value.replace(token, "").length()) / token.length();
	}

	private static void assertNoAlternativeConfiguration(String sql) {
		assertThat(sql).doesNotContain(
				"websearch_to_tsquery('simple'::regconfig",
				"websearch_to_tsquery('german'::regconfig",
				"websearch_to_tsquery('french'::regconfig",
				"websearch_to_tsquery('spanish'::regconfig");
	}

	private static void assertRankNormalization(String sql) {
		String normalized = sql.replaceAll("\\s+", " ");
		assertThat(normalized).contains(
				"ts_rank_cd( paper.search_vector, " + ENGLISH_QUERY
						+ ", 32 )::double precision AS text_score");
	}
}
