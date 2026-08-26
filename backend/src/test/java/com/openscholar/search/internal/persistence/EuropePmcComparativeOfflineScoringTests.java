package com.openscholar.search.internal.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.Locale;

import com.openscholar.search.internal.persistence.ProviderQualityComparativeJudgments.BoundJudgments;
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
	private static final long MAXIMUM_REPORT_BYTES = 8L * 1024L * 1024L;

	@Test
	void scoresVerifiedEvidenceWithoutContactingProviders() throws Exception {
		Path repositoryRoot = EuropePmcComparativeLiveEvaluationTests.repositoryRoot();
		String repositoryRevision =
				EuropePmcComparativeLiveEvaluationTests.requiredRepositoryRevision(repositoryRoot);
		Path evidenceDirectory = requiredAbsolutePath(EVIDENCE_DIRECTORY_ENV);
		Path judgmentPacket = requiredAbsolutePath(JUDGMENT_PACKET_ENV);
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

		ScoringResult result = ProviderQualityComparativeScorer.score(
				evidence, judgments, policy);
		ProviderQualityLiveQuerySet.BoundQuerySet frozenQuerySet =
				ProviderQualityLiveQuerySet.loadFrozen(objectMapper);
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
		assertThat(result.readerFacing()).isFalse();
		assertThat(result.defaultEnablementDecision()).isFalse();

		ProviderQualityEvidenceWriter.WriteResult written =
				ProviderQualityEvidenceWriter.forRepository(
						objectMapper, repositoryRoot, MAXIMUM_REPORT_BYTES)
						.write(result.reportId(), ProviderQualityComparativeScorer.artifacts(result));
		System.out.printf(
				Locale.ROOT,
				"provider-quality-comparative-score-v1 report=%s evidence=%s "
						+ "revision=%s queries=%d bytes=%d%n",
				written.directory(),
				result.evidenceId(),
				repositoryRevision,
				result.queryCount(),
				written.totalBytes());
		assertThat(written.manifest().files()).hasSize(2);
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
