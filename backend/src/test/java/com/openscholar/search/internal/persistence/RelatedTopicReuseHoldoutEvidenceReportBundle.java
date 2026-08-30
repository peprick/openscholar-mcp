package com.openscholar.search.internal.persistence;

import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.Channels;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;

import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectWriter;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * Exact read-only verification boundary for a privately retained related-topic
 * holdout evidence report. A retained directory is trusted only relative to the
 * already verified in-memory artifact capability supplied by the caller.
 */
final class RelatedTopicReuseHoldoutEvidenceReportBundle {

	static final String MANIFEST_FILENAME = "manifest.json";
	static final String PROTOCOL_ID =
			"related-topic-reuse-holdout-retained-report-v1";
	static final int MAXIMUM_MANIFEST_BYTES = 64 * 1024;

	private static final int SCHEMA_VERSION = 1;
	private static final Pattern REPORT_ID = Pattern.compile(
			"related-topic-reuse-holdout-report-v2-[0-9a-f]{64}");
	private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");
	private static final List<String> ARTIFACT_FILENAMES = List.of(
			RelatedTopicReuseHoldoutEvidenceReport.EVALUATOR_SOURCE_FILENAME,
			RelatedTopicReuseHoldoutEvidenceReport.RANKING_SNAPSHOT_FILENAME,
			RelatedTopicReuseHoldoutEvidenceReport.SCORING_RESULT_FILENAME,
			RelatedTopicReuseHoldoutEvidenceReport.EVIDENCE_REPORT_FILENAME);
	private static final Set<String> EXPECTED_FILENAMES = Set.of(
			MANIFEST_FILENAME,
			RelatedTopicReuseHoldoutEvidenceReport.EVALUATOR_SOURCE_FILENAME,
			RelatedTopicReuseHoldoutEvidenceReport.RANKING_SNAPSHOT_FILENAME,
			RelatedTopicReuseHoldoutEvidenceReport.SCORING_RESULT_FILENAME,
			RelatedTopicReuseHoldoutEvidenceReport.EVIDENCE_REPORT_FILENAME);
	private static final Set<PosixFilePermission> DIRECTORY_PERMISSIONS =
			PosixFilePermissions.fromString("rwx------");
	private static final Set<PosixFilePermission> FILE_PERMISSIONS =
			PosixFilePermissions.fromString("rw-------");
	private static final ObjectWriter CANONICAL_WRITER = JsonMapper.builder()
			.build()
			.writer()
			.with(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
			.without(SerializationFeature.INDENT_OUTPUT);
	private static final JsonMapper STRICT_MAPPER = JsonMapper.builder().build();

	private RelatedTopicReuseHoldoutEvidenceReportBundle() {
	}

	static VerifiedRetainedReport verifyExact(
			Path repositoryRoot,
			Path retainedDirectory,
			RelatedTopicReuseHoldoutEvidenceReport.VerifiedArtifacts expected)
			throws IOException {
		try {
			return verifyExactInternal(repositoryRoot, retainedDirectory, expectedBundle(expected));
		}
		catch (VerificationException exception) {
			throw exception;
		}
		catch (IOException | RuntimeException exception) {
			throw failure("HOLDOUT_REPORT_BUNDLE_VERIFICATION_FAILED");
		}
	}

	static ExpectedBundle expectedBundle(
			RelatedTopicReuseHoldoutEvidenceReport.VerifiedArtifacts expected)
			throws IOException {
		RelatedTopicReuseHoldoutEvidenceReport.VerifiedArtifacts verified =
				Objects.requireNonNull(expected, "expected");
		if (!REPORT_ID.matcher(verified.reportId()).matches()) {
			throw failure("HOLDOUT_REPORT_BUNDLE_ID_INVALID");
		}
		Map<String, byte[]> artifacts = verified.artifacts();
		Map<String, String> digests = verified.artifactSha256();
		if (!new ArrayList<>(artifacts.keySet()).equals(ARTIFACT_FILENAMES)
				|| !new ArrayList<>(digests.keySet()).equals(ARTIFACT_FILENAMES)) {
			throw failure("HOLDOUT_REPORT_BUNDLE_EXPECTED_LAYOUT_INVALID");
		}

		long payloadBytes = 0L;
		List<FileCommitment> files = new ArrayList<>(ARTIFACT_FILENAMES.size());
		for (String filename : ARTIFACT_FILENAMES) {
			byte[] bytes = Objects.requireNonNull(artifacts.get(filename), filename);
			String digest = Objects.requireNonNull(digests.get(filename), filename);
			if (!SHA256.matcher(digest).matches()
					|| bytes.length < 1
					|| bytes.length > maximumArtifactBytes(filename)
					|| !digest.equals(sha256(bytes))) {
				throw failure("HOLDOUT_REPORT_BUNDLE_EXPECTED_ARTIFACT_INVALID");
			}
			try {
				payloadBytes = Math.addExact(payloadBytes, bytes.length);
			}
			catch (ArithmeticException exception) {
				throw failure("HOLDOUT_REPORT_BUNDLE_EXPECTED_SIZE_INVALID");
			}
			files.add(new FileCommitment(filename, bytes.length, digest));
		}
		if (payloadBytes != verified.totalBytes()) {
			throw failure("HOLDOUT_REPORT_BUNDLE_EXPECTED_SIZE_INVALID");
		}

		byte[] manifest = canonicalManifest(verified.reportId(), payloadBytes, files);
		if (manifest.length < 1 || manifest.length > MAXIMUM_MANIFEST_BYTES) {
			throw failure("HOLDOUT_REPORT_BUNDLE_MANIFEST_TOO_LARGE");
		}
		long totalBytes;
		try {
			totalBytes = Math.addExact(payloadBytes, manifest.length);
		}
		catch (ArithmeticException exception) {
			throw failure("HOLDOUT_REPORT_BUNDLE_EXPECTED_SIZE_INVALID");
		}
		return new ExpectedBundle(
				verified.reportId(),
				payloadBytes,
				totalBytes,
				manifest,
				artifacts,
				files);
	}

	private static VerifiedRetainedReport verifyExactInternal(
			Path repositoryRoot, Path suppliedDirectory, ExpectedBundle expected)
			throws IOException {
		Path repository = realDirectory(
				Objects.requireNonNull(repositoryRoot, "repositoryRoot"),
				false,
				"HOLDOUT_REPORT_BUNDLE_REPOSITORY_INVALID");
		Path directory = realDirectory(
				Objects.requireNonNull(suppliedDirectory, "retainedDirectory"),
				true,
				"HOLDOUT_REPORT_BUNDLE_DIRECTORY_INVALID");
		if (directory.startsWith(repository) || repository.startsWith(directory)) {
			throw failure("HOLDOUT_REPORT_BUNDLE_REPOSITORY_OVERLAP");
		}
		Path directoryName = directory.getFileName();
		if (directoryName == null || !expected.reportId().equals(directoryName.toString())) {
			throw failure("HOLDOUT_REPORT_BUNDLE_ID_INVALID");
		}
		requirePrivatePermissions(directory, true);
		verifyLayout(directory);

		byte[] observedManifest = readExact(
				directory.resolve(MANIFEST_FILENAME),
				expected.manifestBytes(),
				MAXIMUM_MANIFEST_BYTES,
				"HOLDOUT_REPORT_BUNDLE_MANIFEST_INVALID");
		parseStrict(observedManifest);
		if (!MessageDigest.isEqual(observedManifest, expected.manifestBytes())) {
			throw failure("HOLDOUT_REPORT_BUNDLE_MANIFEST_NOT_EXPECTED");
		}
		for (String filename : ARTIFACT_FILENAMES) {
			byte[] expectedBytes = expected.artifacts().get(filename);
			readExact(
					directory.resolve(filename),
					expectedBytes,
					maximumArtifactBytes(filename),
					"HOLDOUT_REPORT_BUNDLE_ARTIFACT_NOT_EXPECTED");
		}

		verifyUnchanged(directory, expected);
		List<FileCommitment> retainedFiles = new ArrayList<>();
		retainedFiles.add(new FileCommitment(
				MANIFEST_FILENAME,
				expected.manifestBytes().length,
				sha256(expected.manifestBytes())));
		retainedFiles.addAll(expected.files());
		return new VerifiedRetainedReport(
				directory,
				expected.reportId(),
				sha256(expected.manifestBytes()),
				expected.payloadBytes(),
				expected.totalBytes(),
				retainedFiles);
	}

	private static void verifyUnchanged(Path directory, ExpectedBundle expected)
			throws IOException {
		verifyLayout(directory);
		byte[] manifest = readExact(
				directory.resolve(MANIFEST_FILENAME),
				expected.manifestBytes(),
				MAXIMUM_MANIFEST_BYTES,
				"HOLDOUT_REPORT_BUNDLE_FILE_CHANGED");
		if (!MessageDigest.isEqual(manifest, expected.manifestBytes())) {
			throw failure("HOLDOUT_REPORT_BUNDLE_FILE_CHANGED");
		}
		for (String filename : ARTIFACT_FILENAMES) {
			readExact(
					directory.resolve(filename),
					expected.artifacts().get(filename),
					maximumArtifactBytes(filename),
					"HOLDOUT_REPORT_BUNDLE_FILE_CHANGED");
		}
		verifyLayout(directory);
	}

	private static void verifyLayout(Path directory) throws IOException {
		List<Path> entries;
		try (var paths = Files.list(directory)) {
			entries = paths.toList();
		}
		catch (IOException | RuntimeException exception) {
			throw failure("HOLDOUT_REPORT_BUNDLE_DIRECTORY_UNREADABLE");
		}
		Set<String> names = new LinkedHashSet<>();
		for (Path entry : entries) {
			names.add(entry.getFileName().toString());
		}
		if (entries.size() != EXPECTED_FILENAMES.size()
				|| !names.equals(EXPECTED_FILENAMES)) {
			throw failure("HOLDOUT_REPORT_BUNDLE_LAYOUT_INVALID");
		}
		for (String filename : EXPECTED_FILENAMES) {
			Path file = directory.resolve(filename);
			if (Files.isSymbolicLink(file)
					|| !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
				throw failure("HOLDOUT_REPORT_BUNDLE_FILE_INVALID");
			}
			requirePrivatePermissions(file, false);
			requireSingleLink(file);
		}
	}

	private static byte[] readExact(
			Path path, byte[] expected, int maximum, String diagnostic)
			throws IOException {
		BasicFileAttributes before = attributes(path, diagnostic);
		if (!before.isRegularFile()
				|| before.size() != expected.length
				|| before.size() < 1
				|| before.size() > maximum) {
			throw failure(diagnostic);
		}
		byte[] observed;
		try (SeekableByteChannel channel = Files.newByteChannel(
				path, Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS));
				InputStream input = Channels.newInputStream(channel)) {
			observed = input.readNBytes(maximum + 1);
			if (observed.length > maximum || channel.size() != observed.length) {
				throw failure(diagnostic);
			}
		}
		catch (VerificationException exception) {
			throw exception;
		}
		catch (IOException | RuntimeException exception) {
			throw failure(diagnostic);
		}
		BasicFileAttributes after = attributes(path, diagnostic);
		if (!after.isRegularFile()
				|| after.size() != before.size()
				|| !Objects.equals(after.fileKey(), before.fileKey())
				|| !MessageDigest.isEqual(observed, expected)) {
			throw failure(diagnostic);
		}
		return observed;
	}

	private static BasicFileAttributes attributes(Path path, String diagnostic)
			throws IOException {
		try {
			return Files.readAttributes(
					path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
		}
		catch (IOException | RuntimeException exception) {
			throw failure(diagnostic);
		}
	}

	private static Path realDirectory(
			Path supplied, boolean requireAbsolute, String diagnostic)
			throws IOException {
		if (requireAbsolute && !supplied.isAbsolute()) {
			throw failure(diagnostic);
		}
		Path normalized;
		try {
			normalized = supplied.toAbsolutePath().normalize();
		}
		catch (RuntimeException exception) {
			throw failure(diagnostic);
		}
		if (Files.isSymbolicLink(normalized)
				|| !Files.isDirectory(normalized, LinkOption.NOFOLLOW_LINKS)) {
			throw failure(diagnostic);
		}
		try {
			return normalized.toRealPath();
		}
		catch (IOException | RuntimeException exception) {
			throw failure(diagnostic);
		}
	}

	private static void requirePrivatePermissions(Path path, boolean directory)
			throws IOException {
		if (!path.getFileSystem().supportedFileAttributeViews().contains("posix")) {
			throw failure("HOLDOUT_REPORT_BUNDLE_FILESYSTEM_UNSUPPORTED");
		}
		Set<PosixFilePermission> expected = directory
				? DIRECTORY_PERMISSIONS
				: FILE_PERMISSIONS;
		try {
			Set<PosixFilePermission> actual = Files.getPosixFilePermissions(
					path, LinkOption.NOFOLLOW_LINKS);
			if (!actual.equals(expected)) {
				throw failure("HOLDOUT_REPORT_BUNDLE_PERMISSIONS_NOT_PRIVATE");
			}
		}
		catch (VerificationException exception) {
			throw exception;
		}
		catch (IOException | RuntimeException exception) {
			throw failure("HOLDOUT_REPORT_BUNDLE_PERMISSIONS_NOT_PRIVATE");
		}
	}

	private static void requireSingleLink(Path path) throws IOException {
		if (!path.getFileSystem().supportedFileAttributeViews().contains("unix")) {
			throw failure("HOLDOUT_REPORT_BUNDLE_FILESYSTEM_UNSUPPORTED");
		}
		try {
			Object observed = Files.getAttribute(
					path, "unix:nlink", LinkOption.NOFOLLOW_LINKS);
			if (!(observed instanceof Number linkCount)
					|| linkCount.longValue() != 1L) {
				throw failure("HOLDOUT_REPORT_BUNDLE_FILE_LINK_COUNT_INVALID");
			}
		}
		catch (VerificationException exception) {
			throw exception;
		}
		catch (IOException | RuntimeException exception) {
			throw failure("HOLDOUT_REPORT_BUNDLE_FILE_LINK_COUNT_INVALID");
		}
	}

	private static void parseStrict(byte[] manifest) throws IOException {
		try {
			STRICT_MAPPER.reader()
					.with(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
					.with(
							DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY,
							DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
					.readTree(manifest);
		}
		catch (RuntimeException exception) {
			throw failure("HOLDOUT_REPORT_BUNDLE_MANIFEST_JSON_INVALID");
		}
	}

	private static byte[] canonicalManifest(
			String reportId, long payloadBytes, List<FileCommitment> files) {
		List<Map<String, Object>> fileDocuments = files.stream().map(file -> {
			Map<String, Object> value = new TreeMap<>();
			value.put("bytes", file.bytes());
			value.put("filename", file.filename());
			value.put("sha256", file.sha256());
			return Collections.unmodifiableMap(value);
		}).toList();
		Map<String, Object> authorization = new TreeMap<>();
		authorization.put("custodyReleaseAuthorized", false);
		authorization.put("externalBundleAcceptanceAuthorized", false);
		authorization.put("productActivationAuthorized", false);
		authorization.put("readerFacing", false);
		Map<String, Object> manifest = new TreeMap<>();
		manifest.put("authorization", authorization);
		manifest.put("files", fileDocuments);
		manifest.put("payloadBytes", payloadBytes);
		manifest.put("protocolId", PROTOCOL_ID);
		manifest.put("reportId", reportId);
		manifest.put("schemaVersion", SCHEMA_VERSION);
		byte[] json = CANONICAL_WRITER.writeValueAsBytes(manifest);
		if (json.length > 0 && json[json.length - 1] == '\n') {
			return json;
		}
		byte[] terminated = Arrays.copyOf(json, json.length + 1);
		terminated[terminated.length - 1] = '\n';
		return terminated;
	}

	private static int maximumArtifactBytes(String filename) throws IOException {
		return switch (filename) {
			case RelatedTopicReuseHoldoutEvidenceReport.EVALUATOR_SOURCE_FILENAME ->
					RelatedTopicReuseHoldoutEvidenceReport.MAXIMUM_SOURCE_ARTIFACT_BYTES;
			case RelatedTopicReuseHoldoutEvidenceReport.RANKING_SNAPSHOT_FILENAME ->
					RelatedTopicReuseHoldoutEvidenceReport.MAXIMUM_SNAPSHOT_ARTIFACT_BYTES;
			case RelatedTopicReuseHoldoutEvidenceReport.SCORING_RESULT_FILENAME ->
					RelatedTopicReuseHoldoutEvidenceReport.MAXIMUM_RESULT_ARTIFACT_BYTES;
			case RelatedTopicReuseHoldoutEvidenceReport.EVIDENCE_REPORT_FILENAME ->
					RelatedTopicReuseHoldoutEvidenceReport.MAXIMUM_REPORT_ARTIFACT_BYTES;
			default -> throw failure("HOLDOUT_REPORT_BUNDLE_FILENAME_INVALID");
		};
	}

	private static String sha256(byte[] bytes) {
		try {
			return HexFormat.of().formatHex(
					MessageDigest.getInstance("SHA-256").digest(bytes));
		}
		catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException("SHA-256 is unavailable", exception);
		}
	}

	private static VerificationException failure(String diagnostic) {
		return new VerificationException(diagnostic);
	}

	record FileCommitment(String filename, long bytes, String sha256) {

		FileCommitment {
			if (filename == null
					|| (!MANIFEST_FILENAME.equals(filename)
							&& !ARTIFACT_FILENAMES.contains(filename))
					|| bytes < 1
					|| sha256 == null
					|| !SHA256.matcher(sha256).matches()) {
				throw new IllegalArgumentException("invalid retained file commitment");
			}
		}
	}

	static final class ExpectedBundle {

		private final String reportId;
		private final long payloadBytes;
		private final long totalBytes;
		private final byte[] manifestBytes;
		private final Map<String, byte[]> artifacts;
		private final List<FileCommitment> files;

		private ExpectedBundle(
				String reportId,
				long payloadBytes,
				long totalBytes,
				byte[] manifestBytes,
				Map<String, byte[]> artifacts,
				List<FileCommitment> files) {
			this.reportId = reportId;
			this.payloadBytes = payloadBytes;
			this.totalBytes = totalBytes;
			this.manifestBytes = manifestBytes.clone();
			Map<String, byte[]> frozen = new LinkedHashMap<>();
			artifacts.forEach((filename, bytes) -> frozen.put(filename, bytes.clone()));
			this.artifacts = Collections.unmodifiableMap(frozen);
			this.files = List.copyOf(files);
		}

		String reportId() {
			return reportId;
		}

		long payloadBytes() {
			return payloadBytes;
		}

		long totalBytes() {
			return totalBytes;
		}

		byte[] manifestBytes() {
			return manifestBytes.clone();
		}

		Map<String, byte[]> artifacts() {
			Map<String, byte[]> copy = new LinkedHashMap<>();
			artifacts.forEach((filename, bytes) -> copy.put(filename, bytes.clone()));
			return Collections.unmodifiableMap(copy);
		}

		List<FileCommitment> files() {
			return files;
		}
	}

	record VerifiedRetainedReport(
			Path directory,
			String reportId,
			String manifestSha256,
			long payloadBytes,
			long totalBytes,
			List<FileCommitment> files) {

		VerifiedRetainedReport {
			directory = Objects.requireNonNull(directory, "directory")
					.toAbsolutePath().normalize();
			if (reportId == null
					|| !REPORT_ID.matcher(reportId).matches()
					|| manifestSha256 == null
					|| !SHA256.matcher(manifestSha256).matches()
					|| payloadBytes < 1
					|| totalBytes <= payloadBytes) {
				throw new IllegalArgumentException("invalid retained report identity");
			}
			files = List.copyOf(Objects.requireNonNull(files, "files"));
			if (files.size() != EXPECTED_FILENAMES.size()
					|| !files.getFirst().filename().equals(MANIFEST_FILENAME)) {
				throw new IllegalArgumentException("invalid retained report inventory");
			}
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

	static final class VerificationException extends IOException {

		private VerificationException(String diagnostic) {
			super(diagnostic);
		}
	}
}
