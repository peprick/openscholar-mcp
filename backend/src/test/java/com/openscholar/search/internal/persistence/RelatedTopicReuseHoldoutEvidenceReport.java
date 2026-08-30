package com.openscholar.search.internal.persistence;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.regex.Pattern;

import tools.jackson.databind.ObjectWriter;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * Deterministic in-memory evidence artifacts for one related-topic holdout score.
 * This type deliberately has no filesystem or custody capability.
 */
final class RelatedTopicReuseHoldoutEvidenceReport {

	static final int SCHEMA_VERSION = 2;
	static final int RESULT_DIGEST_VERSION = 1;
	static final int MAXIMUM_SOURCE_ARTIFACT_BYTES = 2 * 1024 * 1024;
	static final int MAXIMUM_SNAPSHOT_ARTIFACT_BYTES = 8 * 1024 * 1024;
	static final int MAXIMUM_RESULT_ARTIFACT_BYTES = 2 * 1024 * 1024;
	static final int MAXIMUM_REPORT_ARTIFACT_BYTES = 64 * 1024;
	static final String EVALUATOR_SOURCE_FILENAME = "evaluator-source.json";
	static final String RANKING_SNAPSHOT_FILENAME = "ranking-snapshot.json";
	static final String SCORING_RESULT_FILENAME = "scoring-result.json";
	static final String EVIDENCE_REPORT_FILENAME = "evidence-report.json";

	private static final String ARTIFACT_TYPE =
			"RELATED_TOPIC_REUSE_HOLDOUT_EVIDENCE_REPORT";
	private static final String SNAPSHOT_ARTIFACT_TYPE =
			"RELATED_TOPIC_REUSE_HOLDOUT_RANKING_SNAPSHOT";
	private static final String SOURCE_ARTIFACT_TYPE =
			"RELATED_TOPIC_REUSE_HOLDOUT_EVALUATOR_SOURCE";
	private static final String RESULT_ARTIFACT_TYPE =
			"RELATED_TOPIC_REUSE_HOLDOUT_SCORING_RESULT";
	private static final String EVALUATION_PROTOCOL_ID =
			"related-topic-reuse-holdout-evaluation-v1";
	private static final String RESULT_DIGEST_DOMAIN =
			"openscholar-related-topic-reuse-holdout-scoring-result";
	private static final String REPORT_ID_DOMAIN =
			"openscholar-related-topic-reuse-holdout-evidence-report";
	private static final String REPORT_ID_PREFIX =
			"related-topic-reuse-holdout-report-v2-";
	private static final Pattern GIT_REVISION = Pattern.compile("[0-9a-f]{40}");
	private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");
	private static final HexFormat HEX = HexFormat.of();
	private static final ObjectWriter CANONICAL_WRITER = JsonMapper.builder()
			.build()
			.writer()
			.with(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
			.without(SerializationFeature.INDENT_OUTPUT);

	private final String reportId;
	private final String firstRunKey;
	private final int freezeSchemaVersion;
	private final String sourceInventoryId;
	private final String evaluatorRevision;
	private final String evaluatorSourceSha256;
	private final String candidateRevision;
	private final String candidateSourceSha256;
	private final String rankingSnapshotSha256;
	private final String evaluatorSourceArtifactSha256;
	private final String rankingSnapshotArtifactSha256;
	private final String scoringResultSha256;
	private final String scoringResultArtifactSha256;
	private final long judgmentsBytes;
	private final long evaluatorSourceBytes;
	private final long rankingSnapshotBytes;
	private final long scoringResultBytes;
	private final Map<String, byte[]> artifacts;

	private RelatedTopicReuseHoldoutEvidenceReport(
			String reportId,
			String firstRunKey,
			int freezeSchemaVersion,
			String sourceInventoryId,
			String evaluatorRevision,
			String evaluatorSourceSha256,
			String candidateRevision,
			String candidateSourceSha256,
			String rankingSnapshotSha256,
			String evaluatorSourceArtifactSha256,
			String rankingSnapshotArtifactSha256,
			String scoringResultSha256,
			String scoringResultArtifactSha256,
			long judgmentsBytes,
			byte[] evaluatorSourceArtifact,
			byte[] rankingSnapshotArtifact,
			byte[] scoringResultArtifact,
			byte[] evidenceReportArtifact) {
		this.reportId = reportId;
		this.firstRunKey = firstRunKey;
		this.freezeSchemaVersion = freezeSchemaVersion;
		this.sourceInventoryId = sourceInventoryId;
		this.evaluatorRevision = evaluatorRevision;
		this.evaluatorSourceSha256 = evaluatorSourceSha256;
		this.candidateRevision = candidateRevision;
		this.candidateSourceSha256 = candidateSourceSha256;
		this.rankingSnapshotSha256 = rankingSnapshotSha256;
		this.evaluatorSourceArtifactSha256 = evaluatorSourceArtifactSha256;
		this.rankingSnapshotArtifactSha256 = rankingSnapshotArtifactSha256;
		this.scoringResultSha256 = scoringResultSha256;
		this.scoringResultArtifactSha256 = scoringResultArtifactSha256;
		this.judgmentsBytes = judgmentsBytes;
		this.evaluatorSourceBytes = evaluatorSourceArtifact.length;
		this.rankingSnapshotBytes = rankingSnapshotArtifact.length;
		this.scoringResultBytes = scoringResultArtifact.length;
		Map<String, byte[]> frozenArtifacts = new LinkedHashMap<>();
		frozenArtifacts.put(EVALUATOR_SOURCE_FILENAME, evaluatorSourceArtifact.clone());
		frozenArtifacts.put(RANKING_SNAPSHOT_FILENAME, rankingSnapshotArtifact.clone());
		frozenArtifacts.put(SCORING_RESULT_FILENAME, scoringResultArtifact.clone());
		frozenArtifacts.put(EVIDENCE_REPORT_FILENAME, evidenceReportArtifact.clone());
		this.artifacts = Collections.unmodifiableMap(frozenArtifacts);
	}

	static RelatedTopicReuseHoldoutEvidenceReport create(
			RelatedTopicReuseHoldoutScorer.VerifiedScoringOutcome scoringOutcome) {
		RelatedTopicReuseHoldoutScorer.VerifiedScoringOutcome frozenOutcome =
				Objects.requireNonNull(scoringOutcome, "scoringOutcome");
		RelatedTopicReuseHoldoutPostgresFirstRunLedger.FirstRunEvidence frozenEvidence =
				frozenOutcome.firstRunEvidence();
		RelatedTopicReuseHoldoutRankingSnapshot frozenSnapshot =
				frozenOutcome.rankingSnapshot();
		RelatedTopicReuseHoldoutScoringResult frozenResult = frozenOutcome.result();
		if (!frozenOutcome.authorizes(frozenSnapshot, frozenResult)
				|| !frozenEvidence.authorizes(frozenSnapshot)) {
			throw new IllegalArgumentException(
					"verified scoring outcome is not bound to the completed first run");
		}
		RelatedTopicReuseHoldoutEvaluatorSeal.VerifiedEvaluatorSeal frozenSeal =
				frozenEvidence.evaluatorSeal();
		if (!frozenSeal.candidateRevision().equals(frozenSnapshot.candidateRevision())) {
			throw new IllegalArgumentException(
					"evaluator seal and ranking snapshot candidate revisions must match");
		}
		return create(
				frozenEvidence.runKey(),
				frozenEvidence.evaluationProtocolId(),
				frozenEvidence.policyId(),
				frozenEvidence.freezeSchemaVersion(),
				frozenEvidence.inventoryId(),
				frozenSeal.evaluatorRevision(),
				frozenSeal.evaluatorSourceSha256(),
				frozenSeal.candidateRevision(),
				frozenSeal.candidateSourceSha256(),
				frozenSeal.files(),
				frozenSnapshot,
				frozenResult);
	}

	static VerifiedArtifacts verifyExact(
			RelatedTopicReuseHoldoutScorer.VerifiedScoringOutcome scoringOutcome,
			Map<String, byte[]> observedArtifacts) {
		RelatedTopicReuseHoldoutEvidenceReport expected = create(scoringOutcome);
		Objects.requireNonNull(observedArtifacts, "observedArtifacts");
		Map<String, byte[]> frozenObservedArtifacts = new LinkedHashMap<>();
		observedArtifacts.forEach((filename, bytes) -> frozenObservedArtifacts.put(
				Objects.requireNonNull(filename, "artifact filename"),
				Objects.requireNonNull(bytes, filename).clone()));
		if (!new ArrayList<>(frozenObservedArtifacts.keySet())
				.equals(new ArrayList<>(expected.artifacts.keySet()))) {
			throw new IllegalArgumentException(
					"evidence artifacts must contain the complete fixed filename order");
		}
		long totalBytes = 0L;
		Map<String, String> digests = new LinkedHashMap<>();
		for (Map.Entry<String, byte[]> entry : expected.artifacts.entrySet()) {
			byte[] observed = Objects.requireNonNull(
					frozenObservedArtifacts.get(entry.getKey()), entry.getKey());
			requireArtifactSize(
					entry.getKey(), observed, maximumArtifactBytes(entry.getKey()));
			if (!MessageDigest.isEqual(observed, entry.getValue())) {
				throw new IllegalArgumentException(
						entry.getKey() + " is not the exact regenerated canonical artifact");
			}
			totalBytes = Math.addExact(totalBytes, observed.length);
			digests.put(entry.getKey(), sha256(observed));
		}
		return new VerifiedArtifacts(
				expected.reportId(), totalBytes, digests, frozenObservedArtifacts);
	}

	private static RelatedTopicReuseHoldoutEvidenceReport create(
			String firstRunKey,
			String claimedEvaluationProtocolId,
			String claimedPolicyId,
			int freezeSchemaVersion,
			String sourceInventoryId,
			String evaluatorRevision,
			String evaluatorSourceSha256,
			String sealedCandidateRevision,
			String candidateSourceSha256,
			List<RelatedTopicReuseHoldoutEvaluatorSeal.SourceFileCommitment>
					sourceCommitments,
			RelatedTopicReuseHoldoutRankingSnapshot snapshot,
			RelatedTopicReuseHoldoutScoringResult scoringResult) {
		String frozenFirstRunKey = requirePattern(
				firstRunKey, "firstRunKey", SHA256);
		if (!EVALUATION_PROTOCOL_ID.equals(claimedEvaluationProtocolId)
				|| !RelatedTopicReuseHoldoutPolicy.POLICY_ID.equals(claimedPolicyId)
				|| freezeSchemaVersion
						!= RelatedTopicReuseHoldoutGitCollector.FREEZE_SCHEMA_VERSION
				|| !RelatedTopicReuseHoldoutGitCollector.INVENTORY_ID.equals(
						sourceInventoryId)) {
			throw new IllegalArgumentException(
					"first-run evidence does not match the frozen operator contract");
		}
		String frozenEvaluatorRevision = requirePattern(
				evaluatorRevision, "evaluatorRevision", GIT_REVISION);
		String frozenEvaluatorSource = requirePattern(
				evaluatorSourceSha256, "evaluatorSourceSha256", SHA256);
		String frozenCandidateSource = requirePattern(
				candidateSourceSha256, "candidateSourceSha256", SHA256);
		String frozenSealedCandidateRevision = requirePattern(
				sealedCandidateRevision, "sealedCandidateRevision", GIT_REVISION);
		RelatedTopicReuseHoldoutRankingSnapshot frozenSnapshot =
				Objects.requireNonNull(snapshot, "snapshot");
		if (!frozenSealedCandidateRevision.equals(frozenSnapshot.candidateRevision())) {
			throw new IllegalArgumentException(
					"evaluator seal and ranking snapshot candidate revisions must match");
		}
		RelatedTopicReuseHoldoutScoringResult frozenResult =
				Objects.requireNonNull(scoringResult, "scoringResult");
		validateBindings(frozenSnapshot, frozenResult);
		if (!claimedEvaluationProtocolId.equals(
				frozenResult.identity().evaluationProtocolId())) {
			throw new IllegalArgumentException(
					"first-run evidence and score protocol must match");
		}

		String snapshotSha256 = frozenSnapshot.evidenceSha256();
		byte[] sourceArtifact = canonicalBytes(sourceProjection(
				frozenFirstRunKey,
				claimedEvaluationProtocolId,
				claimedPolicyId,
				freezeSchemaVersion,
				sourceInventoryId,
				frozenEvaluatorRevision,
				frozenEvaluatorSource,
				frozenSealedCandidateRevision,
				frozenCandidateSource,
				sourceCommitments));
		byte[] snapshotArtifact = canonicalBytes(snapshotProjection(
				frozenSnapshot, snapshotSha256));
		byte[] resultArtifact = canonicalBytes(resultProjection(frozenResult));
		requireArtifactSize(
				EVALUATOR_SOURCE_FILENAME,
				sourceArtifact,
				MAXIMUM_SOURCE_ARTIFACT_BYTES);
		requireArtifactSize(
				RANKING_SNAPSHOT_FILENAME,
				snapshotArtifact,
				MAXIMUM_SNAPSHOT_ARTIFACT_BYTES);
		requireArtifactSize(
				SCORING_RESULT_FILENAME,
				resultArtifact,
				MAXIMUM_RESULT_ARTIFACT_BYTES);
		String sourceArtifactSha256 = sha256(sourceArtifact);
		String snapshotArtifactSha256 = sha256(snapshotArtifact);
		String resultArtifactSha256 = sha256(resultArtifact);
		String resultSha256 = resultDigest(resultArtifact);
		String reportId = reportId(
				frozenFirstRunKey,
				claimedEvaluationProtocolId,
				claimedPolicyId,
				freezeSchemaVersion,
				sourceInventoryId,
				frozenEvaluatorRevision,
				frozenEvaluatorSource,
				frozenSnapshot.candidateRevision(),
				frozenCandidateSource,
				frozenResult.identity().evaluationProtocolId(),
				frozenSnapshot.bundleId(),
				frozenSnapshot.corpusId(),
				frozenSnapshot.policySha256(),
				frozenSnapshot.corpusSha256(),
				frozenSnapshot.manifestSha256(),
				frozenSnapshot.judgmentsSha256(),
				frozenSnapshot.judgmentsBytes(),
				sourceArtifactSha256,
				sourceArtifact.length,
				snapshotSha256,
				snapshotArtifactSha256,
				snapshotArtifact.length,
				resultSha256,
				resultArtifactSha256,
				resultArtifact.length);
		byte[] reportArtifact = canonicalBytes(reportProjection(
				reportId,
				frozenFirstRunKey,
				claimedEvaluationProtocolId,
				claimedPolicyId,
				freezeSchemaVersion,
				sourceInventoryId,
				frozenEvaluatorRevision,
				frozenEvaluatorSource,
				frozenCandidateSource,
				frozenSnapshot,
				frozenResult,
				sourceArtifactSha256,
				sourceArtifact.length,
				snapshotSha256,
				snapshotArtifactSha256,
				snapshotArtifact.length,
				resultSha256,
				resultArtifactSha256,
				resultArtifact.length));
		requireArtifactSize(
				EVIDENCE_REPORT_FILENAME,
				reportArtifact,
				MAXIMUM_REPORT_ARTIFACT_BYTES);
		return new RelatedTopicReuseHoldoutEvidenceReport(
				reportId,
				frozenFirstRunKey,
				freezeSchemaVersion,
				sourceInventoryId,
				frozenEvaluatorRevision,
				frozenEvaluatorSource,
				frozenSnapshot.candidateRevision(),
				frozenCandidateSource,
				snapshotSha256,
				sourceArtifactSha256,
				snapshotArtifactSha256,
				resultSha256,
				resultArtifactSha256,
				frozenSnapshot.judgmentsBytes(),
				sourceArtifact,
				snapshotArtifact,
				resultArtifact,
				reportArtifact);
	}

	int schemaVersion() {
		return SCHEMA_VERSION;
	}

	String reportId() {
		return reportId;
	}

	String firstRunKey() {
		return firstRunKey;
	}

	int freezeSchemaVersion() {
		return freezeSchemaVersion;
	}

	String sourceInventoryId() {
		return sourceInventoryId;
	}

	String evaluatorRevision() {
		return evaluatorRevision;
	}

	String evaluatorSourceSha256() {
		return evaluatorSourceSha256;
	}

	String candidateRevision() {
		return candidateRevision;
	}

	String candidateSourceSha256() {
		return candidateSourceSha256;
	}

	String rankingSnapshotSha256() {
		return rankingSnapshotSha256;
	}

	String evaluatorSourceArtifactSha256() {
		return evaluatorSourceArtifactSha256;
	}

	String rankingSnapshotArtifactSha256() {
		return rankingSnapshotArtifactSha256;
	}

	String scoringResultSha256() {
		return scoringResultSha256;
	}

	String scoringResultArtifactSha256() {
		return scoringResultArtifactSha256;
	}

	long judgmentsBytes() {
		return judgmentsBytes;
	}

	long evaluatorSourceBytes() {
		return evaluatorSourceBytes;
	}

	long rankingSnapshotBytes() {
		return rankingSnapshotBytes;
	}

	long scoringResultBytes() {
		return scoringResultBytes;
	}

	boolean readerFacing() {
		return false;
	}

	boolean externalBundleAcceptanceAuthorized() {
		return false;
	}

	boolean custodyReleaseAuthorized() {
		return false;
	}

	boolean productActivationAuthorized() {
		return false;
	}

	byte[] rankingSnapshotJson() {
		return artifact(RANKING_SNAPSHOT_FILENAME);
	}

	byte[] evaluatorSourceJson() {
		return artifact(EVALUATOR_SOURCE_FILENAME);
	}

	byte[] scoringResultJson() {
		return artifact(SCORING_RESULT_FILENAME);
	}

	byte[] evidenceReportJson() {
		return artifact(EVIDENCE_REPORT_FILENAME);
	}

	byte[] artifact(String filename) {
		byte[] value = artifacts.get(filename);
		if (value == null) {
			throw new IllegalArgumentException("unknown evidence artifact: " + filename);
		}
		return value.clone();
	}

	Map<String, byte[]> artifacts() {
		Map<String, byte[]> copy = new LinkedHashMap<>();
		artifacts.forEach((filename, bytes) -> copy.put(filename, bytes.clone()));
		return Collections.unmodifiableMap(copy);
	}

	Map<String, byte[]> artifactBytes() {
		return artifacts();
	}

	private static void validateBindings(
			RelatedTopicReuseHoldoutRankingSnapshot snapshot,
			RelatedTopicReuseHoldoutScoringResult result) {
		RelatedTopicReuseHoldoutScoringResult.ScoreIdentity identity = result.identity();
		boolean identityMatches = EVALUATION_PROTOCOL_ID.equals(identity.evaluationProtocolId())
				&& snapshot.bundleId().equals(identity.bundleId())
				&& snapshot.corpusId().equals(identity.corpusId())
				&& snapshot.policySha256().equals(identity.policySha256())
				&& snapshot.corpusSha256().equals(identity.corpusSha256())
				&& snapshot.manifestSha256().equals(identity.manifestSha256())
				&& snapshot.judgmentsSha256().equals(identity.judgmentsSha256())
				&& snapshot.evidenceSha256().equals(identity.rankingSnapshotSha256())
				&& snapshot.judgmentsBytes() == identity.judgmentsBytes()
				&& snapshot.candidateRevision().equals(identity.candidateRevision())
				&& snapshot.cutoff() == identity.cutoff()
				&& snapshot.queryOrder().equals(identity.queryOrder());
		if (!identityMatches) {
			throw new IllegalArgumentException(
					"snapshot and scoring result identities must match exactly");
		}
		if (snapshot.counters().providerCallCount()
					!= result.aggregate().providerCallCount()
				|| snapshot.counters().experimentalSnapshotWriteCount()
						!= result.aggregate().experimentalSnapshotWriteCount()) {
			throw new IllegalArgumentException(
					"snapshot and scoring result structural counters must match exactly");
		}
		if (result.readerFacing()
				|| result.externalBundleAcceptanceAuthorized()
				|| result.custodyReleaseAuthorized()
				|| result.productActivationAuthorized()) {
			throw new IllegalArgumentException(
					"holdout evidence cannot grant reader, acceptance, custody, or activation authorization");
		}
	}

	private static Map<String, Object> snapshotProjection(
			RelatedTopicReuseHoldoutRankingSnapshot snapshot, String snapshotSha256) {
		Map<String, Object> root = orderedMap();
		root.put("schemaVersion", SCHEMA_VERSION);
		root.put("artifactType", SNAPSHOT_ARTIFACT_TYPE);
		root.put("rankingSnapshotSha256", snapshotSha256);
		root.put("bundleId", snapshot.bundleId());
		root.put("corpusId", snapshot.corpusId());
		root.put("policySha256", snapshot.policySha256());
		root.put("corpusSha256", snapshot.corpusSha256());
		root.put("manifestSha256", snapshot.manifestSha256());
		root.put("judgmentsSha256", snapshot.judgmentsSha256());
		root.put("judgmentsBytes", snapshot.judgmentsBytes());
		root.put("candidateRevision", snapshot.candidateRevision());
		root.put("cutoff", snapshot.cutoff());
		root.put("queryOrder", List.copyOf(snapshot.queryOrder()));
		root.put("queries", project(snapshot.queries(),
				RelatedTopicReuseHoldoutEvidenceReport::queryRankingProjection));
		root.put("counters", countersProjection(snapshot.counters()));
		return root;
	}

	private static Map<String, Object> sourceProjection(
			String firstRunKey,
			String evaluationProtocolId,
			String policyId,
			int freezeSchemaVersion,
			String sourceInventoryId,
			String evaluatorRevision,
			String evaluatorSourceSha256,
			String candidateRevision,
			String candidateSourceSha256,
			List<RelatedTopicReuseHoldoutEvaluatorSeal.SourceFileCommitment> files) {
		Map<String, Object> root = orderedMap();
		root.put("schemaVersion", SCHEMA_VERSION);
		root.put("artifactType", SOURCE_ARTIFACT_TYPE);
		root.put("firstRunKey", firstRunKey);
		root.put("evaluationProtocolId", evaluationProtocolId);
		root.put("policyId", policyId);
		root.put("freezeSchemaVersion", freezeSchemaVersion);
		root.put("sourceInventoryId", sourceInventoryId);
		root.put("evaluatorRevision", evaluatorRevision);
		root.put("evaluatorSourceSha256", evaluatorSourceSha256);
		root.put("candidateRevision", candidateRevision);
		root.put("candidateSourceSha256", candidateSourceSha256);
		root.put("files", project(
				List.copyOf(Objects.requireNonNull(files, "sourceCommitments")),
				RelatedTopicReuseHoldoutEvidenceReport::sourceFileProjection));
		return root;
	}

	private static Map<String, Object> sourceFileProjection(
			RelatedTopicReuseHoldoutEvaluatorSeal.SourceFileCommitment file) {
		Map<String, Object> value = orderedMap();
		value.put("role", file.role().name());
		value.put("gitMode", file.gitMode());
		value.put("path", file.path());
		value.put("bytes", file.bytes());
		value.put("sha256", file.sha256());
		return value;
	}

	private static Map<String, Object> queryRankingProjection(
			RelatedTopicReuseHoldoutRankingSnapshot.QueryRanking query) {
		Map<String, Object> value = orderedMap();
		value.put("queryKey", query.queryKey());
		value.put("initialRun", rankingRunProjection(query.initialRun()));
		value.put("repeatedRun", rankingRunProjection(query.repeatedRun()));
		value.put("hiddenPerturbation",
				hiddenPerturbationProjection(query.hiddenPerturbation()));
		return value;
	}

	private static Map<String, Object> rankingRunProjection(
			RelatedTopicReuseHoldoutRankingSnapshot.RankingRun run) {
		Map<String, Object> value = orderedMap();
		value.put("controlPool", project(run.controlPool(),
				RelatedTopicReuseHoldoutEvidenceReport::rankedPaperProjection));
		value.put("controlTop10", project(run.controlTop10(),
				RelatedTopicReuseHoldoutEvidenceReport::rankedPaperProjection));
		value.put("eligibleSeedKeys", List.copyOf(run.eligibleSeedKeys()));
		value.put("feedbackPools", project(run.feedbackPools(),
				RelatedTopicReuseHoldoutEvidenceReport::feedbackPoolProjection));
		value.put("candidateTop10", project(run.candidateTop10(),
				RelatedTopicReuseHoldoutEvidenceReport::rankedPaperProjection));
		return value;
	}

	private static Map<String, Object> hiddenPerturbationProjection(
			RelatedTopicReuseHoldoutRankingSnapshot.HiddenPerturbation hidden) {
		Map<String, Object> value = orderedMap();
		value.put("otherOwnerCandidateKey", hidden.otherOwnerCandidateKey());
		value.put("catalogOnlyCandidateKey", hidden.catalogOnlyCandidateKey());
		value.put("visibleFeedbackPools", project(hidden.visibleFeedbackPools(),
				RelatedTopicReuseHoldoutEvidenceReport::feedbackPoolProjection));
		value.put("visibleCandidateTop10", project(hidden.visibleCandidateTop10(),
				RelatedTopicReuseHoldoutEvidenceReport::rankedPaperProjection));
		return value;
	}

	private static Map<String, Object> feedbackPoolProjection(
			RelatedTopicReuseHoldoutRankingSnapshot.FeedbackPool pool) {
		Map<String, Object> value = orderedMap();
		value.put("seedPaperKey", pool.seedPaperKey());
		value.put("candidates", project(pool.candidates(),
				RelatedTopicReuseHoldoutEvidenceReport::rankedPaperProjection));
		return value;
	}

	private static Map<String, Object> rankedPaperProjection(
			RelatedTopicReuseHoldoutRankingSnapshot.RankedPaper paper) {
		Map<String, Object> value = orderedMap();
		value.put("paperKey", paper.paperKey());
		value.put("scoreBits", HEX.toHexDigits(paper.scoreBits()));
		return value;
	}

	private static Map<String, Object> countersProjection(
			RelatedTopicReuseHoldoutRankingSnapshot.StructuralCounters counters) {
		Map<String, Object> value = orderedMap();
		value.put("providerCallCount", counters.providerCallCount());
		value.put("experimentalSnapshotWriteCount",
				counters.experimentalSnapshotWriteCount());
		return value;
	}

	private static Map<String, Object> resultProjection(
			RelatedTopicReuseHoldoutScoringResult result) {
		Map<String, Object> root = orderedMap();
		root.put("schemaVersion", SCHEMA_VERSION);
		root.put("artifactType", RESULT_ARTIFACT_TYPE);
		root.put("identity", scoreIdentityProjection(result.identity()));
		root.put("queries", project(result.queries(),
				RelatedTopicReuseHoldoutEvidenceReport::queryScoreProjection));
		root.put("control", rankingSummaryProjection(result.control()));
		root.put("candidate", rankingSummaryProjection(result.candidate()));
		root.put("aggregate", aggregateProjection(result.aggregate()));
		root.put("structural", structuralProjection(result.structural()));
		root.put("gates", project(result.gates(),
				RelatedTopicReuseHoldoutEvidenceReport::gateOutcomeProjection));
		root.put("policyGatesPassed", result.policyGatesPassed());
		root.put("readerFacing", result.readerFacing());
		root.put("externalBundleAcceptanceAuthorized",
				result.externalBundleAcceptanceAuthorized());
		root.put("custodyReleaseAuthorized", result.custodyReleaseAuthorized());
		root.put("productActivationAuthorized", result.productActivationAuthorized());
		return root;
	}

	private static Map<String, Object> scoreIdentityProjection(
			RelatedTopicReuseHoldoutScoringResult.ScoreIdentity identity) {
		Map<String, Object> value = orderedMap();
		value.put("evaluationProtocolId", identity.evaluationProtocolId());
		value.put("bundleId", identity.bundleId());
		value.put("corpusId", identity.corpusId());
		value.put("policySha256", identity.policySha256());
		value.put("corpusSha256", identity.corpusSha256());
		value.put("manifestSha256", identity.manifestSha256());
		value.put("judgmentsSha256", identity.judgmentsSha256());
		value.put("rankingSnapshotSha256", identity.rankingSnapshotSha256());
		value.put("judgmentsBytes", identity.judgmentsBytes());
		value.put("candidateRevision", identity.candidateRevision());
		value.put("cutoff", identity.cutoff());
		value.put("queryOrder", List.copyOf(identity.queryOrder()));
		return value;
	}

	private static Map<String, Object> queryScoreProjection(
			RelatedTopicReuseHoldoutScoringResult.QueryScore query) {
		Map<String, Object> value = orderedMap();
		value.put("queryKey", query.queryKey());
		value.put("queryKind", query.queryKind().name());
		value.put("control", rankingMetricsProjection(query.control()));
		value.put("candidate", rankingMetricsProjection(query.candidate()));
		value.put("deltas", metricDeltasProjection(query.deltas()));
		value.put("novelRelevantAt10", query.novelRelevantAt10());
		value.put("controlExplicitAdversaryAt10Count",
				query.controlExplicitAdversaryAt10Count());
		value.put("candidateExplicitAdversaryAt10Count",
				query.candidateExplicitAdversaryAt10Count());
		value.put("rankOneIrrelevant", query.rankOneIrrelevant());
		value.put("ownerScopeViolationCount", query.ownerScopeViolationCount());
		value.put("filterViolationCount", query.filterViolationCount());
		value.put("repeatedStable", query.repeatedStable());
		value.put("hiddenNoninterference", query.hiddenNoninterference());
		value.put("exactFallback", query.exactFallback());
		value.put("recallNonregression", query.recallNonregression());
		value.put("controlNonregression", query.controlNonregression());
		value.put("filteredOpportunityStrictImprovement",
				query.filteredOpportunityStrictImprovement());
		value.put("authorRelevantBaselineHit", query.authorRelevantBaselineHit());
		value.put("authorZeroEligibleSeedsAndFeedback",
				query.authorZeroEligibleSeedsAndFeedback());
		value.put("noSeedZeroEligibleSeedsAndFeedback",
				query.noSeedZeroEligibleSeedsAndFeedback());
		return value;
	}

	private static Map<String, Object> rankingMetricsProjection(
			RelatedTopicReuseHoldoutScoringResult.RankingMetrics metrics) {
		Map<String, Object> value = orderedMap();
		value.put("relevantCandidateCount", metrics.relevantCandidateCount());
		value.put("retrievedRelevantCount", metrics.retrievedRelevantCount());
		value.put("recallAt10Bits", doubleBits(metrics.recallAt10()));
		value.put("ndcgAt10Bits", doubleBits(metrics.ndcgAt10()));
		value.put("precisionAt1Bits", doubleBits(metrics.precisionAt1()));
		value.put("reciprocalRankAt10Bits", doubleBits(metrics.reciprocalRankAt10()));
		return value;
	}

	private static Map<String, Object> metricDeltasProjection(
			RelatedTopicReuseHoldoutScoringResult.MetricDeltas deltas) {
		Map<String, Object> value = orderedMap();
		value.put("recallAt10Bits", doubleBits(deltas.recallAt10()));
		value.put("ndcgAt10Bits", doubleBits(deltas.ndcgAt10()));
		value.put("precisionAt1Bits", doubleBits(deltas.precisionAt1()));
		value.put("reciprocalRankAt10Bits", doubleBits(deltas.reciprocalRankAt10()));
		return value;
	}

	private static Map<String, Object> rankingSummaryProjection(
			RelatedTopicReuseHoldoutScoringResult.RankingSummary summary) {
		Map<String, Object> value = orderedMap();
		value.put("queryCount", summary.queryCount());
		value.put("recallQueryCount", summary.recallQueryCount());
		value.put("ndcgQueryCount", summary.ndcgQueryCount());
		value.put("precisionAt1QueryCount", summary.precisionAt1QueryCount());
		value.put("reciprocalRankQueryCount", summary.reciprocalRankQueryCount());
		value.put("macroRecallAt10Bits", doubleBits(summary.macroRecallAt10()));
		value.put("macroNdcgAt10Bits", doubleBits(summary.macroNdcgAt10()));
		value.put("macroPrecisionAt1Bits", doubleBits(summary.macroPrecisionAt1()));
		value.put("meanReciprocalRankAt10Bits",
				doubleBits(summary.meanReciprocalRankAt10()));
		return value;
	}

	private static Map<String, Object> aggregateProjection(
			RelatedTopicReuseHoldoutScoringResult.AggregateMetrics aggregate) {
		Map<String, Object> value = orderedMap();
		value.put("macroRecallAt10DeltaBits",
				doubleBits(aggregate.macroRecallAt10Delta()));
		value.put("macroNdcgAt10DeltaBits",
				doubleBits(aggregate.macroNdcgAt10Delta()));
		value.put("macroPrecisionAt1DeltaBits",
				doubleBits(aggregate.macroPrecisionAt1Delta()));
		value.put("macroMeanReciprocalRankAt10DeltaBits",
				doubleBits(aggregate.macroMeanReciprocalRankAt10Delta()));
		value.put("strictOpportunityRecallImprovementCount",
				aggregate.strictOpportunityRecallImprovementCount());
		value.put("novelRelevantAt10", aggregate.novelRelevantAt10());
		value.put("perQueryNdcgRegressionCount",
				aggregate.perQueryNdcgRegressionCount());
		value.put("maximumPerQueryNdcgRegressionBits",
				doubleBits(aggregate.maximumPerQueryNdcgRegression()));
		value.put("controlExplicitAdversaryAt10Count",
				aggregate.controlExplicitAdversaryAt10Count());
		value.put("candidateExplicitAdversaryAt10Count",
				aggregate.candidateExplicitAdversaryAt10Count());
		value.put("rankOneIrrelevantCount", aggregate.rankOneIrrelevantCount());
		value.put("ownerScopeLeakCount", aggregate.ownerScopeLeakCount());
		value.put("filterViolationCount", aggregate.filterViolationCount());
		value.put("providerCallCount", aggregate.providerCallCount());
		value.put("experimentalSnapshotWriteCount",
				aggregate.experimentalSnapshotWriteCount());
		return value;
	}

	private static Map<String, Object> structuralProjection(
			RelatedTopicReuseHoldoutScoringResult.StructuralAssessment structural) {
		Map<String, Object> value = orderedMap();
		value.put("recallRegressionQueryCount",
				structural.recallRegressionQueryCount());
		value.put("controlRegressionQueryCount",
				structural.controlRegressionQueryCount());
		value.put("filteredOpportunityFailureCount",
				structural.filteredOpportunityFailureCount());
		value.put("authorRelevantBaselineFailureCount",
				structural.authorRelevantBaselineFailureCount());
		value.put("authorZeroSeedFeedbackFailureCount",
				structural.authorZeroSeedFeedbackFailureCount());
		value.put("noSeedZeroSeedFeedbackFailureCount",
				structural.noSeedZeroSeedFeedbackFailureCount());
		value.put("repeatedInstabilityCount", structural.repeatedInstabilityCount());
		value.put("hiddenInterferenceCount", structural.hiddenInterferenceCount());
		value.put("fallbackMismatchCount", structural.fallbackMismatchCount());
		return value;
	}

	private static Map<String, Object> gateOutcomeProjection(
			RelatedTopicReuseHoldoutScoringResult.GateOutcome outcome) {
		Map<String, Object> value = orderedMap();
		value.put("gate", outcome.gate().name());
		value.put("passed", outcome.passed());
		return value;
	}

	private static Map<String, Object> reportProjection(
			String reportId,
			String firstRunKey,
			String evaluationProtocolId,
			String policyId,
			int freezeSchemaVersion,
			String sourceInventoryId,
			String evaluatorRevision,
			String evaluatorSourceSha256,
			String candidateSourceSha256,
			RelatedTopicReuseHoldoutRankingSnapshot snapshot,
			RelatedTopicReuseHoldoutScoringResult result,
			String sourceArtifactSha256,
			long sourceBytes,
			String snapshotSha256,
			String snapshotArtifactSha256,
			long snapshotBytes,
			String resultSha256,
			String resultArtifactSha256,
			long resultBytes) {
		Map<String, Object> bindings = orderedMap();
		bindings.put("firstRunKey", firstRunKey);
		bindings.put("evaluationProtocolId", evaluationProtocolId);
		bindings.put("policyId", policyId);
		bindings.put("freezeSchemaVersion", freezeSchemaVersion);
		bindings.put("sourceInventoryId", sourceInventoryId);
		bindings.put("evaluatorRevision", evaluatorRevision);
		bindings.put("evaluatorSourceSha256", evaluatorSourceSha256);
		bindings.put("candidateRevision", snapshot.candidateRevision());
		bindings.put("candidateSourceSha256", candidateSourceSha256);
		bindings.put("bundleId", snapshot.bundleId());
		bindings.put("corpusId", snapshot.corpusId());
		bindings.put("policySha256", snapshot.policySha256());
		bindings.put("corpusSha256", snapshot.corpusSha256());
		bindings.put("manifestSha256", snapshot.manifestSha256());
		bindings.put("judgmentsSha256", snapshot.judgmentsSha256());
		bindings.put("judgmentsBytes", snapshot.judgmentsBytes());
		bindings.put("rankingSnapshotSha256", snapshotSha256);
		bindings.put("scoringResultSha256", resultSha256);

		Map<String, Object> sourceFile = orderedMap();
		sourceFile.put("filename", EVALUATOR_SOURCE_FILENAME);
		sourceFile.put("bytes", sourceBytes);
		sourceFile.put("sha256", sourceArtifactSha256);
		sourceFile.put("semanticSha256", evaluatorSourceSha256);
		Map<String, Object> snapshotFile = orderedMap();
		snapshotFile.put("filename", RANKING_SNAPSHOT_FILENAME);
		snapshotFile.put("bytes", snapshotBytes);
		snapshotFile.put("sha256", snapshotArtifactSha256);
		snapshotFile.put("semanticSha256", snapshotSha256);
		Map<String, Object> resultFile = orderedMap();
		resultFile.put("filename", SCORING_RESULT_FILENAME);
		resultFile.put("bytes", resultBytes);
		resultFile.put("sha256", resultArtifactSha256);
		resultFile.put("semanticSha256", resultSha256);

		Map<String, Object> resultCommitment = orderedMap();
		resultCommitment.put("domain", RESULT_DIGEST_DOMAIN);
		resultCommitment.put("version", RESULT_DIGEST_VERSION);
		resultCommitment.put("sha256", resultSha256);

		Map<String, Object> authorization = orderedMap();
		authorization.put("readerFacing", false);
		authorization.put("externalBundleAcceptanceAuthorized", false);
		authorization.put("custodyReleaseAuthorized", false);
		authorization.put("productActivationAuthorized", false);

		Map<String, Object> root = orderedMap();
		root.put("schemaVersion", SCHEMA_VERSION);
		root.put("artifactType", ARTIFACT_TYPE);
		root.put("reportId", reportId);
		root.put("bindings", bindings);
		root.put("files", List.of(sourceFile, snapshotFile, resultFile));
		root.put("resultCommitment", resultCommitment);
		root.put("authorization", authorization);
		return root;
	}

	private static String resultDigest(byte[] resultArtifact) {
		MessageDigest digest = sha256Digest();
		updateString(digest, RESULT_DIGEST_DOMAIN);
		updateInt(digest, RESULT_DIGEST_VERSION);
		updateLong(digest, resultArtifact.length);
		digest.update(resultArtifact);
		return HEX.formatHex(digest.digest());
	}

	private static String reportId(
			String firstRunKey,
			String claimedEvaluationProtocolId,
			String claimedPolicyId,
			int freezeSchemaVersion,
			String sourceInventoryId,
			String evaluatorRevision,
			String evaluatorSourceSha256,
			String candidateRevision,
			String candidateSourceSha256,
			String evaluationProtocolId,
			String bundleId,
			String corpusId,
			String policySha256,
			String corpusSha256,
			String manifestSha256,
			String judgmentsSha256,
			long judgmentsBytes,
			String sourceArtifactSha256,
			long sourceBytes,
			String snapshotSha256,
			String snapshotArtifactSha256,
			long snapshotBytes,
			String resultSha256,
			String resultArtifactSha256,
			long resultBytes) {
		MessageDigest digest = sha256Digest();
		updateString(digest, REPORT_ID_DOMAIN);
		updateInt(digest, SCHEMA_VERSION);
		updateString(digest, ARTIFACT_TYPE);
		updateString(digest, EVIDENCE_REPORT_FILENAME);
		updateString(digest, firstRunKey);
		updateString(digest, claimedEvaluationProtocolId);
		updateString(digest, claimedPolicyId);
		updateInt(digest, freezeSchemaVersion);
		updateString(digest, sourceInventoryId);
		updateString(digest, evaluatorRevision);
		updateString(digest, evaluatorSourceSha256);
		updateString(digest, candidateRevision);
		updateString(digest, candidateSourceSha256);
		updateString(digest, evaluationProtocolId);
		updateString(digest, bundleId);
		updateString(digest, corpusId);
		updateString(digest, policySha256);
		updateString(digest, corpusSha256);
		updateString(digest, manifestSha256);
		updateString(digest, judgmentsSha256);
		updateLong(digest, judgmentsBytes);
		updateString(digest, EVALUATOR_SOURCE_FILENAME);
		updateString(digest, sourceArtifactSha256);
		updateLong(digest, sourceBytes);
		updateString(digest, RANKING_SNAPSHOT_FILENAME);
		updateString(digest, snapshotSha256);
		updateString(digest, snapshotArtifactSha256);
		updateLong(digest, snapshotBytes);
		updateString(digest, SCORING_RESULT_FILENAME);
		updateString(digest, resultSha256);
		updateString(digest, resultArtifactSha256);
		updateLong(digest, resultBytes);
		updateString(digest, RESULT_DIGEST_DOMAIN);
		updateInt(digest, RESULT_DIGEST_VERSION);
		updateAuthorizationBoundary(digest, "readerFacing", false);
		updateAuthorizationBoundary(
				digest, "externalBundleAcceptanceAuthorized", false);
		updateAuthorizationBoundary(digest, "custodyReleaseAuthorized", false);
		updateAuthorizationBoundary(digest, "productActivationAuthorized", false);
		return REPORT_ID_PREFIX + HEX.formatHex(digest.digest());
	}

	private static void updateAuthorizationBoundary(
			MessageDigest digest, String name, boolean authorized) {
		updateString(digest, name);
		digest.update((byte) (authorized ? 1 : 0));
	}

	private static byte[] canonicalBytes(Object value) {
		byte[] json = CANONICAL_WRITER.writeValueAsBytes(value);
		if (json.length > 0 && json[json.length - 1] == '\n') {
			return json;
		}
		byte[] terminated = Arrays.copyOf(json, json.length + 1);
		terminated[terminated.length - 1] = '\n';
		return terminated;
	}

	private static String sha256(byte[] bytes) {
		return HEX.formatHex(sha256Digest().digest(bytes));
	}

	private static MessageDigest sha256Digest() {
		try {
			return MessageDigest.getInstance("SHA-256");
		}
		catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException("SHA-256 is unavailable", exception);
		}
	}

	private static void updateString(MessageDigest digest, String value) {
		byte[] encoded = value.getBytes(StandardCharsets.UTF_8);
		updateInt(digest, encoded.length);
		digest.update(encoded);
	}

	private static void updateInt(MessageDigest digest, int value) {
		digest.update((byte) (value >>> 24));
		digest.update((byte) (value >>> 16));
		digest.update((byte) (value >>> 8));
		digest.update((byte) value);
	}

	private static void updateLong(MessageDigest digest, long value) {
		digest.update((byte) (value >>> 56));
		digest.update((byte) (value >>> 48));
		digest.update((byte) (value >>> 40));
		digest.update((byte) (value >>> 32));
		digest.update((byte) (value >>> 24));
		digest.update((byte) (value >>> 16));
		digest.update((byte) (value >>> 8));
		digest.update((byte) value);
	}

	private static String doubleBits(double value) {
		return HEX.toHexDigits(Double.doubleToRawLongBits(value));
	}

	private static String doubleBits(Double value) {
		return value == null ? null : doubleBits(value.doubleValue());
	}

	private static <T, R> List<R> project(
			List<T> values, Function<T, R> projection) {
		List<R> result = new ArrayList<>(values.size());
		for (T value : values) {
			result.add(projection.apply(value));
		}
		return List.copyOf(result);
	}

	private static Map<String, Object> orderedMap() {
		return new LinkedHashMap<>();
	}

	private static String requirePattern(String value, String field, Pattern pattern) {
		if (value == null || !pattern.matcher(value).matches()) {
			throw new IllegalArgumentException(
					field + " must be a full lowercase hexadecimal value");
		}
		return value;
	}

	private static void requireArtifactSize(
			String filename, byte[] bytes, int maximumBytes) {
		if (bytes.length < 1 || bytes.length > maximumBytes) {
			throw new IllegalArgumentException(
					filename + " exceeds its canonical artifact byte budget");
		}
	}

	private static int maximumArtifactBytes(String filename) {
		return switch (filename) {
			case EVALUATOR_SOURCE_FILENAME -> MAXIMUM_SOURCE_ARTIFACT_BYTES;
			case RANKING_SNAPSHOT_FILENAME -> MAXIMUM_SNAPSHOT_ARTIFACT_BYTES;
			case SCORING_RESULT_FILENAME -> MAXIMUM_RESULT_ARTIFACT_BYTES;
			case EVIDENCE_REPORT_FILENAME -> MAXIMUM_REPORT_ARTIFACT_BYTES;
			default -> throw new IllegalArgumentException(
					"unknown evidence artifact: " + filename);
		};
	}

	static final class VerifiedArtifacts {

		private final String reportId;
		private final long totalBytes;
		private final Map<String, String> artifactSha256;
		private final Map<String, byte[]> artifacts;

		private VerifiedArtifacts(
				String reportId,
				long totalBytes,
				Map<String, String> artifactSha256,
				Map<String, byte[]> artifacts) {
			if (reportId == null
					|| !reportId.matches(
							"related-topic-reuse-holdout-report-v2-[0-9a-f]{64}")
					|| totalBytes < 1) {
				throw new IllegalArgumentException("invalid verified evidence identity");
			}
			Map<String, String> frozenDigests = new LinkedHashMap<>();
			Objects.requireNonNull(artifactSha256, "artifactSha256")
					.forEach((filename, digest) -> frozenDigests.put(
							Objects.requireNonNull(filename, "artifact filename"),
							requirePattern(digest, filename + " sha256", SHA256)));
			Map<String, byte[]> frozenArtifacts = new LinkedHashMap<>();
			Objects.requireNonNull(artifacts, "artifacts")
					.forEach((filename, bytes) -> frozenArtifacts.put(
							Objects.requireNonNull(filename, "artifact filename"),
							Objects.requireNonNull(bytes, filename).clone()));
			if (!new ArrayList<>(frozenDigests.keySet())
					.equals(new ArrayList<>(frozenArtifacts.keySet()))) {
				throw new IllegalArgumentException(
						"verified artifact bytes and digests must have identical order");
			}
			if (!new ArrayList<>(frozenArtifacts.keySet()).equals(List.of(
					EVALUATOR_SOURCE_FILENAME,
					RANKING_SNAPSHOT_FILENAME,
					SCORING_RESULT_FILENAME,
					EVIDENCE_REPORT_FILENAME))) {
				throw new IllegalArgumentException(
						"verified artifacts must have the complete fixed filename order");
			}
			long frozenTotalBytes = 0L;
			for (Map.Entry<String, byte[]> entry : frozenArtifacts.entrySet()) {
				requireArtifactSize(
						entry.getKey(), entry.getValue(), maximumArtifactBytes(entry.getKey()));
				if (!sha256(entry.getValue()).equals(frozenDigests.get(entry.getKey()))) {
					throw new IllegalArgumentException(
							entry.getKey() + " digest does not match verified bytes");
				}
				frozenTotalBytes = Math.addExact(frozenTotalBytes, entry.getValue().length);
			}
			if (frozenTotalBytes != totalBytes) {
				throw new IllegalArgumentException(
						"verified artifact total does not match verified bytes");
			}
			this.reportId = reportId;
			this.totalBytes = totalBytes;
			this.artifactSha256 = Collections.unmodifiableMap(frozenDigests);
			this.artifacts = Collections.unmodifiableMap(frozenArtifacts);
		}

		String reportId() {
			return reportId;
		}

		long totalBytes() {
			return totalBytes;
		}

		Map<String, String> artifactSha256() {
			return artifactSha256;
		}

		Map<String, byte[]> artifacts() {
			Map<String, byte[]> copy = new LinkedHashMap<>();
			artifacts.forEach((filename, bytes) -> copy.put(filename, bytes.clone()));
			return Collections.unmodifiableMap(copy);
		}

		byte[] artifact(String filename) {
			byte[] value = artifacts.get(filename);
			if (value == null) {
				throw new IllegalArgumentException(
						"unknown verified evidence artifact: " + filename);
			}
			return value.clone();
		}

		boolean readerFacing() {
			return false;
		}

		boolean externalBundleAcceptanceAuthorized() {
			return false;
		}

		boolean custodyReleaseAuthorized() {
			return false;
		}

		boolean productActivationAuthorized() {
			return false;
		}
	}
}
