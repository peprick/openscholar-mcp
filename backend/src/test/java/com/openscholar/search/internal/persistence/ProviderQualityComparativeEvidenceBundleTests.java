package com.openscholar.search.internal.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectWriter;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

class ProviderQualityComparativeEvidenceBundleTests {

	private static final JsonMapper OBJECT_MAPPER = JsonMapper.builder().build();
	private static final ObjectWriter CANONICAL_WRITER = OBJECT_MAPPER.writer()
			.with(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
	private static final List<String> PAYLOAD_FILENAMES = List.of(
			"blinded-candidates.json",
			"provenance-map.json",
			"reconciliation-trace.json",
			"summary.json");

	@TempDir
	private Path temporaryDirectory;

	@Test
	void verifiesAWriterGeneratedReviewReadyBundleAndReturnsDetachedTrees() throws Exception {
		Path directory = writeBundle("comparative-bundle-valid", true);

		ProviderQualityComparativeEvidenceBundle bundle =
				ProviderQualityComparativeEvidenceBundle.verify(OBJECT_MAPPER, directory);

		assertThat(bundle.sourceDirectory()).isEqualTo(directory.toAbsolutePath().normalize());
		assertThat(bundle.evidenceId()).isEqualTo("comparative-bundle-valid");
		assertThat(bundle.manifestSha256()).isEqualTo(sha256(
				Files.readAllBytes(directory.resolve("manifest.json"))));
		assertThat(bundle.reviewReady()).isTrue();
		assertThat(bundle.manifest().required("files")).hasSize(4);
		assertThat(bundle.summary().required("evidenceType").asString())
				.isEqualTo("LIVE_COMPARATIVE_METADATA_CAPTURE");
		assertThat(bundle.blindedCandidates().required("candidates")).isEmpty();
		assertThat(bundle.provenanceMap().required("candidates")).isEmpty();
		assertThat(bundle.reconciliationTrace().required("queries")).isEmpty();

		((ObjectNode) bundle.summary()).put("evidenceType", "MUTATED_BY_CALLER");
		assertThat(bundle.summary().required("evidenceType").asString())
				.isEqualTo("LIVE_COMPARATIVE_METADATA_CAPTURE");
	}

	@Test
	void verifiesAnIncompleteBundleButDoesNotMarkItReviewReady() throws Exception {
		Path directory = writeBundle("comparative-bundle-incomplete", false);

		ProviderQualityComparativeEvidenceBundle bundle =
				ProviderQualityComparativeEvidenceBundle.verify(OBJECT_MAPPER, directory);

		assertThat(bundle.reviewReady()).isFalse();
		assertThat(bundle.blindedCandidates().required("instructions").asString())
				.startsWith("Do not label");
	}

	@Test
	void rejectsByteTamperingEvenWhenTheChangedPayloadRemainsValidJson() throws Exception {
		Path directory = writeBundle("comparative-bundle-byte-tamper", true);
		mutateJsonWithoutRebindingManifest(
				directory.resolve("summary.json"),
				node -> node.put("evidenceType", "LIVE_COMPARATIVE_METADATA_CAPTURF"));

		assertRejected(directory, "EVIDENCE_PAYLOAD_DIGEST_MISMATCH");
	}

	@Test
	void rejectsMissingAndExtraEntriesWithoutEchoingTheirNamesOrPaths() throws Exception {
		Path missing = writeBundle("comparative-bundle-missing", true);
		Files.delete(missing.resolve("provenance-map.json"));
		assertRejected(missing, "EVIDENCE_LAYOUT_INVALID");

		Path extra = writeBundle("comparative-bundle-extra", true);
		Files.writeString(
				extra.resolve("unexpected-secret-name.json"),
				"{}\n",
				StandardCharsets.UTF_8);
		assertRejected(extra, "EVIDENCE_LAYOUT_INVALID");
	}

	@Test
	void rejectsSymlinkedDirectoriesAndPayloads() throws Exception {
		Path target = writeBundle("comparative-bundle-symlink-file", true);
		Path outside = temporaryDirectory.resolve("outside-summary.json");
		Files.writeString(outside, "{}\n", StandardCharsets.UTF_8);
		Files.delete(target.resolve("summary.json"));
		Files.createSymbolicLink(target.resolve("summary.json"), outside);
		assertRejected(target, "EVIDENCE_FILE_INVALID");

		Path directoryTarget = writeBundle("comparative-bundle-symlink-directory", true);
		Path linkParent = temporaryDirectory.resolve("bundle-links");
		Files.createDirectories(linkParent);
		Path directoryLink = linkParent.resolve("comparative-bundle-symlink-directory");
		Files.createSymbolicLink(directoryLink, directoryTarget);
		assertRejected(directoryLink, "EVIDENCE_DIRECTORY_INVALID");
	}

	@Test
	void rejectsDuplicateKeysAndTrailingDocumentsAfterIntegrityIsRebound() throws Exception {
		Path duplicate = writeBundle("comparative-bundle-duplicate-json", true);
		String duplicateSummary = "{"
				+ "\"schemaVersion\":2,"
				+ "\"evidenceId\":\"comparative-bundle-duplicate-json\","
				+ "\"evidenceId\":\"comparative-bundle-duplicate-json\","
				+ "\"evidenceType\":\"LIVE_COMPARATIVE_METADATA_CAPTURE\","
				+ "\"qualityReviewEligible\":true}\n";
		Files.writeString(
				duplicate.resolve("summary.json"), duplicateSummary, StandardCharsets.UTF_8);
		rewriteManifest(duplicate);
		assertRejected(duplicate, "EVIDENCE_PAYLOAD_JSON_INVALID");

		Path trailing = writeBundle("comparative-bundle-trailing-json", true);
		Files.writeString(
				trailing.resolve("reconciliation-trace.json"),
				"{}\n",
				StandardCharsets.UTF_8,
				StandardOpenOption.APPEND);
		rewriteManifest(trailing);
		assertRejected(trailing, "EVIDENCE_PAYLOAD_JSON_INVALID");

		Path duplicateManifest = writeBundle("comparative-bundle-duplicate-manifest", true);
		String manifestJson = Files.readString(
				duplicateManifest.resolve("manifest.json"), StandardCharsets.UTF_8);
		String evidenceField = "\"evidenceId\":\"comparative-bundle-duplicate-manifest\"";
		Files.writeString(
				duplicateManifest.resolve("manifest.json"),
				manifestJson.replaceFirst(evidenceField, evidenceField + "," + evidenceField),
				StandardCharsets.UTF_8);
		assertRejected(duplicateManifest, "EVIDENCE_MANIFEST_JSON_INVALID");
	}

	@Test
	void rejectsManifestSchemaOrderingPayloadBytesAndDirectoryIdentityViolations()
			throws Exception {
		Path unknownField = writeBundle("comparative-bundle-manifest-schema", true);
		mutateManifest(unknownField, manifest -> manifest.put("untrustedField", "secret"));
		assertRejected(unknownField, "EVIDENCE_MANIFEST_SCHEMA_INVALID");

		Path unsorted = writeBundle("comparative-bundle-manifest-order", true);
		mutateManifest(unsorted, manifest -> {
			ArrayNode files = (ArrayNode) manifest.required("files");
			JsonNode first = files.remove(0);
			files.add(first);
		});
		assertRejected(unsorted, "EVIDENCE_MANIFEST_FILES_INVALID");

		Path payloadBytes = writeBundle("comparative-bundle-manifest-bytes", true);
		mutateManifest(payloadBytes, manifest -> manifest.put(
				"payloadBytes", manifest.required("payloadBytes").asLong() + 1));
		assertRejected(payloadBytes, "EVIDENCE_MANIFEST_PAYLOAD_BYTES_INVALID");

		Path original = writeBundle("comparative-bundle-directory-source", true);
		Path renamed = original.resolveSibling("comparative-bundle-directory-renamed");
		Files.move(original, renamed);
		assertRejected(renamed, "EVIDENCE_ID_INVALID");
	}

	@Test
	void rejectsInconsistentDocumentIdsSchemaAndEligibility() throws Exception {
		Path inconsistentId = writeBundle("comparative-bundle-id-mismatch", true);
		mutatePayloadAndRebind(
				inconsistentId,
				"provenance-map.json",
				node -> node.put("evidenceId", "another-valid-evidence-id"));
		assertRejected(inconsistentId, "EVIDENCE_DOCUMENT_IDENTITY_INVALID");

		Path schema = writeBundle("comparative-bundle-schema-mismatch", true);
		mutatePayloadAndRebind(
				schema,
				"reconciliation-trace.json",
				node -> node.put("schemaVersion", 1));
		assertRejected(schema, "EVIDENCE_DOCUMENT_IDENTITY_INVALID");

		Path eligibility = writeBundle("comparative-bundle-eligibility-mismatch", true);
		mutatePayloadAndRebind(
				eligibility,
				"blinded-candidates.json",
				node -> node.put("qualityReviewEligible", false));
		assertRejected(eligibility, "EVIDENCE_REVIEW_ELIGIBILITY_INVALID");
	}

	@Test
	void rejectsIncompleteInstructionsThatInviteLabeling() throws Exception {
		Path directory = writeBundle("comparative-bundle-label-incomplete", false);
		mutatePayloadAndRebind(
				directory,
				"blinded-candidates.json",
				node -> node.put(
						"instructions",
						"Do not label immediately; assign one relevanceGrade to every candidate."));

		assertRejected(directory, "EVIDENCE_INCOMPLETE_REVIEW_INVALID");
	}

	@Test
	void rejectsEligibleInstructionsThatDoNotFreezeTheBlindedReviewProtocol()
			throws Exception {
		Path directory = writeBundle("comparative-bundle-unblinded-instructions", true);
		mutatePayloadAndRebind(
				directory,
				"blinded-candidates.json",
				node -> node.put(
						"instructions",
						"Assign relevance grades after consulting provenance-map.json."));

		assertRejected(directory, "EVIDENCE_REVIEW_INSTRUCTIONS_INVALID");
	}

	@Test
	void rejectsBoundedManifestAndAggregatePayloadLimitViolations() throws Exception {
		Path manifest = writeBundle("comparative-bundle-large-manifest", true);
		Files.write(
				manifest.resolve("manifest.json"),
				new byte[ProviderQualityComparativeEvidenceBundle.MAXIMUM_MANIFEST_BYTES + 1]);
		assertRejected(manifest, "EVIDENCE_MANIFEST_TOO_LARGE");

		Path payload = writeBundle("comparative-bundle-large-payload", true);
		try (var channel = Files.newByteChannel(
				payload.resolve("summary.json"),
				StandardOpenOption.WRITE,
				StandardOpenOption.TRUNCATE_EXISTING)) {
			channel.position(ProviderQualityComparativeEvidenceBundle.MAXIMUM_PAYLOAD_BYTES);
			channel.write(ByteBuffer.wrap(new byte[] {'}'}));
		}
		assertRejected(payload, "EVIDENCE_PAYLOAD_TOO_LARGE");
	}

	private Path writeBundle(String evidenceId, boolean eligible) throws Exception {
		ProviderQualityEvidenceWriter writer = ProviderQualityEvidenceWriter.forRepository(
				OBJECT_MAPPER,
				temporaryDirectory,
				ProviderQualityComparativeEvidenceBundle.MAXIMUM_PAYLOAD_BYTES);
		return writer.write(evidenceId, validArtifacts(evidenceId, eligible)).directory();
	}

	private static Map<String, Object> validArtifacts(String evidenceId, boolean eligible) {
		Map<String, Object> summary = new LinkedHashMap<>();
		summary.put("schemaVersion", 2);
		summary.put("evidenceType", "LIVE_COMPARATIVE_METADATA_CAPTURE");
		summary.put("evidenceId", evidenceId);
		summary.put("qualityReviewEligible", eligible);
		summary.put("queries", List.of());

		Map<String, Object> blinded = new LinkedHashMap<>();
		blinded.put("schemaVersion", 2);
		blinded.put("evidenceId", evidenceId);
		blinded.put("qualityReviewEligible", eligible);
		blinded.put(
				"instructions",
				eligible
						? ProviderQualityComparativeEvidenceBundle.ELIGIBLE_REVIEW_INSTRUCTIONS
						: "Do not label this incomplete capture; inspect summary.json and repeat the isolated run.");
		blinded.put("candidates", List.of());

		Map<String, Object> artifacts = new LinkedHashMap<>();
		artifacts.put("summary.json", summary);
		artifacts.put("blinded-candidates.json", blinded);
		artifacts.put("provenance-map.json", Map.of(
				"schemaVersion", 2,
				"evidenceId", evidenceId,
				"candidates", List.of()));
		artifacts.put("reconciliation-trace.json", Map.of(
				"schemaVersion", 2,
				"evidenceId", evidenceId,
				"queries", List.of()));
		return artifacts;
	}

	private static void mutateJsonWithoutRebindingManifest(
			Path file, Consumer<ObjectNode> mutation) throws Exception {
		ObjectNode node = (ObjectNode) OBJECT_MAPPER.readTree(Files.readAllBytes(file));
		mutation.accept(node);
		writeJson(file, node);
	}

	private static void mutatePayloadAndRebind(
			Path directory, String filename, Consumer<ObjectNode> mutation) throws Exception {
		mutateJsonWithoutRebindingManifest(directory.resolve(filename), mutation);
		rewriteManifest(directory);
	}

	private static void mutateManifest(
			Path directory, Consumer<ObjectNode> mutation) throws Exception {
		Path file = directory.resolve("manifest.json");
		ObjectNode manifest = (ObjectNode) OBJECT_MAPPER.readTree(Files.readAllBytes(file));
		mutation.accept(manifest);
		writeJson(file, manifest);
	}

	private static void rewriteManifest(Path directory) throws Exception {
		long payloadBytes = 0;
		List<Map<String, Object>> files = new ArrayList<>();
		for (String filename : PAYLOAD_FILENAMES) {
			byte[] bytes = Files.readAllBytes(directory.resolve(filename));
			payloadBytes += bytes.length;
			files.add(Map.of(
					"filename", filename,
					"bytes", bytes.length,
					"sha256", sha256(bytes)));
		}
		Map<String, Object> manifest = new LinkedHashMap<>();
		manifest.put("schemaVersion", 1);
		manifest.put("evidenceId", directory.getFileName().toString());
		manifest.put("payloadBytes", payloadBytes);
		manifest.put("files", files);
		writeJson(directory.resolve("manifest.json"), manifest);
	}

	private static void writeJson(Path file, Object value) throws Exception {
		byte[] json = CANONICAL_WRITER.writeValueAsBytes(value);
		byte[] terminated = Arrays.copyOf(json, json.length + 1);
		terminated[terminated.length - 1] = '\n';
		Files.write(file, terminated);
	}

	private static String sha256(byte[] bytes) throws Exception {
		return HexFormat.of().formatHex(
				MessageDigest.getInstance("SHA-256").digest(bytes));
	}

	private static void assertRejected(Path directory, String diagnostic) {
		assertThatThrownBy(() -> ProviderQualityComparativeEvidenceBundle.verify(
				OBJECT_MAPPER, directory))
				.isInstanceOf(ProviderQualityComparativeEvidenceBundle.VerificationException.class)
				.hasMessage(diagnostic)
				.satisfies(failure -> {
					assertThat(failure.getMessage()).hasSizeLessThan(128);
					assertThat(failure.getMessage()).doesNotContain(directory.toString());
					assertThat(failure.getMessage()).doesNotContain("secret");
				});
	}
}
