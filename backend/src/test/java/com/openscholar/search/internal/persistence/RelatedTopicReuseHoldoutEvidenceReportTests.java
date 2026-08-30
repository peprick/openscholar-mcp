package com.openscholar.search.internal.persistence;

import static com.openscholar.search.internal.persistence.RelatedTopicReuseHoldoutEvidenceTestFixture.createReport;
import static com.openscholar.search.internal.persistence.RelatedTopicReuseHoldoutEvidenceTestFixture.firstRunEvidence;
import static com.openscholar.search.internal.persistence.RelatedTopicReuseHoldoutEvidenceTestFixture.scoringOutcome;
import static com.openscholar.search.internal.persistence.RelatedTopicReuseHoldoutEvidenceTestFixture.verifyExactReport;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import com.openscholar.search.internal.persistence.RelatedTopicReuseHoldoutRankingSnapshot.FeedbackPool;
import com.openscholar.search.internal.persistence.RelatedTopicReuseHoldoutRankingSnapshot.HiddenPerturbation;
import com.openscholar.search.internal.persistence.RelatedTopicReuseHoldoutRankingSnapshot.QueryRanking;
import com.openscholar.search.internal.persistence.RelatedTopicReuseHoldoutRankingSnapshot.RankedPaper;
import com.openscholar.search.internal.persistence.RelatedTopicReuseHoldoutRankingSnapshot.RankingRun;
import com.openscholar.search.internal.persistence.RelatedTopicReuseHoldoutRankingSnapshot.StructuralCounters;
import com.openscholar.search.internal.persistence.RelatedTopicReuseHoldoutEvaluatorSeal.RepositoryState;
import com.openscholar.search.internal.persistence.RelatedTopicReuseHoldoutEvaluatorSeal.SourceFile;
import com.openscholar.search.internal.persistence.RelatedTopicReuseHoldoutEvaluatorSeal.SourceRole;
import com.openscholar.search.internal.persistence.RelatedTopicReuseHoldoutScoringResult.AggregateMetrics;
import com.openscholar.search.internal.persistence.RelatedTopicReuseHoldoutScoringResult.GateId;
import com.openscholar.search.internal.persistence.RelatedTopicReuseHoldoutScoringResult.GateOutcome;
import com.openscholar.search.internal.persistence.RelatedTopicReuseHoldoutScoringResult.MetricDeltas;
import com.openscholar.search.internal.persistence.RelatedTopicReuseHoldoutScoringResult.QueryScore;
import com.openscholar.search.internal.persistence.RelatedTopicReuseHoldoutScoringResult.RankingMetrics;
import com.openscholar.search.internal.persistence.RelatedTopicReuseHoldoutScoringResult.RankingSummary;
import com.openscholar.search.internal.persistence.RelatedTopicReuseHoldoutScoringResult.ScoreIdentity;
import com.openscholar.search.internal.persistence.RelatedTopicReuseHoldoutScoringResult.StructuralAssessment;

class RelatedTopicReuseHoldoutEvidenceReportTests {

	private static final ObjectMapper JSON = JsonMapper.builder().build();

	private static final String BUNDLE_ID = "bundle-alpha";
	private static final String CORPUS_ID = "corpus-alpha";
	private static final String POLICY_SHA256 = "a".repeat(64);
	private static final String CORPUS_SHA256 = "b".repeat(64);
	private static final String MANIFEST_SHA256 = "c".repeat(64);
	private static final String JUDGMENTS_SHA256 = "d".repeat(64);
	private static final long JUDGMENTS_BYTES = 4096L;
	private static final String CANDIDATE_REVISION = "1".repeat(40);
	private static final String EVALUATOR_REVISION = "2".repeat(40);
	private static final String QUERY_KEY = "query-empty";
	private static final Pattern ENCODED_BITS = Pattern.compile(
			"\\\"[A-Za-z0-9]+Bits\\\":(?:null|\\\"[0-9a-f]{16}\\\")");

	@Test
	void createsDeterministicCanonicalContentAddressedArtifacts() {
		Fixture firstFixture = fixture(CANDIDATE_REVISION, false, 0L);
		Fixture secondFixture = fixture(CANDIDATE_REVISION, false, 0L);
		RelatedTopicReuseHoldoutEvidenceReport first = report(firstFixture);
		RelatedTopicReuseHoldoutEvidenceReport second = report(secondFixture);

		assertThat(first.schemaVersion()).isEqualTo(2);
		assertThat(first.reportId())
				.startsWith("related-topic-reuse-holdout-report-v2-")
				.matches("related-topic-reuse-holdout-report-v2-[0-9a-f]{64}")
				.isEqualTo(second.reportId())
				.isEqualTo("related-topic-reuse-holdout-report-v2-"
						+ "939295d73f15bab1e76777deb26729431136b1298a55111310d3c8dfd914562e");
		assertThat(first.firstRunKey()).isEqualTo("d".repeat(64));
		assertThat(first.freezeSchemaVersion())
				.isEqualTo(RelatedTopicReuseHoldoutGitCollector.FREEZE_SCHEMA_VERSION);
		assertThat(first.sourceInventoryId())
				.isEqualTo(RelatedTopicReuseHoldoutGitCollector.INVENTORY_ID);
		assertThat(first.artifacts().keySet()).containsExactly(
				RelatedTopicReuseHoldoutEvidenceReport.EVALUATOR_SOURCE_FILENAME,
				RelatedTopicReuseHoldoutEvidenceReport.RANKING_SNAPSHOT_FILENAME,
				RelatedTopicReuseHoldoutEvidenceReport.SCORING_RESULT_FILENAME,
				RelatedTopicReuseHoldoutEvidenceReport.EVIDENCE_REPORT_FILENAME);
		assertThat(first.rankingSnapshotJson()).isEqualTo(second.rankingSnapshotJson());
		assertThat(first.evaluatorSourceJson()).isEqualTo(second.evaluatorSourceJson());
		assertThat(first.scoringResultJson()).isEqualTo(second.scoringResultJson());
		assertThat(first.evidenceReportJson()).isEqualTo(second.evidenceReportJson());
		assertThat(first.rankingSnapshotArtifactSha256())
				.isEqualTo(sha256(first.rankingSnapshotJson()));
		assertThat(first.evaluatorSourceArtifactSha256())
				.isEqualTo(sha256(first.evaluatorSourceJson()));
		assertThat(first.scoringResultArtifactSha256())
				.isEqualTo(sha256(first.scoringResultJson()));
		assertThat(first.scoringResultSha256())
				.matches("[0-9a-f]{64}")
				.isNotEqualTo(first.scoringResultArtifactSha256());
		assertThat(first.rankingSnapshotBytes()).isEqualTo(first.rankingSnapshotJson().length);
		assertThat(first.evaluatorSourceBytes()).isEqualTo(first.evaluatorSourceJson().length);
		assertThat(first.scoringResultBytes()).isEqualTo(first.scoringResultJson().length);
		first.artifacts().values().forEach(bytes -> {
			String json = new String(bytes, StandardCharsets.UTF_8);
			assertThat(json).endsWith("\n");
			assertThat(json.substring(0, json.length() - 1)).doesNotContain("\n");
		});

		Map<String, byte[]> returned = first.artifacts();
		assertThatThrownBy(() -> returned.clear())
				.isInstanceOf(UnsupportedOperationException.class);
		byte expectedFirstByte = first.rankingSnapshotJson()[0];
		returned.get(RelatedTopicReuseHoldoutEvidenceReport.RANKING_SNAPSHOT_FILENAME)[0]
				^= 0x7f;
		assertThat(first.rankingSnapshotJson()[0]).isEqualTo(expectedFirstByte);
		byte[] returnedDirectly = first.evidenceReportJson();
		returnedDirectly[0] ^= 0x7f;
		assertThat(first.evidenceReportJson()[0]).isEqualTo((byte) '{');
	}

	@Test
	void preservesRawScoreBitsNegativeZeroAndNullableMetricValues() {
		RelatedTopicReuseHoldoutEvidenceReport report = report(
				fixture(CANDIDATE_REVISION, false, 0L));
		String snapshotJson = new String(
				report.rankingSnapshotJson(), StandardCharsets.UTF_8);
		String resultJson = new String(report.scoringResultJson(), StandardCharsets.UTF_8);

		assertThat(snapshotJson)
				.contains("\"scoreBits\":\"0000000000000000\"")
				.contains("\"scoreBits\":\"8000000000000000\"");
		assertThat(resultJson)
				.contains("\"recallAt10Bits\":null")
				.contains("\"precisionAt1Bits\":\"0000000000000000\"")
				.doesNotContain("\"precisionAt1\":");
		long allBitsFields = Pattern.compile("\\\"[A-Za-z0-9]+Bits\\\":")
				.matcher(resultJson).results().count();
		long correctlyEncodedFields = ENCODED_BITS.matcher(resultJson).results().count();
		assertThat(correctlyEncodedFields).isEqualTo(allBitsFields).isEqualTo(25L);
	}

	@Test
	void projectsEverySnapshotAndScoringComponentFromSentinelDistinctEvidence() {
		RelatedTopicReuseHoldoutRankingSnapshot snapshot = sentinelSnapshot();
		RelatedTopicReuseHoldoutScoringResult result = sentinelScoringResult(snapshot);
		RelatedTopicReuseHoldoutEvidenceReport report =
				createReport(
						seal(
								EVALUATOR_REVISION,
								"sentinel-evaluator",
								snapshot.candidateRevision(),
								"sentinel-candidate"),
						snapshot,
						result);

		assertSnapshotProjection(JSON.readTree(report.rankingSnapshotJson()), snapshot);
		assertResultProjection(JSON.readTree(report.scoringResultJson()), result);
	}

	@Test
	void sourceAndRevisionChangesProduceDifferentReportIds() {
		Fixture baselineFixture = fixture(CANDIDATE_REVISION, false, 0L);
		RelatedTopicReuseHoldoutEvidenceReport baseline = report(baselineFixture);
		RelatedTopicReuseHoldoutEvidenceReport evaluatorRevisionChanged =
				createReport(
						seal("5".repeat(40), "evaluator", CANDIDATE_REVISION, "candidate"),
						baselineFixture.snapshot(), baselineFixture.result());
		RelatedTopicReuseHoldoutEvidenceReport evaluatorSourceChanged =
				createReport(
						seal(EVALUATOR_REVISION, "changed-evaluator", CANDIDATE_REVISION,
								"candidate"),
						baselineFixture.snapshot(), baselineFixture.result());
		RelatedTopicReuseHoldoutEvidenceReport candidateSourceChanged =
				createReport(
						seal(EVALUATOR_REVISION, "evaluator", CANDIDATE_REVISION,
								"changed-candidate"),
						baselineFixture.snapshot(), baselineFixture.result());
		RelatedTopicReuseHoldoutEvidenceReport candidateRevisionChanged = report(
				fixture("8".repeat(40), false, 0L));

		assertThat(List.of(
				baseline.reportId(),
				evaluatorRevisionChanged.reportId(),
				evaluatorSourceChanged.reportId(),
				candidateSourceChanged.reportId(),
				candidateRevisionChanged.reportId())).doesNotHaveDuplicates();
		assertThat(evaluatorRevisionChanged.evaluatorRevision()).isEqualTo("5".repeat(40));
		assertThat(candidateRevisionChanged.candidateRevision()).isEqualTo("8".repeat(40));
	}

	@Test
	void bindsTheDurableRunKeyAndCollectorInventoryIntoBothCanonicalIdentities()
			throws Exception {
		Fixture fixture = fixture(CANDIDATE_REVISION, false, 0L);
		var evaluatorSeal = seal(
				EVALUATOR_REVISION, "evaluator", CANDIDATE_REVISION, "candidate");
		var firstRun = firstRunEvidence(evaluatorSeal, fixture.snapshot());
		var changedRun = firstRunEvidence(
				"e".repeat(64),
				RelatedTopicReuseHoldoutGitCollector.FREEZE_SCHEMA_VERSION,
				RelatedTopicReuseHoldoutGitCollector.INVENTORY_ID,
				evaluatorSeal,
				fixture.snapshot());
		RelatedTopicReuseHoldoutEvidenceReport report =
				RelatedTopicReuseHoldoutEvidenceReport.create(
						scoringOutcome(
								firstRun, fixture.snapshot(), fixture.result()));
		RelatedTopicReuseHoldoutEvidenceReport changed =
				RelatedTopicReuseHoldoutEvidenceReport.create(
						scoringOutcome(
								changedRun, fixture.snapshot(), fixture.result()));

		assertThat(changed.reportId()).isNotEqualTo(report.reportId());
		JsonNode source = JSON.readTree(report.evaluatorSourceJson());
		assertThat(source.required("firstRunKey").asString()).isEqualTo("d".repeat(64));
		assertThat(source.required("freezeSchemaVersion").asInt())
				.isEqualTo(RelatedTopicReuseHoldoutGitCollector.FREEZE_SCHEMA_VERSION);
		assertThat(source.required("sourceInventoryId").asString())
				.isEqualTo(RelatedTopicReuseHoldoutGitCollector.INVENTORY_ID);
		JsonNode bindings = JSON.readTree(report.evidenceReportJson()).required("bindings");
		assertThat(bindings.required("firstRunKey").asString())
				.isEqualTo("d".repeat(64));
		assertThat(bindings.required("sourceInventoryId").asString())
				.isEqualTo(RelatedTopicReuseHoldoutGitCollector.INVENTORY_ID);

		Fixture equalButDistinct = fixture(CANDIDATE_REVISION, false, 0L);
		assertThatThrownBy(() -> RelatedTopicReuseHoldoutEvidenceReport.create(
				scoringOutcome(
						firstRun,
						equalButDistinct.snapshot(),
						equalButDistinct.result())))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("completed first run");
		assertThatThrownBy(() -> firstRunEvidence(
				"d".repeat(64),
				RelatedTopicReuseHoldoutGitCollector.FREEZE_SCHEMA_VERSION,
				"unreviewed-inventory",
				evaluatorSeal,
				fixture.snapshot()))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessage("invalid first-run evidence");
	}

	@Test
	void reportCreationRequiresTheOpaqueScorerIssuedOutcome() {
		assertThat(Arrays.stream(RelatedTopicReuseHoldoutEvidenceReport.class
				.getDeclaredMethods())
				.filter(method -> method.getName().equals("create"))
				.filter(method -> Modifier.isStatic(method.getModifiers()))
				.filter(method -> !Modifier.isPrivate(method.getModifiers()))
				.toList())
				.singleElement()
				.satisfies(method -> assertThat(method.getParameterTypes())
						.containsExactly(RelatedTopicReuseHoldoutScorer
								.VerifiedScoringOutcome.class));
		assertThat(Arrays.stream(RelatedTopicReuseHoldoutEvidenceReport.class
				.getDeclaredMethods())
				.filter(method -> method.getName().equals("verifyExact"))
				.filter(method -> !Modifier.isPrivate(method.getModifiers()))
				.toList())
				.singleElement()
				.satisfies(method -> assertThat(method.getParameterTypes())
						.containsExactly(
								RelatedTopicReuseHoldoutScorer.VerifiedScoringOutcome.class,
								Map.class));
		assertThat(Arrays.stream(RelatedTopicReuseHoldoutScorer.VerifiedScoringOutcome
				.class.getDeclaredConstructors()))
				.allSatisfy(constructor -> assertThat(
						Modifier.isPrivate(constructor.getModifiers())).isTrue());
		assertThat(Arrays.stream(RelatedTopicReuseHoldoutPostgresFirstRunLedger
				.FirstRunEvidence.class.getDeclaredConstructors()))
				.allSatisfy(constructor -> assertThat(
						Modifier.isPrivate(constructor.getModifiers())).isTrue());
	}

	@Test
	void rejectsSnapshotResultIdentityAndStructuralCounterMismatches() {
		Fixture baseline = fixture(CANDIDATE_REVISION, false, 0L);
		RelatedTopicReuseHoldoutScoringResult wrongSnapshotIdentity = scoringResult(
				baseline.snapshot(), "f".repeat(64), false, 0L);
		assertThatThrownBy(() -> createReport(
				seal(EVALUATOR_REVISION, "evaluator", CANDIDATE_REVISION, "candidate"),
				baseline.snapshot(),
				wrongSnapshotIdentity))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("identities must match exactly");

		RelatedTopicReuseHoldoutScoringResult wrongCounters = scoringResult(
				baseline.snapshot(), baseline.snapshot().evidenceSha256(), false, 1L);
		assertThatThrownBy(() -> createReport(
				seal(EVALUATOR_REVISION, "evaluator", CANDIDATE_REVISION, "candidate"),
				baseline.snapshot(),
				wrongCounters))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("structural counters must match exactly");
		assertThatThrownBy(() -> seal(
				"A".repeat(40), "evaluator", CANDIDATE_REVISION, "candidate"))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("revision");
		assertThatThrownBy(() -> createReport(
				seal(
						EVALUATOR_REVISION,
						"evaluator",
						"9".repeat(40),
						"candidate"),
				baseline.snapshot(),
				baseline.result()))
				.hasMessageContaining("candidate revisions must match");
	}

	@Test
	void rejectsEveryOneFieldIdentityAndStructuralCounterMismatch() {
		Fixture baseline = fixture(CANDIDATE_REVISION, false, 0L);
		var evaluatorSeal = seal(
				EVALUATOR_REVISION, "evaluator", CANDIDATE_REVISION, "candidate");
		ScoreIdentity identity = baseline.result().identity();

		for (String field : List.of(
				"evaluationProtocolId",
				"bundleId",
				"corpusId",
				"policySha256",
				"corpusSha256",
				"manifestSha256",
				"judgmentsSha256",
				"rankingSnapshotSha256",
				"judgmentsBytes",
				"candidateRevision",
				"queryOrder")) {
			ScoreIdentity changedIdentity = identityWithChangedField(identity, field);
			List<QueryScore> changedQueries = field.equals("queryOrder")
					? List.of(queryWithKey(
							baseline.result().queries().getFirst(),
							changedIdentity.queryOrder().getFirst()))
					: baseline.result().queries();
			RelatedTopicReuseHoldoutScoringResult changedResult = copyResult(
					baseline.result(), changedIdentity, changedQueries, baseline.result().aggregate());

			assertThatThrownBy(() -> createReport(
					evaluatorSeal, baseline.snapshot(), changedResult))
					.as(field)
					.isInstanceOf(IllegalArgumentException.class)
					.hasMessageContaining("identities must match exactly");
		}

		assertThatThrownBy(() -> identityWithCutoff(identity, 9))
				.as("cutoff is frozen by both identity constructors before cross-binding")
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("invalid holdout score identity");

		for (String field : List.of(
				"providerCallCount", "experimentalSnapshotWriteCount")) {
			AggregateMetrics changedAggregate = aggregateWithCounters(
					baseline.result().aggregate(),
					field.equals("providerCallCount") ? 1L : 0L,
					field.equals("experimentalSnapshotWriteCount") ? 1L : 0L);
			RelatedTopicReuseHoldoutScoringResult changedResult = copyResult(
					baseline.result(), identity, baseline.result().queries(), changedAggregate);

			assertThatThrownBy(() -> createReport(
					evaluatorSeal, baseline.snapshot(), changedResult))
					.as(field)
					.isInstanceOf(IllegalArgumentException.class)
					.hasMessageContaining("structural counters must match exactly");
		}
	}

	@Test
	void resultAndRawScoreMutationsChangeTheCommittedReport() {
		Fixture baselineFixture = fixture(CANDIDATE_REVISION, false, 0L);
		Fixture failedGateFixture = fixture(CANDIDATE_REVISION, true, 0L);
		RelatedTopicReuseHoldoutEvidenceReport baseline = report(baselineFixture);
		RelatedTopicReuseHoldoutEvidenceReport failedGate = report(failedGateFixture);

		RelatedTopicReuseHoldoutRankingSnapshot changedSnapshot = snapshot(
				CANDIDATE_REVISION, Double.MIN_VALUE);
		Fixture rawScoreChangedFixture = new Fixture(
				changedSnapshot,
				scoringResult(
						changedSnapshot, changedSnapshot.evidenceSha256(), false, 0L));
		RelatedTopicReuseHoldoutEvidenceReport rawScoreChanged =
				report(rawScoreChangedFixture);

		assertThat(failedGate.rankingSnapshotSha256())
				.isEqualTo(baseline.rankingSnapshotSha256());
		assertThat(failedGate.rankingSnapshotJson())
				.isEqualTo(baseline.rankingSnapshotJson());
		assertThat(failedGate.scoringResultSha256())
				.isNotEqualTo(baseline.scoringResultSha256());
		assertThat(rawScoreChanged.rankingSnapshotSha256())
				.isNotEqualTo(baseline.rankingSnapshotSha256());
		assertThat(List.of(
				baseline.reportId(), failedGate.reportId(), rawScoreChanged.reportId()))
				.doesNotHaveDuplicates();
	}

	@Test
	void keepsEveryAuthorizationBoundaryFalseInMemoryAndInBothArtifacts() {
		RelatedTopicReuseHoldoutEvidenceReport report = report(
				fixture(CANDIDATE_REVISION, false, 0L));

		assertThat(report.readerFacing()).isFalse();
		assertThat(report.externalBundleAcceptanceAuthorized()).isFalse();
		assertThat(report.custodyReleaseAuthorized()).isFalse();
		assertThat(report.productActivationAuthorized()).isFalse();
		for (byte[] artifact : List.of(
				report.scoringResultJson(), report.evidenceReportJson())) {
			String json = new String(artifact, StandardCharsets.UTF_8);
			assertThat(json)
					.contains("\"readerFacing\":false")
					.contains("\"externalBundleAcceptanceAuthorized\":false")
					.contains("\"custodyReleaseAuthorized\":false")
					.contains("\"productActivationAuthorized\":false");
		}
	}

	@Test
	void exactVerifierRejectsTamperingMissingFilesAndNoncanonicalOrder() {
		Fixture fixture = fixture(CANDIDATE_REVISION, false, 0L);
		var seal = seal(
				EVALUATOR_REVISION,
				"evaluator",
				fixture.snapshot().candidateRevision(),
				"candidate");
		RelatedTopicReuseHoldoutEvidenceReport report =
				createReport(
						seal, fixture.snapshot(), fixture.result());

		var verified = verifyExactReport(
				seal, fixture.snapshot(), fixture.result(), report.artifacts());
		assertThat(verified.reportId()).isEqualTo(report.reportId());
		assertThat(verified.artifactSha256()).hasSize(4);
		assertThat(verified.externalBundleAcceptanceAuthorized()).isFalse();
		assertThat(verified.artifacts().keySet()).containsExactlyElementsOf(
				report.artifacts().keySet());

		Map<String, byte[]> mutableObserved = mutableArtifacts(report);
		var frozenVerified = verifyExactReport(
				seal, fixture.snapshot(), fixture.result(), mutableObserved);
		String frozenFilename = RelatedTopicReuseHoldoutEvidenceReport.SCORING_RESULT_FILENAME;
		byte expectedFirstByte = frozenVerified.artifact(frozenFilename)[0];
		mutableObserved.get(frozenFilename)[0] ^= 0x01;
		assertThat(frozenVerified.artifact(frozenFilename)[0])
				.isEqualTo(expectedFirstByte);
		Map<String, byte[]> returnedVerifiedArtifacts = frozenVerified.artifacts();
		assertThatThrownBy(() -> returnedVerifiedArtifacts.clear())
				.isInstanceOf(UnsupportedOperationException.class);
		returnedVerifiedArtifacts.get(frozenFilename)[0] ^= 0x01;
		assertThat(frozenVerified.artifact(frozenFilename)[0])
				.isEqualTo(expectedFirstByte);

		Map<String, byte[]> tampered = mutableArtifacts(report);
		tampered.get(RelatedTopicReuseHoldoutEvidenceReport.SCORING_RESULT_FILENAME)[0]
				^= 0x01;
		assertThatThrownBy(() -> verifyExactReport(
				seal, fixture.snapshot(), fixture.result(), tampered))
				.hasMessageContaining("exact regenerated canonical artifact");

		Map<String, byte[]> missing = mutableArtifacts(report);
		missing.remove(RelatedTopicReuseHoldoutEvidenceReport.EVIDENCE_REPORT_FILENAME);
		assertThatThrownBy(() -> verifyExactReport(
				seal, fixture.snapshot(), fixture.result(), missing))
				.hasMessageContaining("complete fixed filename order");

		Map<String, byte[]> reordered = new LinkedHashMap<>();
		reordered.put(
				RelatedTopicReuseHoldoutEvidenceReport.EVIDENCE_REPORT_FILENAME,
				report.evidenceReportJson());
		report.artifacts().forEach(reordered::putIfAbsent);
		assertThatThrownBy(() -> verifyExactReport(
				seal, fixture.snapshot(), fixture.result(), reordered))
				.hasMessageContaining("fixed filename order");
	}

	@Test
	void exactVerifierIsTheOnlyConstructorBoundaryForVerifiedArtifacts() {
		assertThat(RelatedTopicReuseHoldoutEvidenceReport.VerifiedArtifacts.class
				.getDeclaredConstructors())
				.singleElement()
				.satisfies(constructor -> assertThat(
						Modifier.isPrivate(constructor.getModifiers())).isTrue());
	}

	private static void assertSnapshotProjection(
			JsonNode root, RelatedTopicReuseHoldoutRankingSnapshot snapshot) {
		assertFields(
				root,
				"schemaVersion",
				"artifactType",
				"rankingSnapshotSha256",
				"bundleId",
				"corpusId",
				"policySha256",
				"corpusSha256",
				"manifestSha256",
				"judgmentsSha256",
				"judgmentsBytes",
				"candidateRevision",
				"cutoff",
				"queryOrder",
				"queries",
				"counters");
		assertThat(root.required("schemaVersion").asInt())
				.isEqualTo(RelatedTopicReuseHoldoutEvidenceReport.SCHEMA_VERSION);
		assertThat(root.required("artifactType").asString())
				.isEqualTo("RELATED_TOPIC_REUSE_HOLDOUT_RANKING_SNAPSHOT");
		assertThat(root.required("rankingSnapshotSha256").asString())
				.isEqualTo(snapshot.evidenceSha256());
		assertThat(root.required("bundleId").asString()).isEqualTo(snapshot.bundleId());
		assertThat(root.required("corpusId").asString()).isEqualTo(snapshot.corpusId());
		assertThat(root.required("policySha256").asString())
				.isEqualTo(snapshot.policySha256());
		assertThat(root.required("corpusSha256").asString())
				.isEqualTo(snapshot.corpusSha256());
		assertThat(root.required("manifestSha256").asString())
				.isEqualTo(snapshot.manifestSha256());
		assertThat(root.required("judgmentsSha256").asString())
				.isEqualTo(snapshot.judgmentsSha256());
		assertThat(root.required("judgmentsBytes").asLong())
				.isEqualTo(snapshot.judgmentsBytes());
		assertThat(root.required("candidateRevision").asString())
				.isEqualTo(snapshot.candidateRevision());
		assertThat(root.required("cutoff").asInt()).isEqualTo(snapshot.cutoff());
		assertStringArray(root.required("queryOrder"), snapshot.queryOrder());
		JsonNode queries = root.required("queries");
		assertThat(queries.size()).isEqualTo(snapshot.queries().size());
		for (int index = 0; index < snapshot.queries().size(); index++) {
			assertQueryRanking(queries.required(index), snapshot.queries().get(index));
		}
		assertStructuralCounters(root.required("counters"), snapshot.counters());
	}

	private static void assertQueryRanking(
			JsonNode value, QueryRanking query) {
		assertFields(value, "queryKey", "initialRun", "repeatedRun", "hiddenPerturbation");
		assertThat(value.required("queryKey").asString()).isEqualTo(query.queryKey());
		assertRankingRun(value.required("initialRun"), query.initialRun());
		assertRankingRun(value.required("repeatedRun"), query.repeatedRun());
		assertHiddenPerturbation(
				value.required("hiddenPerturbation"), query.hiddenPerturbation());
	}

	private static void assertRankingRun(JsonNode value, RankingRun run) {
		assertFields(
				value,
				"controlPool",
				"controlTop10",
				"eligibleSeedKeys",
				"feedbackPools",
				"candidateTop10");
		assertRankedPapers(value.required("controlPool"), run.controlPool());
		assertRankedPapers(value.required("controlTop10"), run.controlTop10());
		assertStringArray(value.required("eligibleSeedKeys"), run.eligibleSeedKeys());
		assertFeedbackPools(value.required("feedbackPools"), run.feedbackPools());
		assertRankedPapers(value.required("candidateTop10"), run.candidateTop10());
	}

	private static void assertHiddenPerturbation(
			JsonNode value, HiddenPerturbation hidden) {
		assertFields(
				value,
				"otherOwnerCandidateKey",
				"catalogOnlyCandidateKey",
				"visibleFeedbackPools",
				"visibleCandidateTop10");
		assertThat(value.required("otherOwnerCandidateKey").asString())
				.isEqualTo(hidden.otherOwnerCandidateKey());
		assertThat(value.required("catalogOnlyCandidateKey").asString())
				.isEqualTo(hidden.catalogOnlyCandidateKey());
		assertFeedbackPools(
				value.required("visibleFeedbackPools"), hidden.visibleFeedbackPools());
		assertRankedPapers(
				value.required("visibleCandidateTop10"), hidden.visibleCandidateTop10());
	}

	private static void assertFeedbackPools(
			JsonNode values, List<FeedbackPool> expected) {
		assertThat(values.size()).isEqualTo(expected.size());
		for (int index = 0; index < expected.size(); index++) {
			JsonNode value = values.required(index);
			FeedbackPool pool = expected.get(index);
			assertFields(value, "seedPaperKey", "candidates");
			assertThat(value.required("seedPaperKey").asString())
					.isEqualTo(pool.seedPaperKey());
			assertRankedPapers(value.required("candidates"), pool.candidates());
		}
	}

	private static void assertRankedPapers(
			JsonNode values, List<RankedPaper> expected) {
		assertThat(values.size()).isEqualTo(expected.size());
		for (int index = 0; index < expected.size(); index++) {
			JsonNode value = values.required(index);
			RankedPaper paper = expected.get(index);
			assertFields(value, "paperKey", "scoreBits");
			assertThat(value.required("paperKey").asString()).isEqualTo(paper.paperKey());
			assertThat(value.required("scoreBits").asString())
					.isEqualTo(rawBits(paper.scoreBits()));
		}
	}

	private static void assertStructuralCounters(
			JsonNode value, StructuralCounters counters) {
		assertFields(value, "providerCallCount", "experimentalSnapshotWriteCount");
		assertThat(value.required("providerCallCount").asLong())
				.isEqualTo(counters.providerCallCount());
		assertThat(value.required("experimentalSnapshotWriteCount").asLong())
				.isEqualTo(counters.experimentalSnapshotWriteCount());
	}

	private static void assertResultProjection(
			JsonNode root, RelatedTopicReuseHoldoutScoringResult result) {
		assertFields(
				root,
				"schemaVersion",
				"artifactType",
				"identity",
				"queries",
				"control",
				"candidate",
				"aggregate",
				"structural",
				"gates",
				"policyGatesPassed",
				"readerFacing",
				"externalBundleAcceptanceAuthorized",
				"custodyReleaseAuthorized",
				"productActivationAuthorized");
		assertThat(root.required("schemaVersion").asInt())
				.isEqualTo(RelatedTopicReuseHoldoutEvidenceReport.SCHEMA_VERSION);
		assertThat(root.required("artifactType").asString())
				.isEqualTo("RELATED_TOPIC_REUSE_HOLDOUT_SCORING_RESULT");
		assertScoreIdentity(root.required("identity"), result.identity());
		JsonNode queries = root.required("queries");
		assertThat(queries.size()).isEqualTo(result.queries().size());
		for (int index = 0; index < result.queries().size(); index++) {
			assertQueryScore(queries.required(index), result.queries().get(index));
		}
		assertRankingSummary(root.required("control"), result.control());
		assertRankingSummary(root.required("candidate"), result.candidate());
		assertAggregate(root.required("aggregate"), result.aggregate());
		assertStructural(root.required("structural"), result.structural());
		JsonNode gates = root.required("gates");
		assertThat(gates.size()).isEqualTo(result.gates().size());
		for (int index = 0; index < result.gates().size(); index++) {
			JsonNode value = gates.required(index);
			GateOutcome gate = result.gates().get(index);
			assertFields(value, "gate", "passed");
			assertThat(value.required("gate").asString()).isEqualTo(gate.gate().name());
			assertThat(value.required("passed").asBoolean()).isEqualTo(gate.passed());
		}
		assertThat(root.required("policyGatesPassed").asBoolean())
				.isEqualTo(result.policyGatesPassed());
		assertThat(root.required("readerFacing").asBoolean())
				.isEqualTo(result.readerFacing());
		assertThat(root.required("externalBundleAcceptanceAuthorized").asBoolean())
				.isEqualTo(result.externalBundleAcceptanceAuthorized());
		assertThat(root.required("custodyReleaseAuthorized").asBoolean())
				.isEqualTo(result.custodyReleaseAuthorized());
		assertThat(root.required("productActivationAuthorized").asBoolean())
				.isEqualTo(result.productActivationAuthorized());
	}

	private static void assertScoreIdentity(JsonNode value, ScoreIdentity identity) {
		assertFields(
				value,
				"evaluationProtocolId",
				"bundleId",
				"corpusId",
				"policySha256",
				"corpusSha256",
				"manifestSha256",
				"judgmentsSha256",
				"rankingSnapshotSha256",
				"judgmentsBytes",
				"candidateRevision",
				"cutoff",
				"queryOrder");
		assertThat(value.required("evaluationProtocolId").asString())
				.isEqualTo(identity.evaluationProtocolId());
		assertThat(value.required("bundleId").asString()).isEqualTo(identity.bundleId());
		assertThat(value.required("corpusId").asString()).isEqualTo(identity.corpusId());
		assertThat(value.required("policySha256").asString())
				.isEqualTo(identity.policySha256());
		assertThat(value.required("corpusSha256").asString())
				.isEqualTo(identity.corpusSha256());
		assertThat(value.required("manifestSha256").asString())
				.isEqualTo(identity.manifestSha256());
		assertThat(value.required("judgmentsSha256").asString())
				.isEqualTo(identity.judgmentsSha256());
		assertThat(value.required("rankingSnapshotSha256").asString())
				.isEqualTo(identity.rankingSnapshotSha256());
		assertThat(value.required("judgmentsBytes").asLong())
				.isEqualTo(identity.judgmentsBytes());
		assertThat(value.required("candidateRevision").asString())
				.isEqualTo(identity.candidateRevision());
		assertThat(value.required("cutoff").asInt()).isEqualTo(identity.cutoff());
		assertStringArray(value.required("queryOrder"), identity.queryOrder());
	}

	private static void assertQueryScore(JsonNode value, QueryScore query) {
		assertFields(
				value,
				"queryKey",
				"queryKind",
				"control",
				"candidate",
				"deltas",
				"novelRelevantAt10",
				"controlExplicitAdversaryAt10Count",
				"candidateExplicitAdversaryAt10Count",
				"rankOneIrrelevant",
				"ownerScopeViolationCount",
				"filterViolationCount",
				"repeatedStable",
				"hiddenNoninterference",
				"exactFallback",
				"recallNonregression",
				"controlNonregression",
				"filteredOpportunityStrictImprovement",
				"authorRelevantBaselineHit",
				"authorZeroEligibleSeedsAndFeedback",
				"noSeedZeroEligibleSeedsAndFeedback");
		assertThat(value.required("queryKey").asString()).isEqualTo(query.queryKey());
		assertThat(value.required("queryKind").asString()).isEqualTo(query.queryKind().name());
		assertRankingMetrics(value.required("control"), query.control());
		assertRankingMetrics(value.required("candidate"), query.candidate());
		assertMetricDeltas(value.required("deltas"), query.deltas());
		assertThat(value.required("novelRelevantAt10").asInt())
				.isEqualTo(query.novelRelevantAt10());
		assertThat(value.required("controlExplicitAdversaryAt10Count").asInt())
				.isEqualTo(query.controlExplicitAdversaryAt10Count());
		assertThat(value.required("candidateExplicitAdversaryAt10Count").asInt())
				.isEqualTo(query.candidateExplicitAdversaryAt10Count());
		assertThat(value.required("rankOneIrrelevant").asBoolean())
				.isEqualTo(query.rankOneIrrelevant());
		assertThat(value.required("ownerScopeViolationCount").asInt())
				.isEqualTo(query.ownerScopeViolationCount());
		assertThat(value.required("filterViolationCount").asInt())
				.isEqualTo(query.filterViolationCount());
		assertThat(value.required("repeatedStable").asBoolean())
				.isEqualTo(query.repeatedStable());
		assertThat(value.required("hiddenNoninterference").asBoolean())
				.isEqualTo(query.hiddenNoninterference());
		assertThat(value.required("exactFallback").asBoolean())
				.isEqualTo(query.exactFallback());
		assertThat(value.required("recallNonregression").asBoolean())
				.isEqualTo(query.recallNonregression());
		assertThat(value.required("controlNonregression").asBoolean())
				.isEqualTo(query.controlNonregression());
		assertThat(value.required("filteredOpportunityStrictImprovement").asBoolean())
				.isEqualTo(query.filteredOpportunityStrictImprovement());
		assertThat(value.required("authorRelevantBaselineHit").asBoolean())
				.isEqualTo(query.authorRelevantBaselineHit());
		assertThat(value.required("authorZeroEligibleSeedsAndFeedback").asBoolean())
				.isEqualTo(query.authorZeroEligibleSeedsAndFeedback());
		assertThat(value.required("noSeedZeroEligibleSeedsAndFeedback").asBoolean())
				.isEqualTo(query.noSeedZeroEligibleSeedsAndFeedback());
	}

	private static void assertRankingMetrics(JsonNode value, RankingMetrics metrics) {
		assertFields(
				value,
				"relevantCandidateCount",
				"retrievedRelevantCount",
				"recallAt10Bits",
				"ndcgAt10Bits",
				"precisionAt1Bits",
				"reciprocalRankAt10Bits");
		assertThat(value.required("relevantCandidateCount").asInt())
				.isEqualTo(metrics.relevantCandidateCount());
		assertThat(value.required("retrievedRelevantCount").asInt())
				.isEqualTo(metrics.retrievedRelevantCount());
		assertDoubleBits(value, "recallAt10Bits", metrics.recallAt10());
		assertDoubleBits(value, "ndcgAt10Bits", metrics.ndcgAt10());
		assertDoubleBits(value, "precisionAt1Bits", metrics.precisionAt1());
		assertDoubleBits(
				value, "reciprocalRankAt10Bits", metrics.reciprocalRankAt10());
	}

	private static void assertMetricDeltas(JsonNode value, MetricDeltas deltas) {
		assertFields(
				value,
				"recallAt10Bits",
				"ndcgAt10Bits",
				"precisionAt1Bits",
				"reciprocalRankAt10Bits");
		assertDoubleBits(value, "recallAt10Bits", deltas.recallAt10());
		assertDoubleBits(value, "ndcgAt10Bits", deltas.ndcgAt10());
		assertDoubleBits(value, "precisionAt1Bits", deltas.precisionAt1());
		assertDoubleBits(
				value, "reciprocalRankAt10Bits", deltas.reciprocalRankAt10());
	}

	private static void assertRankingSummary(JsonNode value, RankingSummary summary) {
		assertFields(
				value,
				"queryCount",
				"recallQueryCount",
				"ndcgQueryCount",
				"precisionAt1QueryCount",
				"reciprocalRankQueryCount",
				"macroRecallAt10Bits",
				"macroNdcgAt10Bits",
				"macroPrecisionAt1Bits",
				"meanReciprocalRankAt10Bits");
		assertThat(value.required("queryCount").asInt()).isEqualTo(summary.queryCount());
		assertThat(value.required("recallQueryCount").asInt())
				.isEqualTo(summary.recallQueryCount());
		assertThat(value.required("ndcgQueryCount").asInt())
				.isEqualTo(summary.ndcgQueryCount());
		assertThat(value.required("precisionAt1QueryCount").asInt())
				.isEqualTo(summary.precisionAt1QueryCount());
		assertThat(value.required("reciprocalRankQueryCount").asInt())
				.isEqualTo(summary.reciprocalRankQueryCount());
		assertDoubleBits(value, "macroRecallAt10Bits", summary.macroRecallAt10());
		assertDoubleBits(value, "macroNdcgAt10Bits", summary.macroNdcgAt10());
		assertDoubleBits(
				value, "macroPrecisionAt1Bits", summary.macroPrecisionAt1());
		assertDoubleBits(
				value,
				"meanReciprocalRankAt10Bits",
				summary.meanReciprocalRankAt10());
	}

	private static void assertAggregate(JsonNode value, AggregateMetrics aggregate) {
		assertFields(
				value,
				"macroRecallAt10DeltaBits",
				"macroNdcgAt10DeltaBits",
				"macroPrecisionAt1DeltaBits",
				"macroMeanReciprocalRankAt10DeltaBits",
				"strictOpportunityRecallImprovementCount",
				"novelRelevantAt10",
				"perQueryNdcgRegressionCount",
				"maximumPerQueryNdcgRegressionBits",
				"controlExplicitAdversaryAt10Count",
				"candidateExplicitAdversaryAt10Count",
				"rankOneIrrelevantCount",
				"ownerScopeLeakCount",
				"filterViolationCount",
				"providerCallCount",
				"experimentalSnapshotWriteCount");
		assertDoubleBits(
				value, "macroRecallAt10DeltaBits", aggregate.macroRecallAt10Delta());
		assertDoubleBits(
				value, "macroNdcgAt10DeltaBits", aggregate.macroNdcgAt10Delta());
		assertDoubleBits(
				value,
				"macroPrecisionAt1DeltaBits",
				aggregate.macroPrecisionAt1Delta());
		assertDoubleBits(
				value,
				"macroMeanReciprocalRankAt10DeltaBits",
				aggregate.macroMeanReciprocalRankAt10Delta());
		assertThat(value.required("strictOpportunityRecallImprovementCount").asInt())
				.isEqualTo(aggregate.strictOpportunityRecallImprovementCount());
		assertThat(value.required("novelRelevantAt10").asInt())
				.isEqualTo(aggregate.novelRelevantAt10());
		assertThat(value.required("perQueryNdcgRegressionCount").asInt())
				.isEqualTo(aggregate.perQueryNdcgRegressionCount());
		assertDoubleBits(
				value,
				"maximumPerQueryNdcgRegressionBits",
				aggregate.maximumPerQueryNdcgRegression());
		assertThat(value.required("controlExplicitAdversaryAt10Count").asInt())
				.isEqualTo(aggregate.controlExplicitAdversaryAt10Count());
		assertThat(value.required("candidateExplicitAdversaryAt10Count").asInt())
				.isEqualTo(aggregate.candidateExplicitAdversaryAt10Count());
		assertThat(value.required("rankOneIrrelevantCount").asInt())
				.isEqualTo(aggregate.rankOneIrrelevantCount());
		assertThat(value.required("ownerScopeLeakCount").asInt())
				.isEqualTo(aggregate.ownerScopeLeakCount());
		assertThat(value.required("filterViolationCount").asInt())
				.isEqualTo(aggregate.filterViolationCount());
		assertThat(value.required("providerCallCount").asLong())
				.isEqualTo(aggregate.providerCallCount());
		assertThat(value.required("experimentalSnapshotWriteCount").asLong())
				.isEqualTo(aggregate.experimentalSnapshotWriteCount());
	}

	private static void assertStructural(
			JsonNode value, StructuralAssessment structural) {
		assertFields(
				value,
				"recallRegressionQueryCount",
				"controlRegressionQueryCount",
				"filteredOpportunityFailureCount",
				"authorRelevantBaselineFailureCount",
				"authorZeroSeedFeedbackFailureCount",
				"noSeedZeroSeedFeedbackFailureCount",
				"repeatedInstabilityCount",
				"hiddenInterferenceCount",
				"fallbackMismatchCount");
		assertThat(value.required("recallRegressionQueryCount").asInt())
				.isEqualTo(structural.recallRegressionQueryCount());
		assertThat(value.required("controlRegressionQueryCount").asInt())
				.isEqualTo(structural.controlRegressionQueryCount());
		assertThat(value.required("filteredOpportunityFailureCount").asInt())
				.isEqualTo(structural.filteredOpportunityFailureCount());
		assertThat(value.required("authorRelevantBaselineFailureCount").asInt())
				.isEqualTo(structural.authorRelevantBaselineFailureCount());
		assertThat(value.required("authorZeroSeedFeedbackFailureCount").asInt())
				.isEqualTo(structural.authorZeroSeedFeedbackFailureCount());
		assertThat(value.required("noSeedZeroSeedFeedbackFailureCount").asInt())
				.isEqualTo(structural.noSeedZeroSeedFeedbackFailureCount());
		assertThat(value.required("repeatedInstabilityCount").asInt())
				.isEqualTo(structural.repeatedInstabilityCount());
		assertThat(value.required("hiddenInterferenceCount").asInt())
				.isEqualTo(structural.hiddenInterferenceCount());
		assertThat(value.required("fallbackMismatchCount").asInt())
				.isEqualTo(structural.fallbackMismatchCount());
	}

	private static void assertFields(JsonNode value, String... expected) {
		assertThat(value.propertyNames()).containsExactlyInAnyOrder(expected);
	}

	private static void assertStringArray(JsonNode values, List<String> expected) {
		assertThat(values.size()).isEqualTo(expected.size());
		for (int index = 0; index < expected.size(); index++) {
			assertThat(values.required(index).asString()).isEqualTo(expected.get(index));
		}
	}

	private static void assertDoubleBits(
			JsonNode value, String field, Double expected) {
		JsonNode actual = value.required(field);
		if (expected == null) {
			assertThat(actual.isNull()).as(field).isTrue();
		}
		else {
			assertThat(actual.asString()).as(field)
					.isEqualTo(rawBits(Double.doubleToRawLongBits(expected)));
		}
	}

	private static String rawBits(long bits) {
		return HexFormat.of().toHexDigits(bits);
	}

	private static RelatedTopicReuseHoldoutRankingSnapshot sentinelSnapshot() {
		RankingRun initial = sentinelRun("initial", 11, 1, 10.0d);
		RankingRun repeated = sentinelRun("repeated", 12, 2, 30.0d);
		FeedbackPool hiddenFeedback = new FeedbackPool(
				initial.eligibleSeedKeys().getFirst(),
				List.of(
						new RankedPaper("hidden-visible-feedback-one", 61.0d),
						new RankedPaper("hidden-visible-feedback-two", 62.0d)));
		List<RankedPaper> hiddenCandidateTop = new ArrayList<>();
		hiddenCandidateTop.add(new RankedPaper("hidden-visible-feedback-two", 80.0d));
		hiddenCandidateTop.add(new RankedPaper("hidden-visible-feedback-one", 79.0d));
		for (int index = initial.controlPool().size() - 1;
				hiddenCandidateTop.size() < 10;
				index--) {
			hiddenCandidateTop.add(new RankedPaper(
					initial.controlPool().get(index).paperKey(),
					78.0d - hiddenCandidateTop.size()));
		}
		HiddenPerturbation hidden = new HiddenPerturbation(
				"hidden-other-candidate",
				"hidden-catalog-candidate",
				List.of(hiddenFeedback),
				hiddenCandidateTop);
		List<String> queryOrder = new ArrayList<>();
		List<QueryRanking> queries = new ArrayList<>();
		for (int index = 0; index < 9; index++) {
			String queryKey = "query-projection-" + (index + 1);
			queryOrder.add(queryKey);
			queries.add(new QueryRanking(queryKey, initial, repeated, hidden));
		}
		return RelatedTopicReuseHoldoutRankingSnapshot.seal(
				BUNDLE_ID,
				CORPUS_ID,
				POLICY_SHA256,
				CORPUS_SHA256,
				MANIFEST_SHA256,
				JUDGMENTS_SHA256,
				JUDGMENTS_BYTES,
				CANDIDATE_REVISION,
				10,
				queryOrder,
				queries,
				new StructuralCounters(6L, 7L));
	}

	private static RankingRun sentinelRun(
			String prefix, int controlCount, int seedCount, double scoreBase) {
		List<RankedPaper> control = new ArrayList<>();
		for (int index = 0; index < controlCount; index++) {
			control.add(new RankedPaper(
					prefix + "-control-" + (index + 1), scoreBase - index * 0.03125d));
		}
		List<String> seeds = new ArrayList<>();
		List<FeedbackPool> feedbackPools = new ArrayList<>();
		for (int index = 0; index < seedCount; index++) {
			String seedKey = control.get(1 + index * 3).paperKey();
			seeds.add(seedKey);
			feedbackPools.add(new FeedbackPool(
					seedKey,
					List.of(
							new RankedPaper(
									prefix + "-feedback-" + (index + 1) + "-one",
									scoreBase + 5.0d + index),
							new RankedPaper(
									prefix + "-feedback-" + (index + 1) + "-two",
									scoreBase + 6.0d + index))));
		}
		List<RankedPaper> candidate = new ArrayList<>();
		for (FeedbackPool pool : feedbackPools) {
			for (RankedPaper paper : pool.candidates()) {
				candidate.add(new RankedPaper(
						paper.paperKey(), scoreBase + 20.0d - candidate.size()));
			}
		}
		for (int index = control.size() - 1; candidate.size() < 10; index--) {
			candidate.add(new RankedPaper(
					control.get(index).paperKey(), scoreBase + 20.0d - candidate.size()));
		}
		return new RankingRun(
				control,
				control.subList(0, 10),
				seeds,
				feedbackPools,
				candidate);
	}

	private static RelatedTopicReuseHoldoutScoringResult sentinelScoringResult(
			RelatedTopicReuseHoldoutRankingSnapshot snapshot) {
		List<QueryScore> queries = new ArrayList<>();
		for (int index = 0; index < snapshot.queryOrder().size(); index++) {
			double controlNdcg = 0.125d + index * 0.0625d;
			RankingMetrics control;
			RankingMetrics candidate;
			if (index == 8) {
				control = new RankingMetrics(8, 2, 0.25d, controlNdcg, 1.0d, 1.0d);
				candidate = new RankingMetrics(
						8, 2, 0.25d, controlNdcg - 0.125d, 0.0d, 0.5d);
			}
			else {
				int retrievedRelevant = index < 2 ? 2 : 3;
				control = new RankingMetrics(8, 2, 0.25d, controlNdcg, 0.0d, 0.5d);
				candidate = new RankingMetrics(
						8,
						retrievedRelevant,
						retrievedRelevant / 8.0d,
						controlNdcg + (index + 1) * 0.00390625d,
						1.0d,
						1.0d);
			}
			RelatedTopicReuseHoldoutBundle.QueryKind kind = switch (index) {
				case 0, 1 -> RelatedTopicReuseHoldoutBundle.QueryKind
						.FILTERED_LEXICAL_BRIDGE_OPPORTUNITY;
				case 2, 3, 4, 5 -> RelatedTopicReuseHoldoutBundle.QueryKind
						.LEXICAL_BRIDGE_OPPORTUNITY;
				case 6, 7 -> RelatedTopicReuseHoldoutBundle.QueryKind
						.NO_SEED_FALLBACK_CONTROL;
				default -> RelatedTopicReuseHoldoutBundle.QueryKind
						.AUTHOR_NO_RELATED_SIGNAL_CONTROL;
			};
			MetricDeltas deltas = metricDeltas(control, candidate);
			queries.add(new QueryScore(
					snapshot.queryOrder().get(index),
					kind,
					control,
					candidate,
					deltas,
					index,
					index + 1,
					(index * 2 + 2) % 10,
					index % 2 == 0,
					index + 10,
					index + 20,
					index >= 6,
					index >= 7,
					index >= 8,
					true,
					index != 8,
					index >= 2,
					index >= 3,
					index >= 4,
					index >= 5));
		}
		RankingSummary control = rankingSummary(
				queries.stream().map(QueryScore::control).toList());
		RankingSummary candidate = rankingSummary(
				queries.stream().map(QueryScore::candidate).toList());
		AggregateMetrics aggregate = aggregateMetrics(queries, control, candidate, 6L, 7L);
		List<GateOutcome> gates = Arrays.stream(GateId.values())
				.map(gate -> new GateOutcome(gate, gate.ordinal() % 2 != 0))
				.toList();
		return new RelatedTopicReuseHoldoutScoringResult(
				new ScoreIdentity(
						"related-topic-reuse-holdout-evaluation-v1",
						snapshot.bundleId(),
						snapshot.corpusId(),
						snapshot.policySha256(),
						snapshot.corpusSha256(),
						snapshot.manifestSha256(),
						snapshot.judgmentsSha256(),
						snapshot.evidenceSha256(),
						snapshot.judgmentsBytes(),
						snapshot.candidateRevision(),
						snapshot.cutoff(),
						snapshot.queryOrder()),
				queries,
				control,
				candidate,
				aggregate,
				new StructuralAssessment(0, 1, 2, 3, 4, 5, 6, 7, 8),
				gates,
				false,
				false,
				false,
				false,
				false);
	}

	private static MetricDeltas metricDeltas(
			RankingMetrics control, RankingMetrics candidate) {
		return new MetricDeltas(
				candidate.recallAt10() - control.recallAt10(),
				candidate.ndcgAt10() - control.ndcgAt10(),
				candidate.precisionAt1() - control.precisionAt1(),
				candidate.reciprocalRankAt10() - control.reciprocalRankAt10());
	}

	private static RankingSummary rankingSummary(List<RankingMetrics> metrics) {
		return new RankingSummary(
				metrics.size(),
				metrics.size(),
				metrics.size(),
				metrics.size(),
				metrics.size(),
				mean(metrics.stream().map(RankingMetrics::recallAt10).toList()),
				mean(metrics.stream().map(RankingMetrics::ndcgAt10).toList()),
				mean(metrics.stream().map(RankingMetrics::precisionAt1).toList()),
				mean(metrics.stream().map(RankingMetrics::reciprocalRankAt10).toList()));
	}

	private static double mean(List<Double> values) {
		double sum = 0.0d;
		for (double value : values) {
			sum += value;
		}
		return sum / values.size();
	}

	private static AggregateMetrics aggregateMetrics(
			List<QueryScore> queries,
			RankingSummary control,
			RankingSummary candidate,
			long providerCallCount,
			long snapshotWriteCount) {
		List<Double> ndcgRegressions = queries.stream()
				.map(query -> query.deltas().ndcgAt10())
				.filter(delta -> delta < -1.0e-12d)
				.map(delta -> -delta)
				.toList();
		return new AggregateMetrics(
				candidate.macroRecallAt10() - control.macroRecallAt10(),
				candidate.macroNdcgAt10() - control.macroNdcgAt10(),
				candidate.macroPrecisionAt1() - control.macroPrecisionAt1(),
				candidate.meanReciprocalRankAt10()
						- control.meanReciprocalRankAt10(),
				(int) queries.stream()
						.filter(query -> query.queryKind().opportunity())
						.filter(query -> query.deltas().recallAt10() > 1.0e-12d)
						.count(),
				queries.stream().mapToInt(QueryScore::novelRelevantAt10).sum(),
				ndcgRegressions.size(),
				ndcgRegressions.stream().mapToDouble(Double::doubleValue).max().orElse(0.0d),
				queries.stream().mapToInt(QueryScore::controlExplicitAdversaryAt10Count).sum(),
				queries.stream().mapToInt(QueryScore::candidateExplicitAdversaryAt10Count).sum(),
				queries.stream().mapToInt(query -> query.rankOneIrrelevant() ? 1 : 0).sum(),
				queries.stream().mapToInt(QueryScore::ownerScopeViolationCount).sum(),
				queries.stream().mapToInt(QueryScore::filterViolationCount).sum(),
				providerCallCount,
				snapshotWriteCount);
	}

	private static ScoreIdentity identityWithChangedField(
			ScoreIdentity identity, String field) {
		if (!List.of(
				"evaluationProtocolId",
				"bundleId",
				"corpusId",
				"policySha256",
				"corpusSha256",
				"manifestSha256",
				"judgmentsSha256",
				"rankingSnapshotSha256",
				"judgmentsBytes",
				"candidateRevision",
				"queryOrder").contains(field)) {
			throw new IllegalArgumentException("unsupported identity field: " + field);
		}
		return new ScoreIdentity(
				field.equals("evaluationProtocolId")
						? "related-topic-reuse-holdout-evaluation-v2"
						: identity.evaluationProtocolId(),
				field.equals("bundleId") ? "bundle-beta" : identity.bundleId(),
				field.equals("corpusId") ? "corpus-beta" : identity.corpusId(),
				field.equals("policySha256") ? "0".repeat(64) : identity.policySha256(),
				field.equals("corpusSha256") ? "1".repeat(64) : identity.corpusSha256(),
				field.equals("manifestSha256") ? "2".repeat(64) : identity.manifestSha256(),
				field.equals("judgmentsSha256") ? "3".repeat(64) : identity.judgmentsSha256(),
				field.equals("rankingSnapshotSha256")
						? "4".repeat(64)
						: identity.rankingSnapshotSha256(),
				field.equals("judgmentsBytes")
						? identity.judgmentsBytes() + 1L
						: identity.judgmentsBytes(),
				field.equals("candidateRevision")
						? "9".repeat(40)
						: identity.candidateRevision(),
				identity.cutoff(),
				field.equals("queryOrder")
						? List.of("query-binding-mismatch")
						: identity.queryOrder());
	}

	private static ScoreIdentity identityWithCutoff(ScoreIdentity identity, int cutoff) {
		return new ScoreIdentity(
				identity.evaluationProtocolId(),
				identity.bundleId(),
				identity.corpusId(),
				identity.policySha256(),
				identity.corpusSha256(),
				identity.manifestSha256(),
				identity.judgmentsSha256(),
				identity.rankingSnapshotSha256(),
				identity.judgmentsBytes(),
				identity.candidateRevision(),
				cutoff,
				identity.queryOrder());
	}

	private static QueryScore queryWithKey(QueryScore query, String queryKey) {
		return new QueryScore(
				queryKey,
				query.queryKind(),
				query.control(),
				query.candidate(),
				query.deltas(),
				query.novelRelevantAt10(),
				query.controlExplicitAdversaryAt10Count(),
				query.candidateExplicitAdversaryAt10Count(),
				query.rankOneIrrelevant(),
				query.ownerScopeViolationCount(),
				query.filterViolationCount(),
				query.repeatedStable(),
				query.hiddenNoninterference(),
				query.exactFallback(),
				query.recallNonregression(),
				query.controlNonregression(),
				query.filteredOpportunityStrictImprovement(),
				query.authorRelevantBaselineHit(),
				query.authorZeroEligibleSeedsAndFeedback(),
				query.noSeedZeroEligibleSeedsAndFeedback());
	}

	private static RelatedTopicReuseHoldoutScoringResult copyResult(
			RelatedTopicReuseHoldoutScoringResult result,
			ScoreIdentity identity,
			List<QueryScore> queries,
			AggregateMetrics aggregate) {
		return new RelatedTopicReuseHoldoutScoringResult(
				identity,
				queries,
				result.control(),
				result.candidate(),
				aggregate,
				result.structural(),
				result.gates(),
				result.policyGatesPassed(),
				result.readerFacing(),
				result.externalBundleAcceptanceAuthorized(),
				result.custodyReleaseAuthorized(),
				result.productActivationAuthorized());
	}

	private static AggregateMetrics aggregateWithCounters(
			AggregateMetrics aggregate,
			long providerCallCount,
			long snapshotWriteCount) {
		return new AggregateMetrics(
				aggregate.macroRecallAt10Delta(),
				aggregate.macroNdcgAt10Delta(),
				aggregate.macroPrecisionAt1Delta(),
				aggregate.macroMeanReciprocalRankAt10Delta(),
				aggregate.strictOpportunityRecallImprovementCount(),
				aggregate.novelRelevantAt10(),
				aggregate.perQueryNdcgRegressionCount(),
				aggregate.maximumPerQueryNdcgRegression(),
				aggregate.controlExplicitAdversaryAt10Count(),
				aggregate.candidateExplicitAdversaryAt10Count(),
				aggregate.rankOneIrrelevantCount(),
				aggregate.ownerScopeLeakCount(),
				aggregate.filterViolationCount(),
				providerCallCount,
				snapshotWriteCount);
	}

	private static RelatedTopicReuseHoldoutEvidenceReport report(Fixture fixture) {
		return createReport(
				seal(
						EVALUATOR_REVISION,
						"evaluator",
						fixture.snapshot().candidateRevision(),
						"candidate"),
				fixture.snapshot(),
				fixture.result());
	}

	private static Map<String, byte[]> mutableArtifacts(
			RelatedTopicReuseHoldoutEvidenceReport report) {
		Map<String, byte[]> copy = new LinkedHashMap<>();
		report.artifacts().forEach((filename, bytes) -> copy.put(filename, bytes.clone()));
		return copy;
	}

	private static RelatedTopicReuseHoldoutEvaluatorSeal.VerifiedEvaluatorSeal seal(
			String evaluatorRevision,
			String evaluatorContent,
			String candidateRevision,
			String candidateContent) {
		List<SourceFile> evaluatorSources = List.of(new SourceFile(
				100644,
				"backend/src/test/java/Evaluator.java",
				evaluatorContent.getBytes(StandardCharsets.UTF_8)));
		List<SourceFile> candidateSources = List.of(new SourceFile(
				100644,
				"backend/src/main/java/Candidate.java",
				candidateContent.getBytes(StandardCharsets.UTF_8)));
		String evaluatorSha256 = RelatedTopicReuseHoldoutEvaluatorSeal.sourceSha256(
				SourceRole.EVALUATOR, evaluatorRevision, evaluatorSources);
		String candidateSha256 = RelatedTopicReuseHoldoutEvaluatorSeal.sourceSha256(
				SourceRole.CANDIDATE, candidateRevision, candidateSources);
		return RelatedTopicReuseHoldoutEvaluatorSeal.verify(
				evaluatorRevision,
				evaluatorSha256,
				candidateRevision,
				candidateSha256,
				new RepositoryState(
						evaluatorRevision,
						"",
						candidateRevision,
						candidateSha256,
						true),
				evaluatorSources,
				candidateSources);
	}

	private static Fixture fixture(
			String candidateRevision, boolean oneFailedGate, long resultProviderCalls) {
		RelatedTopicReuseHoldoutRankingSnapshot snapshot = snapshot(candidateRevision, 0.0d);
		return new Fixture(
				snapshot,
				scoringResult(
						snapshot,
						snapshot.evidenceSha256(),
						oneFailedGate,
						resultProviderCalls));
	}

	private static RelatedTopicReuseHoldoutRankingSnapshot snapshot(
			String candidateRevision, double positiveScore) {
		RankedPaper positiveZero = new RankedPaper("paper-positive", positiveScore);
		RankedPaper negativeZero = new RankedPaper("paper-negative", -0.0d);
		List<RankedPaper> papers = List.of(positiveZero, negativeZero);
		RankingRun run = new RankingRun(
				papers, papers, List.of(), List.of(), papers);
		HiddenPerturbation hidden = new HiddenPerturbation(
				"hidden-other", "hidden-catalog", List.of(), papers);
		QueryRanking query = new QueryRanking(QUERY_KEY, run, run, hidden);
		return RelatedTopicReuseHoldoutRankingSnapshot.seal(
				BUNDLE_ID,
				CORPUS_ID,
				POLICY_SHA256,
				CORPUS_SHA256,
				MANIFEST_SHA256,
				JUDGMENTS_SHA256,
				JUDGMENTS_BYTES,
				candidateRevision,
				10,
				List.of(QUERY_KEY),
				List.of(query),
				new StructuralCounters(0L, 0L));
	}

	private static RelatedTopicReuseHoldoutScoringResult scoringResult(
			RelatedTopicReuseHoldoutRankingSnapshot snapshot,
			String snapshotSha256,
			boolean oneFailedGate,
			long providerCallCount) {
		RankingMetrics emptyMetrics = new RankingMetrics(
				0, 0, null, null, 0.0d, null);
		QueryScore query = new QueryScore(
				QUERY_KEY,
				RelatedTopicReuseHoldoutBundle.QueryKind.NO_SEED_FALLBACK_CONTROL,
				emptyMetrics,
				emptyMetrics,
				new MetricDeltas(null, null, 0.0d, null),
				0, 0, 0, false, 0, 0,
				true, true, true, true, true, true, true, true, true);
		RankingSummary summary = new RankingSummary(
				1, 0, 0, 1, 0, null, null, 0.0d, null);
		AggregateMetrics aggregate = new AggregateMetrics(
				null, null, 0.0d, null,
				0, 0, 0, 0.0d, 0, 0, 0, 0, 0,
				providerCallCount, 0L);
		List<GateOutcome> gates = Arrays.stream(GateId.values())
				.map(gate -> new GateOutcome(
						gate, !oneFailedGate || gate != GateId.MINIMUM_MACRO_NDCG_DELTA))
				.toList();
		return new RelatedTopicReuseHoldoutScoringResult(
				new ScoreIdentity(
						"related-topic-reuse-holdout-evaluation-v1",
						snapshot.bundleId(),
						snapshot.corpusId(),
						snapshot.policySha256(),
						snapshot.corpusSha256(),
						snapshot.manifestSha256(),
						snapshot.judgmentsSha256(),
						snapshotSha256,
						snapshot.judgmentsBytes(),
						snapshot.candidateRevision(),
						snapshot.cutoff(),
						snapshot.queryOrder()),
				List.of(query),
				summary,
				summary,
				aggregate,
				new StructuralAssessment(0, 0, 0, 0, 0, 0, 0, 0, 0),
				gates,
				!oneFailedGate,
				false,
				false,
				false,
				false);
	}

	private static String sha256(byte[] bytes) {
		try {
			return HexFormat.of().formatHex(
					MessageDigest.getInstance("SHA-256").digest(bytes));
		}
		catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException(exception);
		}
	}

	private record Fixture(
			RelatedTopicReuseHoldoutRankingSnapshot snapshot,
			RelatedTopicReuseHoldoutScoringResult result) {
	}
}
