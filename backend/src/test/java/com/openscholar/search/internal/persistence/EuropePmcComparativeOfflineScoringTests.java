package com.openscholar.search.internal.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Locale;

import com.openscholar.search.internal.persistence.ProviderQualityComparativeJudgments.BoundJudgments;
import com.openscholar.search.internal.persistence.ProviderQualityComparativeReviewPacket.Generated;
import com.openscholar.search.internal.persistence.ProviderQualityComparativeScorer.ScoringResult;
import com.openscholar.search.internal.persistence.ProviderQualityComparativeScoringPolicy.BoundPolicy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Manual, opt-in offline scoring entry point. The class has no Spring context,
 * provider adapter, database, Docker, document, or network dependency.
 */
@EnabledIfEnvironmentVariable(
		named = "RUN_PROVIDER_QUALITY_COMPARATIVE_SCORING",
		matches = "true")
class EuropePmcComparativeOfflineScoringTests {

	private static final String EVIDENCE_DIRECTORY_ENV =
			"OPENSCHOLAR_PROVIDER_QUALITY_COMPARATIVE_EVIDENCE";
	private static final String JUDGMENT_PACKET_ENV =
			"OPENSCHOLAR_PROVIDER_QUALITY_COMPARATIVE_JUDGMENTS";
	private static final String REVIEW_PACKET_ENV =
			"OPENSCHOLAR_PROVIDER_QUALITY_COMPARATIVE_REVIEW_PACKET";
	private static final String SCORE_REPORT_ENV =
			"OPENSCHOLAR_PROVIDER_QUALITY_COMPARATIVE_SCORE_REPORT";

	@Test
	void scoresVerifiedEvidenceWithoutContactingProviders() throws Exception {
		Path repositoryRoot = EuropePmcComparativeLiveEvaluationTests.repositoryRoot();
		String repositoryRevision =
				EuropePmcComparativeLiveEvaluationTests.requiredRepositoryRevision(repositoryRoot);
		Path evidenceDirectory = requiredAbsolutePath(EVIDENCE_DIRECTORY_ENV);
		Path judgmentPacket = requiredAbsolutePath(JUDGMENT_PACKET_ENV);
		Path reviewPacket = requiredAbsolutePath(REVIEW_PACKET_ENV);
		Path retainedReport = optionalAbsolutePath(SCORE_REPORT_ENV);
		if (retainedReport != null) {
			retainedReport = validateExternalReplayPath(repositoryRoot, retainedReport);
		}
		ObjectMapper objectMapper = JsonMapper.builder().build();

		ProviderQualityComparativeEvidenceBundle evidence =
				ProviderQualityComparativeEvidenceBundle.verify(objectMapper, evidenceDirectory);
		BoundJudgments judgments =
				ProviderQualityComparativeJudgments.loadBound(objectMapper, judgmentPacket);
		BoundPolicy policy = ProviderQualityComparativeScoringPolicy.loadBound(
				objectMapper, ProviderQualityComparativeScoringPolicy.RESOURCE_PATH);
		policy.validateReference(
				ProviderQualityComparativeScoringPolicy.POLICY_ID,
				ProviderQualityComparativeScoringPolicy.POLICY_SHA256);
		ProviderQualityLiveQuerySet.BoundQuerySet frozenQuerySet =
				ProviderQualityLiveQuerySet.loadFrozen(objectMapper);
		ProviderQualityComparativeScorer.preflightForReview(
				objectMapper, evidence, frozenQuerySet, policy);
		Generated expectedReviewPacket = ProviderQualityComparativeReviewPacket.generate(
				objectMapper, evidence, frozenQuerySet, policy);
		ProviderQualityComparativeReviewPacket.verifyReviewedPacket(
				reviewPacket, expectedReviewPacket);

		ScoringResult result = ProviderQualityComparativeScorer.score(
				evidence, judgments, policy, expectedReviewPacket.reviewPacketSha256());
		assertThat(result.querySetId()).isEqualTo(frozenQuerySet.querySet().querySetId());
		assertThat(result.querySetSha256()).isEqualTo(frozenQuerySet.sha256());
		assertThat(result.queries())
				.extracting(ProviderQualityComparativeScorer.QueryScore::queryKey)
				.containsExactlyElementsOf(frozenQuerySet.querySet().queries().stream()
						.map(ProviderQualityLiveQuerySet.Query::key)
						.toList());
		assertThat(result.captureRepositoryRevision())
				.as("score only with the exact clean capture/evaluator revision")
				.isEqualTo(repositoryRevision);
		assertThat(result.captureMeasuredAt())
				.as("carry the exact capture time through the score-report lineage")
				.isEqualTo(Instant.parse(
						evidence.summary().required("measuredAt").asString()).toString());
		assertThat(result.schemaVersion())
				.isEqualTo(ProviderQualityComparativeScorer.REPORT_SCHEMA_VERSION);
		assertThat(result.readerFacing()).isFalse();
		assertThat(result.defaultEnablementDecision()).isFalse();

		String mode;
		Path reportDirectory;
		if (retainedReport == null) {
			mode = "generated";
			reportDirectory = ProviderQualityEvidenceWriter.forRepository(
					objectMapper,
					repositoryRoot,
					ProviderQualityComparativeScoreReportBundle.MAXIMUM_REPORT_BYTES)
					.write(result.reportId(), ProviderQualityComparativeScorer.artifacts(result))
					.directory();
		}
		else {
			mode = "replayed";
			reportDirectory = retainedReport;
		}
		ProviderQualityComparativeScoreReportBundle verified =
				ProviderQualityComparativeScoreReportBundle.verifyExact(
						objectMapper, reportDirectory, result);
		System.out.printf(
				Locale.ROOT,
				"provider-quality-comparative-score-v2 mode=%s report-id=%s "
						+ "report-manifest-sha256=%s evidence=%s captured-at=%s "
						+ "revision=%s queries=%d bytes=%d%n",
				mode,
				verified.reportId(),
				verified.manifestSha256(),
				result.evidenceId(),
				result.captureMeasuredAt(),
				repositoryRevision,
				result.queryCount(),
				verified.totalBytes());
	}

	private static Path optionalAbsolutePath(String environmentName) {
		if (System.getenv(environmentName) == null) {
			return null;
		}
		return requiredAbsolutePath(environmentName);
	}

	static Path validateExternalReplayPath(Path repositoryRoot, Path reportDirectory) {
		Path repository = repositoryRoot.toAbsolutePath().normalize();
		Path report = reportDirectory.toAbsolutePath().normalize();
		Path target = repository.resolve("backend/target").normalize();
		if (report.startsWith(target)) {
			throw new IllegalStateException(
					SCORE_REPORT_ENV + " must resolve outside backend/target");
		}
		if (!Files.exists(report, LinkOption.NOFOLLOW_LINKS)) {
			throw new IllegalStateException(SCORE_REPORT_ENV + " must name an existing directory");
		}
		try {
			Path resolvedReport = report.toRealPath();
			Path resolvedTarget = target.toRealPath();
			if (resolvedReport.startsWith(resolvedTarget)) {
				throw new IllegalStateException(
						SCORE_REPORT_ENV + " must resolve outside backend/target");
			}
		}
		catch (IOException exception) {
			throw new IllegalStateException(
					SCORE_REPORT_ENV + " must name a resolvable external directory",
					exception);
		}
		return report;
	}

	private static Path requiredAbsolutePath(String environmentName) {
		String value = System.getenv(environmentName);
		if (value == null || value.isBlank()) {
			throw new IllegalStateException(environmentName + " must name an absolute path");
		}
		Path path;
		try {
			path = Path.of(value).toAbsolutePath().normalize();
		}
		catch (RuntimeException exception) {
			throw new IllegalStateException(
					environmentName + " must name an absolute path", exception);
		}
		if (!Path.of(value).isAbsolute()) {
			throw new IllegalStateException(environmentName + " must name an absolute path");
		}
		return path;
	}
}
