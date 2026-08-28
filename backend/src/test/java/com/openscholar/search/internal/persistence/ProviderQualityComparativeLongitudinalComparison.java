package com.openscholar.search.internal.persistence;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

import com.openscholar.search.internal.persistence.ProviderQualityComparativeRunSealBundle.Bindings;
import com.openscholar.search.internal.persistence.ProviderQualityComparativeRunSealBundle.VerifiedRunSeal;
import com.openscholar.search.internal.persistence.ProviderQualityComparativeScorer.DeduplicationScore;
import com.openscholar.search.internal.persistence.ProviderQualityComparativeScorer.ExpectedFieldRecovery;
import com.openscholar.search.internal.persistence.ProviderQualityComparativeScorer.FieldRecovery;
import com.openscholar.search.internal.persistence.ProviderQualityComparativeScorer.MustSeparateMeasurement;
import com.openscholar.search.internal.persistence.ProviderQualityComparativeScorer.QueryScenarioScore;
import com.openscholar.search.internal.persistence.ProviderQualityComparativeScorer.QueryScore;
import com.openscholar.search.internal.persistence.ProviderQualityComparativeScorer.RankingScore;
import com.openscholar.search.internal.persistence.ProviderQualityComparativeScorer.RankingSummaryScore;
import com.openscholar.search.internal.persistence.ProviderQualityComparativeScorer.ScenarioSummary;
import com.openscholar.search.internal.persistence.ProviderQualityComparativeScorer.ScoringResult;
import com.openscholar.search.internal.persistence.ProviderQualityComparativeScorer.UniqueRelevantQueryCoverage;
import com.openscholar.search.internal.persistence.ProviderQualityComparativeScoringPolicy.Scenario;
import com.openscholar.search.internal.persistence.ProviderQualityMetrics.MetadataField;

/**
 * Pure operator-only comparison of a bounded cohort of fully replayed comparative runs.
 *
 * <p>The report retains exact per-run snapshots and adjacent transitions. It never averages runs,
 * labels a change as good or bad, or makes a provider-enablement decision. The resulting document
 * is private engineering evidence and must not be exposed through reader-facing surfaces.</p>
 */
final class ProviderQualityComparativeLongitudinalComparison {

	static final int SCHEMA_VERSION = 1;
	static final int MINIMUM_RUNS = 2;
	static final int MAXIMUM_RUNS = 16;
	static final String PROTOCOL_ID =
			"provider-quality-comparative-longitudinal-v1";
	static final String COMPARISON_ID_PREFIX = PROTOCOL_ID + '-';
	static final String REPORT_FILENAME = "longitudinal-report.json";

	private static final byte[] IDENTITY_DOMAIN =
			"openscholar-provider-quality-comparative-longitudinal-v1"
					.getBytes(StandardCharsets.US_ASCII);
	private static final Pattern SHA256 = Pattern.compile("^[0-9a-f]{64}$");
	private static final Set<Scenario> EXPECTED_SCENARIOS = Set.of(Scenario.values());
	private static final Set<MetadataField> EXPECTED_METADATA_FIELDS =
			Set.of(MetadataField.values());

	private ProviderQualityComparativeLongitudinalComparison() {
	}

	static VerifiedRun verifiedRun(VerifiedRunSeal seal, ScoringResult result) {
		VerifiedRun verified = new VerifiedRun(
				Objects.requireNonNull(seal, "seal"),
				Objects.requireNonNull(result, "result"));
		validateRun(verified);
		return verified;
	}

	static Comparison compare(List<VerifiedRun> suppliedRuns) {
		Objects.requireNonNull(suppliedRuns, "runs");
		if (suppliedRuns.size() < MINIMUM_RUNS || suppliedRuns.size() > MAXIMUM_RUNS) {
			throw failure("LONGITUDINAL_RUN_COUNT_INVALID");
		}
		List<VerifiedRun> runs = new ArrayList<>(suppliedRuns);
		runs.forEach(ProviderQualityComparativeLongitudinalComparison::validateRun);
		runs.sort(Comparator
				.comparing((VerifiedRun run) -> canonicalInstant(
						run.result().captureMeasuredAt()))
				.thenComparing(run -> run.seal().sealId()));

		validateDistinctRuns(runs);
		VerifiedRun cohort = runs.getFirst();
		List<String> cohortQueryKeys = queryKeys(cohort.result());
		for (VerifiedRun run : runs.subList(1, runs.size())) {
			validateCompatibility(cohort.result(), cohortQueryKeys, run.result());
		}

		List<RunSnapshot> snapshots = new ArrayList<>(runs.size());
		for (int index = 0; index < runs.size(); index++) {
			snapshots.add(runSnapshot(index + 1, runs.get(index)));
		}
		List<Transition> transitions = new ArrayList<>(runs.size() - 1);
		for (int index = 1; index < runs.size(); index++) {
			transitions.add(transition(index, runs.get(index - 1), runs.get(index)));
		}

		ScoringResult common = cohort.result();
		return new Comparison(
				SCHEMA_VERSION,
				PROTOCOL_ID,
				comparisonId(runs),
				runs.size(),
				common.captureRepositoryRevision(),
				common.querySetId(),
				common.querySetSha256(),
				common.scoringPolicyId(),
				common.scoringPolicySha256(),
				common.queryCount(),
				snapshots,
				transitions,
				Use.OBSERVATIONAL_ONLY,
				false,
				false);
	}

	static Map<String, Object> artifacts(Comparison comparison) {
		return Map.of(REPORT_FILENAME, Objects.requireNonNull(comparison, "comparison"));
	}

	private static void validateRun(VerifiedRun verified) {
		VerifiedRunSeal seal = verified.seal();
		ScoringResult result = verified.result();
		Bindings bindings = seal.bindings();
		if (result.schemaVersion() != ProviderQualityComparativeScorer.REPORT_SCHEMA_VERSION
				|| result.readerFacing()
				|| result.defaultEnablementDecision()
				|| result.queryCount() < 1
				|| result.queries().size() != result.queryCount()
				|| result.uniqueRelevantQueryCoverage().totalQueries() != result.queryCount()
				|| !new LinkedHashSet<>(result.scenarios().keySet())
						.equals(EXPECTED_SCENARIOS)
				|| !result.queries().stream().allMatch(query ->
						new LinkedHashSet<>(query.scenarios().keySet())
								.equals(EXPECTED_SCENARIOS))) {
			throw failure("LONGITUDINAL_SCORING_RESULT_INVALID");
		}
		if (!bindings.evidenceId().equals(result.evidenceId())
				|| !bindings.evidenceManifestSha256()
						.equals(result.evidenceManifestSha256())
				|| !bindings.captureRepositoryRevision()
						.equals(result.captureRepositoryRevision())
				|| !bindings.captureMeasuredAt().equals(result.captureMeasuredAt())
				|| !bindings.querySetId().equals(result.querySetId())
				|| !bindings.querySetSha256().equals(result.querySetSha256())
				|| !bindings.scoringPolicyId().equals(result.scoringPolicyId())
				|| !bindings.scoringPolicySha256().equals(result.scoringPolicySha256())
				|| !bindings.reviewPacketSha256().equals(result.reviewPacketSha256())
				|| !bindings.judgmentsSha256().equals(result.judgmentPacketSha256())
				|| !bindings.reportId().equals(result.reportId())) {
			throw failure("LONGITUDINAL_RUN_LINEAGE_MISMATCH");
		}
		canonicalInstant(result.captureMeasuredAt());
		validateCoverage(result.uniqueRelevantQueryCoverage());
		result.scenarios().values().forEach(
				ProviderQualityComparativeLongitudinalComparison::validateScenario);
		for (QueryScore query : result.queries()) {
			if (query.queryKey() == null || query.queryKey().isBlank()) {
				throw failure("LONGITUDINAL_QUERY_INVALID");
			}
			query.scenarios().values().forEach(
					ProviderQualityComparativeLongitudinalComparison::validateQueryScenario);
		}
		if (new LinkedHashSet<>(queryKeys(result)).size() != result.queryCount()) {
			throw failure("LONGITUDINAL_QUERY_INVALID");
		}
	}

	private static void validateDistinctRuns(List<VerifiedRun> runs) {
		Set<String> sealIds = new LinkedHashSet<>();
		Set<String> evidenceIds = new LinkedHashSet<>();
		Set<String> reportIds = new LinkedHashSet<>();
		Set<Instant> captureTimes = new LinkedHashSet<>();
		for (VerifiedRun run : runs) {
			ScoringResult result = run.result();
			if (!sealIds.add(run.seal().sealId())
					|| !evidenceIds.add(result.evidenceId())
					|| !reportIds.add(result.reportId())) {
				throw failure("LONGITUDINAL_RUN_IDENTITY_NOT_DISTINCT");
			}
			if (!captureTimes.add(canonicalInstant(result.captureMeasuredAt()))) {
				throw failure("LONGITUDINAL_CHRONOLOGY_INVALID");
			}
		}
	}

	private static void validateCompatibility(
			ScoringResult cohort, List<String> cohortQueryKeys, ScoringResult candidate) {
		if (!cohort.captureRepositoryRevision()
				.equals(candidate.captureRepositoryRevision())) {
			throw failure("LONGITUDINAL_REPOSITORY_REVISION_MISMATCH");
		}
		if (!cohort.querySetId().equals(candidate.querySetId())
				|| !cohort.querySetSha256().equals(candidate.querySetSha256())) {
			throw failure("LONGITUDINAL_QUERY_SET_MISMATCH");
		}
		if (!cohort.scoringPolicyId().equals(candidate.scoringPolicyId())
				|| !cohort.scoringPolicySha256().equals(candidate.scoringPolicySha256())) {
			throw failure("LONGITUDINAL_SCORING_POLICY_MISMATCH");
		}
		if (cohort.queryCount() != candidate.queryCount()
				|| !cohortQueryKeys.equals(queryKeys(candidate))) {
			throw failure("LONGITUDINAL_QUERY_PARTITION_MISMATCH");
		}
	}

	private static RunSnapshot runSnapshot(int ordinal, VerifiedRun run) {
		ScoringResult result = run.result();
		Bindings bindings = run.seal().bindings();
		Map<Scenario, ScenarioSummary> scenarios = new EnumMap<>(Scenario.class);
		scenarios.putAll(result.scenarios());
		return new RunSnapshot(
				ordinal,
				new RunReference(
						run.seal().sealId(),
						run.seal().sealSha256(),
						result.evidenceId(),
						result.evidenceManifestSha256(),
						result.reportId(),
						bindings.reportManifestSha256(),
						result.captureMeasuredAt()),
				result.uniqueRelevantQueryCoverage(),
				scenarios,
				result.queries());
	}

	private static Transition transition(
			int transitionOrdinal, VerifiedRun earlier, VerifiedRun later) {
		ScoringResult baseline = earlier.result();
		ScoringResult current = later.result();
		Map<Scenario, ScenarioChange> scenarioChanges = new EnumMap<>(Scenario.class);
		for (Scenario scenario : Scenario.values()) {
			scenarioChanges.put(
					scenario,
					scenarioChange(
							baseline.scenarios().get(scenario),
							current.scenarios().get(scenario)));
		}
		List<QueryChange> queryChanges = new ArrayList<>(baseline.queryCount());
		for (int index = 0; index < baseline.queryCount(); index++) {
			QueryScore baselineQuery = baseline.queries().get(index);
			QueryScore currentQuery = current.queries().get(index);
			Map<Scenario, QueryScenarioChange> changes = new EnumMap<>(Scenario.class);
			for (Scenario scenario : Scenario.values()) {
				changes.put(
						scenario,
						queryScenarioChange(
								baselineQuery.scenarios().get(scenario),
								currentQuery.scenarios().get(scenario)));
			}
			queryChanges.add(new QueryChange(baselineQuery.queryKey(), changes));
		}
		return new Transition(
				transitionOrdinal,
				transitionOrdinal + 1,
				earlier.seal().sealId(),
				later.seal().sealId(),
				Duration.between(
						canonicalInstant(baseline.captureMeasuredAt()),
						canonicalInstant(current.captureMeasuredAt())).toString(),
				coverageChange(
						baseline.uniqueRelevantQueryCoverage(),
						current.uniqueRelevantQueryCoverage()),
				scenarioChanges,
				queryChanges);
	}

	private static ScenarioChange scenarioChange(
			ScenarioSummary baseline, ScenarioSummary current) {
		return new ScenarioChange(
				new RankingChange(
						count(
								baseline.ranking().relevanceApplicableQueryCount(),
								current.ranking().relevanceApplicableQueryCount()),
						count(
								baseline.ranking().noRelevantGoldQueryCount(),
								current.ranking().noRelevantGoldQueryCount()),
						rate(
								baseline.ranking().macroRecall(),
								current.ranking().macroRecall()),
						rate(
								baseline.ranking().macroNdcg(),
								current.ranking().macroNdcg()),
						rate(
								baseline.ranking().macroPrecision(),
								current.ranking().macroPrecision()),
						rate(
								baseline.ranking().meanReciprocalRank(),
								current.ranking().meanReciprocalRank())),
				deduplicationChange(baseline.deduplication(), current.deduplication()),
				metadataChange(baseline.metadataRecovery(), current.metadataRecovery()),
				mustSeparateChange(baseline.mustSeparate(), current.mustSeparate()));
	}

	private static QueryScenarioChange queryScenarioChange(
			QueryScenarioScore baseline, QueryScenarioScore current) {
		return new QueryScenarioChange(
				count(baseline.rankedResultCount(), current.rankedResultCount()),
				count(baseline.creditedGoldWorkCount(), current.creditedGoldWorkCount()),
				queryRankingChange(baseline.ranking(), current.ranking()),
				deduplicationChange(baseline.deduplication(), current.deduplication()),
				metadataChange(baseline.metadataRecovery(), current.metadataRecovery()),
				mustSeparateChange(baseline.mustSeparate(), current.mustSeparate()));
	}

	private static QueryRankingChange queryRankingChange(
			RankingScore baseline, RankingScore current) {
		return new QueryRankingChange(
				count(baseline.relevantGoldWorkCount(), current.relevantGoldWorkCount()),
				rate(baseline.recall(), current.recall()),
				rate(baseline.ndcg(), current.ndcg()),
				rate(baseline.precision(), current.precision()),
				rate(baseline.reciprocalRank(), current.reciprocalRank()));
	}

	private static DeduplicationChange deduplicationChange(
			DeduplicationScore baseline, DeduplicationScore current) {
		return new DeduplicationChange(
				count(baseline.candidateCount(), current.candidateCount()),
				count(baseline.evaluatedPairCount(), current.evaluatedPairCount()),
				count(baseline.truePositives(), current.truePositives()),
				count(baseline.falsePositives(), current.falsePositives()),
				count(baseline.falseNegatives(), current.falseNegatives()),
				count(baseline.trueNegatives(), current.trueNegatives()),
				rate(baseline.precision(), current.precision()),
				rate(baseline.recall(), current.recall()),
				rate(baseline.f1(), current.f1()));
	}

	private static MetadataRecoveryChange metadataChange(
			ExpectedFieldRecovery baseline, ExpectedFieldRecovery current) {
		Map<MetadataField, FieldRecoveryChange> fields = new EnumMap<>(MetadataField.class);
		for (MetadataField field : MetadataField.values()) {
			FieldRecovery earlier = baseline.fields().get(field);
			FieldRecovery later = current.fields().get(field);
			fields.put(field, new FieldRecoveryChange(
					count(earlier.expectedCount(), later.expectedCount()),
					count(earlier.recoveredCount(), later.recoveredCount()),
					rate(earlier.recoveryRate(), later.recoveryRate())));
		}
		return new MetadataRecoveryChange(
				count(baseline.creditedGoldWorks(), current.creditedGoldWorks()),
				count(
						baseline.goldWorksWithExpectations(),
						current.goldWorksWithExpectations()),
				count(baseline.expectedFieldCount(), current.expectedFieldCount()),
				count(baseline.recoveredFieldCount(), current.recoveredFieldCount()),
				rate(baseline.recoveryRate(), current.recoveryRate()),
				fields);
	}

	private static MustSeparateChange mustSeparateChange(
			MustSeparateMeasurement baseline, MustSeparateMeasurement current) {
		return new MustSeparateChange(
				count(baseline.applicablePairs(), current.applicablePairs()),
				count(baseline.violations(), current.violations()),
				rate(baseline.passRate(), current.passRate()));
	}

	private static CoverageChange coverageChange(
			UniqueRelevantQueryCoverage baseline,
			UniqueRelevantQueryCoverage current) {
		Set<String> baselineKeys = new TreeSet<>(baseline.queryKeys());
		Set<String> currentKeys = new TreeSet<>(current.queryKeys());
		Set<String> added = new TreeSet<>(currentKeys);
		added.removeAll(baselineKeys);
		Set<String> removed = new TreeSet<>(baselineKeys);
		removed.removeAll(currentKeys);
		return new CoverageChange(
				count(baseline.coveredQueries(), current.coveredQueries()),
				count(baseline.totalQueries(), current.totalQueries()),
				rate(baseline.rate(), current.rate()),
				List.copyOf(added),
				List.copyOf(removed));
	}

	private static void validateCoverage(UniqueRelevantQueryCoverage coverage) {
		if (coverage.coveredQueries() < 0
				|| coverage.totalQueries() < 1
				|| coverage.coveredQueries() > coverage.totalQueries()
				|| coverage.queryKeys().size() != coverage.coveredQueries()
				|| new LinkedHashSet<>(coverage.queryKeys()).size()
						!= coverage.queryKeys().size()) {
			throw failure("LONGITUDINAL_COVERAGE_INVALID");
		}
		requireRate(coverage.rate());
	}

	private static void validateScenario(ScenarioSummary summary) {
		if (summary == null) {
			throw failure("LONGITUDINAL_SCENARIO_INVALID");
		}
		RankingSummaryScore ranking = summary.ranking();
		if (ranking.queryCount() < 1
				|| ranking.relevanceApplicableQueryCount() < 0
				|| ranking.noRelevantGoldQueryCount() < 0
				|| ranking.relevanceApplicableQueryCount()
						+ ranking.noRelevantGoldQueryCount() != ranking.queryCount()) {
			throw failure("LONGITUDINAL_SCENARIO_INVALID");
		}
		requireNullableRate(ranking.macroRecall());
		requireNullableRate(ranking.macroNdcg());
		requireRate(ranking.macroPrecision());
		requireNullableRate(ranking.meanReciprocalRank());
		validateDeduplication(summary.deduplication());
		validateMetadata(summary.metadataRecovery());
		validateMustSeparate(summary.mustSeparate());
	}

	private static void validateQueryScenario(QueryScenarioScore score) {
		if (score == null
				|| score.rankedResultCount() < 0
				|| score.creditedGoldWorkCount() < 0
				|| score.creditedGoldWorkCount() > score.rankedResultCount()
				|| score.ranking().relevantGoldWorkCount() < 0) {
			throw failure("LONGITUDINAL_QUERY_SCENARIO_INVALID");
		}
		requireNullableRate(score.ranking().recall());
		requireNullableRate(score.ranking().ndcg());
		requireRate(score.ranking().precision());
		requireNullableRate(score.ranking().reciprocalRank());
		validateDeduplication(score.deduplication());
		validateMetadata(score.metadataRecovery());
		validateMustSeparate(score.mustSeparate());
	}

	private static void validateDeduplication(DeduplicationScore value) {
		if (value == null
				|| value.candidateCount() < 0
				|| value.evaluatedPairCount() < 0
				|| value.truePositives() < 0
				|| value.falsePositives() < 0
				|| value.falseNegatives() < 0
				|| value.trueNegatives() < 0) {
			throw failure("LONGITUDINAL_DEDUPLICATION_INVALID");
		}
		requireNullableRate(value.precision());
		requireNullableRate(value.recall());
		requireNullableRate(value.f1());
	}

	private static void validateMetadata(ExpectedFieldRecovery value) {
		if (value == null
				|| value.creditedGoldWorks() < 0
				|| value.goldWorksWithExpectations() < 0
				|| value.expectedFieldCount() < 0
				|| value.recoveredFieldCount() < 0
				|| value.recoveredFieldCount() > value.expectedFieldCount()
				|| !new LinkedHashSet<>(value.fields().keySet())
						.equals(EXPECTED_METADATA_FIELDS)) {
			throw failure("LONGITUDINAL_METADATA_INVALID");
		}
		requireNullableRate(value.recoveryRate());
		for (FieldRecovery field : value.fields().values()) {
			if (field.expectedCount() < 0
					|| field.recoveredCount() < 0
					|| field.recoveredCount() > field.expectedCount()) {
				throw failure("LONGITUDINAL_METADATA_INVALID");
			}
			requireNullableRate(field.recoveryRate());
		}
	}

	private static void validateMustSeparate(MustSeparateMeasurement value) {
		if (value == null
				|| value.applicablePairs() < 0
				|| value.violations() < 0
				|| value.violations() > value.applicablePairs()) {
			throw failure("LONGITUDINAL_MUST_SEPARATE_INVALID");
		}
		requireNullableRate(value.passRate());
	}

	private static CountChange count(long baseline, long current) {
		try {
			return new CountChange(baseline, current, Math.subtractExact(current, baseline));
		}
		catch (ArithmeticException exception) {
			throw failure("LONGITUDINAL_COUNT_DELTA_OVERFLOW");
		}
	}

	private static RateChange rate(Double baseline, Double current) {
		Double normalizedBaseline = normalizeZero(baseline);
		Double normalizedCurrent = normalizeZero(current);
		BigDecimal delta = normalizedBaseline == null || normalizedCurrent == null
				? null
				: BigDecimal.valueOf(normalizedCurrent)
						.subtract(BigDecimal.valueOf(normalizedBaseline))
						.stripTrailingZeros();
		if (delta != null && delta.signum() == 0) {
			delta = BigDecimal.ZERO;
		}
		return new RateChange(normalizedBaseline, normalizedCurrent, delta);
	}

	private static Double normalizeZero(Double value) {
		if (value == null) {
			return null;
		}
		return value.doubleValue() == 0.0d ? Double.valueOf(0.0d) : value;
	}

	private static List<String> queryKeys(ScoringResult result) {
		return result.queries().stream().map(QueryScore::queryKey).toList();
	}

	private static Instant canonicalInstant(String value) {
		try {
			Instant parsed = Instant.parse(value);
			if (!parsed.toString().equals(value)) {
				throw failure("LONGITUDINAL_CAPTURE_TIME_INVALID");
			}
			return parsed;
		}
		catch (ComparisonException exception) {
			throw exception;
		}
		catch (RuntimeException exception) {
			throw failure("LONGITUDINAL_CAPTURE_TIME_INVALID");
		}
	}

	private static void requireRate(double value) {
		if (!Double.isFinite(value) || value < 0.0d || value > 1.0d) {
			throw failure("LONGITUDINAL_RATE_INVALID");
		}
	}

	private static void requireNullableRate(Double value) {
		if (value != null) {
			requireRate(value);
		}
	}

	private static String comparisonId(List<VerifiedRun> runs) {
		MessageDigest digest = sha256Digest();
		digest.update(IDENTITY_DOMAIN);
		for (VerifiedRun run : runs) {
			for (String value : List.of(run.seal().sealId(), run.seal().sealSha256())) {
				digest.update((byte) 0);
				digest.update(value.getBytes(StandardCharsets.US_ASCII));
			}
		}
		return COMPARISON_ID_PREFIX + HexFormat.of().formatHex(digest.digest());
	}

	private static MessageDigest sha256Digest() {
		try {
			return MessageDigest.getInstance("SHA-256");
		}
		catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException("LONGITUDINAL_SHA256_UNAVAILABLE", exception);
		}
	}

	private static ComparisonException failure(String diagnostic) {
		return new ComparisonException(diagnostic);
	}

	record VerifiedRun(VerifiedRunSeal seal, ScoringResult result) {

		VerifiedRun {
			Objects.requireNonNull(seal, "seal");
			Objects.requireNonNull(result, "result");
		}
	}

	record Comparison(
			int schemaVersion,
			String protocolId,
			String comparisonId,
			int runCount,
			String captureRepositoryRevision,
			String querySetId,
			String querySetSha256,
			String scoringPolicyId,
			String scoringPolicySha256,
			int queryCount,
			List<RunSnapshot> runs,
			List<Transition> transitions,
			Use use,
			boolean readerFacing,
			boolean defaultEnablementDecision) {

		Comparison {
			if (schemaVersion != SCHEMA_VERSION
					|| !PROTOCOL_ID.equals(protocolId)
					|| comparisonId == null
					|| !comparisonId.startsWith(COMPARISON_ID_PREFIX)
					|| comparisonId.length() != COMPARISON_ID_PREFIX.length() + 64
					|| runCount < MINIMUM_RUNS
					|| runCount > MAXIMUM_RUNS
					|| queryCount < 1
					|| use != Use.OBSERVATIONAL_ONLY
					|| readerFacing
					|| defaultEnablementDecision) {
				throw failure("LONGITUDINAL_COMPARISON_INVALID");
			}
			Objects.requireNonNull(captureRepositoryRevision, "captureRepositoryRevision");
			Objects.requireNonNull(querySetId, "querySetId");
			requireSha256(querySetSha256);
			Objects.requireNonNull(scoringPolicyId, "scoringPolicyId");
			requireSha256(scoringPolicySha256);
			runs = List.copyOf(Objects.requireNonNull(runs, "runs"));
			transitions = List.copyOf(Objects.requireNonNull(transitions, "transitions"));
			if (runs.size() != runCount || transitions.size() != runCount - 1) {
				throw failure("LONGITUDINAL_COMPARISON_INVALID");
			}
		}
	}

	record RunSnapshot(
			int ordinal,
			RunReference run,
			UniqueRelevantQueryCoverage europePmcUniqueRelevantQueryCoverage,
			Map<Scenario, ScenarioSummary> scenarios,
			List<QueryScore> queries) {

		RunSnapshot {
			if (ordinal < 1) {
				throw failure("LONGITUDINAL_RUN_SNAPSHOT_INVALID");
			}
			Objects.requireNonNull(run, "run");
			Objects.requireNonNull(
					europePmcUniqueRelevantQueryCoverage,
					"europePmcUniqueRelevantQueryCoverage");
			Map<Scenario, ScenarioSummary> copied = new EnumMap<>(Scenario.class);
			copied.putAll(Objects.requireNonNull(scenarios, "scenarios"));
			if (!copied.keySet().equals(EXPECTED_SCENARIOS)) {
				throw failure("LONGITUDINAL_SCENARIO_INVALID");
			}
			scenarios = Collections.unmodifiableMap(copied);
			queries = List.copyOf(Objects.requireNonNull(queries, "queries"));
		}
	}

	record RunReference(
			String runSealId,
			String runSealSha256,
			String evidenceId,
			String evidenceManifestSha256,
			String reportId,
			String reportManifestSha256,
			String captureMeasuredAt) {

		RunReference {
			if (runSealId == null
					|| !runSealId.startsWith(
							ProviderQualityComparativeRunSealBundle.RUN_SEAL_ID_PREFIX)) {
				throw failure("LONGITUDINAL_RUN_REFERENCE_INVALID");
			}
			requireSha256(runSealSha256);
			Objects.requireNonNull(evidenceId, "evidenceId");
			requireSha256(evidenceManifestSha256);
			Objects.requireNonNull(reportId, "reportId");
			requireSha256(reportManifestSha256);
			canonicalInstant(captureMeasuredAt);
		}
	}

	record Transition(
			int fromOrdinal,
			int toOrdinal,
			String fromRunSealId,
			String toRunSealId,
			String elapsed,
			CoverageChange europePmcUniqueRelevantQueryCoverage,
			Map<Scenario, ScenarioChange> scenarios,
			List<QueryChange> queries) {

		Transition {
			if (fromOrdinal < 1 || toOrdinal != fromOrdinal + 1) {
				throw failure("LONGITUDINAL_TRANSITION_INVALID");
			}
			Objects.requireNonNull(fromRunSealId, "fromRunSealId");
			Objects.requireNonNull(toRunSealId, "toRunSealId");
			Objects.requireNonNull(elapsed, "elapsed");
			Objects.requireNonNull(
					europePmcUniqueRelevantQueryCoverage,
					"europePmcUniqueRelevantQueryCoverage");
			Map<Scenario, ScenarioChange> copied = new EnumMap<>(Scenario.class);
			copied.putAll(Objects.requireNonNull(scenarios, "scenarios"));
			if (!copied.keySet().equals(EXPECTED_SCENARIOS)) {
				throw failure("LONGITUDINAL_SCENARIO_INVALID");
			}
			scenarios = Collections.unmodifiableMap(copied);
			queries = List.copyOf(Objects.requireNonNull(queries, "queries"));
		}
	}

	record QueryChange(
			String queryKey,
			Map<Scenario, QueryScenarioChange> scenarios) {

		QueryChange {
			Objects.requireNonNull(queryKey, "queryKey");
			Map<Scenario, QueryScenarioChange> copied = new EnumMap<>(Scenario.class);
			copied.putAll(Objects.requireNonNull(scenarios, "scenarios"));
			if (!copied.keySet().equals(EXPECTED_SCENARIOS)) {
				throw failure("LONGITUDINAL_SCENARIO_INVALID");
			}
			scenarios = Collections.unmodifiableMap(copied);
		}
	}

	record QueryScenarioChange(
			CountChange rankedResultCount,
			CountChange creditedGoldWorkCount,
			QueryRankingChange ranking,
			DeduplicationChange deduplication,
			MetadataRecoveryChange metadataRecovery,
			MustSeparateChange mustSeparate) {
	}

	record QueryRankingChange(
			CountChange relevantGoldWorkCount,
			RateChange recall,
			RateChange ndcg,
			RateChange precision,
			RateChange reciprocalRank) {
	}

	record ScenarioChange(
			RankingChange ranking,
			DeduplicationChange deduplication,
			MetadataRecoveryChange metadataRecovery,
			MustSeparateChange mustSeparate) {
	}

	record RankingChange(
			CountChange relevanceApplicableQueries,
			CountChange noRelevantGoldQueries,
			RateChange macroRecall,
			RateChange macroNdcg,
			RateChange macroPrecision,
			RateChange meanReciprocalRank) {
	}

	record DeduplicationChange(
			CountChange candidateCount,
			CountChange evaluatedPairCount,
			CountChange truePositives,
			CountChange falsePositives,
			CountChange falseNegatives,
			CountChange trueNegatives,
			RateChange precision,
			RateChange recall,
			RateChange f1) {
	}

	record MetadataRecoveryChange(
			CountChange creditedGoldWorks,
			CountChange goldWorksWithExpectations,
			CountChange expectedFieldCount,
			CountChange recoveredFieldCount,
			RateChange recoveryRate,
			Map<MetadataField, FieldRecoveryChange> fields) {

		MetadataRecoveryChange {
			Map<MetadataField, FieldRecoveryChange> copied =
					new EnumMap<>(MetadataField.class);
			copied.putAll(Objects.requireNonNull(fields, "fields"));
			if (!copied.keySet().equals(EXPECTED_METADATA_FIELDS)) {
				throw failure("LONGITUDINAL_METADATA_INVALID");
			}
			fields = Collections.unmodifiableMap(copied);
		}
	}

	record FieldRecoveryChange(
			CountChange expectedCount,
			CountChange recoveredCount,
			RateChange recoveryRate) {
	}

	record MustSeparateChange(
			CountChange applicablePairs,
			CountChange violations,
			RateChange passRate) {
	}

	record CoverageChange(
			CountChange coveredQueries,
			CountChange totalQueries,
			RateChange rate,
			List<String> addedQueryKeys,
			List<String> removedQueryKeys) {

		CoverageChange {
			addedQueryKeys = List.copyOf(Objects.requireNonNull(addedQueryKeys, "addedQueryKeys"));
			removedQueryKeys = List.copyOf(
					Objects.requireNonNull(removedQueryKeys, "removedQueryKeys"));
		}
	}

	record CountChange(long baseline, long current, long currentMinusBaseline) {
	}

	record RateChange(
			Double baseline,
			Double current,
			BigDecimal currentMinusBaseline) {
	}

	enum Use {
		OBSERVATIONAL_ONLY
	}

	static final class ComparisonException extends IllegalArgumentException {

		private ComparisonException(String diagnostic) {
			super(diagnostic);
		}
	}

	private static void requireSha256(String value) {
		if (value == null || !SHA256.matcher(value).matches()) {
			throw failure("LONGITUDINAL_SHA256_INVALID");
		}
	}
}
