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
import java.util.regex.Pattern;

import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.ObjectWriter;
import tools.jackson.databind.SerializationFeature;

/**
 * Read-only verification boundary for a retained comparative score report.
 *
 * <p>The report is accepted only when it is the exact canonical output regenerated from the
 * expected scorer result and the enclosing generic evidence-writer manifest is intact. This
 * prevents a self-consistent but unrelated report from being mistaken for the expected result.</p>
 */
final class ProviderQualityComparativeScoreReportBundle {

	static final long MAXIMUM_REPORT_BYTES = 8L * 1024L * 1024L;
	static final int MAXIMUM_MANIFEST_BYTES = 64 * 1024;

	private static final String MANIFEST_FILENAME = "manifest.json";
	private static final String QUERY_SCORES_FILENAME = "query-scores.json";
	private static final String SCORE_SUMMARY_FILENAME = "score-summary.json";
	private static final Pattern REPORT_ID =
			Pattern.compile('^' + Pattern.quote(
					ProviderQualityComparativeScorer.REPORT_ID_PREFIX) + "[0-9a-f]{64}$");
	private static final Pattern SHA256 = Pattern.compile("^[0-9a-f]{64}$");
	private static final List<String> PAYLOAD_FILENAMES =
			List.of(QUERY_SCORES_FILENAME, SCORE_SUMMARY_FILENAME);
	private static final Set<String> EXPECTED_FILENAMES = Set.of(
			MANIFEST_FILENAME, QUERY_SCORES_FILENAME, SCORE_SUMMARY_FILENAME);
	private static final Set<String> MANIFEST_FIELDS = Set.of(
			"schemaVersion", "evidenceId", "payloadBytes", "files");
	private static final Set<String> MANIFEST_FILE_FIELDS = Set.of(
			"filename", "bytes", "sha256");

	private final Path sourceDirectory;
	private final String reportId;
	private final String manifestSha256;
	private final long payloadBytes;
	private final long totalBytes;

	private ProviderQualityComparativeScoreReportBundle(
			Path sourceDirectory,
			String reportId,
			String manifestSha256,
			long payloadBytes,
			long totalBytes) {
		this.sourceDirectory = sourceDirectory;
		this.reportId = reportId;
		this.manifestSha256 = manifestSha256;
		this.payloadBytes = payloadBytes;
		this.totalBytes = totalBytes;
	}

	static ProviderQualityComparativeScoreReportBundle verifyExact(
			ObjectMapper objectMapper,
			Path sourceDirectory,
			ProviderQualityComparativeScorer.ScoringResult expectedResult)
			throws IOException {
		if (objectMapper == null || sourceDirectory == null || expectedResult == null) {
			throw failure("SCORE_REPORT_INPUT_INVALID");
		}
		String expectedReportId = expectedResult.reportId();
		if (expectedResult.schemaVersion()
					!= ProviderQualityComparativeScorer.REPORT_SCHEMA_VERSION
				|| expectedReportId == null
				|| !REPORT_ID.matcher(expectedReportId).matches()
				|| !isSha256(expectedResult.evidenceManifestSha256())
				|| !isSha256(expectedResult.judgmentPacketSha256())
				|| !isSha256(expectedResult.scoringPolicySha256())) {
			throw failure("SCORE_REPORT_ID_INVALID");
		}
		String derivedReportId = ProviderQualityComparativeScorer.reportId(
				expectedResult.evidenceManifestSha256(),
				expectedResult.judgmentPacketSha256(),
				expectedResult.scoringPolicySha256());
		if (!MessageDigest.isEqual(
					expectedReportId.getBytes(StandardCharsets.US_ASCII),
						derivedReportId.getBytes(StandardCharsets.US_ASCII))) {
			throw failure("SCORE_REPORT_ID_INVALID");
		}

		Path normalizedDirectory;
		try {
			normalizedDirectory = sourceDirectory.toAbsolutePath().normalize();
		}
		catch (RuntimeException exception) {
			throw failure("SCORE_REPORT_INPUT_INVALID");
		}
		verifyLayout(normalizedDirectory);
		Path directoryName = normalizedDirectory.getFileName();
		if (directoryName == null || !expectedReportId.equals(directoryName.toString())) {
			throw failure("SCORE_REPORT_ID_INVALID");
		}

		byte[] manifestBytes = readBounded(
				normalizedDirectory.resolve(MANIFEST_FILENAME),
				MAXIMUM_MANIFEST_BYTES,
				"SCORE_REPORT_MANIFEST_TOO_LARGE");
		JsonNode manifest = parseStrict(
				objectMapper, manifestBytes, "SCORE_REPORT_MANIFEST_JSON_INVALID");
		ManifestMetadata metadata = validateManifest(manifest, expectedReportId);

		Map<String, Long> observedSizes = payloadSizes(normalizedDirectory);
		long observedPayloadBytes = observedSizes.values().stream()
				.mapToLong(Long::longValue)
				.sum();
		long totalBytes;
		try {
			totalBytes = Math.addExact(observedPayloadBytes, (long) manifestBytes.length);
		}
		catch (ArithmeticException exception) {
			throw failure("SCORE_REPORT_TOTAL_BYTES_INVALID");
		}
		if (totalBytes > MAXIMUM_REPORT_BYTES) {
			throw failure("SCORE_REPORT_TOO_LARGE");
		}
		Map<String, byte[]> observedPayloads = readPayloads(
				normalizedDirectory, observedSizes, observedPayloadBytes);
		verifyManifestIntegrity(
				metadata, observedSizes, observedPayloadBytes, observedPayloads);
		for (String filename : PAYLOAD_FILENAMES) {
			parseStrict(
					objectMapper,
					observedPayloads.get(filename),
					"SCORE_REPORT_PAYLOAD_JSON_INVALID");
		}

		ExpectedReport expected = expectedReport(objectMapper, expectedResult);
		if (expected.totalBytes() > MAXIMUM_REPORT_BYTES) {
			throw failure("SCORE_REPORT_EXPECTED_PAYLOAD_TOO_LARGE");
		}
		Map<String, byte[]> expectedPayloads = expected.payloads();
		for (String filename : PAYLOAD_FILENAMES) {
			if (!MessageDigest.isEqual(
					observedPayloads.get(filename), expectedPayloads.get(filename))) {
				throw failure("SCORE_REPORT_PAYLOAD_NOT_EXPECTED");
			}
		}
		if (!MessageDigest.isEqual(manifestBytes, expected.manifestBytes())) {
			throw failure("SCORE_REPORT_MANIFEST_NOT_CANONICAL");
		}

		verifyUnchanged(normalizedDirectory, manifestBytes, observedPayloads);
		return new ProviderQualityComparativeScoreReportBundle(
				normalizedDirectory,
				expectedReportId,
				sha256(manifestBytes),
				observedPayloadBytes,
				totalBytes);
	}

	Path sourceDirectory() {
		return sourceDirectory;
	}

	String reportId() {
		return reportId;
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

	private static void verifyLayout(Path directory) throws IOException {
		if (Files.isSymbolicLink(directory)
				|| !Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
			throw failure("SCORE_REPORT_DIRECTORY_INVALID");
		}
		List<Path> entries;
		try (var paths = Files.list(directory)) {
			entries = paths.toList();
		}
		catch (IOException | SecurityException exception) {
			throw failure("SCORE_REPORT_DIRECTORY_UNREADABLE");
		}
		Set<String> filenames = new LinkedHashSet<>();
		for (Path entry : entries) {
			filenames.add(entry.getFileName().toString());
		}
		if (entries.size() != EXPECTED_FILENAMES.size()
				|| !filenames.equals(EXPECTED_FILENAMES)) {
			throw failure("SCORE_REPORT_LAYOUT_INVALID");
		}
		for (String filename : EXPECTED_FILENAMES) {
			Path file = directory.resolve(filename);
			if (Files.isSymbolicLink(file)
					|| !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
				throw failure("SCORE_REPORT_FILE_INVALID");
			}
		}
	}

	private static ManifestMetadata validateManifest(
			JsonNode manifest, String expectedReportId) throws IOException {
		if (!hasExactFields(manifest, MANIFEST_FIELDS)
				|| manifest.get("schemaVersion") == null
				|| !manifest.get("schemaVersion").isInt()
				|| manifest.get("schemaVersion").asInt() != 1) {
			throw failure("SCORE_REPORT_MANIFEST_SCHEMA_INVALID");
		}
		JsonNode evidenceId = manifest.get("evidenceId");
		if (evidenceId == null
				|| !evidenceId.isString()
				|| !expectedReportId.equals(evidenceId.asString())) {
			throw failure("SCORE_REPORT_ID_INVALID");
		}
		Long payloadBytes = nonNegativeLong(manifest.get("payloadBytes"));
		if (payloadBytes == null) {
			throw failure("SCORE_REPORT_MANIFEST_SCHEMA_INVALID");
		}
		if (payloadBytes > MAXIMUM_REPORT_BYTES) {
			throw failure("SCORE_REPORT_PAYLOAD_TOO_LARGE");
		}

		JsonNode files = manifest.get("files");
		if (files == null || !files.isArray() || files.size() != PAYLOAD_FILENAMES.size()) {
			throw failure("SCORE_REPORT_MANIFEST_FILES_INVALID");
		}
		List<ManifestFile> manifestFiles = new ArrayList<>();
		long declaredPayloadBytes = 0;
		for (int index = 0; index < PAYLOAD_FILENAMES.size(); index++) {
			JsonNode file = files.get(index);
			if (!hasExactFields(file, MANIFEST_FILE_FIELDS)) {
				throw failure("SCORE_REPORT_MANIFEST_FILES_INVALID");
			}
			JsonNode filename = file.get("filename");
			Long bytes = nonNegativeLong(file.get("bytes"));
			JsonNode digest = file.get("sha256");
			if (filename == null
					|| !filename.isString()
					|| !PAYLOAD_FILENAMES.get(index).equals(filename.asString())
					|| bytes == null
					|| bytes < 1
					|| bytes > MAXIMUM_REPORT_BYTES - declaredPayloadBytes
					|| digest == null
					|| !digest.isString()
					|| !SHA256.matcher(digest.asString()).matches()) {
				throw failure("SCORE_REPORT_MANIFEST_FILES_INVALID");
			}
			declaredPayloadBytes += bytes;
			manifestFiles.add(new ManifestFile(filename.asString(), bytes, digest.asString()));
		}
		if (payloadBytes != declaredPayloadBytes) {
			throw failure("SCORE_REPORT_MANIFEST_PAYLOAD_BYTES_INVALID");
		}
		return new ManifestMetadata(payloadBytes, Collections.unmodifiableList(manifestFiles));
	}

	private static Map<String, Long> payloadSizes(Path directory) throws IOException {
		Map<String, Long> sizes = new LinkedHashMap<>();
		long aggregate = 0;
		for (String filename : PAYLOAD_FILENAMES) {
			BasicFileAttributes attributes;
			try {
				attributes = Files.readAttributes(
						directory.resolve(filename),
						BasicFileAttributes.class,
						LinkOption.NOFOLLOW_LINKS);
			}
			catch (IOException | SecurityException exception) {
				throw failure("SCORE_REPORT_FILE_UNREADABLE");
			}
			long size = attributes.size();
			if (!attributes.isRegularFile() || size < 1) {
				throw failure("SCORE_REPORT_FILE_INVALID");
			}
			if (size > MAXIMUM_REPORT_BYTES - aggregate) {
				throw failure("SCORE_REPORT_PAYLOAD_TOO_LARGE");
			}
			aggregate += size;
			sizes.put(filename, size);
		}
		return Collections.unmodifiableMap(sizes);
	}

	private static Map<String, byte[]> readPayloads(
			Path directory, Map<String, Long> sizes, long expectedAggregate) throws IOException {
		Map<String, byte[]> payloads = new LinkedHashMap<>();
		long aggregate = 0;
		for (String filename : PAYLOAD_FILENAMES) {
			byte[] bytes = readBounded(
					directory.resolve(filename),
					MAXIMUM_REPORT_BYTES - aggregate,
					"SCORE_REPORT_PAYLOAD_TOO_LARGE");
			if (bytes.length != sizes.get(filename)) {
				throw failure("SCORE_REPORT_FILE_CHANGED");
			}
			aggregate += bytes.length;
			payloads.put(filename, bytes);
		}
		if (aggregate != expectedAggregate) {
			throw failure("SCORE_REPORT_FILE_CHANGED");
		}
		return Collections.unmodifiableMap(payloads);
	}

	private static void verifyManifestIntegrity(
			ManifestMetadata manifest,
			Map<String, Long> observedSizes,
			long observedPayloadBytes,
			Map<String, byte[]> observedPayloads)
			throws IOException {
		if (manifest.payloadBytes() != observedPayloadBytes) {
			throw failure("SCORE_REPORT_PAYLOAD_BYTES_MISMATCH");
		}
		for (ManifestFile file : manifest.files()) {
			if (!Objects.equals(observedSizes.get(file.filename()), file.bytes())
					|| !file.sha256().equals(sha256(observedPayloads.get(file.filename())))) {
				throw failure("SCORE_REPORT_PAYLOAD_DIGEST_MISMATCH");
			}
		}
	}

	private static void verifyUnchanged(
			Path directory, byte[] manifestBytes, Map<String, byte[]> payloads)
			throws IOException {
		verifyLayout(directory);
		byte[] currentManifest = readBounded(
				directory.resolve(MANIFEST_FILENAME),
				manifestBytes.length,
				"SCORE_REPORT_FILE_CHANGED");
		if (!MessageDigest.isEqual(manifestBytes, currentManifest)) {
			throw failure("SCORE_REPORT_FILE_CHANGED");
		}
		for (String filename : PAYLOAD_FILENAMES) {
			byte[] expected = payloads.get(filename);
			byte[] current = readBounded(
					directory.resolve(filename),
					expected.length,
					"SCORE_REPORT_FILE_CHANGED");
			if (!MessageDigest.isEqual(expected, current)) {
				throw failure("SCORE_REPORT_FILE_CHANGED");
			}
		}
		verifyLayout(directory);
	}

	private static ExpectedReport expectedReport(
			ObjectMapper objectMapper,
			ProviderQualityComparativeScorer.ScoringResult expectedResult)
			throws IOException {
		Map<String, Object> scorerArtifacts =
				ProviderQualityComparativeScorer.artifacts(expectedResult);
		if (!scorerArtifacts.keySet().equals(new LinkedHashSet<>(PAYLOAD_FILENAMES))) {
			throw failure("SCORE_REPORT_EXPECTED_ARTIFACTS_INVALID");
		}
		ObjectWriter canonicalWriter = objectMapper.writer()
				.with(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
		Map<String, byte[]> payloads = new LinkedHashMap<>();
		List<ProviderQualityEvidenceWriter.FileDigest> digests = new ArrayList<>();
		long payloadBytes = 0;
		for (String filename : PAYLOAD_FILENAMES) {
			byte[] bytes = canonicalBytes(canonicalWriter, scorerArtifacts.get(filename));
			if (bytes.length > MAXIMUM_REPORT_BYTES - payloadBytes) {
				throw failure("SCORE_REPORT_EXPECTED_PAYLOAD_TOO_LARGE");
			}
			payloadBytes += bytes.length;
			payloads.put(filename, bytes);
			digests.add(new ProviderQualityEvidenceWriter.FileDigest(
					filename, bytes.length, sha256(bytes)));
		}
		ProviderQualityEvidenceWriter.EvidenceManifest manifest =
				new ProviderQualityEvidenceWriter.EvidenceManifest(
						1, expectedResult.reportId(), payloadBytes, digests);
		byte[] manifestBytes = canonicalBytes(canonicalWriter, manifest);
		long totalBytes;
		try {
			totalBytes = Math.addExact(payloadBytes, (long) manifestBytes.length);
		}
		catch (ArithmeticException exception) {
			throw failure("SCORE_REPORT_EXPECTED_PAYLOAD_TOO_LARGE");
		}
		return new ExpectedReport(
				Collections.unmodifiableMap(payloads), manifestBytes, totalBytes);
	}

	private static byte[] canonicalBytes(ObjectWriter canonicalWriter, Object value)
			throws IOException {
		byte[] json = canonicalWriter.writeValueAsBytes(value);
		if (json.length > 0 && json[json.length - 1] == '\n') {
			return json;
		}
		byte[] terminated = Arrays.copyOf(json, json.length + 1);
		terminated[terminated.length - 1] = '\n';
		return terminated;
	}

	private static JsonNode parseStrict(
			ObjectMapper objectMapper, byte[] bytes, String diagnostic) throws IOException {
		try {
			return objectMapper.reader()
					.with(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
					.with(
							DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY,
							DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
					.readTree(bytes);
		}
		catch (RuntimeException exception) {
			throw failure(diagnostic);
		}
	}

	private static byte[] readBounded(Path path, long maximum, String limitDiagnostic)
			throws IOException {
		if (maximum < 0 || maximum >= Integer.MAX_VALUE) {
			throw failure("SCORE_REPORT_INTERNAL_LIMIT_INVALID");
		}
		try (SeekableByteChannel channel = Files.newByteChannel(
				path, Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS));
				InputStream input = Channels.newInputStream(channel)) {
			byte[] bytes = input.readNBytes((int) maximum + 1);
			if (bytes.length > maximum) {
				throw failure(limitDiagnostic);
			}
			return bytes;
		}
		catch (VerificationException exception) {
			throw exception;
		}
		catch (IOException | SecurityException exception) {
			throw failure("SCORE_REPORT_FILE_UNREADABLE");
		}
	}

	private static boolean hasExactFields(JsonNode node, Set<String> fields) {
		return node != null
				&& node.isObject()
				&& new LinkedHashSet<>(node.propertyNames()).equals(fields);
	}

	private static Long nonNegativeLong(JsonNode node) {
		if (node == null
				|| !node.isIntegralNumber()
				|| !node.canConvertToLong()
				|| node.longValue() < 0) {
			return null;
		}
		return node.longValue();
	}

	private static boolean isSha256(String value) {
		return value != null && SHA256.matcher(value).matches();
	}

	private static String sha256(byte[] bytes) {
		try {
			return HexFormat.of().formatHex(
					MessageDigest.getInstance("SHA-256").digest(bytes));
		}
		catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException("SCORE_REPORT_SHA256_UNAVAILABLE", exception);
		}
	}

	private static VerificationException failure(String diagnostic) {
		return new VerificationException(diagnostic);
	}

	private record ManifestMetadata(long payloadBytes, List<ManifestFile> files) {

		private ManifestMetadata {
			files = List.copyOf(files);
		}
	}

	private record ManifestFile(String filename, long bytes, String sha256) {
	}

	private record ExpectedReport(
			Map<String, byte[]> payloads,
			byte[] manifestBytes,
			long totalBytes) {

		private ExpectedReport {
			Map<String, byte[]> copies = new LinkedHashMap<>();
			payloads.forEach((filename, bytes) -> copies.put(filename, bytes.clone()));
			payloads = Collections.unmodifiableMap(copies);
			manifestBytes = manifestBytes.clone();
		}

		@Override
		public Map<String, byte[]> payloads() {
			Map<String, byte[]> copies = new LinkedHashMap<>();
			payloads.forEach((filename, bytes) -> copies.put(filename, bytes.clone()));
			return Collections.unmodifiableMap(copies);
		}

		@Override
		public byte[] manifestBytes() {
			return manifestBytes.clone();
		}
	}

	static final class VerificationException extends IOException {

		private VerificationException(String diagnostic) {
			super(diagnostic);
		}
	}
}
