package com.openscholar.search.internal.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.openscholar.search.internal.persistence.ProviderQualityComparativeScorer.ScoringResult;
import com.openscholar.search.internal.persistence.ProviderQualityComparativeScorer.UniqueRelevantQueryCoverage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

class ProviderQualityComparativeScoreReportBundleTests {

	private static final JsonMapper OBJECT_MAPPER = JsonMapper.builder().build();
	private static final String SHA_B = "b".repeat(64);
	private static final String SHA_C = "c".repeat(64);
	private static final String SHA_D = "d".repeat(64);
	private static final String SHA_E = "e".repeat(64);
	private static final String SHA_F = "f".repeat(64);
	private static final String REPORT_ID = ProviderQualityComparativeScorer.reportId(
			SHA_B, SHA_C, SHA_F);
	private static final String EXPECTED_REPORT_ID =
			"provider-comparative-score-v2-"
					+ "3a466d88e5fdc39f90e48595437b699b14970b2fd973853b3a9de98c7bd5a9be";
	private static final String EXPECTED_MANIFEST_SHA256 =
			"32a9af7761d99a159f109ba5f76f8fced4e9a65911f82466d4f3f9e92cc583e8";

	@TempDir
	private Path temporaryDirectory;

	@Test
	void verifiesExactDeterministicReportWithoutMutatingRetainedFiles() throws Exception {
		ScoringResult expected = result("provider-comparative-live-fixture");
		ProviderQualityEvidenceWriter.WriteResult written = writeReport("happy", expected);
		Map<String, byte[]> before = snapshot(written.directory());

		ProviderQualityComparativeScoreReportBundle first =
				ProviderQualityComparativeScoreReportBundle.verifyExact(
						OBJECT_MAPPER, written.directory(), expected);
		ProviderQualityComparativeScoreReportBundle second =
				ProviderQualityComparativeScoreReportBundle.verifyExact(
						OBJECT_MAPPER, written.directory(), expected);

		assertThat(first.sourceDirectory()).isEqualTo(written.directory());
		assertThat(REPORT_ID).isEqualTo(EXPECTED_REPORT_ID);
		assertThat(first.reportId()).isEqualTo(REPORT_ID);
		assertThat(first.manifestSha256()).isEqualTo(written.manifestFile().sha256());
		assertThat(first.manifestSha256()).isEqualTo(EXPECTED_MANIFEST_SHA256);
		assertThat(first.payloadBytes()).isEqualTo(written.manifest().payloadBytes());
		assertThat(first.totalBytes()).isEqualTo(written.totalBytes());
		assertThat(second.reportId()).isEqualTo(first.reportId());
		assertThat(second.manifestSha256()).isEqualTo(first.manifestSha256());
		assertSnapshotEquals(before, snapshot(written.directory()));
	}

	@Test
	void rejectsMissingAndExtraEntries() throws Exception {
		ScoringResult expected = result("provider-comparative-live-fixture");
		Path missing = writeReport("missing", expected).directory();
		Files.delete(missing.resolve("score-summary.json"));
		assertFailure(missing, expected, "SCORE_REPORT_LAYOUT_INVALID");

		Path extra = writeReport("extra", expected).directory();
		Files.writeString(extra.resolve("unexpected.json"), "{}\n", StandardCharsets.UTF_8);
		assertFailure(extra, expected, "SCORE_REPORT_LAYOUT_INVALID");
	}

	@Test
	void rejectsDirectoryAndPayloadSymlinks() throws Exception {
		ScoringResult expected = result("provider-comparative-live-fixture");
		Path target = writeReport("directory-symlink-target", expected).directory();
		Path directoryLink = temporaryDirectory.resolve(REPORT_ID);
		Files.createSymbolicLink(directoryLink, target);
		assertFailure(directoryLink, expected, "SCORE_REPORT_DIRECTORY_INVALID");

		Path withFileLink = writeReport("file-symlink", expected).directory();
		Path outside = temporaryDirectory.resolve("outside-score-summary.json");
		Files.copy(withFileLink.resolve("score-summary.json"), outside);
		Files.delete(withFileLink.resolve("score-summary.json"));
		Files.createSymbolicLink(withFileLink.resolve("score-summary.json"), outside);
		assertFailure(withFileLink, expected, "SCORE_REPORT_FILE_INVALID");
	}

	@Test
	void rejectsOversizedManifestAndPayload() throws Exception {
		ScoringResult expected = result("provider-comparative-live-fixture");
		Path manifestTooLarge = writeReport("large-manifest", expected).directory();
		Files.write(
				manifestTooLarge.resolve("manifest.json"),
				new byte[ProviderQualityComparativeScoreReportBundle.MAXIMUM_MANIFEST_BYTES + 1]);
		assertFailure(
				manifestTooLarge, expected, "SCORE_REPORT_MANIFEST_TOO_LARGE");

		Path payloadTooLarge = writeReport("large-payload", expected).directory();
		Files.write(
				payloadTooLarge.resolve("query-scores.json"),
				new byte[(int) ProviderQualityComparativeScoreReportBundle.MAXIMUM_REPORT_BYTES + 1]);
		assertFailure(payloadTooLarge, expected, "SCORE_REPORT_PAYLOAD_TOO_LARGE");

		Path totalTooLarge = writeReport("large-total", expected).directory();
		long summaryBytes = Files.size(totalTooLarge.resolve("score-summary.json"));
		Files.write(
				totalTooLarge.resolve("query-scores.json"),
				new byte[(int) (ProviderQualityComparativeScoreReportBundle.MAXIMUM_REPORT_BYTES
						- summaryBytes)]);
		assertFailure(totalTooLarge, expected, "SCORE_REPORT_TOO_LARGE");
	}

	@Test
	void rejectsPayloadAndManifestDigestTampering() throws Exception {
		ScoringResult expected = result("provider-comparative-live-fixture");
		Path payloadTampered = writeReport("payload-tampered", expected).directory();
		byte[] payload = Files.readAllBytes(payloadTampered.resolve("query-scores.json"));
		payload[payload.length / 2] ^= 1;
		Files.write(payloadTampered.resolve("query-scores.json"), payload);
		assertFailure(
				payloadTampered, expected, "SCORE_REPORT_PAYLOAD_DIGEST_MISMATCH");

		Path digestTampered = writeReport("digest-tampered", expected).directory();
		String manifest = Files.readString(digestTampered.resolve("manifest.json"));
		String changed = manifest.replaceFirst(
				"(\"sha256\":\")[0-9a-f]{64}(\")", "$1" + "0".repeat(64) + "$2");
		assertThat(changed).isNotEqualTo(manifest);
		Files.writeString(digestTampered.resolve("manifest.json"), changed);
		assertFailure(
				digestTampered, expected, "SCORE_REPORT_PAYLOAD_DIGEST_MISMATCH");
	}

	@Test
	void rejectsSelfConsistentReportForADifferentExpectedResult() throws Exception {
		ScoringResult retained = result("provider-comparative-live-retained");
		Path directory = writeReport("mixed-result", retained).directory();
		ScoringResult expected = result("provider-comparative-live-expected");

		assertFailure(directory, expected, "SCORE_REPORT_PAYLOAD_NOT_EXPECTED");
	}

	@Test
	void rejectsMalformedDuplicateAndTrailingManifestJson() throws Exception {
		ScoringResult expected = result("provider-comparative-live-fixture");
		Path malformed = writeReport("malformed-manifest", expected).directory();
		Files.writeString(malformed.resolve("manifest.json"), "{\n");
		assertFailure(malformed, expected, "SCORE_REPORT_MANIFEST_JSON_INVALID");

		Path duplicate = writeReport("duplicate-manifest", expected).directory();
		String duplicateJson = Files.readString(duplicate.resolve("manifest.json"))
				.replaceFirst(
						"\\\"schemaVersion\\\":1",
						"\\\"schemaVersion\\\":1,\\\"schemaVersion\\\":1");
		Files.writeString(duplicate.resolve("manifest.json"), duplicateJson);
		assertFailure(duplicate, expected, "SCORE_REPORT_MANIFEST_JSON_INVALID");

		Path trailing = writeReport("trailing-manifest", expected).directory();
		Files.writeString(
				trailing.resolve("manifest.json"),
				Files.readString(trailing.resolve("manifest.json")) + "{}\n");
		assertFailure(trailing, expected, "SCORE_REPORT_MANIFEST_JSON_INVALID");
	}

	@Test
	void rejectsStrictJsonViolationsInSelfConsistentPayloads() throws Exception {
		ScoringResult expected = result("provider-comparative-live-fixture");
		Path malformed = writeReport("malformed-payload", expected).directory();
		replacePayloadAndRebindManifest(
				malformed, "query-scores.json", "{\n".getBytes(StandardCharsets.UTF_8));
		assertFailure(malformed, expected, "SCORE_REPORT_PAYLOAD_JSON_INVALID");

		Path duplicate = writeReport("duplicate-payload", expected).directory();
		String duplicateJson = Files.readString(duplicate.resolve("query-scores.json"))
				.replaceFirst(
						"\\\"schemaVersion\\\":2",
						"\\\"schemaVersion\\\":2,\\\"schemaVersion\\\":2");
		replacePayloadAndRebindManifest(
				duplicate,
				"query-scores.json",
				duplicateJson.getBytes(StandardCharsets.UTF_8));
		assertFailure(duplicate, expected, "SCORE_REPORT_PAYLOAD_JSON_INVALID");

		Path trailing = writeReport("trailing-payload", expected).directory();
		byte[] trailingBytes = (Files.readString(trailing.resolve("query-scores.json"))
				+ "{}\n").getBytes(StandardCharsets.UTF_8);
		replacePayloadAndRebindManifest(trailing, "query-scores.json", trailingBytes);
		assertFailure(trailing, expected, "SCORE_REPORT_PAYLOAD_JSON_INVALID");
	}

	@Test
	void rejectsAValidButNoncanonicalManifest() throws Exception {
		ScoringResult expected = result("provider-comparative-live-fixture");
		Path directory = writeReport("noncanonical-manifest", expected).directory();
		Path manifest = directory.resolve("manifest.json");
		String canonical = Files.readString(manifest);
		Files.writeString(manifest, canonical.stripTrailing() + " \n");

		assertFailure(directory, expected, "SCORE_REPORT_MANIFEST_NOT_CANONICAL");
	}

	@Test
	void rejectsWrongSchemaAndReportIdentityBeforeReadingTheDirectory() {
		ScoringResult wrongSchema = result(
				"provider-comparative-live-fixture", 1, REPORT_ID);
		assertFailure(
				temporaryDirectory.resolve(REPORT_ID),
				wrongSchema,
				"SCORE_REPORT_ID_INVALID");

		ScoringResult unrelatedId = result(
				"provider-comparative-live-fixture",
				ProviderQualityComparativeScorer.REPORT_SCHEMA_VERSION,
				ProviderQualityComparativeScorer.REPORT_ID_PREFIX + "a".repeat(64));
		assertFailure(
				temporaryDirectory.resolve(unrelatedId.reportId()),
				unrelatedId,
				"SCORE_REPORT_ID_INVALID");
	}

	private ProviderQualityEvidenceWriter.WriteResult writeReport(
			String repositoryName, ScoringResult result) throws Exception {
		return ProviderQualityEvidenceWriter.forRepository(
					OBJECT_MAPPER,
					temporaryDirectory.resolve(repositoryName),
					ProviderQualityComparativeScoreReportBundle.MAXIMUM_REPORT_BYTES)
				.write(result.reportId(), ProviderQualityComparativeScorer.artifacts(result));
	}

	private static ScoringResult result(String evidenceId) {
		return result(
				evidenceId,
				ProviderQualityComparativeScorer.REPORT_SCHEMA_VERSION,
				REPORT_ID);
	}

	private static ScoringResult result(
			String evidenceId, int schemaVersion, String reportId) {
		return new ScoringResult(
				schemaVersion,
				reportId,
				evidenceId,
				SHA_B,
				"1".repeat(40),
				"2026-08-27T05:45:00Z",
				SHA_C,
				SHA_D,
				"provider-quality-live-v1",
				SHA_E,
				"provider-quality-comparative-scoring-v1",
				SHA_F,
				0,
				new UniqueRelevantQueryCoverage(0, 0, 0.0d, List.of()),
				List.of(),
				Map.of(),
				false,
				false);
	}

	private static Map<String, byte[]> snapshot(Path directory) throws IOException {
		Map<String, byte[]> files = new LinkedHashMap<>();
		try (var paths = Files.list(directory)) {
			for (Path path : paths.sorted().toList()) {
				files.put(path.getFileName().toString(), Files.readAllBytes(path));
			}
		}
		return files;
	}

	private static void replacePayloadAndRebindManifest(
			Path directory, String filename, byte[] bytes) throws Exception {
		Files.write(directory.resolve(filename), bytes);
		List<ProviderQualityEvidenceWriter.FileDigest> digests = List.of(
				digest(directory, "query-scores.json"),
				digest(directory, "score-summary.json"));
		long payloadBytes = digests.stream()
				.mapToLong(ProviderQualityEvidenceWriter.FileDigest::bytes)
				.sum();
		ProviderQualityEvidenceWriter.EvidenceManifest manifest =
				new ProviderQualityEvidenceWriter.EvidenceManifest(
						1, REPORT_ID, payloadBytes, digests);
		Files.write(
				directory.resolve("manifest.json"),
				ProviderQualityComparativeReviewPacket.canonicalBytes(
						OBJECT_MAPPER, manifest));
	}

	private static ProviderQualityEvidenceWriter.FileDigest digest(
			Path directory, String filename) throws Exception {
		byte[] bytes = Files.readAllBytes(directory.resolve(filename));
		return new ProviderQualityEvidenceWriter.FileDigest(
				filename,
				bytes.length,
				HexFormat.of().formatHex(
						MessageDigest.getInstance("SHA-256").digest(bytes)));
	}

	private static void assertSnapshotEquals(
			Map<String, byte[]> expected, Map<String, byte[]> actual) {
		assertThat(actual.keySet()).containsExactlyElementsOf(expected.keySet());
		expected.forEach((filename, bytes) -> assertThat(actual.get(filename))
				.as(filename)
				.isEqualTo(bytes));
	}

	private static void assertFailure(
			Path directory, ScoringResult expected, String diagnostic) {
		assertThatThrownBy(() -> ProviderQualityComparativeScoreReportBundle.verifyExact(
				OBJECT_MAPPER, directory, expected))
				.isInstanceOf(IOException.class)
				.hasMessage(diagnostic);
	}
}
