package com.openscholar.search.internal.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;

import com.openscholar.search.internal.persistence.ProviderQualityComparativeReviewPacket.Generated;
import com.openscholar.search.internal.persistence.ProviderQualityComparativeReviewWorksheet.CompiledJudgments;
import com.openscholar.search.internal.persistence.ProviderQualityComparativeScoringPolicy.BoundPolicy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Manual, opt-in compiler for a completed independent-review worksheet. It regenerates the
 * expected blinded packet from verified evidence and emits only the scorer's canonical judgment
 * input. The class has no Spring, provider, database, Docker, document, or network dependency.
 */
@EnabledIfEnvironmentVariable(
		named = "RUN_PROVIDER_QUALITY_COMPARATIVE_REVIEW_COMPILE",
		matches = "true")
class EuropePmcComparativeReviewWorksheetCompilationTests {

	private static final String EVIDENCE_DIRECTORY_ENV =
			"OPENSCHOLAR_PROVIDER_QUALITY_COMPARATIVE_EVIDENCE";
	private static final String WORKSHEET_ENV =
			"OPENSCHOLAR_PROVIDER_QUALITY_COMPARATIVE_WORKSHEET";
	private static final String REVIEW_PACKET_ENV =
			"OPENSCHOLAR_PROVIDER_QUALITY_COMPARATIVE_REVIEW_PACKET";
	private static final long MAXIMUM_OUTPUT_BYTES = 2L * 1024L * 1024L;

	@Test
	void compilesACompletedBoundWorksheetIntoCanonicalJudgments() throws Exception {
		Path repositoryRoot = EuropePmcComparativeLiveEvaluationTests.repositoryRoot();
		String repositoryRevision =
				EuropePmcComparativeLiveEvaluationTests.requiredRepositoryRevision(repositoryRoot);
		Path evidenceDirectory =
				EuropePmcComparativeReviewPacketGenerationTests.requiredAbsolutePath(
						EVIDENCE_DIRECTORY_ENV);
		Path worksheetPath =
				EuropePmcComparativeReviewPacketGenerationTests.requiredAbsolutePath(WORKSHEET_ENV);
		Path reviewPacketPath =
				EuropePmcComparativeReviewPacketGenerationTests.requiredAbsolutePath(
						REVIEW_PACKET_ENV);
		ObjectMapper objectMapper = JsonMapper.builder().build();

		ProviderQualityComparativeEvidenceBundle evidence =
				ProviderQualityComparativeEvidenceBundle.verify(objectMapper, evidenceDirectory);
		assertThat(evidence.summary().required("repositoryRevision").asString())
				.as("compile judgments only with evaluator code at the capture revision")
				.isEqualTo(repositoryRevision);
		ProviderQualityLiveQuerySet.BoundQuerySet querySet =
				ProviderQualityLiveQuerySet.loadFrozen(objectMapper);
		BoundPolicy policy = ProviderQualityComparativeScoringPolicy.loadBound(
				objectMapper, ProviderQualityComparativeScoringPolicy.RESOURCE_PATH);
		policy.validateReference(
				ProviderQualityComparativeScoringPolicy.POLICY_ID,
				ProviderQualityComparativeScoringPolicy.POLICY_SHA256);
		ProviderQualityComparativeScorer.preflightForReview(
				objectMapper, evidence, querySet, policy);
		Generated expected = ProviderQualityComparativeReviewPacket.generate(
				objectMapper, evidence, querySet, policy);
		ProviderQualityComparativeReviewPacket.verifyReviewedPacket(
				reviewPacketPath, expected);

		CompiledJudgments compiled = ProviderQualityComparativeReviewWorksheet.compile(
				objectMapper, worksheetPath, expected.expectedReviewContext());
		JsonNode canonicalJudgments = objectMapper.readTree(compiled.canonicalBytes());
		String outputId = "provider-comparative-judgments-" + compiled.sha256();
		ProviderQualityEvidenceWriter.WriteResult written =
				ProviderQualityEvidenceWriter.forRepository(
						objectMapper, repositoryRoot, MAXIMUM_OUTPUT_BYTES)
						.write(outputId, Map.of("judgments.json", canonicalJudgments));

		assertThat(written.manifest().files()).singleElement().satisfies(file -> {
			assertThat(file.filename()).isEqualTo("judgments.json");
			assertThat(file.sha256()).isEqualTo(compiled.sha256());
			assertThat(file.bytes()).isEqualTo(compiled.canonicalBytes().length);
		});
		assertThat(written.directory().resolve("judgments.json")).isRegularFile();

		System.out.printf(
				Locale.ROOT,
				"provider-quality-comparative-review-judgments-v2 directory=%s evidence=%s "
						+ "revision=%s judgments-sha256=%s bytes=%d%n",
				written.directory(),
				evidence.evidenceId(),
				repositoryRevision,
				compiled.sha256(),
				written.totalBytes());
	}
}
