package com.openscholar.search.internal.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import com.openscholar.search.internal.persistence.ProviderQualityComparativeRunSealBundle.Bindings;
import com.openscholar.search.internal.persistence.ProviderQualityComparativeRunSealBundle.VerifiedRunSeal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

class ProviderQualityComparativeRunSealBundleTests {

	private static final JsonMapper OBJECT_MAPPER = JsonMapper.builder().build();
	private static final byte[] BASE_WORKSHEET = bytes("{\"answer\":1}\n");
	private static final byte[] ONE_BYTE_WORKSHEET = bytes("{\"answer\":1} \n");
	private static final byte[] WHITESPACE_WORKSHEET = bytes("{\n  \"answer\" : 1\n}\n");
	private static final String EXPECTED_SEAL_ID =
			"provider-quality-comparative-run-seal-v1-"
					+ "e5011e6e04f80f383d93165bc2f5f83d68d220aa8dd0057761e534313cb8a728";
	private static final String EXPECTED_SEAL_SHA256 =
			"cb49ed2052c8057e165d3f53077e6687c589be35404f5be3d29ba5ec0c69864b";

	@TempDir
	private Path temporaryDirectory;

	@Test
	void publishesAndVerifiesTheFixedVectorWithoutMutatingSources() throws Exception {
		Fixture fixture = fixture("fixed-vector-sources", BASE_WORKSHEET);
		Path root = privateDirectory("fixed-vector-root");
		Map<String, byte[]> sourceSnapshot = snapshotSources(fixture.sources());

		VerifiedRunSeal published = ProviderQualityComparativeRunSealBundle.publishAndVerify(
				OBJECT_MAPPER, root, fixture.bindings(), fixture.sources());
		BasicFileAttributes beforeRepublish = Files.readAttributes(
				published.sourceDirectory(), BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
		VerifiedRunSeal verified = ProviderQualityComparativeRunSealBundle.verifyExact(
				OBJECT_MAPPER, published.sourceDirectory(), fixture.bindings());
		VerifiedRunSeal republished = ProviderQualityComparativeRunSealBundle.publishAndVerify(
				OBJECT_MAPPER, root, fixture.bindings(), fixture.sources());
		BasicFileAttributes afterRepublish = Files.readAttributes(
				republished.sourceDirectory(), BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);

		assertThat(List.of(published.sealId(), published.sealSha256()))
				.containsExactly(EXPECTED_SEAL_ID, EXPECTED_SEAL_SHA256);
		assertThat(verified).isEqualTo(published);
		assertThat(republished).isEqualTo(published);
		assertThat(afterRepublish.fileKey()).isEqualTo(beforeRepublish.fileKey());
		assertThat(published.sourceDirectory()).isEqualTo(root.resolve(EXPECTED_SEAL_ID));
		assertThat(published.bindings()).isEqualTo(fixture.bindings());
		assertThat(published.files())
				.extracting(ProviderQualityComparativeRunSealBundle.SealedFile::path)
				.containsExactlyElementsOf(
						ProviderQualityComparativeRunSealBundle.expectedPayloadPaths(
								fixture.bindings()));
		assertThat(published.payloadBytes()).isEqualTo(sourceSnapshot.values().stream()
				.mapToLong(bytes -> bytes.length)
				.sum());
		assertThat(published.totalBytes()).isEqualTo(
				published.payloadBytes() + Files.size(
						published.sourceDirectory().resolve("run-seal.json")));
		assertThat(relativeEntries(published.sourceDirectory()))
				.isEqualTo(expectedEntries(fixture.bindings()));
		assertThat(entries(root)).containsExactly(EXPECTED_SEAL_ID);
		assertSourceSnapshot(sourceSnapshot, fixture.sources());
		assertPrivateTreeWhenPosix(published.sourceDirectory());
	}

	@Test
	void rawWorksheetBytesChangeIdentityWhileCanonicalJudgmentsStayTheSame()
			throws Exception {
		Fixture base = fixture("worksheet-base-sources", BASE_WORKSHEET);
		Fixture oneByte = fixture("worksheet-one-byte-sources", ONE_BYTE_WORKSHEET);
		Fixture whitespace = fixture("worksheet-whitespace-sources", WHITESPACE_WORKSHEET);
		Path root = privateDirectory("worksheet-root");

		VerifiedRunSeal baseSeal = publish(root, base);
		VerifiedRunSeal oneByteSeal = publish(root, oneByte);
		VerifiedRunSeal whitespaceSeal = publish(root, whitespace);

		assertThat(ONE_BYTE_WORKSHEET).hasSize(BASE_WORKSHEET.length + 1);
		assertThat(oneByte.bindings().judgmentsSha256())
				.isEqualTo(base.bindings().judgmentsSha256());
		assertThat(whitespace.bindings().judgmentsSha256())
				.isEqualTo(base.bindings().judgmentsSha256());
		assertThat(oneByte.bindings().reportId()).isEqualTo(base.bindings().reportId());
		assertThat(whitespace.bindings().reportId()).isEqualTo(base.bindings().reportId());
		assertThat(Set.of(
				base.bindings().completedWorksheetSha256(),
				oneByte.bindings().completedWorksheetSha256(),
				whitespace.bindings().completedWorksheetSha256())).hasSize(3);
		assertThat(Set.of(baseSeal.sealId(), oneByteSeal.sealId(), whitespaceSeal.sealId()))
				.hasSize(3);
	}

	@Test
	void rejectsMissingExtraSymlinkedAndTamperedPayloads() throws Exception {
		Published missing = published("payload-missing", BASE_WORKSHEET);
		Files.delete(missing.runDirectory().resolve("review/judgments.json"));
		assertVerifyFailure(missing, "RUN_SEAL_LAYOUT_INVALID");

		Published extra = published("payload-extra", BASE_WORKSHEET);
		Path unexpected = extra.runDirectory().resolve("unexpected.json");
		Files.writeString(unexpected, "{}\n", StandardCharsets.UTF_8);
		makePrivateFile(unexpected);
		assertVerifyFailure(extra, "RUN_SEAL_LAYOUT_INVALID");

		Published linked = published("payload-link", BASE_WORKSHEET);
		Path linkedPayload = linked.runDirectory().resolve("review/judgments.json");
		Path outside = temporaryDirectory.resolve("outside-judgments.json");
		Files.copy(linkedPayload, outside);
		Files.delete(linkedPayload);
		Files.createSymbolicLink(linkedPayload, outside);
		assertVerifyFailure(linked, "RUN_SEAL_LINK_INVALID");

		Published tampered = published("payload-tampered", BASE_WORKSHEET);
		Path summary = tampered.runDirectory().resolve(
				"capture/" + tampered.fixture().bindings().evidenceId() + "/summary.json");
		Files.writeString(summary, "{\"artifact\":\"tampered\"}\n", StandardCharsets.UTF_8);
		assertVerifyFailure(tampered, "RUN_SEAL_ID_INVALID");
	}

	@Test
	void rejectsMalformedDuplicateTrailingNoncanonicalAndWrongSealIds() throws Exception {
		Published malformed = published("seal-malformed", BASE_WORKSHEET);
		Files.writeString(malformed.sealFile(), "{\n", StandardCharsets.UTF_8);
		assertVerifyFailure(malformed, "RUN_SEAL_JSON_INVALID");

		Published duplicate = published("seal-duplicate", BASE_WORKSHEET);
		String canonical = Files.readString(duplicate.sealFile());
		String duplicateJson = canonical.replace(
				"\"schemaVersion\":1",
				"\"schemaVersion\":1,\"schemaVersion\":1");
		assertThat(duplicateJson).isNotEqualTo(canonical);
		Files.writeString(duplicate.sealFile(), duplicateJson, StandardCharsets.UTF_8);
		assertVerifyFailure(duplicate, "RUN_SEAL_JSON_INVALID");

		Published trailing = published("seal-trailing", BASE_WORKSHEET);
		Files.writeString(
				trailing.sealFile(),
				Files.readString(trailing.sealFile()) + "{}\n",
				StandardCharsets.UTF_8);
		assertVerifyFailure(trailing, "RUN_SEAL_JSON_INVALID");

		Published noncanonical = published("seal-noncanonical", BASE_WORKSHEET);
		Files.writeString(
				noncanonical.sealFile(),
				" " + Files.readString(noncanonical.sealFile()),
				StandardCharsets.UTF_8);
		assertVerifyFailure(noncanonical, "RUN_SEAL_NOT_CANONICAL_OR_EXPECTED");

		Published wrongEmbeddedId = published("seal-wrong-id", BASE_WORKSHEET);
		String zeroId = ProviderQualityComparativeRunSealBundle.RUN_SEAL_ID_PREFIX
				+ "0".repeat(64);
		String changed = Files.readString(wrongEmbeddedId.sealFile())
				.replace(wrongEmbeddedId.seal().sealId(), zeroId);
		assertThat(changed).isNotEqualTo(Files.readString(wrongEmbeddedId.sealFile()));
		Files.writeString(wrongEmbeddedId.sealFile(), changed, StandardCharsets.UTF_8);
		assertVerifyFailure(wrongEmbeddedId, "RUN_SEAL_NOT_CANONICAL_OR_EXPECTED");
	}

	@Test
	void rejectsWrongBindingsAndDirectoryIdentity() throws Exception {
		Published wrongBinding = published("wrong-binding", BASE_WORKSHEET);
		Bindings original = wrongBinding.fixture().bindings();
		Bindings mismatched = new Bindings(
				original.evidenceId(),
				original.evidenceManifestSha256(),
				original.captureRepositoryRevision(),
				original.captureMeasuredAt(),
				original.querySetId(),
				original.querySetSha256(),
				original.scoringPolicyId(),
				original.scoringPolicySha256(),
				"9".repeat(64),
				original.completedWorksheetSha256(),
				original.judgmentsSha256(),
				original.reportId(),
				original.reportManifestSha256());
		assertThatThrownBy(() -> ProviderQualityComparativeRunSealBundle.verifyExact(
				OBJECT_MAPPER, wrongBinding.runDirectory(), mismatched))
				.isInstanceOf(IOException.class)
				.hasMessage("RUN_SEAL_BINDING_MISMATCH");

		Published wrongDirectory = published("wrong-directory-id", BASE_WORKSHEET);
		Path renamed = wrongDirectory.root().resolve(
				ProviderQualityComparativeRunSealBundle.RUN_SEAL_ID_PREFIX + "0".repeat(64));
		Files.move(wrongDirectory.runDirectory(), renamed);
		assertThatThrownBy(() -> ProviderQualityComparativeRunSealBundle.verifyExact(
				OBJECT_MAPPER, renamed, wrongDirectory.fixture().bindings()))
				.isInstanceOf(IOException.class)
				.hasMessage("RUN_SEAL_ID_INVALID");
	}

	@Test
	void rejectsNoncanonicalPosixDirectoryAndFileModes() throws Exception {
		Published directoryMode = published("directory-mode", BASE_WORKSHEET);
		PosixFileAttributeView directoryView = Files.getFileAttributeView(
				directoryMode.runDirectory(),
				PosixFileAttributeView.class,
				LinkOption.NOFOLLOW_LINKS);
		if (directoryView == null) {
			return;
		}
		Files.setPosixFilePermissions(
				directoryMode.runDirectory(),
				PosixFilePermissions.fromString("rwxr-x---"));
		assertVerifyFailure(directoryMode, "RUN_SEAL_PERMISSIONS_NOT_PRIVATE");

		Published fileMode = published("file-mode", BASE_WORKSHEET);
		Files.setPosixFilePermissions(
				fileMode.runDirectory().resolve("review/judgments.json"),
				PosixFilePermissions.fromString("rwx------"));
		assertVerifyFailure(fileMode, "RUN_SEAL_PERMISSIONS_NOT_PRIVATE");
	}

	@Test
	void enforcesExactSourceMapKeysAndRejectsSourceSymlinks() throws Exception {
		Fixture fixture = fixture("invalid-source-map-sources", BASE_WORKSHEET);
		String firstPath = ProviderQualityComparativeRunSealBundle
				.expectedPayloadPaths(fixture.bindings()).getFirst();

		Map<String, Path> missing = new LinkedHashMap<>(fixture.sources());
		missing.remove(firstPath);
		assertPublishFailure(
				privateDirectory("missing-source-map-root"),
				fixture.bindings(),
				missing,
				"RUN_SEAL_SOURCE_LAYOUT_INVALID");

		Map<String, Path> extra = new LinkedHashMap<>(fixture.sources());
		extra.put("unexpected.json", fixture.sources().get(firstPath));
		assertPublishFailure(
				privateDirectory("extra-source-map-root"),
				fixture.bindings(),
				extra,
				"RUN_SEAL_SOURCE_LAYOUT_INVALID");

		Path linkTarget = fixture.sources().get(firstPath);
		Path link = temporaryDirectory.resolve("source-link.json");
		Files.createSymbolicLink(link, linkTarget);
		Map<String, Path> symlinked = new LinkedHashMap<>(fixture.sources());
		symlinked.put(firstPath, link);
		assertPublishFailure(
				privateDirectory("linked-source-map-root"),
				fixture.bindings(),
				symlinked,
				"RUN_SEAL_SOURCE_FILE_INVALID");
	}

	@Test
	void rejectsAggregateEvidenceAboveTheGroupBoundBeforeCopying() throws Exception {
		Fixture fixture = fixture("group-bound-sources", BASE_WORKSHEET);
		Map<String, Path> sources = new LinkedHashMap<>(fixture.sources());
		long halfPlusOne = ProviderQualityComparativeEvidenceBundle.MAXIMUM_PAYLOAD_BYTES / 2 + 1;
		String prefix = "capture/" + fixture.bindings().evidenceId() + "/";
		sources.put(prefix + "summary.json", sparseFile("large-summary.json", halfPlusOne));
		sources.put(
				prefix + "blinded-candidates.json",
				sparseFile("large-blinded-candidates.json", halfPlusOne));
		Path root = privateDirectory("group-bound-root");

		assertPublishFailure(
				root,
				fixture.bindings(),
				sources,
				"RUN_SEAL_PAYLOAD_TOO_LARGE");
		assertThat(entries(root)).isEmpty();
	}

	@Test
	void neverOverwritesAMismatchedExistingBundle() throws Exception {
		Fixture fixture = fixture("no-overwrite-sources", BASE_WORKSHEET);
		Path root = privateDirectory("no-overwrite-root");
		VerifiedRunSeal first = publish(root, fixture);
		Path summary = first.sourceDirectory().resolve(
				"capture/" + fixture.bindings().evidenceId() + "/summary.json");
		Files.writeString(summary, "{\"artifact\":\"existing-mismatch\"}\n");
		Map<String, byte[]> mismatchedSnapshot = snapshotTree(first.sourceDirectory());

		assertPublishFailure(
				root,
				fixture.bindings(),
				fixture.sources(),
				"RUN_SEAL_ID_INVALID");
		assertTreeSnapshot(mismatchedSnapshot, first.sourceDirectory());
		assertThat(entries(root)).containsExactly(first.sealId());
	}

	private Published published(String name, byte[] worksheet) throws Exception {
		Fixture fixture = fixture(name + "-sources", worksheet);
		Path root = privateDirectory(name + "-root");
		VerifiedRunSeal seal = publish(root, fixture);
		return new Published(fixture, root, seal);
	}

	private static VerifiedRunSeal publish(Path root, Fixture fixture) throws IOException {
		return ProviderQualityComparativeRunSealBundle.publishAndVerify(
				OBJECT_MAPPER, root, fixture.bindings(), fixture.sources());
	}

	private Fixture fixture(String name, byte[] worksheetBytes) throws Exception {
		byte[] evidenceManifest = bytes("{\"kind\":\"capture-manifest\",\"version\":1}\n");
		byte[] reviewPacket = bytes("{\"kind\":\"review-packet\",\"version\":1}\n");
		byte[] judgments = bytes("{\"judgments\":\"canonical-and-unchanged\"}\n");
		String evidenceManifestSha256 = sha256(evidenceManifest);
		String judgmentsSha256 = sha256(judgments);
		String scoringPolicySha256 = "3".repeat(64);
		String reportId = ProviderQualityComparativeScorer.reportId(
				evidenceManifestSha256, judgmentsSha256, scoringPolicySha256);
		byte[] reportManifest = bytes(
				"{\"kind\":\"score-manifest\",\"reportId\":\"" + reportId + "\"}\n");
		Bindings bindings = new Bindings(
				"synthetic-comparative-run",
				evidenceManifestSha256,
				"1".repeat(40),
				"2026-08-27T06:00:00Z",
				"synthetic-query-set",
				"2".repeat(64),
				"synthetic-scoring-policy",
				scoringPolicySha256,
				sha256(reviewPacket),
				sha256(worksheetBytes),
				judgmentsSha256,
				reportId,
				sha256(reportManifest));

		Path sourceDirectory = Files.createDirectory(temporaryDirectory.resolve(name));
		Map<String, Path> sources = new LinkedHashMap<>();
		int index = 0;
		for (String relative : ProviderQualityComparativeRunSealBundle
				.expectedPayloadPaths(bindings)) {
			byte[] content = switch (relative) {
				case "capture/synthetic-comparative-run/manifest.json" -> evidenceManifest;
				case "review/review-packet.json" -> reviewPacket;
				case "review/completed-worksheet.json" -> worksheetBytes;
				case "review/judgments.json" -> judgments;
				default -> relative.equals("score/" + reportId + "/manifest.json")
						? reportManifest
						: bytes("{\"artifact\":\"" + relative + "\"}\n");
			};
			Path source = sourceDirectory.resolve("source-" + index++ + ".json");
			Files.write(source, content);
			sources.put(relative, source);
		}
		return new Fixture(bindings, Map.copyOf(sources));
	}

	private Path privateDirectory(String name) throws IOException {
		Path directory = Files.createDirectory(temporaryDirectory.resolve(name))
				.toAbsolutePath().normalize();
		PosixFileAttributeView view = Files.getFileAttributeView(
				directory, PosixFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
		if (view != null) {
			Files.setPosixFilePermissions(
					directory, PosixFilePermissions.fromString("rwx------"));
		}
		return directory.toRealPath();
	}

	private Path sparseFile(String name, long bytes) throws IOException {
		Path file = temporaryDirectory.resolve(name);
		try (SeekableByteChannel channel = Files.newByteChannel(
				file, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
			channel.position(bytes - 1);
			channel.write(ByteBuffer.wrap(new byte[] {1}));
		}
		assertThat(Files.size(file)).isEqualTo(bytes);
		return file;
	}

	private static Set<String> expectedEntries(Bindings bindings) {
		Set<String> expected = new TreeSet<>(Set.of(
				"capture",
				"capture/" + bindings.evidenceId(),
				"review",
				"score",
				"score/" + bindings.reportId(),
				"run-seal.json"));
		expected.addAll(ProviderQualityComparativeRunSealBundle.expectedPayloadPaths(bindings));
		return expected;
	}

	private static Set<String> relativeEntries(Path directory) throws IOException {
		Set<String> result = new TreeSet<>();
		try (var paths = Files.walk(directory)) {
			paths.filter(path -> !path.equals(directory))
					.map(directory::relativize)
					.map(ProviderQualityComparativeRunSealBundleTests::posixPath)
					.forEach(result::add);
		}
		return result;
	}

	private static List<String> entries(Path directory) throws IOException {
		try (var paths = Files.list(directory)) {
			return paths.map(path -> path.getFileName().toString()).sorted().toList();
		}
	}

	private static Map<String, byte[]> snapshotSources(Map<String, Path> sources)
			throws IOException {
		Map<String, byte[]> result = new TreeMap<>();
		for (Map.Entry<String, Path> source : sources.entrySet()) {
			result.put(source.getKey(), Files.readAllBytes(source.getValue()));
		}
		return result;
	}

	private static Map<String, byte[]> snapshotTree(Path directory) throws IOException {
		Map<String, byte[]> result = new TreeMap<>();
		try (var paths = Files.walk(directory)) {
			for (Path path : paths.filter(Files::isRegularFile).toList()) {
				result.put(posixPath(directory.relativize(path)), Files.readAllBytes(path));
			}
		}
		return result;
	}

	private static void assertSourceSnapshot(
			Map<String, byte[]> expected, Map<String, Path> sources) throws IOException {
		assertThat(sources.keySet()).containsExactlyInAnyOrderElementsOf(expected.keySet());
		for (Map.Entry<String, byte[]> entry : expected.entrySet()) {
			assertThat(Files.readAllBytes(sources.get(entry.getKey())))
					.as(entry.getKey())
					.isEqualTo(entry.getValue());
		}
	}

	private static void assertTreeSnapshot(Map<String, byte[]> expected, Path directory)
			throws IOException {
		Map<String, byte[]> actual = snapshotTree(directory);
		assertThat(actual.keySet()).containsExactlyElementsOf(expected.keySet());
		for (Map.Entry<String, byte[]> entry : expected.entrySet()) {
			assertThat(actual.get(entry.getKey()))
					.as(entry.getKey())
					.isEqualTo(entry.getValue());
		}
	}

	private static void assertPrivateTreeWhenPosix(Path directory) throws Exception {
		PosixFileAttributeView rootView = Files.getFileAttributeView(
				directory, PosixFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
		if (rootView == null) {
			return;
		}
		try (var paths = Files.walk(directory)) {
			for (Path path : paths.toList()) {
				String expected = Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)
						? "rwx------"
						: "rw-------";
				assertThat(Files.getPosixFilePermissions(path, LinkOption.NOFOLLOW_LINKS))
						.as(posixPath(directory.relativize(path)))
						.isEqualTo(PosixFilePermissions.fromString(expected));
			}
		}
	}

	private static void makePrivateFile(Path file) throws IOException {
		PosixFileAttributeView view = Files.getFileAttributeView(
				file, PosixFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
		if (view != null) {
			Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
		}
	}

	private static void assertVerifyFailure(Published published, String diagnostic) {
		assertThatThrownBy(() -> ProviderQualityComparativeRunSealBundle.verifyExact(
				OBJECT_MAPPER,
				published.runDirectory(),
				published.fixture().bindings()))
				.isInstanceOf(IOException.class)
				.hasMessage(diagnostic);
	}

	private static void assertPublishFailure(
			Path root,
			Bindings bindings,
			Map<String, Path> sources,
			String diagnostic) {
		assertThatThrownBy(() -> ProviderQualityComparativeRunSealBundle.publishAndVerify(
				OBJECT_MAPPER, root, bindings, sources))
				.isInstanceOf(IOException.class)
				.hasMessage(diagnostic);
	}

	private static byte[] bytes(String value) {
		return value.getBytes(StandardCharsets.UTF_8);
	}

	private static String sha256(byte[] bytes) throws Exception {
		return HexFormat.of().formatHex(
				MessageDigest.getInstance("SHA-256").digest(bytes));
	}

	private static String posixPath(Path path) {
		StringBuilder result = new StringBuilder();
		for (Path component : path) {
			if (!result.isEmpty()) {
				result.append('/');
			}
			result.append(component);
		}
		return result.toString();
	}

	private record Fixture(Bindings bindings, Map<String, Path> sources) {
	}

	private record Published(Fixture fixture, Path root, VerifiedRunSeal seal) {

		private Path runDirectory() {
			return seal.sourceDirectory();
		}

		private Path sealFile() {
			return runDirectory().resolve("run-seal.json");
		}
	}
}
