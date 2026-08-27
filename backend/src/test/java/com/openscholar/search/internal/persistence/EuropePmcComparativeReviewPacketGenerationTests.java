package com.openscholar.search.internal.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;

import com.openscholar.search.internal.persistence.ProviderQualityComparativeReviewPacket.Generated;
import com.openscholar.search.internal.persistence.ProviderQualityComparativeScoringPolicy.BoundPolicy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Manual, opt-in generator for the provenance-free packet sent to an independent reviewer.
 * The class has no Spring, provider, database, Docker, document, or network dependency.
 */
@EnabledIfEnvironmentVariable(
		named = "RUN_PROVIDER_QUALITY_COMPARATIVE_REVIEW_PACKET",
		matches = "true")
class EuropePmcComparativeReviewPacketGenerationTests {

	private static final String EVIDENCE_DIRECTORY_ENV =
			"OPENSCHOLAR_PROVIDER_QUALITY_COMPARATIVE_EVIDENCE";
	private static final long MAXIMUM_OUTPUT_BYTES = 72L * 1024L * 1024L;

	@Test
	void writesOnlyTheBlindedPacketAndAnIncompleteWorksheet() throws Exception {
		Path repositoryRoot = EuropePmcComparativeLiveEvaluationTests.repositoryRoot();
		String repositoryRevision =
				EuropePmcComparativeLiveEvaluationTests.requiredRepositoryRevision(repositoryRoot);
		Path evidenceDirectory = requiredAbsolutePath(EVIDENCE_DIRECTORY_ENV);
		ObjectMapper objectMapper = JsonMapper.builder().build();

		ProviderQualityComparativeEvidenceBundle evidence =
				ProviderQualityComparativeEvidenceBundle.verify(objectMapper, evidenceDirectory);
		assertThat(evidence.summary().required("repositoryRevision").asString())
				.as("generate a packet only with evaluator code at the capture revision")
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

		Generated generated = ProviderQualityComparativeReviewPacket.generate(
				objectMapper, evidence, querySet, policy);
		String outputId = "provider-comparative-review-" + generated.reviewPacketSha256();
		ProviderQualityEvidenceWriter.WriteResult written =
				ProviderQualityEvidenceWriter.forRepository(
						objectMapper, repositoryRoot, MAXIMUM_OUTPUT_BYTES)
						.write(outputId, Map.of(
								"review-packet.json", generated.reviewPacket(),
								"review-worksheet.json", generated.worksheetSkeleton()));

		assertThat(written.manifest().files()).hasSize(2);
		ProviderQualityEvidenceWriter.FileDigest packetDigest = written.manifest().files()
				.stream()
				.filter(file -> "review-packet.json".equals(file.filename()))
				.findFirst()
				.orElseThrow();
		assertThat(packetDigest.sha256()).isEqualTo(generated.reviewPacketSha256());
		assertThat(packetDigest.bytes()).isEqualTo(generated.reviewPacketBytes().length);
		assertThat(written.directory().resolve("review-packet.json")).isRegularFile();
		assertThat(written.directory().resolve("review-worksheet.json")).isRegularFile();

		System.out.printf(
				Locale.ROOT,
				"provider-quality-comparative-review-packet-v1 directory=%s evidence=%s "
						+ "revision=%s packet-sha256=%s bytes=%d%n",
				written.directory(),
				evidence.evidenceId(),
				repositoryRevision,
				generated.reviewPacketSha256(),
				written.totalBytes());
	}

	static Path requiredAbsolutePath(String environmentName) {
		String value = System.getenv(environmentName);
		if (value == null || value.isBlank()) {
			throw new IllegalStateException(environmentName + " must name an absolute path");
		}
		try {
			Path supplied = Path.of(value);
			if (!supplied.isAbsolute()) {
				throw new IllegalStateException(
						environmentName + " must name an absolute path");
			}
			return supplied.toAbsolutePath().normalize();
		}
		catch (RuntimeException exception) {
			if (exception instanceof IllegalStateException state) {
				throw state;
			}
			throw new IllegalStateException(
					environmentName + " must name an absolute path", exception);
		}
	}
}
