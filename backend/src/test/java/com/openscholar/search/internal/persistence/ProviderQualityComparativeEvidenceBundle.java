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
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Read-only verification boundary for a comparative provider-quality evidence bundle.
 *
 * <p>The verifier deliberately returns detached JSON trees. This keeps later scoring code
 * independent from the live capture process while preventing callers from mutating the verified
 * representation retained by this object.</p>
 */
final class ProviderQualityComparativeEvidenceBundle {

	static final long MAXIMUM_PAYLOAD_BYTES = 64L * 1024L * 1024L;
	static final int MAXIMUM_MANIFEST_BYTES = 64 * 1024;
	static final String ELIGIBLE_REVIEW_INSTRUCTIONS =
			"Assign one integer relevanceGrade from 0 through 3 without consulting "
					+ "provenance-map.json or reconciliation-trace.json.";

	private static final String MANIFEST_FILENAME = "manifest.json";
	private static final String SUMMARY_FILENAME = "summary.json";
	private static final String BLINDED_FILENAME = "blinded-candidates.json";
	private static final String PROVENANCE_FILENAME = "provenance-map.json";
	private static final String RECONCILIATION_FILENAME = "reconciliation-trace.json";
	private static final String EXPECTED_EVIDENCE_TYPE =
			"LIVE_COMPARATIVE_METADATA_CAPTURE";
	private static final Pattern EVIDENCE_ID =
			Pattern.compile("^[a-z0-9][a-z0-9-]{2,127}$");
	private static final Pattern SHA256 = Pattern.compile("^[0-9a-f]{64}$");
	private static final byte[] BLINDED_ORDER_DOMAIN =
			"openscholar-provider-quality-blinded-order-v1"
					.getBytes(StandardCharsets.UTF_8);
	private static final List<String> PAYLOAD_FILENAMES = List.of(
			BLINDED_FILENAME,
			PROVENANCE_FILENAME,
			RECONCILIATION_FILENAME,
			SUMMARY_FILENAME);
	private static final Set<String> EXPECTED_FILENAMES;
	private static final Set<String> MANIFEST_FIELDS = Set.of(
			"schemaVersion", "evidenceId", "payloadBytes", "files");
	private static final Set<String> MANIFEST_FILE_FIELDS = Set.of(
			"filename", "bytes", "sha256");

	static {
		LinkedHashSet<String> filenames = new LinkedHashSet<>(PAYLOAD_FILENAMES);
		filenames.add(MANIFEST_FILENAME);
		EXPECTED_FILENAMES = Collections.unmodifiableSet(filenames);
	}

	private final Path sourceDirectory;
	private final String evidenceId;
	private final String manifestSha256;
	private final boolean reviewReady;
	private final JsonNode manifest;
	private final JsonNode summary;
	private final JsonNode blindedCandidates;
	private final JsonNode provenanceMap;
	private final JsonNode reconciliationTrace;

	private ProviderQualityComparativeEvidenceBundle(
			Path sourceDirectory,
			String evidenceId,
			String manifestSha256,
			boolean reviewReady,
			JsonNode manifest,
			JsonNode summary,
			JsonNode blindedCandidates,
			JsonNode provenanceMap,
			JsonNode reconciliationTrace) {
		this.sourceDirectory = sourceDirectory;
		this.evidenceId = evidenceId;
		this.manifestSha256 = manifestSha256;
		this.reviewReady = reviewReady;
		this.manifest = manifest.deepCopy();
		this.summary = summary.deepCopy();
		this.blindedCandidates = blindedCandidates.deepCopy();
		this.provenanceMap = provenanceMap.deepCopy();
		this.reconciliationTrace = reconciliationTrace.deepCopy();
	}

	static ProviderQualityComparativeEvidenceBundle verify(
			ObjectMapper objectMapper, Path sourceDirectory) throws IOException {
		if (objectMapper == null || sourceDirectory == null) {
			throw new IllegalArgumentException("EVIDENCE_INPUT_INVALID");
		}

		Path normalizedDirectory;
		try {
			normalizedDirectory = sourceDirectory.toAbsolutePath().normalize();
		}
		catch (RuntimeException exception) {
			throw failure("EVIDENCE_INPUT_INVALID");
		}
		verifyLayout(normalizedDirectory);

		byte[] manifestBytes = readBounded(
				normalizedDirectory.resolve(MANIFEST_FILENAME),
				MAXIMUM_MANIFEST_BYTES,
				"EVIDENCE_MANIFEST_TOO_LARGE");
		JsonNode manifest = parseStrict(
				objectMapper, manifestBytes, "EVIDENCE_MANIFEST_JSON_INVALID");
		ManifestMetadata manifestMetadata = validateManifest(manifest);
		Path directoryName = normalizedDirectory.getFileName();
		if (directoryName == null
				|| !manifestMetadata.evidenceId().equals(directoryName.toString())) {
			throw failure("EVIDENCE_ID_INVALID");
		}

		Map<String, Long> observedSizes = payloadSizes(normalizedDirectory);
		long observedPayloadBytes = observedSizes.values().stream()
				.mapToLong(Long::longValue)
				.sum();
		Map<String, byte[]> payloadBytes = readPayloads(
				normalizedDirectory, observedSizes, observedPayloadBytes);
		verifyIntegrity(manifestMetadata, observedSizes, observedPayloadBytes, payloadBytes);

		Map<String, JsonNode> documents = new LinkedHashMap<>();
		for (String filename : PAYLOAD_FILENAMES) {
			documents.put(filename, parseStrict(
					objectMapper,
					payloadBytes.get(filename),
					"EVIDENCE_PAYLOAD_JSON_INVALID"));
		}
		boolean reviewReady = validateCrossDocumentSemantics(
				manifestMetadata.evidenceId(), documents);

		return new ProviderQualityComparativeEvidenceBundle(
				normalizedDirectory,
				manifestMetadata.evidenceId(),
				sha256(manifestBytes),
				reviewReady,
				manifest,
				documents.get(SUMMARY_FILENAME),
				documents.get(BLINDED_FILENAME),
				documents.get(PROVENANCE_FILENAME),
				documents.get(RECONCILIATION_FILENAME));
	}

	Path sourceDirectory() {
		return sourceDirectory;
	}

	String evidenceId() {
		return evidenceId;
	}

	String manifestSha256() {
		return manifestSha256;
	}

	boolean reviewReady() {
		return reviewReady;
	}

	JsonNode manifest() {
		return manifest.deepCopy();
	}

	JsonNode summary() {
		return summary.deepCopy();
	}

	JsonNode blindedCandidates() {
		return blindedCandidates.deepCopy();
	}

	JsonNode provenanceMap() {
		return provenanceMap.deepCopy();
	}

	JsonNode reconciliationTrace() {
		return reconciliationTrace.deepCopy();
	}

	static String blindedOrderingKey(String evidenceId, String reviewKey) {
		if (evidenceId == null || !EVIDENCE_ID.matcher(evidenceId).matches()) {
			throw new IllegalArgumentException("evidenceId is not a bounded safe identifier");
		}
		if (reviewKey == null || !SHA256.matcher(reviewKey).matches()) {
			throw new IllegalArgumentException("reviewKey is not a SHA-256 identifier");
		}
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			digest.update(BLINDED_ORDER_DOMAIN);
			digest.update((byte) 0);
			digest.update(evidenceId.getBytes(StandardCharsets.UTF_8));
			digest.update((byte) 0);
			digest.update(reviewKey.getBytes(StandardCharsets.UTF_8));
			return HexFormat.of().formatHex(digest.digest());
		}
		catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException("SHA-256 is unavailable", exception);
		}
	}

	private static void verifyLayout(Path directory) throws IOException {
		if (Files.isSymbolicLink(directory)
				|| !Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
			throw failure("EVIDENCE_DIRECTORY_INVALID");
		}

		List<Path> entries;
		try (var paths = Files.list(directory)) {
			entries = paths.toList();
		}
		catch (IOException | SecurityException exception) {
			throw failure("EVIDENCE_DIRECTORY_UNREADABLE");
		}
		Set<String> filenames = new LinkedHashSet<>();
		for (Path entry : entries) {
			filenames.add(entry.getFileName().toString());
		}
		if (entries.size() != EXPECTED_FILENAMES.size()
				|| !filenames.equals(EXPECTED_FILENAMES)) {
			throw failure("EVIDENCE_LAYOUT_INVALID");
		}
		for (String filename : EXPECTED_FILENAMES) {
			Path file = directory.resolve(filename);
			if (Files.isSymbolicLink(file)
					|| !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
				throw failure("EVIDENCE_FILE_INVALID");
			}
		}
	}

	private static ManifestMetadata validateManifest(JsonNode manifest) throws IOException {
		if (!hasExactFields(manifest, MANIFEST_FIELDS)
				|| !isSchemaVersion(manifest.get("schemaVersion"), 1)) {
			throw failure("EVIDENCE_MANIFEST_SCHEMA_INVALID");
		}
		String evidenceId = boundedText(manifest.get("evidenceId"), 3, 128);
		if (evidenceId == null || !EVIDENCE_ID.matcher(evidenceId).matches()) {
			throw failure("EVIDENCE_ID_INVALID");
		}
		Long payloadBytes = nonNegativeLong(manifest.get("payloadBytes"));
		if (payloadBytes == null) {
			throw failure("EVIDENCE_MANIFEST_SCHEMA_INVALID");
		}
		if (payloadBytes > MAXIMUM_PAYLOAD_BYTES) {
			throw failure("EVIDENCE_PAYLOAD_TOO_LARGE");
		}

		JsonNode files = manifest.get("files");
		if (files == null || !files.isArray() || files.size() != PAYLOAD_FILENAMES.size()) {
			throw failure("EVIDENCE_MANIFEST_FILES_INVALID");
		}
		List<ManifestFile> manifestFiles = new ArrayList<>();
		for (int index = 0; index < files.size(); index++) {
			JsonNode file = files.get(index);
			if (!hasExactFields(file, MANIFEST_FILE_FIELDS)) {
				throw failure("EVIDENCE_MANIFEST_FILES_INVALID");
			}
			String filename = boundedText(file.get("filename"), 1, 128);
			Long bytes = nonNegativeLong(file.get("bytes"));
			String digest = boundedText(file.get("sha256"), 64, 64);
			if (!PAYLOAD_FILENAMES.get(index).equals(filename)
					|| bytes == null
					|| bytes < 1
					|| bytes > MAXIMUM_PAYLOAD_BYTES
					|| digest == null
					|| !SHA256.matcher(digest).matches()) {
				throw failure("EVIDENCE_MANIFEST_FILES_INVALID");
			}
			manifestFiles.add(new ManifestFile(filename, bytes, digest));
		}
		long declaredBytes = 0;
		for (ManifestFile file : manifestFiles) {
			if (file.bytes() > MAXIMUM_PAYLOAD_BYTES - declaredBytes) {
				throw failure("EVIDENCE_PAYLOAD_TOO_LARGE");
			}
			declaredBytes += file.bytes();
		}
		if (payloadBytes != declaredBytes) {
			throw failure("EVIDENCE_MANIFEST_PAYLOAD_BYTES_INVALID");
		}
		return new ManifestMetadata(evidenceId, payloadBytes, manifestFiles);
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
				throw failure("EVIDENCE_FILE_UNREADABLE");
			}
			long size = attributes.size();
			if (!attributes.isRegularFile() || size < 1) {
				throw failure("EVIDENCE_FILE_INVALID");
			}
			if (size > MAXIMUM_PAYLOAD_BYTES - aggregate) {
				throw failure("EVIDENCE_PAYLOAD_TOO_LARGE");
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
			long remaining = MAXIMUM_PAYLOAD_BYTES - aggregate;
			byte[] bytes = readBounded(
					directory.resolve(filename),
					remaining,
					"EVIDENCE_PAYLOAD_TOO_LARGE");
			if (bytes.length != sizes.get(filename)) {
				throw failure("EVIDENCE_FILE_CHANGED");
			}
			aggregate += bytes.length;
			payloads.put(filename, bytes);
		}
		if (aggregate != expectedAggregate) {
			throw failure("EVIDENCE_FILE_CHANGED");
		}
		return Collections.unmodifiableMap(payloads);
	}

	private static void verifyIntegrity(
			ManifestMetadata manifest,
			Map<String, Long> observedSizes,
			long observedPayloadBytes,
			Map<String, byte[]> payloads) throws IOException {
		if (manifest.payloadBytes() != observedPayloadBytes) {
			throw failure("EVIDENCE_PAYLOAD_BYTES_MISMATCH");
		}
		for (ManifestFile file : manifest.files()) {
			if (!Objects.equals(observedSizes.get(file.filename()), file.bytes())
					|| !file.sha256().equals(sha256(payloads.get(file.filename())))) {
				throw failure("EVIDENCE_PAYLOAD_DIGEST_MISMATCH");
			}
		}
	}

	private static boolean validateCrossDocumentSemantics(
			String evidenceId, Map<String, JsonNode> documents) throws IOException {
		for (String filename : PAYLOAD_FILENAMES) {
			JsonNode document = documents.get(filename);
			if (document == null
					|| !document.isObject()
					|| !isSchemaVersion(document.get("schemaVersion"), 2)
					|| !matchesText(document.get("evidenceId"), evidenceId)) {
				throw failure("EVIDENCE_DOCUMENT_IDENTITY_INVALID");
			}
		}

		JsonNode summary = documents.get(SUMMARY_FILENAME);
		if (!matchesText(summary.get("evidenceType"), EXPECTED_EVIDENCE_TYPE)
				|| !isBoolean(summary.get("qualityReviewEligible"))) {
			throw failure("EVIDENCE_SUMMARY_SEMANTICS_INVALID");
		}
		boolean summaryEligible = summary.get("qualityReviewEligible").asBoolean();

		JsonNode blinded = documents.get(BLINDED_FILENAME);
		if (!isBoolean(blinded.get("qualityReviewEligible"))
				|| blinded.get("qualityReviewEligible").asBoolean() != summaryEligible) {
			throw failure("EVIDENCE_REVIEW_ELIGIBILITY_INVALID");
		}
		String instructions = boundedText(blinded.get("instructions"), 1, 2_048);
		if (instructions == null) {
			throw failure("EVIDENCE_REVIEW_INSTRUCTIONS_INVALID");
		}
		if (summaryEligible && !ELIGIBLE_REVIEW_INSTRUCTIONS.equals(instructions)) {
			throw failure("EVIDENCE_REVIEW_INSTRUCTIONS_INVALID");
		}
		if (!summaryEligible && !safelyForbidsLabeling(instructions)) {
			throw failure("EVIDENCE_INCOMPLETE_REVIEW_INVALID");
		}
		return summaryEligible;
	}

	private static boolean safelyForbidsLabeling(String instructions) {
		String normalized = instructions.toLowerCase(Locale.ROOT)
				.replaceAll("\\s+", " ")
				.strip();
		if (!normalized.contains("do not label")) {
			return false;
		}
		return !normalized.contains("assign ")
				&& !normalized.contains("relevancegrade")
				&& !normalized.contains(" grade ")
				&& !normalized.contains(" score ")
				&& !normalized.contains("please label")
				&& !normalized.contains("label each")
				&& !normalized.contains("label the candidates");
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
			throw failure("EVIDENCE_INTERNAL_LIMIT_INVALID");
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
			throw failure("EVIDENCE_FILE_UNREADABLE");
		}
	}

	private static boolean hasExactFields(JsonNode node, Set<String> fields) {
		return node != null
				&& node.isObject()
				&& new LinkedHashSet<>(node.propertyNames()).equals(fields);
	}

	private static boolean isSchemaVersion(JsonNode node, int expected) {
		return node != null && node.isInt() && node.asInt() == expected;
	}

	private static boolean isBoolean(JsonNode node) {
		return node != null && node.isBoolean();
	}

	private static boolean matchesText(JsonNode node, String expected) {
		return node != null && node.isString() && expected.equals(node.asString());
	}

	private static String boundedText(JsonNode node, int minimum, int maximum) {
		if (node == null || !node.isString()) {
			return null;
		}
		String value = node.asString();
		if (!value.equals(value.strip())
				|| value.length() < minimum
				|| value.length() > maximum) {
			return null;
		}
		return value;
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

	private static String sha256(byte[] bytes) {
		try {
			return HexFormat.of().formatHex(
					MessageDigest.getInstance("SHA-256").digest(bytes));
		}
		catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException("EVIDENCE_SHA256_UNAVAILABLE");
		}
	}

	private static VerificationException failure(String diagnostic) {
		return new VerificationException(diagnostic);
	}

	private record ManifestMetadata(
			String evidenceId, long payloadBytes, List<ManifestFile> files) {

		private ManifestMetadata {
			files = List.copyOf(files);
		}
	}

	private record ManifestFile(String filename, long bytes, String sha256) {
	}

	static final class VerificationException extends IOException {

		private VerificationException(String diagnostic) {
			super(diagnostic);
		}
	}
}
