package com.openscholar.search.internal.persistence;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.util.Map;

import com.openscholar.search.internal.persistence.RelatedTopicReuseHoldoutEvaluatorSeal.VerifiedEvaluatorSeal;
import com.openscholar.search.internal.persistence.RelatedTopicReuseHoldoutPostgresFirstRunLedger.FirstRunEvidence;
import com.openscholar.search.internal.persistence.RelatedTopicReuseHoldoutScorer.VerifiedScoringOutcome;

/** Reflection-only mechanics fixture for otherwise privately minted capabilities. */
final class RelatedTopicReuseHoldoutEvidenceTestFixture {

	private RelatedTopicReuseHoldoutEvidenceTestFixture() {
	}

	static FirstRunEvidence firstRunEvidence(
			VerifiedEvaluatorSeal evaluatorSeal,
			RelatedTopicReuseHoldoutRankingSnapshot rankingSnapshot) {
		return firstRunEvidence(
				"d".repeat(64),
				RelatedTopicReuseHoldoutGitCollector.FREEZE_SCHEMA_VERSION,
				RelatedTopicReuseHoldoutGitCollector.INVENTORY_ID,
				evaluatorSeal,
				rankingSnapshot);
	}

	static RelatedTopicReuseHoldoutEvidenceReport createReport(
			VerifiedEvaluatorSeal evaluatorSeal,
			RelatedTopicReuseHoldoutRankingSnapshot rankingSnapshot,
			RelatedTopicReuseHoldoutScoringResult scoringResult) {
		FirstRunEvidence evidence = firstRunEvidence(evaluatorSeal, rankingSnapshot);
		return RelatedTopicReuseHoldoutEvidenceReport.create(
				scoringOutcome(evidence, rankingSnapshot, scoringResult));
	}

	static RelatedTopicReuseHoldoutEvidenceReport.VerifiedArtifacts verifyExactReport(
			VerifiedEvaluatorSeal evaluatorSeal,
			RelatedTopicReuseHoldoutRankingSnapshot rankingSnapshot,
			RelatedTopicReuseHoldoutScoringResult scoringResult,
			Map<String, byte[]> observedArtifacts) {
		FirstRunEvidence evidence = firstRunEvidence(evaluatorSeal, rankingSnapshot);
		return RelatedTopicReuseHoldoutEvidenceReport.verifyExact(
				scoringOutcome(evidence, rankingSnapshot, scoringResult),
				observedArtifacts);
	}

	static VerifiedScoringOutcome scoringOutcome(
			FirstRunEvidence firstRunEvidence,
			RelatedTopicReuseHoldoutRankingSnapshot rankingSnapshot,
			RelatedTopicReuseHoldoutScoringResult scoringResult) {
		try {
			Constructor<VerifiedScoringOutcome> constructor =
					VerifiedScoringOutcome.class.getDeclaredConstructor(
							FirstRunEvidence.class,
							RelatedTopicReuseHoldoutRankingSnapshot.class,
							RelatedTopicReuseHoldoutScoringResult.class);
			constructor.setAccessible(true);
			return constructor.newInstance(
					firstRunEvidence, rankingSnapshot, scoringResult);
		}
		catch (InvocationTargetException exception) {
			if (exception.getCause() instanceof RuntimeException runtimeException) {
				throw runtimeException;
			}
			throw new AssertionError(
					"unable to create synthetic verified scoring outcome",
					exception.getCause());
		}
		catch (NoSuchMethodException
				| InstantiationException
				| IllegalAccessException exception) {
			throw new AssertionError(
					"unable to create synthetic verified scoring outcome", exception);
		}
	}

	static FirstRunEvidence firstRunEvidence(
			String runKey,
			int freezeSchemaVersion,
			String inventoryId,
			VerifiedEvaluatorSeal evaluatorSeal,
			RelatedTopicReuseHoldoutRankingSnapshot rankingSnapshot) {
		try {
			Constructor<FirstRunEvidence> constructor =
					FirstRunEvidence.class.getDeclaredConstructor(
							String.class,
							String.class,
							String.class,
							int.class,
							String.class,
							VerifiedEvaluatorSeal.class,
							RelatedTopicReuseHoldoutRankingSnapshot.class);
			constructor.setAccessible(true);
			return constructor.newInstance(
					runKey,
					RelatedTopicReuseHoldoutPolicy.EVALUATION_PROTOCOL_ID,
					RelatedTopicReuseHoldoutPolicy.POLICY_ID,
					freezeSchemaVersion,
					inventoryId,
					evaluatorSeal,
					rankingSnapshot);
		}
		catch (InvocationTargetException exception) {
			if (exception.getCause() instanceof RuntimeException runtimeException) {
				throw runtimeException;
			}
			throw new AssertionError(
					"unable to create synthetic first-run evidence", exception.getCause());
		}
		catch (NoSuchMethodException
				| InstantiationException
				| IllegalAccessException exception) {
			throw new AssertionError("unable to create synthetic first-run evidence", exception);
		}
	}
}
