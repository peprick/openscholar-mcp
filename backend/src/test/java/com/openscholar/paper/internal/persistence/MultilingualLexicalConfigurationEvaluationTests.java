package com.openscholar.paper.internal.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.ToDoubleFunction;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import com.openscholar.TestcontainersConfiguration;
import com.openscholar.paper.internal.persistence.MultilingualLexicalEvaluationFixture.Candidate;
import com.openscholar.paper.internal.persistence.MultilingualLexicalEvaluationFixture.Query;
import com.openscholar.paper.internal.persistence.MultilingualLexicalEvaluationPolicy.Gates;
import com.openscholar.paper.internal.persistence.MultilingualLexicalEvaluationPolicy.Profile;
import com.openscholar.paper.internal.persistence.MultilingualLexicalEvaluationPolicy.ProfileKey;
import com.openscholar.paper.internal.persistence.MultilingualLexicalEvaluationPolicy.TextSearchConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.ObjectMapper;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@Transactional(readOnly = true)
class MultilingualLexicalConfigurationEvaluationTests {

	private static final double EPSILON = 0.000_000_1d;
	private static final String SCORE_QUERY = """
			WITH lexical AS (
			    SELECT (
			        setweight(to_tsvector('%1$s'::regconfig, coalesce(:title, '')), 'A')
			        || setweight(to_tsvector('%1$s'::regconfig, coalesce(:abstractText, '')), 'B')
			        || setweight(to_tsvector('%1$s'::regconfig, coalesce(:venueName, '')), 'C')
			    ) AS document_vector,
			    websearch_to_tsquery('%1$s'::regconfig, :queryText) AS query_vector
			)
			SELECT document_vector @@ query_vector AS matches,
			       ts_rank_cd(document_vector, query_vector, %2$d)::double precision AS score,
			       document_vector::text AS document_vector,
			       query_vector::text AS query_vector
			FROM lexical
			""";

	@Autowired
	private ObjectMapper objectMapper;

	@Autowired
	private JdbcClient jdbcClient;

	@Autowired
	private PostgreSQLContainer postgresContainer;

	@Test
	void comparesAllowlistedConfigurationsWithoutChangingTheProductionLexicalColumn()
			throws Exception {
		var boundFixture = MultilingualLexicalEvaluationFixture.loadFrozen(objectMapper);
		var boundPolicy = MultilingualLexicalEvaluationPolicy.loadFrozen(objectMapper);
		MultilingualLexicalEvaluationFixture fixture = boundFixture.fixture();
		MultilingualLexicalEvaluationPolicy policy = boundPolicy.policy();
		policy.validateFixture(boundFixture);

		assertPinnedPostgresRuntime(policy);
		assertComparatorMatchesPolicy(policy);
		assertAllowlistedConfigurationsExist(policy);
		assertProductionGeneratedColumnRemainsEnglish(policy);

		Map<ProfileKey, List<QueryMeasurement>> measurements = new EnumMap<>(ProfileKey.class);
		for (Profile profile : policy.profiles()) {
			List<QueryMeasurement> profileMeasurements = new ArrayList<>();
			for (Query query : fixture.queries()) {
				TextSearchConfiguration configuration =
						profile.configurationFor(query.language());
				List<RankedCandidate> first = rank(
						profile,
						query,
						fixture.candidatesFor(query.language()),
						policy.baseline().rankNormalization());
				List<RankedCandidate> repeated = rank(
						profile,
						query,
						fixture.candidatesFor(query.language()),
						policy.baseline().rankNormalization());
				assertThat(repeated)
						.as("stable repeated %s ranking for %s", profile.key(), query.key())
						.containsExactlyElementsOf(first);
				profileMeasurements.add(measure(query, configuration, first));
			}
			measurements.put(profile.key(), List.copyOf(profileMeasurements));
		}

		printEvidence(policy, measurements);
		assertPolicyGates(policy, fixture, measurements);
	}

	private void assertPinnedPostgresRuntime(MultilingualLexicalEvaluationPolicy policy) {
		int serverVersionNumber = jdbcClient.sql("SHOW server_version_num")
				.query(Integer.class)
				.single();
		assertThat(serverVersionNumber / 10_000)
				.as("PostgreSQL major for %s", policy.runtime().containerImage())
				.isEqualTo(policy.runtime().postgresMajor());
		assertThat(postgresContainer.getDockerImageName())
				.isEqualTo(policy.runtime().containerImage());
	}

	private static void assertComparatorMatchesPolicy(
			MultilingualLexicalEvaluationPolicy policy) {
		var baseline = policy.baseline();
		assertThat(baseline.queryFunction())
				.isEqualTo(MultilingualLexicalEvaluationPolicy.QueryFunction.WEBSEARCH_TO_TSQUERY);
		assertThat(baseline.rankFunction())
				.isEqualTo(MultilingualLexicalEvaluationPolicy.RankFunction.TS_RANK_CD);
		assertThat(baseline.weightedFields())
				.containsExactly("TITLE_A", "ABSTRACT_B", "VENUE_C");
		assertThat(occurrences(SCORE_QUERY, "to_tsvector('%1$s'::regconfig"))
				.isEqualTo(3);
		assertThat(occurrences(SCORE_QUERY, "websearch_to_tsquery('%1$s'::regconfig"))
				.isEqualTo(1);
		assertThat(occurrences(SCORE_QUERY, "setweight("))
				.isEqualTo(3);
		assertThat(SCORE_QUERY)
				.contains(
						"coalesce(:title, '')), 'A'",
						"coalesce(:abstractText, '')), 'B'",
						"coalesce(:venueName, '')), 'C'",
						"ts_rank_cd(document_vector, query_vector, %2$d)");
	}

	private void assertAllowlistedConfigurationsExist(MultilingualLexicalEvaluationPolicy policy) {
		Set<String> expected = policy.profiles().stream()
				.flatMap(profile -> java.util.stream.Stream.concat(
						java.util.stream.Stream.of(profile.defaultConfiguration()),
						profile.languageConfigurations().values().stream()))
				.map(TextSearchConfiguration::sqlName)
				.collect(Collectors.toUnmodifiableSet());
		List<String> available = jdbcClient.sql("""
				SELECT DISTINCT config.cfgname
				FROM pg_catalog.pg_ts_config config
				WHERE config.cfgname IN (:configurations)
				ORDER BY config.cfgname
				""")
				.param("configurations", expected)
				.query(String.class)
				.list();
		assertThat(available).containsExactlyInAnyOrderElementsOf(expected);
	}

	private void assertProductionGeneratedColumnRemainsEnglish(
			MultilingualLexicalEvaluationPolicy policy) {
		String expression = jdbcClient.sql("""
				SELECT column_definition.generation_expression
				FROM information_schema.columns column_definition
				WHERE column_definition.table_schema = current_schema()
				  AND column_definition.table_name = 'paper'
				  AND column_definition.column_name = 'search_vector'
				""")
				.query(String.class)
				.single();
		assertThat(policy.gates().requireProductionConfigurationUnchanged()).isTrue();
		String normalized = expression.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
		assertThat(occurrences(normalized, "to_tsvector('english'::regconfig"))
				.isEqualTo(3);
		assertThat(occurrences(normalized, "to_tsvector("))
				.isEqualTo(3);
		assertThat(normalized)
				.contains(
						"coalesce(title",
						"coalesce(abstract_text",
						"coalesce(venue_name",
						"'a'::\"char\"",
						"'b'::\"char\"",
						"'c'::\"char\"")
				.doesNotContain(
						"to_tsvector('simple'::regconfig",
						"to_tsvector('german'::regconfig",
						"to_tsvector('french'::regconfig",
						"to_tsvector('spanish'::regconfig");
	}

	private List<RankedCandidate> rank(
			Profile profile,
			Query query,
			List<Candidate> candidates,
			int rankNormalization) {
		TextSearchConfiguration configuration = profile.configurationFor(query.language());
		String sql = SCORE_QUERY.formatted(configuration.sqlName(), rankNormalization);
		return candidates.stream()
				.map(candidate -> score(sql, configuration, query, candidate))
				.filter(ScoredCandidate::matches)
				.map(candidate -> new RankedCandidate(
						candidate.key(), candidate.score(), candidate.configuration()))
				.sorted(Comparator.comparingDouble(RankedCandidate::score)
						.reversed()
						.thenComparing(RankedCandidate::key))
				.limit(query.cutoff())
				.toList();
	}

	private ScoredCandidate score(
			String sql,
			TextSearchConfiguration configuration,
			Query query,
			Candidate candidate) {
		return jdbcClient.sql(sql)
				.param("title", candidate.title())
				.param("abstractText", candidate.abstractText(), Types.VARCHAR)
				.param("venueName", candidate.venueName(), Types.VARCHAR)
				.param("queryText", query.text())
				.query((resultSet, rowNumber) -> mapScore(
						resultSet, candidate.key(), configuration))
				.single();
	}

	private static ScoredCandidate mapScore(
			ResultSet resultSet,
			String candidateKey,
			TextSearchConfiguration configuration) throws SQLException {
		return new ScoredCandidate(
				candidateKey,
				resultSet.getBoolean("matches"),
				resultSet.getDouble("score"),
				configuration,
				resultSet.getString("document_vector"),
				resultSet.getString("query_vector"));
	}

	private static QueryMeasurement measure(
			Query query,
			TextSearchConfiguration configuration,
			List<RankedCandidate> ranked) {
		List<String> rankedKeys = ranked.stream().map(RankedCandidate::key).toList();
		Set<String> relevant = query.judgments().entrySet().stream()
				.filter(entry -> entry.getValue() > 0)
				.map(Map.Entry::getKey)
				.collect(Collectors.toUnmodifiableSet());
		int matchedRelevantCount = (int) rankedKeys.stream()
				.limit(query.cutoff())
				.filter(relevant::contains)
				.distinct()
				.count();
		double recall = (double) matchedRelevantCount / relevant.size();
		double actualDcg = IntStream.range(0, Math.min(query.cutoff(), rankedKeys.size()))
				.mapToDouble(index -> discountedGain(
						query.judgments().getOrDefault(rankedKeys.get(index), 0), index))
				.sum();
		List<Integer> idealGrades = query.judgments().values().stream()
				.filter(grade -> grade > 0)
				.sorted(Comparator.reverseOrder())
				.limit(query.cutoff())
				.toList();
		double idealDcg = IntStream.range(0, idealGrades.size())
				.mapToDouble(index -> discountedGain(idealGrades.get(index), index))
				.sum();
		double precisionAtOne = !rankedKeys.isEmpty()
				&& query.judgments().getOrDefault(rankedKeys.getFirst(), 0) > 0 ? 1.0d : 0.0d;
		double reciprocalRank = IntStream.range(0, Math.min(query.cutoff(), rankedKeys.size()))
				.filter(index -> query.judgments().getOrDefault(rankedKeys.get(index), 0) > 0)
				.mapToDouble(index -> 1.0d / (index + 1.0d))
				.findFirst()
				.orElse(0.0d);
		return new QueryMeasurement(
				query.key(), query.language(), configuration, query.cutoff(), recall,
				actualDcg / idealDcg,
				precisionAtOne, reciprocalRank, matchedRelevantCount, List.copyOf(ranked));
	}

	private static double discountedGain(int grade, int zeroBasedRank) {
		if (grade <= 0) {
			return 0.0d;
		}
		return (Math.pow(2.0d, grade) - 1.0d)
				/ (Math.log(zeroBasedRank + 2.0d) / Math.log(2.0d));
	}

	private static void assertPolicyGates(
			MultilingualLexicalEvaluationPolicy policy,
			MultilingualLexicalEvaluationFixture fixture,
			Map<ProfileKey, List<QueryMeasurement>> measurements) {
		Gates gates = policy.gates();
		List<QueryMeasurement> production = measurements.get(ProfileKey.PRODUCTION_ENGLISH);
		List<QueryMeasurement> languageAware = measurements.get(ProfileKey.LANGUAGE_AWARE);
		Map<String, QueryMeasurement> productionByQuery = byQuery(production);
		Map<String, QueryMeasurement> languageAwareByQuery = byQuery(languageAware);

		List<QueryMeasurement> supportedLanguageAware = languageAware.stream()
				.filter(measurement -> policy.isSupportedLanguage(measurement.language()))
				.toList();
		Summary supportedSummary = summarize(supportedLanguageAware);
		assertThat(supportedSummary.macroRecall())
				.isGreaterThanOrEqualTo(gates.minimumLanguageAwareSupportedMacroRecallAt3());
		assertThat(supportedSummary.macroNdcg())
				.isGreaterThanOrEqualTo(gates.minimumLanguageAwareSupportedMacroNdcgAt3());

		long strictNonEnglishImprovements = fixture.queries().stream()
				.filter(query -> policy.isSupportedLanguage(query.language()))
				.filter(query -> !"en".equals(query.language()))
				.filter(query -> languageAwareByQuery.get(query.key()).recall()
						> productionByQuery.get(query.key()).recall() + EPSILON)
				.count();
		long regressions = fixture.queries().stream()
				.filter(query -> policy.isSupportedLanguage(query.language()))
				.filter(query -> languageAwareByQuery.get(query.key()).recall() + EPSILON
						< productionByQuery.get(query.key()).recall())
				.count();
		assertThat(strictNonEnglishImprovements)
				.isGreaterThanOrEqualTo(gates.minimumStrictNonEnglishImprovementCount());
		assertThat(regressions).isLessThanOrEqualTo(gates.maximumSupportedQueryRegressionCount());

		List<QueryMeasurement> fallback = languageAware.stream()
				.filter(measurement -> policy.fallbackLanguages().contains(measurement.language()))
				.toList();
		assertThat(gates.requireFallbackReportedSeparately()).isTrue();
		assertThat(fallback).hasSize(policy.fallbackLanguages().size());
		assertThat(fallback)
				.allSatisfy(measurement -> assertThat(measurement.configuration())
						.isEqualTo(TextSearchConfiguration.SIMPLE));
	}

	private static Map<String, QueryMeasurement> byQuery(List<QueryMeasurement> measurements) {
		Map<String, QueryMeasurement> result = new LinkedHashMap<>();
		measurements.forEach(measurement -> result.put(measurement.queryKey(), measurement));
		return Map.copyOf(result);
	}

	private static Summary summarize(List<QueryMeasurement> measurements) {
		if (measurements.isEmpty()) {
			throw new IllegalArgumentException("measurements must not be empty");
		}
		return new Summary(
				average(measurements, QueryMeasurement::recall),
				average(measurements, QueryMeasurement::ndcg),
				average(measurements, QueryMeasurement::precisionAtOne),
				average(measurements, QueryMeasurement::reciprocalRank));
	}

	private static double average(
			List<QueryMeasurement> measurements,
			ToDoubleFunction<QueryMeasurement> metric) {
		return measurements.stream().mapToDouble(metric).average().orElseThrow();
	}

	private static void printEvidence(
			MultilingualLexicalEvaluationPolicy policy,
			Map<ProfileKey, List<QueryMeasurement>> measurements) {
		for (var entry : measurements.entrySet()) {
			for (QueryMeasurement measurement : entry.getValue()) {
				String support = policy.isSupportedLanguage(measurement.language())
						? "SUPPORTED" : "TOKENIZER_FALLBACK";
				System.out.printf(
						Locale.ROOT,
						"multilingual-lexical-development-v1 profile=%s query=%s language=%s "
								+ "support=%s configuration=%s recall@%d=%.3f ndcg@%d=%.3f "
								+ "precision@1=%.3f "
								+ "reciprocal-rank=%.3f matched-relevant=%d ranked=%s%n",
						entry.getKey(), measurement.queryKey(), measurement.language(), support,
						measurement.configuration(), measurement.cutoff(), measurement.recall(),
						measurement.cutoff(),
						measurement.ndcg(), measurement.precisionAtOne(),
						measurement.reciprocalRank(), measurement.matchedRelevantCount(),
						measurement.ranked());
			}
			List<QueryMeasurement> supported = entry.getValue().stream()
					.filter(measurement -> policy.isSupportedLanguage(measurement.language()))
					.toList();
			Summary summary = summarize(supported);
			System.out.printf(
					Locale.ROOT,
					"multilingual-lexical-development-v1 profile=%s supported-macro-recall=%.3f "
							+ "supported-macro-ndcg=%.3f supported-macro-precision@1=%.3f "
							+ "supported-mrr=%.3f%n",
					entry.getKey(), summary.macroRecall(), summary.macroNdcg(),
					summary.macroPrecisionAtOne(), summary.meanReciprocalRank());
		}
	}

	record ScoredCandidate(
			String key,
			boolean matches,
			double score,
			TextSearchConfiguration configuration,
			String documentVector,
			String queryVector) {
	}

	record RankedCandidate(
			String key,
			double score,
			TextSearchConfiguration configuration) {
	}

	record QueryMeasurement(
			String queryKey,
			String language,
			TextSearchConfiguration configuration,
			int cutoff,
			double recall,
			double ndcg,
			double precisionAtOne,
			double reciprocalRank,
			int matchedRelevantCount,
			List<RankedCandidate> ranked) {

		QueryMeasurement {
			ranked = List.copyOf(ranked);
		}
	}

	record Summary(
			double macroRecall,
			double macroNdcg,
			double macroPrecisionAtOne,
			double meanReciprocalRank) {
	}

	private static int occurrences(String value, String token) {
		return (value.length() - value.replace(token, "").length()) / token.length();
	}
}
