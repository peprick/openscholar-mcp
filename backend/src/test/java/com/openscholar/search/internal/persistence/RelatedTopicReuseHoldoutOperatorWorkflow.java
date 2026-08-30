package com.openscholar.search.internal.persistence;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;

import com.openscholar.search.internal.persistence.RelatedTopicReuseHoldoutGitCollector.FreezeRecord;
import com.openscholar.search.internal.persistence.RelatedTopicReuseHoldoutGitCollector.ProcessGitRunner;
import com.openscholar.search.internal.persistence.RelatedTopicReuseHoldoutPostgresFirstRunLedger.AlreadyClaimedException;
import com.openscholar.search.internal.persistence.RelatedTopicReuseHoldoutPostgresFirstRunLedger.CommitOutcomeUnknownException;
import com.openscholar.search.internal.persistence.RelatedTopicReuseHoldoutPostgresFirstRunLedger.FirstRunEvidence;
import com.openscholar.search.internal.persistence.RelatedTopicReuseHoldoutPostgresFirstRunLedger.LedgerException;
import tools.jackson.databind.ObjectMapper;

/**
 * Filesystem-write-free composition boundary for one related-topic holdout run.
 *
 * <p>This is not yet a live command. It centralizes the supported in-process call
 * graph while the isolated build launcher, TLS ledger connection factory, and
 * no-clobber external publisher remain pending.</p>
 */
final class RelatedTopicReuseHoldoutOperatorWorkflow {

	private final ObjectMapper objectMapper;
	private final RelatedTopicReuseHoldoutPostgresFirstRunLedger ledger;
	private final RelatedTopicReuseHoldoutBundle.LabelFreeRankingPhase rankingPhase;
	private final ProcessGitRunner gitRunner;

	RelatedTopicReuseHoldoutOperatorWorkflow(
			ObjectMapper objectMapper,
			RelatedTopicReuseHoldoutPostgresFirstRunLedger ledger,
			RelatedTopicReuseHoldoutPostgresRanker ranker,
			ProcessGitRunner gitRunner) {
		this(objectMapper, ledger, Objects.requireNonNull(ranker, "ranker")::rank,
				gitRunner);
	}

	private RelatedTopicReuseHoldoutOperatorWorkflow(
			ObjectMapper objectMapper,
			RelatedTopicReuseHoldoutPostgresFirstRunLedger ledger,
			RelatedTopicReuseHoldoutBundle.LabelFreeRankingPhase rankingPhase,
			ProcessGitRunner gitRunner) {
		this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
		this.ledger = Objects.requireNonNull(ledger, "ledger");
		this.rankingPhase = Objects.requireNonNull(rankingPhase, "rankingPhase");
		this.gitRunner = Objects.requireNonNull(gitRunner, "gitRunner");
	}

	PendingPublication execute(
			Path repositoryRoot,
			Path externalBundleDirectory,
			FreezeRecord externallyRetainedFreeze) throws OperatorException {
		boolean claimCommitted = false;
		try {
			var checkout = RelatedTopicReuseHoldoutGitCollector.verifyCleanCheckout(
					repositoryRoot, externallyRetainedFreeze, gitRunner);
			var verifiedCorpus = RelatedTopicReuseHoldoutBundle.verifyCorpus(
					objectMapper, externalBundleDirectory);
			var committed = ledger.claim(
					verifiedCorpus.firstRunCommitment(), checkout);
			claimCommitted = true;
			var completedRanking = RelatedTopicReuseHoldoutBundle.completeRanking(
					verifiedCorpus, committed, rankingPhase);
			var scoringInputs = RelatedTopicReuseHoldoutBundle.verifyAfterRanking(
					objectMapper, externalBundleDirectory, completedRanking);
			var scoringOutcome = RelatedTopicReuseHoldoutScorer.score(scoringInputs);
			var report = RelatedTopicReuseHoldoutEvidenceReport.create(scoringOutcome);
			var verifiedArtifacts = RelatedTopicReuseHoldoutEvidenceReport.verifyExact(
					scoringOutcome,
					report.artifacts());
			return new PendingPublication(
					committed.runKey(),
					scoringOutcome,
					report,
					verifiedArtifacts);
		}
		catch (AlreadyClaimedException exception) {
			throw new OperatorException(
					"HOLDOUT_OPERATOR_FIRST_RUN_ALREADY_CLAIMED",
					ClaimState.REJECTED);
		}
		catch (CommitOutcomeUnknownException exception) {
			throw new OperatorException(
					"HOLDOUT_OPERATOR_CLAIM_OUTCOME_UNKNOWN",
					ClaimState.OUTCOME_UNKNOWN);
		}
		catch (LedgerException exception) {
			if (claimCommitted) {
				throw new OperatorException(
						"HOLDOUT_OPERATOR_FINAL_RUN_FAILED",
						ClaimState.COMMITTED);
			}
			throw new OperatorException(
					"HOLDOUT_OPERATOR_PRECLAIM_FAILED",
					ClaimState.NOT_COMMITTED);
		}
		catch (IOException | RuntimeException exception) {
			if (claimCommitted) {
				throw new OperatorException(
						"HOLDOUT_OPERATOR_FINAL_RUN_FAILED",
						ClaimState.COMMITTED);
			}
			throw new OperatorException(
					"HOLDOUT_OPERATOR_PRECLAIM_FAILED",
					ClaimState.NOT_COMMITTED);
		}
	}

	enum ClaimState {
		NOT_COMMITTED,
		REJECTED,
		OUTCOME_UNKNOWN,
		COMMITTED
	}

	static final class OperatorException extends Exception {

		private final ClaimState claimState;

		private OperatorException(String diagnostic, ClaimState claimState) {
			super(diagnostic);
			this.claimState = Objects.requireNonNull(claimState, "claimState");
		}

		ClaimState claimState() {
			return claimState;
		}
	}

	/** Opaque in-memory handoff for a future separately reviewed publisher. */
	static final class PendingPublication {

		private final String runKey;
		private final String reportId;
		private final String reportSha256;
		private final boolean policyGatesPassed;
		private final FirstRunEvidence firstRunEvidence;
		private final RelatedTopicReuseHoldoutEvidenceReport.VerifiedArtifacts artifacts;

		private PendingPublication(
				String runKey,
				RelatedTopicReuseHoldoutScorer.VerifiedScoringOutcome scoringOutcome,
				RelatedTopicReuseHoldoutEvidenceReport report,
				RelatedTopicReuseHoldoutEvidenceReport.VerifiedArtifacts artifacts) {
			this.runKey = Objects.requireNonNull(runKey, "runKey");
			RelatedTopicReuseHoldoutScorer.VerifiedScoringOutcome frozenOutcome =
					Objects.requireNonNull(scoringOutcome, "scoringOutcome");
			this.firstRunEvidence = frozenOutcome.firstRunEvidence();
			RelatedTopicReuseHoldoutEvidenceReport frozenReport =
					Objects.requireNonNull(report, "report");
			this.artifacts = Objects.requireNonNull(artifacts, "artifacts");
			this.reportId = frozenReport.reportId();
			this.reportSha256 = this.artifacts.artifactSha256().get(
					RelatedTopicReuseHoldoutEvidenceReport.EVIDENCE_REPORT_FILENAME);
			this.policyGatesPassed = frozenOutcome.result().policyGatesPassed();
			if (!this.runKey.equals(this.firstRunEvidence.runKey())
					|| !frozenOutcome.authorizes(
							frozenOutcome.rankingSnapshot(), frozenOutcome.result())
					|| !this.runKey.equals(frozenReport.firstRunKey())
					|| !this.reportId.equals(this.artifacts.reportId())
					|| this.reportSha256 == null
					|| this.firstRunEvidence.externalBundleAcceptanceAuthorized()
					|| this.firstRunEvidence.custodyReleaseAuthorized()
					|| this.firstRunEvidence.productActivationAuthorized()) {
				throw new IllegalArgumentException("invalid pending holdout publication");
			}
		}

		String runKey() {
			return runKey;
		}

		String reportId() {
			return reportId;
		}

		String reportSha256() {
			return reportSha256;
		}

		boolean policyGatesPassed() {
			return policyGatesPassed;
		}

		FirstRunEvidence firstRunEvidence() {
			return firstRunEvidence;
		}

		RelatedTopicReuseHoldoutEvidenceReport.VerifiedArtifacts artifacts() {
			return artifacts;
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
