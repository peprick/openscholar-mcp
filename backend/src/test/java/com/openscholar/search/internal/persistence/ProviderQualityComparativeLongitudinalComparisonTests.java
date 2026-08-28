package com.openscholar.search.internal.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.openscholar.search.internal.persistence.ProviderQualityComparativeLongitudinalComparison.Comparison;
import com.openscholar.search.internal.persistence.ProviderQualityComparativeLongitudinalComparison.ComparisonException;
import com.openscholar.search.internal.persistence.ProviderQualityComparativeLongitudinalComparison.RateChange;
import com.openscholar.search.internal.persistence.ProviderQualityComparativeLongitudinalComparison.VerifiedRun;
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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

class ProviderQualityComparativeLongitudinalComparisonTests {

	private static final ObjectMapper OBJECT_MAPPER = JsonMapper.builder().build();
	private static final String REVISION = "1".repeat(40);
	private static final String QUERY_SET_ID = "synthetic-longitudinal-query-set";
	private static final String QUERY_SET_SHA256 = digest(10);
	private static final String POLICY_ID = "synthetic-longitudinal-policy";
	private static final String POLICY_SHA256 = digest(11);
	private static final List<String> QUERY_KEYS = List.of("topic-alpha", "topic-beta");
	private static final Cohort COHORT = new Cohort(
			REVISION,
			QUERY_SET_ID,
			QUERY_SET_SHA256,
			POLICY_ID,
			POLICY_SHA256,
			QUERY_KEYS);
	private static final String EXPECTED_COMPARISON_ID =
			"provider-quality-comparative-longitudinal-v1-"
					+ "6e45c01f8591ab1ec4c16dbbf8c8b065e456bf778b372482d1c9c93d60125407";

	@TempDir
	private Path temporaryDirectory;

	@Test
	void ordersThreeRunsChronologicallyAndRetainsAnIntermediateReversal() {
		VerifiedRun first = run(1, "2026-08-01T00:00:00Z", 0.4d, 0.4d,
				COHORT, Path.of("first-location"));
		VerifiedRun middle = run(2, "2026-08-02T00:00:00Z", 0.8d, 0.8d,
				COHORT, Path.of("middle-location"));
		VerifiedRun latest = run(3, "2026-08-03T00:00:00Z", 0.6d, 0.6d,
				COHORT, Path.of("latest-location"));

		Comparison comparison = ProviderQualityComparativeLongitudinalComparison.compare(
				List.of(latest, first, middle));

		assertThat(comparison.runs())
				.extracting(snapshot -> snapshot.run().captureMeasuredAt())
				.containsExactly(
						"2026-08-01T00:00:00Z",
						"2026-08-02T00:00:00Z",
						"2026-08-03T00:00:00Z");
		assertThat(comparison.runs())
				.extracting(snapshot -> snapshot.ordinal())
				.containsExactly(1, 2, 3);
		assertThat(comparison.transitions())
				.extracting(transition -> transition.elapsed())
				.containsExactly("PT24H", "PT24H");

		RateChange rise = comparison.transitions().get(0).scenarios().get(Scenario.FUSED)
				.ranking().macroRecall();
		RateChange reversal = comparison.transitions().get(1).scenarios().get(Scenario.FUSED)
				.ranking().macroRecall();
		assertThat(rise)
				.extracting(RateChange::baseline, RateChange::current,
						RateChange::currentMinusBaseline)
				.containsExactly(0.4d, 0.8d, new BigDecimal("0.4"));
		assertThat(reversal)
				.extracting(RateChange::baseline, RateChange::current,
						RateChange::currentMinusBaseline)
				.containsExactly(0.8d, 0.6d, new BigDecimal("-0.2"));
		assertThat(comparison.transitions()).hasSize(2);
		assertThat(comparison.use())
				.isEqualTo(ProviderQualityComparativeLongitudinalComparison.Use.OBSERVATIONAL_ONLY);
		assertThat(comparison.readerFacing()).isFalse();
		assertThat(comparison.defaultEnablementDecision()).isFalse();
	}

	@Test
	void identityIsPinnedAndIndependentOfInputPermutationAndSourcePaths() {
		List<VerifiedRun> original = List.of(
				run(1, "2026-08-01T00:00:00Z", 0.4d, 0.4d,
						COHORT, Path.of("operator-a/run-one")),
				run(2, "2026-08-02T00:00:00Z", 0.8d, 0.8d,
						COHORT, Path.of("operator-a/run-two")),
				run(3, "2026-08-03T00:00:00Z", 0.6d, 0.6d,
						COHORT, Path.of("operator-a/run-three")));
		List<VerifiedRun> relocatedAndPermuted = List.of(
				relocate(original.get(2), Path.of("operator-b/copied-three")),
				relocate(original.get(0), Path.of("operator-b/copied-one")),
				relocate(original.get(1), Path.of("operator-b/copied-two")));

		String originalId = ProviderQualityComparativeLongitudinalComparison.compare(original)
				.comparisonId();
		String relocatedId = ProviderQualityComparativeLongitudinalComparison
				.compare(relocatedAndPermuted).comparisonId();

		assertThat(originalId).isEqualTo(EXPECTED_COMPARISON_ID);
		assertThat(relocatedId).isEqualTo(originalId);
		assertThat(originalId)
				.startsWith(ProviderQualityComparativeLongitudinalComparison.COMPARISON_ID_PREFIX)
				.hasSize(
						ProviderQualityComparativeLongitudinalComparison.COMPARISON_ID_PREFIX.length()
								+ 64);
	}

	@Test
	void rejectsRepositoryQuerySetPolicyAndQueryPartitionMismatches() {
		VerifiedRun baseline = run(1, "2026-08-01T00:00:00Z", 0.4d, 0.4d,
				COHORT, Path.of("baseline"));

		assertComparisonFailure(
				baseline,
				run(2, "2026-08-02T00:00:00Z", 0.5d, 0.5d,
						withRevision("2".repeat(40)), Path.of("revision-mismatch")),
				"LONGITUDINAL_REPOSITORY_REVISION_MISMATCH");
		assertComparisonFailure(
				baseline,
				run(2, "2026-08-02T00:00:00Z", 0.5d, 0.5d,
						withQuerySet("other-query-set", digest(20)),
						Path.of("query-set-mismatch")),
				"LONGITUDINAL_QUERY_SET_MISMATCH");
		assertComparisonFailure(
				baseline,
				run(2, "2026-08-02T00:00:00Z", 0.5d, 0.5d,
						withPolicy("other-policy", digest(21)),
						Path.of("policy-mismatch")),
				"LONGITUDINAL_SCORING_POLICY_MISMATCH");
		assertComparisonFailure(
				baseline,
				run(2, "2026-08-02T00:00:00Z", 0.5d, 0.5d,
						withQueryKeys(List.of("topic-beta", "topic-alpha")),
						Path.of("query-order-mismatch")),
				"LONGITUDINAL_QUERY_PARTITION_MISMATCH");
		assertComparisonFailure(
				baseline,
				run(2, "2026-08-02T00:00:00Z", 0.5d, 0.5d,
						withQueryKeys(List.of("only-one-topic")),
						Path.of("query-count-mismatch")),
				"LONGITUDINAL_QUERY_PARTITION_MISMATCH");
	}

	@Test
	void rejectsInsufficientDuplicateAndEqualTimeRuns() {
		VerifiedRun first = run(1, "2026-08-01T00:00:00Z", 0.4d, 0.4d,
				COHORT, Path.of("first"));
		VerifiedRun second = run(2, "2026-08-02T00:00:00Z", 0.5d, 0.5d,
				COHORT, Path.of("second"));

		assertThatThrownBy(() -> ProviderQualityComparativeLongitudinalComparison.compare(
				List.of(first)))
				.isInstanceOf(ComparisonException.class)
				.hasMessage("LONGITUDINAL_RUN_COUNT_INVALID");
		assertThatThrownBy(() -> ProviderQualityComparativeLongitudinalComparison.compare(
				List.of(first, first)))
				.isInstanceOf(ComparisonException.class)
				.hasMessage("LONGITUDINAL_RUN_IDENTITY_NOT_DISTINCT");
		assertThatThrownBy(() -> ProviderQualityComparativeLongitudinalComparison.compare(
				List.of(
						first,
						run(3, "2026-08-01T00:00:00Z", 0.6d, 0.6d,
								COHORT, Path.of("equal-time")))))
				.isInstanceOf(ComparisonException.class)
				.hasMessage("LONGITUDINAL_CHRONOLOGY_INVALID");

		assertThat(ProviderQualityComparativeLongitudinalComparison.compare(
				List.of(second, first)).runCount()).isEqualTo(2);
	}

	@Test
	void rejectsSealAndResultLineageMismatches() {
		VerifiedRun valid = run(1, "2026-08-01T00:00:00Z", 0.4d, 0.4d,
				COHORT, Path.of("valid"));
		Bindings original = valid.seal().bindings();
		Bindings wrongReviewPacket = new Bindings(
				original.evidenceId(),
				original.evidenceManifestSha256(),
				original.captureRepositoryRevision(),
				original.captureMeasuredAt(),
				original.querySetId(),
				original.querySetSha256(),
				original.scoringPolicyId(),
				original.scoringPolicySha256(),
				digest(99),
				original.completedWorksheetSha256(),
				original.judgmentsSha256(),
				original.reportId(),
				original.reportManifestSha256());
		VerifiedRunSeal mismatchedSeal = copySeal(valid.seal(), Path.of("wrong-seal"),
				wrongReviewPacket);

		assertThatThrownBy(() -> ProviderQualityComparativeLongitudinalComparison.verifiedRun(
				mismatchedSeal, valid.result()))
				.isInstanceOf(ComparisonException.class)
				.hasMessage("LONGITUDINAL_RUN_LINEAGE_MISMATCH");

		ScoringResult wrongEvidenceResult = copyResult(
				valid.result(), "different-evidence", valid.result().captureMeasuredAt());
		assertThatThrownBy(() -> ProviderQualityComparativeLongitudinalComparison.verifiedRun(
				valid.seal(), wrongEvidenceResult))
				.isInstanceOf(ComparisonException.class)
				.hasMessage("LONGITUDINAL_RUN_LINEAGE_MISMATCH");

		ScoringResult wrongTimeResult = copyResult(
				valid.result(), valid.result().evidenceId(), "2026-08-01T01:00:00Z");
		assertThatThrownBy(() -> ProviderQualityComparativeLongitudinalComparison.verifiedRun(
				valid.seal(), wrongTimeResult))
				.isInstanceOf(ComparisonException.class)
				.hasMessage("LONGITUDINAL_RUN_LINEAGE_MISMATCH");
	}

	@Test
	void preservesUndefinedRateTransitionsAndNormalizesNegativeZero() {
		VerifiedRun first = run(1, "2026-08-01T00:00:00Z", null, -0.0d,
				COHORT, Path.of("undefined-first"));
		VerifiedRun second = run(2, "2026-08-02T00:00:00Z", 0.25d, 0.0d,
				COHORT, Path.of("defined-middle"));
		VerifiedRun third = run(3, "2026-08-03T00:00:00Z", null, -0.0d,
				COHORT, Path.of("undefined-third"));

		Comparison comparison = ProviderQualityComparativeLongitudinalComparison.compare(
				List.of(third, first, second));
		RateChange becameDefined = comparison.transitions().get(0).scenarios()
				.get(Scenario.FUSED).ranking().macroRecall();
		RateChange becameUndefined = comparison.transitions().get(1).scenarios()
				.get(Scenario.FUSED).ranking().macroRecall();
		RateChange normalizedZero = comparison.transitions().get(0).scenarios()
				.get(Scenario.FUSED).ranking().macroPrecision();

		assertThat(becameDefined.baseline()).isNull();
		assertThat(becameDefined.current()).isEqualTo(0.25d);
		assertThat(becameDefined.currentMinusBaseline()).isNull();
		assertThat(becameUndefined.baseline()).isEqualTo(0.25d);
		assertThat(becameUndefined.current()).isNull();
		assertThat(becameUndefined.currentMinusBaseline()).isNull();
		assertThat(Double.doubleToRawLongBits(normalizedZero.baseline()))
				.isEqualTo(Double.doubleToRawLongBits(0.0d));
		assertThat(Double.doubleToRawLongBits(normalizedZero.current()))
				.isEqualTo(Double.doubleToRawLongBits(0.0d));
		assertThat(normalizedZero.currentMinusBaseline()).isEqualByComparingTo(BigDecimal.ZERO);

		assertThatThrownBy(() -> run(
				4, "2026-08-04T00:00:00Z", 0.5d, Double.NaN,
				COHORT, Path.of("not-finite")))
				.isInstanceOf(ComparisonException.class)
				.hasMessage("LONGITUDINAL_RATE_INVALID");
	}

	@Test
	void reportSchemaIsPrivateObservationalAndNeverSerializesSourcePaths() {
		String pathCanary = "private-source-path-canary";
		Comparison comparison = ProviderQualityComparativeLongitudinalComparison.compare(
				List.of(
						run(1, "2026-08-01T00:00:00Z", 0.4d, 0.4d,
								COHORT, Path.of(pathCanary, "one")),
						run(2, "2026-08-02T00:00:00Z", 0.5d, 0.5d,
								COHORT, Path.of(pathCanary, "two"))));
		Map<String, Object> artifacts =
				ProviderQualityComparativeLongitudinalComparison.artifacts(comparison);
		JsonNode report = OBJECT_MAPPER.valueToTree(artifacts.get(
				ProviderQualityComparativeLongitudinalComparison.REPORT_FILENAME));

		assertThat(artifacts)
				.containsOnlyKeys(ProviderQualityComparativeLongitudinalComparison.REPORT_FILENAME);
		assertFields(report, Set.of(
				"schemaVersion",
				"protocolId",
				"comparisonId",
				"runCount",
				"captureRepositoryRevision",
				"querySetId",
				"querySetSha256",
				"scoringPolicyId",
				"scoringPolicySha256",
				"queryCount",
				"runs",
				"transitions",
				"use",
				"readerFacing",
				"defaultEnablementDecision"));
		assertFields(report.required("runs").get(0), Set.of(
				"ordinal", "run", "europePmcUniqueRelevantQueryCoverage",
				"scenarios", "queries"));
		assertFields(report.required("runs").get(0).required("run"), Set.of(
				"runSealId",
				"runSealSha256",
				"evidenceId",
				"evidenceManifestSha256",
				"reportId",
				"reportManifestSha256",
				"captureMeasuredAt"));
		assertFields(report.required("transitions").get(0), Set.of(
				"fromOrdinal",
				"toOrdinal",
				"fromRunSealId",
				"toRunSealId",
				"elapsed",
				"europePmcUniqueRelevantQueryCoverage",
				"scenarios",
				"queries"));
		assertThat(report.required("use").asString()).isEqualTo("OBSERVATIONAL_ONLY");
		assertThat(report.required("readerFacing").asBoolean()).isFalse();
		assertThat(report.required("defaultEnablementDecision").asBoolean()).isFalse();
		assertThat(OBJECT_MAPPER.writeValueAsString(report))
				.doesNotContain(
						pathCanary,
						"sourceDirectory",
						"title",
						"abstractText",
						"providerRecordId",
						"authors");
	}

	@Test
	void comparisonDoesNotMutateInputsOrTheirSourceDirectories() throws Exception {
		Path firstDirectory = Files.createDirectory(temporaryDirectory.resolve("first-run"));
		Path secondDirectory = Files.createDirectory(temporaryDirectory.resolve("second-run"));
		Path firstSentinel = firstDirectory.resolve("sentinel.bin");
		Path secondSentinel = secondDirectory.resolve("sentinel.bin");
		Files.write(firstSentinel, "first-private-bytes".getBytes(StandardCharsets.UTF_8));
		Files.write(secondSentinel, "second-private-bytes".getBytes(StandardCharsets.UTF_8));
		byte[] firstBefore = Files.readAllBytes(firstSentinel);
		byte[] secondBefore = Files.readAllBytes(secondSentinel);
		List<String> firstEntries = entries(firstDirectory);
		List<String> secondEntries = entries(secondDirectory);
		VerifiedRun first = run(1, "2026-08-01T00:00:00Z", 0.4d, 0.4d,
				COHORT, firstDirectory);
		VerifiedRun second = run(2, "2026-08-02T00:00:00Z", 0.5d, 0.5d,
				COHORT, secondDirectory);
		List<VerifiedRun> supplied = new ArrayList<>(List.of(second, first));
		List<VerifiedRun> suppliedBefore = List.copyOf(supplied);

		Comparison comparison = ProviderQualityComparativeLongitudinalComparison.compare(supplied);

		assertThat(supplied).containsExactlyElementsOf(suppliedBefore);
		assertThat(Files.readAllBytes(firstSentinel)).isEqualTo(firstBefore);
		assertThat(Files.readAllBytes(secondSentinel)).isEqualTo(secondBefore);
		assertThat(entries(firstDirectory)).isEqualTo(firstEntries);
		assertThat(entries(secondDirectory)).isEqualTo(secondEntries);
		assertThatThrownBy(() -> comparison.runs().add(comparison.runs().getFirst()))
				.isInstanceOf(UnsupportedOperationException.class);
		assertThatThrownBy(() -> comparison.transitions().clear())
				.isInstanceOf(UnsupportedOperationException.class);
	}

	private static VerifiedRun run(
			int index,
			String capturedAt,
			Double recall,
			double precision,
			Cohort cohort,
			Path sourceDirectory) {
		String evidenceId = "longitudinal-evidence-" + index;
		String evidenceSha256 = digest(100 + index);
		String judgmentsSha256 = digest(200 + index);
		String reviewPacketSha256 = digest(300 + index);
		String reportId = ProviderQualityComparativeScorer.reportId(
				evidenceSha256, judgmentsSha256, cohort.policySha256());
		Bindings bindings = new Bindings(
				evidenceId,
				evidenceSha256,
				cohort.revision(),
				capturedAt,
				cohort.querySetId(),
				cohort.querySetSha256(),
				cohort.policyId(),
				cohort.policySha256(),
				reviewPacketSha256,
				digest(400 + index),
				judgmentsSha256,
				reportId,
				digest(500 + index));
		VerifiedRunSeal seal = new VerifiedRunSeal(
				sourceDirectory,
				ProviderQualityComparativeRunSealBundle.RUN_SEAL_ID_PREFIX
						+ digest(600 + index),
				digest(700 + index),
				1,
				2,
				bindings,
				List.of());
		ScoringResult result = scoringResult(
				bindings, cohort.queryKeys(), recall, precision);
		return ProviderQualityComparativeLongitudinalComparison.verifiedRun(seal, result);
	}

	private static ScoringResult scoringResult(
			Bindings bindings, List<String> queryKeys, Double recall, double precision) {
		int queryCount = queryKeys.size();
		Map<Scenario, ScenarioSummary> scenarioSummaries = new EnumMap<>(Scenario.class);
		for (Scenario scenario : Scenario.values()) {
			scenarioSummaries.put(
					scenario, scenarioSummary(queryCount, recall, precision));
		}
		List<QueryScore> queries = queryKeys.stream()
				.map(queryKey -> new QueryScore(
						queryKey, queryScenarios(recall, precision)))
				.toList();
		int coveredQueries = recall == null
				? 0
				: Math.min(queryCount, recall >= 0.75d ? 2 : 1);
		List<String> coveredKeys = queryKeys.subList(0, coveredQueries);
		UniqueRelevantQueryCoverage coverage = new UniqueRelevantQueryCoverage(
				coveredQueries,
				queryCount,
				(double) coveredQueries / queryCount,
				coveredKeys);
		return new ScoringResult(
				ProviderQualityComparativeScorer.REPORT_SCHEMA_VERSION,
				bindings.reportId(),
				bindings.evidenceId(),
				bindings.evidenceManifestSha256(),
				bindings.captureRepositoryRevision(),
				bindings.captureMeasuredAt(),
				bindings.judgmentsSha256(),
				bindings.reviewPacketSha256(),
				bindings.querySetId(),
				bindings.querySetSha256(),
				bindings.scoringPolicyId(),
				bindings.scoringPolicySha256(),
				queryCount,
				coverage,
				queries,
				scenarioSummaries,
				false,
				false);
	}

	private static ScenarioSummary scenarioSummary(
			int queryCount, Double recall, double precision) {
		int applicable = recall == null ? 0 : queryCount;
		return new ScenarioSummary(
				new RankingSummaryScore(
						queryCount,
						applicable,
						queryCount - applicable,
						recall,
						recall,
						precision,
						recall),
				deduplication(),
				metadataRecovery(),
				new MustSeparateMeasurement(1, 0, 1.0d));
	}

	private static Map<Scenario, QueryScenarioScore> queryScenarios(
			Double recall, double precision) {
		Map<Scenario, QueryScenarioScore> scenarios = new EnumMap<>(Scenario.class);
		for (Scenario scenario : Scenario.values()) {
			scenarios.put(scenario, new QueryScenarioScore(
					2,
					recall == null ? 0 : 1,
					new RankingScore(
							recall == null ? 0 : 1,
							recall,
							recall,
							precision,
							recall),
					deduplication(),
					metadataRecovery(),
					new MustSeparateMeasurement(1, 0, 1.0d)));
		}
		return scenarios;
	}

	private static DeduplicationScore deduplication() {
		return new DeduplicationScore(2, 1, 1, 0, 0, 0, 1.0d, 1.0d, 1.0d);
	}

	private static ExpectedFieldRecovery metadataRecovery() {
		Map<MetadataField, FieldRecovery> fields = new EnumMap<>(MetadataField.class);
		for (MetadataField field : MetadataField.values()) {
			fields.put(field, new FieldRecovery(1, 1, 1.0d));
		}
		return new ExpectedFieldRecovery(
				1,
				1,
				MetadataField.values().length,
				MetadataField.values().length,
				1.0d,
				fields);
	}

	private static VerifiedRun relocate(VerifiedRun run, Path sourceDirectory) {
		return ProviderQualityComparativeLongitudinalComparison.verifiedRun(
				copySeal(run.seal(), sourceDirectory, run.seal().bindings()),
				run.result());
	}

	private static VerifiedRunSeal copySeal(
			VerifiedRunSeal original, Path sourceDirectory, Bindings bindings) {
		return new VerifiedRunSeal(
				sourceDirectory,
				original.sealId(),
				original.sealSha256(),
				original.payloadBytes(),
				original.totalBytes(),
				bindings,
				original.files());
	}

	private static ScoringResult copyResult(
			ScoringResult original, String evidenceId, String captureMeasuredAt) {
		return new ScoringResult(
				original.schemaVersion(),
				original.reportId(),
				evidenceId,
				original.evidenceManifestSha256(),
				original.captureRepositoryRevision(),
				captureMeasuredAt,
				original.judgmentPacketSha256(),
				original.reviewPacketSha256(),
				original.querySetId(),
				original.querySetSha256(),
				original.scoringPolicyId(),
				original.scoringPolicySha256(),
				original.queryCount(),
				original.uniqueRelevantQueryCoverage(),
				original.queries(),
				original.scenarios(),
				original.readerFacing(),
				original.defaultEnablementDecision());
	}

	private static Cohort withRevision(String revision) {
		return new Cohort(
				revision,
				COHORT.querySetId(),
				COHORT.querySetSha256(),
				COHORT.policyId(),
				COHORT.policySha256(),
				COHORT.queryKeys());
	}

	private static Cohort withQuerySet(String id, String sha256) {
		return new Cohort(
				COHORT.revision(),
				id,
				sha256,
				COHORT.policyId(),
				COHORT.policySha256(),
				COHORT.queryKeys());
	}

	private static Cohort withPolicy(String id, String sha256) {
		return new Cohort(
				COHORT.revision(),
				COHORT.querySetId(),
				COHORT.querySetSha256(),
				id,
				sha256,
				COHORT.queryKeys());
	}

	private static Cohort withQueryKeys(List<String> queryKeys) {
		return new Cohort(
				COHORT.revision(),
				COHORT.querySetId(),
				COHORT.querySetSha256(),
				COHORT.policyId(),
				COHORT.policySha256(),
				queryKeys);
	}

	private static void assertComparisonFailure(
			VerifiedRun first, VerifiedRun second, String diagnostic) {
		assertThatThrownBy(() -> ProviderQualityComparativeLongitudinalComparison.compare(
				List.of(first, second)))
				.isInstanceOf(ComparisonException.class)
				.hasMessage(diagnostic);
	}

	private static void assertFields(JsonNode node, Set<String> expected) {
		assertThat(node).isNotNull();
		assertThat(new LinkedHashSet<>(node.propertyNames())).containsExactlyInAnyOrderElementsOf(
				expected);
	}

	private static List<String> entries(Path directory) throws Exception {
		try (var paths = Files.list(directory)) {
			return paths.map(path -> path.getFileName().toString()).sorted().toList();
		}
	}

	private static String digest(int seed) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(
					("longitudinal-test-" + seed).getBytes(StandardCharsets.US_ASCII)));
		}
		catch (Exception exception) {
			throw new IllegalStateException(exception);
		}
	}

	private record Cohort(
			String revision,
			String querySetId,
			String querySetSha256,
			String policyId,
			String policySha256,
			List<String> queryKeys) {

		private Cohort {
			queryKeys = List.copyOf(queryKeys);
		}
	}
}
