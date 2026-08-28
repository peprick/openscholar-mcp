package com.openscholar.search.internal.persistence;

import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.Channels;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

import com.openscholar.search.internal.persistence.ProviderQualityComparativeLongitudinalComparison.Comparison;
import com.openscholar.search.internal.persistence.ProviderQualityComparativeLongitudinalComparison.RunSnapshot;
import com.openscholar.search.internal.persistence.ProviderQualityComparativeLongitudinalComparison.Transition;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Private publication and exact replay boundary for one deterministic longitudinal report.
 * The expected comparison is the trusted in-memory result produced only after the runner's
 * full semantic replay; this boundary verifies bytes against that result and does not authenticate
 * an arbitrary caller-created object.
 */
final class ProviderQualityComparativeLongitudinalReportBundle {

	static final long MAXIMUM_REPORT_BYTES = 8L * 1024L * 1024L;
	static final int MAXIMUM_MANIFEST_BYTES = 64 * 1024;

	private static final String REPORT_FILENAME =
			ProviderQualityComparativeLongitudinalComparison.REPORT_FILENAME;
	private static final Set<String> EXPECTED_FILENAMES = Set.of(
			ProviderQualityEvidenceWriter.MANIFEST_FILENAME, REPORT_FILENAME);
	private static final Set<String> MANIFEST_FIELDS = Set.of(
			"schemaVersion", "evidenceId", "payloadBytes", "files");
	private static final Set<String> MANIFEST_FILE_FIELDS = Set.of(
			"filename", "bytes", "sha256");
	private static final byte[] IDENTITY_DOMAIN =
			"openscholar-provider-quality-comparative-longitudinal-v1"
					.getBytes(StandardCharsets.US_ASCII);
	private static final Pattern COMPARISON_ID = Pattern.compile(
			'^' + Pattern.quote(
					ProviderQualityComparativeLongitudinalComparison.COMPARISON_ID_PREFIX)
					+ "[0-9a-f]{64}$");
	private static final Pattern RUN_SEAL_ID = Pattern.compile(
			'^' + Pattern.quote(ProviderQualityComparativeRunSealBundle.RUN_SEAL_ID_PREFIX)
					+ "[0-9a-f]{64}$");
	private static final Pattern REPORT_ID = Pattern.compile(
			'^' + Pattern.quote(ProviderQualityComparativeScorer.REPORT_ID_PREFIX)
					+ "[0-9a-f]{64}$");
	private static final Pattern SHA256 = Pattern.compile("^[0-9a-f]{64}$");
	private static final Set<PosixFilePermission> DIRECTORY_PERMISSIONS =
			PosixFilePermissions.fromString("rwx------");
	private static final Set<PosixFilePermission> FILE_PERMISSIONS =
			PosixFilePermissions.fromString("rw-------");

	private final Path sourceDirectory;
	private final String comparisonId;
	private final String manifestSha256;
	private final long payloadBytes;
	private final long totalBytes;

	private ProviderQualityComparativeLongitudinalReportBundle(
			Path sourceDirectory,
			String comparisonId,
			String manifestSha256,
			long payloadBytes,
			long totalBytes) {
		this.sourceDirectory = sourceDirectory;
		this.comparisonId = comparisonId;
		this.manifestSha256 = manifestSha256;
		this.payloadBytes = payloadBytes;
		this.totalBytes = totalBytes;
	}

	static ProviderQualityComparativeLongitudinalReportBundle publishAndVerify(
			ObjectMapper objectMapper, Path repositoryRoot, Comparison expected)
			throws IOException {
		validateExpected(expected);
		ProviderQualityEvidenceWriter.WriteResult written =
				ProviderQualityEvidenceWriter.forRepository(
						Objects.requireNonNull(objectMapper, "objectMapper"),
						Objects.requireNonNull(repositoryRoot, "repositoryRoot"),
						MAXIMUM_REPORT_BYTES)
					.write(
						expected.comparisonId(),
						ProviderQualityComparativeLongitudinalComparison.artifacts(expected));
		return verifyExact(objectMapper, written.directory(), expected);
	}

	static ProviderQualityComparativeLongitudinalReportBundle verifyExact(
			ObjectMapper objectMapper, Path sourceDirectory, Comparison expected)
			throws IOException {
		if (objectMapper == null || sourceDirectory == null || expected == null) {
			throw failure("LONGITUDINAL_REPORT_INPUT_INVALID");
		}
		validateExpected(expected);
		Path directory;
		try {
			directory = sourceDirectory.toAbsolutePath().normalize();
		}
		catch (RuntimeException exception) {
			throw failure("LONGITUDINAL_REPORT_INPUT_INVALID");
		}
		verifyLayout(directory);
		Path directoryName = directory.getFileName();
		if (directoryName == null || !expected.comparisonId().equals(directoryName.toString())) {
			throw failure("LONGITUDINAL_REPORT_ID_INVALID");
		}

		Path manifestPath = directory.resolve(ProviderQualityEvidenceWriter.MANIFEST_FILENAME);
		Path reportPath = directory.resolve(REPORT_FILENAME);
		long manifestSize = fileSize(manifestPath, MAXIMUM_MANIFEST_BYTES,
				"LONGITUDINAL_REPORT_MANIFEST_TOO_LARGE");
		long reportSize = fileSize(reportPath, MAXIMUM_REPORT_BYTES,
				"LONGITUDINAL_REPORT_PAYLOAD_TOO_LARGE");
		long totalBytes = addExact(manifestSize, reportSize);
		if (totalBytes > MAXIMUM_REPORT_BYTES) {
			throw failure("LONGITUDINAL_REPORT_TOO_LARGE");
		}

		byte[] manifestBytes = readStableBounded(
				manifestPath,
				manifestSize,
				MAXIMUM_MANIFEST_BYTES,
				"LONGITUDINAL_REPORT_MANIFEST_TOO_LARGE");
		JsonNode manifest = parseStrict(
				objectMapper, manifestBytes, "LONGITUDINAL_REPORT_MANIFEST_JSON_INVALID");
		ManifestMetadata manifestMetadata = validateManifest(
				manifest, expected.comparisonId());
		byte[] reportBytes = readStableBounded(
				reportPath,
				reportSize,
				MAXIMUM_REPORT_BYTES,
				"LONGITUDINAL_REPORT_PAYLOAD_TOO_LARGE");
		if (manifestMetadata.payloadBytes() != reportBytes.length) {
			throw failure("LONGITUDINAL_REPORT_PAYLOAD_BYTES_MISMATCH");
		}
		if (!manifestMetadata.sha256().equals(sha256(reportBytes))) {
			throw failure("LONGITUDINAL_REPORT_PAYLOAD_DIGEST_MISMATCH");
		}
		JsonNode observedReport = parseStrict(
				objectMapper, reportBytes, "LONGITUDINAL_REPORT_PAYLOAD_JSON_INVALID");

		byte[] expectedReportBytes = canonicalBytes(objectMapper, expected);
		JsonNode expectedReport = parseStrict(
				objectMapper,
				expectedReportBytes,
				"LONGITUDINAL_REPORT_EXPECTED_PAYLOAD_INVALID");
		if (!MessageDigest.isEqual(reportBytes, expectedReportBytes)) {
			throw failure(observedReport.equals(expectedReport)
					? "LONGITUDINAL_REPORT_PAYLOAD_NOT_CANONICAL"
					: "LONGITUDINAL_REPORT_PAYLOAD_NOT_EXPECTED");
		}

		byte[] expectedManifestBytes = canonicalManifestBytes(
				objectMapper, expected.comparisonId(), expectedReportBytes);
		if (!MessageDigest.isEqual(manifestBytes, expectedManifestBytes)) {
			throw failure("LONGITUDINAL_REPORT_MANIFEST_NOT_CANONICAL");
		}
		verifyUnchanged(directory, manifestBytes, reportBytes);
		return new ProviderQualityComparativeLongitudinalReportBundle(
				directory,
				expected.comparisonId(),
				sha256(manifestBytes),
				reportBytes.length,
				totalBytes);
	}

	Path sourceDirectory() {
		return sourceDirectory;
	}

	String comparisonId() {
		return comparisonId;
	}

	String manifestSha256() {
		return manifestSha256;
	}

	long payloadBytes() {
		return payloadBytes;
	}

	long totalBytes() {
		return totalBytes;
	}

	static String derivedComparisonId(List<RunSnapshot> runs) {
		Objects.requireNonNull(runs, "runs");
		MessageDigest digest = sha256Digest();
		digest.update(IDENTITY_DOMAIN);
		for (RunSnapshot snapshot : runs) {
			Objects.requireNonNull(snapshot, "run");
			String sealId = snapshot.run().runSealId();
			String sealSha256 = snapshot.run().runSealSha256();
			if (!RUN_SEAL_ID.matcher(sealId).matches() || !SHA256.matcher(sealSha256).matches()) {
				throw new IllegalArgumentException("LONGITUDINAL_REPORT_RUN_REFERENCE_INVALID");
			}
			for (String value : List.of(sealId, sealSha256)) {
				digest.update((byte) 0);
				digest.update(value.getBytes(StandardCharsets.US_ASCII));
			}
		}
		return ProviderQualityComparativeLongitudinalComparison.COMPARISON_ID_PREFIX
				+ HexFormat.of().formatHex(digest.digest());
	}

	private static void validateExpected(Comparison expected) throws IOException {
		if (expected == null
				|| expected.schemaVersion()
						!= ProviderQualityComparativeLongitudinalComparison.SCHEMA_VERSION
				|| !ProviderQualityComparativeLongitudinalComparison.PROTOCOL_ID.equals(
						expected.protocolId())
				|| !COMPARISON_ID.matcher(expected.comparisonId()).matches()
				|| expected.runCount() < ProviderQualityComparativeLongitudinalComparison.MINIMUM_RUNS
				|| expected.runCount() > ProviderQualityComparativeLongitudinalComparison.MAXIMUM_RUNS
				|| expected.runs().size() != expected.runCount()
				|| expected.transitions().size() != expected.runCount() - 1
				|| expected.readerFacing()
				|| expected.defaultEnablementDecision()) {
			throw failure("LONGITUDINAL_REPORT_EXPECTED_INVALID");
		}
		String derivedIdentity;
		try {
			derivedIdentity = derivedComparisonId(expected.runs());
		}
		catch (RuntimeException exception) {
			throw failure("LONGITUDINAL_REPORT_EXPECTED_INVALID");
		}
		if (!expected.comparisonId().equals(derivedIdentity)) {
			throw failure("LONGITUDINAL_REPORT_EXPECTED_INVALID");
		}
		Set<String> sealIds = new LinkedHashSet<>();
		Set<String> evidenceIds = new LinkedHashSet<>();
		Set<String> reportIds = new LinkedHashSet<>();
		Instant previous = null;
		for (int index = 0; index < expected.runs().size(); index++) {
			RunSnapshot snapshot = expected.runs().get(index);
			if (snapshot.ordinal() != index + 1
					|| !RUN_SEAL_ID.matcher(snapshot.run().runSealId()).matches()
					|| !SHA256.matcher(snapshot.run().runSealSha256()).matches()
					|| !REPORT_ID.matcher(snapshot.run().reportId()).matches()
					|| !sealIds.add(snapshot.run().runSealId())
					|| !evidenceIds.add(snapshot.run().evidenceId())
					|| !reportIds.add(snapshot.run().reportId())) {
				throw failure("LONGITUDINAL_REPORT_EXPECTED_INVALID");
			}
			Instant captured;
			try {
				captured = Instant.parse(snapshot.run().captureMeasuredAt());
				if (!captured.toString().equals(snapshot.run().captureMeasuredAt())) {
					throw new IllegalArgumentException("noncanonical instant");
				}
			}
			catch (RuntimeException exception) {
				throw failure("LONGITUDINAL_REPORT_EXPECTED_INVALID");
			}
			if (previous != null && !captured.isAfter(previous)) {
				throw failure("LONGITUDINAL_REPORT_EXPECTED_INVALID");
			}
			previous = captured;
		}
		for (int index = 0; index < expected.transitions().size(); index++) {
			Transition transition = expected.transitions().get(index);
			RunSnapshot from = expected.runs().get(index);
			RunSnapshot to = expected.runs().get(index + 1);
			if (transition.fromOrdinal() != index + 1
					|| transition.toOrdinal() != index + 2
					|| !from.run().runSealId().equals(transition.fromRunSealId())
					|| !to.run().runSealId().equals(transition.toRunSealId())
					|| !Duration.between(
							Instant.parse(from.run().captureMeasuredAt()),
							Instant.parse(to.run().captureMeasuredAt()))
							.toString()
							.equals(transition.elapsed())) {
				throw failure("LONGITUDINAL_REPORT_EXPECTED_INVALID");
			}
		}
	}

	private static void verifyLayout(Path directory) throws IOException {
		if (Files.isSymbolicLink(directory)
				|| !Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
			throw failure("LONGITUDINAL_REPORT_DIRECTORY_INVALID");
		}
		requirePrivatePermissions(directory, true);
		List<Path> entries;
		try (var paths = Files.list(directory)) {
			entries = paths.toList();
		}
		catch (IOException | SecurityException exception) {
			throw failure("LONGITUDINAL_REPORT_DIRECTORY_UNREADABLE");
		}
		Set<String> filenames = new LinkedHashSet<>();
		for (Path entry : entries) {
			filenames.add(entry.getFileName().toString());
		}
		if (entries.size() != EXPECTED_FILENAMES.size()
				|| !filenames.equals(EXPECTED_FILENAMES)) {
			throw failure("LONGITUDINAL_REPORT_LAYOUT_INVALID");
		}
		for (String filename : EXPECTED_FILENAMES) {
			Path file = directory.resolve(filename);
			if (Files.isSymbolicLink(file)
					|| !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
				throw failure("LONGITUDINAL_REPORT_FILE_INVALID");
			}
			requirePrivatePermissions(file, false);
		}
	}

	private static long fileSize(Path path, long maximum, String diagnostic)
			throws IOException {
		BasicFileAttributes attributes = readAttributes(path);
		if (!attributes.isRegularFile() || attributes.size() < 1) {
			throw failure("LONGITUDINAL_REPORT_FILE_INVALID");
		}
		if (attributes.size() > maximum) {
			throw failure(diagnostic);
		}
		return attributes.size();
	}

	private static byte[] readStableBounded(
			Path path, long expectedSize, long maximum, String limitDiagnostic)
			throws IOException {
		if (maximum < 0 || maximum >= Integer.MAX_VALUE) {
			throw failure("LONGITUDINAL_REPORT_INTERNAL_LIMIT_INVALID");
		}
		BasicFileAttributes before = readAttributes(path);
		byte[] bytes;
		try (SeekableByteChannel channel = Files.newByteChannel(
				path, Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS));
				InputStream input = Channels.newInputStream(channel)) {
			bytes = input.readNBytes((int) maximum + 1);
			if (bytes.length > maximum) {
				throw failure(limitDiagnostic);
			}
			if (channel.size() != bytes.length) {
				throw failure("LONGITUDINAL_REPORT_FILE_CHANGED");
			}
		}
		catch (VerificationException exception) {
			throw exception;
		}
		catch (IOException | SecurityException exception) {
			throw failure("LONGITUDINAL_REPORT_FILE_UNREADABLE");
		}
		BasicFileAttributes after = readAttributes(path);
		if (expectedSize != bytes.length
				|| before.size() != bytes.length
				|| after.size() != bytes.length
				|| !Objects.equals(before.fileKey(), after.fileKey())) {
			throw failure("LONGITUDINAL_REPORT_FILE_CHANGED");
		}
		return bytes;
	}

	private static ManifestMetadata validateManifest(JsonNode manifest, String comparisonId)
			throws IOException {
		if (!hasExactFields(manifest, MANIFEST_FIELDS)
				|| manifest.get("schemaVersion") == null
				|| !manifest.get("schemaVersion").isInt()
				|| manifest.get("schemaVersion").asInt() != 1
				|| manifest.get("evidenceId") == null
				|| !manifest.get("evidenceId").isString()
				|| !comparisonId.equals(manifest.get("evidenceId").asString())) {
			throw failure("LONGITUDINAL_REPORT_MANIFEST_SCHEMA_INVALID");
		}
		JsonNode payloadBytes = manifest.get("payloadBytes");
		JsonNode files = manifest.get("files");
		if (payloadBytes == null
				|| !payloadBytes.isIntegralNumber()
				|| !payloadBytes.canConvertToLong()
				|| payloadBytes.longValue() < 1
				|| payloadBytes.longValue() > MAXIMUM_REPORT_BYTES
				|| files == null
				|| !files.isArray()
				|| files.size() != 1) {
			throw failure("LONGITUDINAL_REPORT_MANIFEST_SCHEMA_INVALID");
		}
		JsonNode file = files.get(0);
		if (!hasExactFields(file, MANIFEST_FILE_FIELDS)
				|| file.get("filename") == null
				|| !file.get("filename").isString()
				|| !REPORT_FILENAME.equals(file.get("filename").asString())
				|| file.get("bytes") == null
				|| !file.get("bytes").isIntegralNumber()
				|| !file.get("bytes").canConvertToLong()
				|| file.get("bytes").longValue() < 1
				|| file.get("bytes").longValue() != payloadBytes.longValue()
				|| file.get("sha256") == null
				|| !file.get("sha256").isString()
				|| !SHA256.matcher(file.get("sha256").asString()).matches()) {
			throw failure("LONGITUDINAL_REPORT_MANIFEST_FILES_INVALID");
		}
		return new ManifestMetadata(payloadBytes.longValue(), file.get("sha256").asString());
	}

	private static JsonNode parseStrict(
			ObjectMapper objectMapper, byte[] bytes, String diagnostic) throws IOException {
		try {
			JsonNode root = objectMapper.reader()
					.with(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
					.with(
							DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY,
							DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
					.readTree(bytes);
			if (root == null) {
				throw failure(diagnostic);
			}
			return root;
		}
		catch (VerificationException exception) {
			throw exception;
		}
		catch (RuntimeException exception) {
			throw failure(diagnostic);
		}
	}

	private static byte[] canonicalManifestBytes(
			ObjectMapper objectMapper, String comparisonId, byte[] reportBytes)
			throws IOException {
		ProviderQualityEvidenceWriter.FileDigest digest =
				new ProviderQualityEvidenceWriter.FileDigest(
						REPORT_FILENAME, reportBytes.length, sha256(reportBytes));
		return canonicalBytes(
				objectMapper,
				new ProviderQualityEvidenceWriter.EvidenceManifest(
						1, comparisonId, reportBytes.length, List.of(digest)));
	}

	private static byte[] canonicalBytes(ObjectMapper objectMapper, Object value)
			throws IOException {
		return ProviderQualityComparativeReviewPacket.canonicalBytes(objectMapper, value);
	}

	private static void verifyUnchanged(
			Path directory, byte[] expectedManifest, byte[] expectedReport) throws IOException {
		verifyLayout(directory);
		byte[] currentManifest = readStableBounded(
				directory.resolve(ProviderQualityEvidenceWriter.MANIFEST_FILENAME),
				expectedManifest.length,
				expectedManifest.length,
				"LONGITUDINAL_REPORT_FILE_CHANGED");
		byte[] currentReport = readStableBounded(
				directory.resolve(REPORT_FILENAME),
				expectedReport.length,
				expectedReport.length,
				"LONGITUDINAL_REPORT_FILE_CHANGED");
		if (!MessageDigest.isEqual(expectedManifest, currentManifest)
				|| !MessageDigest.isEqual(expectedReport, currentReport)) {
			throw failure("LONGITUDINAL_REPORT_FILE_CHANGED");
		}
		verifyLayout(directory);
	}

	private static void requirePrivatePermissions(Path path, boolean directory)
			throws IOException {
		if (!path.getFileSystem().supportedFileAttributeViews().contains("posix")) {
			return;
		}
		Set<PosixFilePermission> observed;
		try {
			observed = Files.getPosixFilePermissions(path, LinkOption.NOFOLLOW_LINKS);
		}
		catch (IOException | SecurityException exception) {
			throw failure("LONGITUDINAL_REPORT_FILE_UNREADABLE");
		}
		Set<PosixFilePermission> expected = directory
				? DIRECTORY_PERMISSIONS
				: FILE_PERMISSIONS;
		if (!observed.equals(expected)) {
			throw failure("LONGITUDINAL_REPORT_PERMISSIONS_NOT_PRIVATE");
		}
	}

	private static BasicFileAttributes readAttributes(Path path) throws IOException {
		try {
			return Files.readAttributes(
					path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
		}
		catch (IOException | SecurityException exception) {
			throw failure("LONGITUDINAL_REPORT_FILE_UNREADABLE");
		}
	}

	private static boolean hasExactFields(JsonNode node, Set<String> expected) {
		return node != null
				&& node.isObject()
				&& new LinkedHashSet<>(node.propertyNames()).equals(expected);
	}

	private static long addExact(long left, long right) throws IOException {
		try {
			return Math.addExact(left, right);
		}
		catch (ArithmeticException exception) {
			throw failure("LONGITUDINAL_REPORT_BYTE_COUNT_INVALID");
		}
	}

	private static String sha256(byte[] bytes) {
		return HexFormat.of().formatHex(sha256Digest().digest(bytes));
	}

	private static MessageDigest sha256Digest() {
		try {
			return MessageDigest.getInstance("SHA-256");
		}
		catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException("LONGITUDINAL_REPORT_SHA256_UNAVAILABLE", exception);
		}
	}

	private static VerificationException failure(String diagnostic) {
		return new VerificationException(diagnostic);
	}

	private record ManifestMetadata(long payloadBytes, String sha256) {
	}

	static final class VerificationException extends IOException {

		private VerificationException(String diagnostic) {
			super(diagnostic);
		}
	}
}
