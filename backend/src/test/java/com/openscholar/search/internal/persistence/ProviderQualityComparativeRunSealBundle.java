package com.openscholar.search.internal.persistence;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.channels.Channels;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.ObjectWriter;
import tools.jackson.databind.SerializationFeature;

/**
 * Content-addressed promotion boundary for one fully verified comparative evaluation run.
 *
 * <p>The bundle retains exact source bytes and adds a canonical inventory; it is not a signature,
 * trusted timestamp, retention system, or hostile-filesystem snapshot. Callers must use a local,
 * operator-controlled root whose files and ancestors cannot be replaced concurrently.</p>
 */
final class ProviderQualityComparativeRunSealBundle {

	static final int MAXIMUM_SEAL_BYTES = 64 * 1024;
	static final long MAXIMUM_PAYLOAD_BYTES = 153_157_632L;
	static final long MAXIMUM_TOTAL_BYTES = MAXIMUM_PAYLOAD_BYTES + MAXIMUM_SEAL_BYTES;
	static final String PROTOCOL_ID = "provider-quality-comparative-run-seal-v1";
	static final String RUN_SEAL_ID_PREFIX = PROTOCOL_ID + '-';

	private static final String IDENTITY_DOMAIN =
			"openscholar-provider-quality-comparative-run-seal-v1";
	private static final String SEAL_FILENAME = "run-seal.json";
	private static final long MAXIMUM_EVIDENCE_PAYLOAD_BYTES =
			ProviderQualityComparativeEvidenceBundle.MAXIMUM_PAYLOAD_BYTES;
	private static final long MAXIMUM_REVIEW_PACKET_BYTES =
			ProviderQualityComparativeReviewPacket.MAXIMUM_REVIEW_PACKET_BYTES;
	private static final long MAXIMUM_WORKSHEET_BYTES =
			ProviderQualityComparativeReviewWorksheet.MAX_INPUT_BYTES;
	private static final long MAXIMUM_JUDGMENTS_BYTES =
			ProviderQualityComparativeJudgments.MAX_INPUT_BYTES;
	private static final long MAXIMUM_REPORT_BYTES =
			ProviderQualityComparativeScoreReportBundle.MAXIMUM_REPORT_BYTES;
	private static final Pattern SAFE_SLUG =
			Pattern.compile("^[a-z0-9][a-z0-9-]{1,126}[a-z0-9]$");
	private static final Pattern SHA256 = Pattern.compile("^[0-9a-f]{64}$");
	private static final Pattern GIT_REVISION =
			Pattern.compile("^[0-9a-f]{40}(?:[0-9a-f]{24})?$");
	private static final Pattern REPORT_ID = Pattern.compile(
			'^' + Pattern.quote(ProviderQualityComparativeScorer.REPORT_ID_PREFIX)
					+ "[0-9a-f]{64}$");
	private static final Pattern RUN_SEAL_ID = Pattern.compile(
			'^' + Pattern.quote(RUN_SEAL_ID_PREFIX) + "[0-9a-f]{64}$");
	private static final Set<String> SEAL_FIELDS = Set.of(
			"schemaVersion",
			"protocolId",
			"runSealId",
			"bindings",
			"payloadBytes",
			"files");
	private static final Set<String> BINDING_FIELDS = Set.of(
			"evidenceId",
			"evidenceManifestSha256",
			"captureRepositoryRevision",
			"captureMeasuredAt",
			"querySetId",
			"querySetSha256",
			"scoringPolicyId",
			"scoringPolicySha256",
			"reviewPacketSha256",
			"completedWorksheetSha256",
			"judgmentsSha256",
			"reportId",
			"reportManifestSha256");
	private static final Set<PosixFilePermission> DIRECTORY_PERMISSIONS =
			PosixFilePermissions.fromString("rwx------");
	private static final Set<PosixFilePermission> FILE_PERMISSIONS =
			PosixFilePermissions.fromString("rw-------");
	private ProviderQualityComparativeRunSealBundle() {
	}

	static VerifiedRunSeal publishAndVerify(
			ObjectMapper objectMapper,
			Path externalRoot,
			Bindings bindings,
			Map<String, Path> sources) throws IOException {
		ObjectWriter canonicalWriter = canonicalWriter(objectMapper);
		Bindings expectedBindings = Objects.requireNonNull(bindings, "bindings");
		Path root = validateExternalRoot(externalRoot);
		List<String> expectedPaths = expectedPayloadPaths(expectedBindings);
		Map<String, Path> boundedSources = validateSources(root, sources, expectedPaths);
		Map<String, Long> sourceSizes = sourceSizes(boundedSources, expectedBindings);
		validateGroupBounds(sourceSizes, expectedBindings);

		Path staging = createPrivateStagingDirectory(root);
		try {
			createExpectedDirectories(staging, expectedBindings);
			List<SealedFile> files = new ArrayList<>(expectedPaths.size());
			for (String relativePath : expectedPaths) {
				Path target = resolveRelative(staging, relativePath);
				files.add(copyPrivateFile(
						boundedSources.get(relativePath),
						target,
						maximumFileBytes(relativePath, expectedBindings),
						relativePath));
			}
			long payloadBytes = payloadBytes(files);
			validateGroupBounds(fileSizes(files), expectedBindings);
			validateAnchors(files, expectedBindings);

			byte[] coreBytes = canonicalBytes(
					canonicalWriter, coreDocument(expectedBindings, payloadBytes, files));
			String sealId = runSealId(coreBytes);
			byte[] sealBytes = canonicalBytes(
					canonicalWriter,
					sealDocument(expectedBindings, payloadBytes, files, sealId));
			if (sealBytes.length > MAXIMUM_SEAL_BYTES
					|| addExact(payloadBytes, sealBytes.length) > MAXIMUM_TOTAL_BYTES) {
				throw failure("RUN_SEAL_TOO_LARGE");
			}
			writePrivateFile(staging.resolve(SEAL_FILENAME), sealBytes);
			verifyAt(
					objectMapper,
					staging,
					expectedBindings,
					sealId,
					false);

			Path destination = root.resolve(sealId).normalize();
			if (!destination.getParent().equals(root)) {
				throw failure("RUN_SEAL_ID_INVALID");
			}
			if (Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) {
				VerifiedRunSeal existing = verifyExact(
						objectMapper, destination, expectedBindings);
				deleteStagingDirectory(root, staging);
				return existing;
			}
			try {
				Files.move(staging, destination, StandardCopyOption.ATOMIC_MOVE);
			}
			catch (AtomicMoveNotSupportedException exception) {
				throw failure("RUN_SEAL_ATOMIC_PUBLISH_UNSUPPORTED");
			}
			catch (FileAlreadyExistsException exception) {
				VerifiedRunSeal existing = verifyExact(
						objectMapper, destination, expectedBindings);
				deleteStagingDirectory(root, staging);
				return existing;
			}
			return verifyExact(objectMapper, destination, expectedBindings);
		}
		catch (IOException | RuntimeException | Error failure) {
			try {
				deleteStagingDirectory(root, staging);
			}
			catch (IOException cleanupFailure) {
				failure.addSuppressed(cleanupFailure);
			}
			throw failure;
		}
	}

	static VerifiedRunSeal verifyExact(
			ObjectMapper objectMapper,
			Path runDirectory,
			Bindings expectedBindings) throws IOException {
		Objects.requireNonNull(expectedBindings, "bindings");
		return verifyAt(
				objectMapper,
				runDirectory,
				expectedBindings,
				null,
				true);
	}

	static VerifiedRunSeal verifyRetained(ObjectMapper objectMapper, Path runDirectory)
			throws IOException {
		Objects.requireNonNull(objectMapper, "objectMapper");
		Path directory;
		try {
			directory = Objects.requireNonNull(runDirectory, "runDirectory")
					.toAbsolutePath().normalize();
		}
		catch (RuntimeException exception) {
			throw failure("RUN_SEAL_INPUT_INVALID");
		}
		if (Files.isSymbolicLink(directory)
				|| !Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
			throw failure("RUN_SEAL_DIRECTORY_INVALID");
		}
		requirePrivatePermissions(directory, true);
		Path directoryName = directory.getFileName();
		if (directoryName == null || !RUN_SEAL_ID.matcher(directoryName.toString()).matches()) {
			throw failure("RUN_SEAL_ID_INVALID");
		}
		byte[] sealBytes = readBootstrapSeal(
				directory.resolve(SEAL_FILENAME),
				MAXIMUM_SEAL_BYTES);
		JsonNode seal = parseStrict(objectMapper, sealBytes);
		Bindings bindings = parseRetainedBindings(seal);
		String claimedSealId = requiredText(seal.get("runSealId"), "RUN_SEAL_SCHEMA_INVALID");
		if (!RUN_SEAL_ID.matcher(claimedSealId).matches()
				|| !sameAscii(directoryName.toString(), claimedSealId)) {
			throw failure("RUN_SEAL_ID_INVALID");
		}
		return verifyExact(objectMapper, directory, bindings);
	}

	static List<String> expectedPayloadPaths(Bindings bindings) {
		Bindings expected = Objects.requireNonNull(bindings, "bindings");
		List<String> paths = new ArrayList<>(List.of(
				"capture/" + expected.evidenceId() + "/manifest.json",
				"capture/" + expected.evidenceId() + "/summary.json",
				"capture/" + expected.evidenceId() + "/blinded-candidates.json",
				"capture/" + expected.evidenceId() + "/provenance-map.json",
				"capture/" + expected.evidenceId() + "/reconciliation-trace.json",
				"review/review-packet.json",
				"review/completed-worksheet.json",
				"review/judgments.json",
				"score/" + expected.reportId() + "/manifest.json",
				"score/" + expected.reportId() + "/query-scores.json",
				"score/" + expected.reportId() + "/score-summary.json"));
		paths.sort(String::compareTo);
		return List.copyOf(paths);
	}

	private static VerifiedRunSeal verifyAt(
			ObjectMapper objectMapper,
			Path suppliedDirectory,
			Bindings expectedBindings,
			String expectedSealId,
			boolean requireDirectoryName) throws IOException {
		ObjectWriter canonicalWriter = canonicalWriter(objectMapper);
		Path directory;
		try {
			directory = suppliedDirectory.toAbsolutePath().normalize();
		}
		catch (RuntimeException exception) {
			throw failure("RUN_SEAL_INPUT_INVALID");
		}
		verifyLayout(directory, expectedBindings);
		List<SealedFile> files = inventory(directory, expectedBindings);
		long payloadBytes = payloadBytes(files);
		validateGroupBounds(fileSizes(files), expectedBindings);
		validateAnchors(files, expectedBindings);

		byte[] coreBytes = canonicalBytes(
				canonicalWriter, coreDocument(expectedBindings, payloadBytes, files));
		String derivedSealId = runSealId(coreBytes);
		if (expectedSealId != null && !sameAscii(expectedSealId, derivedSealId)) {
			throw failure("RUN_SEAL_ID_INVALID");
		}
		Path directoryName = directory.getFileName();
		if (requireDirectoryName
				&& (directoryName == null || !derivedSealId.equals(directoryName.toString()))) {
			throw failure("RUN_SEAL_ID_INVALID");
		}

		byte[] sealBytes = readBytesBounded(
				directory.resolve(SEAL_FILENAME),
				MAXIMUM_SEAL_BYTES,
				"RUN_SEAL_TOO_LARGE");
		parseStrict(objectMapper, sealBytes);
		byte[] expectedSealBytes = canonicalBytes(
				canonicalWriter,
				sealDocument(expectedBindings, payloadBytes, files, derivedSealId));
		if (!MessageDigest.isEqual(sealBytes, expectedSealBytes)) {
			throw failure("RUN_SEAL_NOT_CANONICAL_OR_EXPECTED");
		}
		long totalBytes = addExact(payloadBytes, sealBytes.length);
		if (totalBytes > MAXIMUM_TOTAL_BYTES) {
			throw failure("RUN_SEAL_TOO_LARGE");
		}

		verifyUnchanged(directory, expectedBindings, files, sealBytes);
		return new VerifiedRunSeal(
				directory,
				derivedSealId,
				sha256(sealBytes),
				payloadBytes,
				totalBytes,
				expectedBindings,
				files);
	}

	private static ObjectWriter canonicalWriter(ObjectMapper objectMapper) {
		return Objects.requireNonNull(objectMapper, "objectMapper")
				.writer()
				.with(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
				.without(SerializationFeature.INDENT_OUTPUT);
	}

	private static Map<String, Path> validateSources(
			Path root, Map<String, Path> sources, List<String> expectedPaths) throws IOException {
		Objects.requireNonNull(sources, "sources");
		if (!new LinkedHashSet<>(sources.keySet()).equals(new LinkedHashSet<>(expectedPaths))) {
			throw failure("RUN_SEAL_SOURCE_LAYOUT_INVALID");
		}
		Map<String, Path> result = new LinkedHashMap<>();
		Set<Path> realSources = new LinkedHashSet<>();
		for (String relativePath : expectedPaths) {
			Path source = Objects.requireNonNull(sources.get(relativePath), relativePath)
					.toAbsolutePath().normalize();
			if (Files.isSymbolicLink(source)
					|| !Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS)) {
				throw failure("RUN_SEAL_SOURCE_FILE_INVALID");
			}
			Path realSource = source.toRealPath();
			if (realSource.startsWith(root) || !realSources.add(realSource)) {
				throw failure("RUN_SEAL_SOURCE_PATH_INVALID");
			}
			result.put(relativePath, source);
		}
		return Collections.unmodifiableMap(result);
	}

	private static Map<String, Long> sourceSizes(
			Map<String, Path> sources, Bindings bindings) throws IOException {
		Map<String, Long> result = new LinkedHashMap<>();
		for (String relativePath : expectedPayloadPaths(bindings)) {
			BasicFileAttributes attributes = readAttributes(sources.get(relativePath));
			if (!attributes.isRegularFile() || attributes.size() < 1) {
				throw failure("RUN_SEAL_SOURCE_FILE_INVALID");
			}
			long maximum = maximumFileBytes(relativePath, bindings);
			if (attributes.size() > maximum) {
				throw failure("RUN_SEAL_SOURCE_TOO_LARGE");
			}
			result.put(relativePath, attributes.size());
		}
		return Collections.unmodifiableMap(result);
	}

	private static List<SealedFile> inventory(Path directory, Bindings bindings)
			throws IOException {
		List<SealedFile> files = new ArrayList<>();
		for (String relativePath : expectedPayloadPaths(bindings)) {
			Path file = resolveRelative(directory, relativePath);
			files.add(readDigest(
					file, maximumFileBytes(relativePath, bindings), relativePath));
		}
		return List.copyOf(files);
	}

	private static SealedFile copyPrivateFile(
			Path source, Path target, long maximumBytes, String relativePath) throws IOException {
		writePrivateFile(target, new byte[0]);
		MessageDigest digest = sha256Digest();
		long count = 0;
		BasicFileAttributes before = readAttributes(source);
		try (SeekableByteChannel sourceChannel = Files.newByteChannel(
				source, Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS));
				InputStream input = Channels.newInputStream(sourceChannel);
				OutputStream output = Files.newOutputStream(target, StandardOpenOption.WRITE)) {
			byte[] buffer = new byte[16 * 1024];
			int read;
			while ((read = input.read(buffer)) != -1) {
				if (read > maximumBytes - count) {
					throw failure("RUN_SEAL_SOURCE_TOO_LARGE");
				}
				output.write(buffer, 0, read);
				digest.update(buffer, 0, read);
				count += read;
			}
			if (sourceChannel.size() != count) {
				throw failure("RUN_SEAL_SOURCE_CHANGED");
			}
		}
		BasicFileAttributes after = readAttributes(source);
		if (count < 1
				|| before.size() != count
				|| after.size() != count
				|| !Objects.equals(before.fileKey(), after.fileKey())) {
			throw failure("RUN_SEAL_SOURCE_CHANGED");
		}
		return new SealedFile(relativePath, count, HexFormat.of().formatHex(digest.digest()));
	}

	private static SealedFile readDigest(Path path, long maximumBytes, String relativePath)
			throws IOException {
		if (Files.isSymbolicLink(path)
				|| !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
			throw failure("RUN_SEAL_FILE_INVALID");
		}
		MessageDigest digest = sha256Digest();
		long count = 0;
		BasicFileAttributes before = readAttributes(path);
		if (before.size() < 1 || before.size() > maximumBytes) {
			throw failure("RUN_SEAL_FILE_SIZE_INVALID");
		}
		try (SeekableByteChannel channel = Files.newByteChannel(
				path, Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS));
				InputStream input = Channels.newInputStream(channel)) {
			byte[] buffer = new byte[16 * 1024];
			int read;
			while ((read = input.read(buffer)) != -1) {
				if (read > maximumBytes - count) {
					throw failure("RUN_SEAL_FILE_SIZE_INVALID");
				}
				digest.update(buffer, 0, read);
				count += read;
			}
			if (channel.size() != count) {
				throw failure("RUN_SEAL_FILE_CHANGED");
			}
		}
		BasicFileAttributes after = readAttributes(path);
		if (count != before.size()
				|| count != after.size()
				|| !Objects.equals(before.fileKey(), after.fileKey())) {
			throw failure("RUN_SEAL_FILE_CHANGED");
		}
		return new SealedFile(relativePath, count, HexFormat.of().formatHex(digest.digest()));
	}

	private static void verifyUnchanged(
			Path directory,
			Bindings bindings,
			List<SealedFile> expectedFiles,
			byte[] expectedSeal) throws IOException {
		verifyLayout(directory, bindings);
		List<SealedFile> currentFiles = inventory(directory, bindings);
		if (!currentFiles.equals(expectedFiles)) {
			throw failure("RUN_SEAL_FILE_CHANGED");
		}
		byte[] currentSeal = readBytesBounded(
				directory.resolve(SEAL_FILENAME),
				expectedSeal.length,
				"RUN_SEAL_FILE_CHANGED");
		if (!MessageDigest.isEqual(expectedSeal, currentSeal)) {
			throw failure("RUN_SEAL_FILE_CHANGED");
		}
		verifyLayout(directory, bindings);
	}

	private static void verifyLayout(Path directory, Bindings bindings) throws IOException {
		if (Files.isSymbolicLink(directory)
				|| !Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
			throw failure("RUN_SEAL_DIRECTORY_INVALID");
		}
		requirePrivatePermissions(directory, true);
		Set<String> expectedDirectories = expectedDirectoryPaths(bindings);
		Set<String> expectedFiles = new LinkedHashSet<>(expectedPayloadPaths(bindings));
		expectedFiles.add(SEAL_FILENAME);
		Set<String> observedDirectories = new LinkedHashSet<>();
		Set<String> observedFiles = new LinkedHashSet<>();
		try (var paths = Files.walk(directory)) {
			for (Path path : paths.toList()) {
				if (path.equals(directory)) {
					continue;
				}
				String relative = relativePath(directory, path);
				if (Files.isSymbolicLink(path)) {
					throw failure("RUN_SEAL_LINK_INVALID");
				}
				if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
					observedDirectories.add(relative);
					requirePrivatePermissions(path, true);
				}
				else if (Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
					observedFiles.add(relative);
					requirePrivatePermissions(path, false);
				}
				else {
					throw failure("RUN_SEAL_FILE_INVALID");
				}
			}
		}
		catch (VerificationException exception) {
			throw exception;
		}
		catch (IOException | SecurityException exception) {
			throw failure("RUN_SEAL_DIRECTORY_UNREADABLE");
		}
		if (!observedDirectories.equals(expectedDirectories)
				|| !observedFiles.equals(expectedFiles)) {
			throw failure("RUN_SEAL_LAYOUT_INVALID");
		}
	}

	private static Set<String> expectedDirectoryPaths(Bindings bindings) {
		return Set.of(
				"capture",
				"capture/" + bindings.evidenceId(),
				"review",
				"score",
				"score/" + bindings.reportId());
	}

	private static void createExpectedDirectories(Path staging, Bindings bindings)
			throws IOException {
		for (String relative : List.of(
				"capture",
				"capture/" + bindings.evidenceId(),
				"review",
				"score",
				"score/" + bindings.reportId())) {
			createPrivateDirectory(resolveRelative(staging, relative));
		}
	}

	private static Path validateExternalRoot(Path supplied) throws IOException {
		Objects.requireNonNull(supplied, "externalRoot");
		if (!supplied.isAbsolute()) {
			throw failure("RUN_SEAL_ROOT_NOT_ABSOLUTE");
		}
		Path root = supplied.toAbsolutePath().normalize();
		if (Files.isSymbolicLink(root)
				|| !Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
			throw failure("RUN_SEAL_ROOT_INVALID");
		}
		root = root.toRealPath();
		requirePrivatePermissions(root, true);
		return root;
	}

	private static Path createPrivateStagingDirectory(Path root) throws IOException {
		Path staging;
		if (supportsPosix(root)) {
			staging = Files.createTempDirectory(
					root,
					'.' + PROTOCOL_ID + ".staging-",
					PosixFilePermissions.asFileAttribute(DIRECTORY_PERMISSIONS));
			Files.setPosixFilePermissions(staging, DIRECTORY_PERMISSIONS);
		}
		else {
			staging = Files.createTempDirectory(root, '.' + PROTOCOL_ID + ".staging-");
		}
		return staging;
	}

	private static void createPrivateDirectory(Path path) throws IOException {
		if (supportsPosix(path)) {
			Files.createDirectory(
					path, PosixFilePermissions.asFileAttribute(DIRECTORY_PERMISSIONS));
			Files.setPosixFilePermissions(path, DIRECTORY_PERMISSIONS);
		}
		else {
			Files.createDirectory(path);
		}
	}

	private static void writePrivateFile(Path path, byte[] bytes) throws IOException {
		if (supportsPosix(path)) {
			Files.createFile(path, PosixFilePermissions.asFileAttribute(FILE_PERMISSIONS));
			Files.setPosixFilePermissions(path, FILE_PERMISSIONS);
		}
		else {
			Files.createFile(path);
		}
		if (bytes.length > 0) {
			Files.write(path, bytes, StandardOpenOption.WRITE);
		}
	}

	private static void deleteStagingDirectory(Path root, Path staging) throws IOException {
		Path normalized = staging.toAbsolutePath().normalize();
		if (!normalized.getParent().equals(root)
				|| !normalized.getFileName().toString().startsWith('.' + PROTOCOL_ID + ".staging-")) {
			throw failure("RUN_SEAL_STAGING_PATH_INVALID");
		}
		if (!Files.exists(normalized, LinkOption.NOFOLLOW_LINKS)) {
			return;
		}
		try (var paths = Files.walk(normalized)) {
			for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
				Files.deleteIfExists(path);
			}
		}
	}

	private static void validateGroupBounds(
			Map<String, Long> sizes, Bindings bindings) throws IOException {
		long evidencePayload = 0;
		long report = 0;
		long payload = 0;
		for (Map.Entry<String, Long> entry : sizes.entrySet()) {
			String path = entry.getKey();
			long bytes = entry.getValue();
			if (bytes < 1 || bytes > maximumFileBytes(path, bindings)) {
				throw failure("RUN_SEAL_FILE_SIZE_INVALID");
			}
			payload = addExact(payload, bytes);
			if (path.startsWith("capture/") && !path.endsWith("/manifest.json")) {
				evidencePayload = addExact(evidencePayload, bytes);
			}
			if (path.startsWith("score/")) {
				report = addExact(report, bytes);
			}
		}
		if (evidencePayload > MAXIMUM_EVIDENCE_PAYLOAD_BYTES
				|| report > MAXIMUM_REPORT_BYTES
				|| payload > MAXIMUM_PAYLOAD_BYTES) {
			throw failure("RUN_SEAL_PAYLOAD_TOO_LARGE");
		}
	}

	private static long maximumFileBytes(String path, Bindings bindings) {
		if (path.equals("capture/" + bindings.evidenceId() + "/manifest.json")) {
			return ProviderQualityComparativeEvidenceBundle.MAXIMUM_MANIFEST_BYTES;
		}
		if (path.startsWith("capture/" + bindings.evidenceId() + '/')) {
			return MAXIMUM_EVIDENCE_PAYLOAD_BYTES;
		}
		if (path.equals("review/review-packet.json")) {
			return MAXIMUM_REVIEW_PACKET_BYTES;
		}
		if (path.equals("review/completed-worksheet.json")) {
			return MAXIMUM_WORKSHEET_BYTES;
		}
		if (path.equals("review/judgments.json")) {
			return MAXIMUM_JUDGMENTS_BYTES;
		}
		if (path.equals("score/" + bindings.reportId() + "/manifest.json")) {
			return ProviderQualityComparativeScoreReportBundle.MAXIMUM_MANIFEST_BYTES;
		}
		if (path.startsWith("score/" + bindings.reportId() + '/')) {
			return MAXIMUM_REPORT_BYTES;
		}
		throw new IllegalArgumentException("unexpected run-seal path");
	}

	private static void validateAnchors(List<SealedFile> files, Bindings bindings)
			throws IOException {
		Map<String, String> digests = new LinkedHashMap<>();
		files.forEach(file -> digests.put(file.path(), file.sha256()));
		requireDigest(
				digests,
				"capture/" + bindings.evidenceId() + "/manifest.json",
				bindings.evidenceManifestSha256());
		requireDigest(digests, "review/review-packet.json", bindings.reviewPacketSha256());
		requireDigest(
				digests,
				"review/completed-worksheet.json",
				bindings.completedWorksheetSha256());
		requireDigest(digests, "review/judgments.json", bindings.judgmentsSha256());
		requireDigest(
				digests,
				"score/" + bindings.reportId() + "/manifest.json",
				bindings.reportManifestSha256());
	}

	private static void requireDigest(
			Map<String, String> digests, String path, String expected) throws IOException {
		String actual = digests.get(path);
		if (actual == null || !sameAscii(actual, expected)) {
			throw failure("RUN_SEAL_BINDING_MISMATCH");
		}
	}

	private static Map<String, Object> coreDocument(
			Bindings bindings, long payloadBytes, List<SealedFile> files) {
		Map<String, Object> document = new TreeMap<>();
		document.put("bindings", bindingsDocument(bindings));
		document.put("files", fileDocuments(files));
		document.put("payloadBytes", payloadBytes);
		document.put("protocolId", PROTOCOL_ID);
		document.put("schemaVersion", 1);
		return document;
	}

	private static Map<String, Object> sealDocument(
			Bindings bindings, long payloadBytes, List<SealedFile> files, String sealId) {
		Map<String, Object> document = new TreeMap<>(coreDocument(bindings, payloadBytes, files));
		document.put("runSealId", sealId);
		return document;
	}

	private static Map<String, Object> bindingsDocument(Bindings bindings) {
		Map<String, Object> result = new TreeMap<>();
		result.put("captureMeasuredAt", bindings.captureMeasuredAt());
		result.put("captureRepositoryRevision", bindings.captureRepositoryRevision());
		result.put("completedWorksheetSha256", bindings.completedWorksheetSha256());
		result.put("evidenceId", bindings.evidenceId());
		result.put("evidenceManifestSha256", bindings.evidenceManifestSha256());
		result.put("judgmentsSha256", bindings.judgmentsSha256());
		result.put("querySetId", bindings.querySetId());
		result.put("querySetSha256", bindings.querySetSha256());
		result.put("reportId", bindings.reportId());
		result.put("reportManifestSha256", bindings.reportManifestSha256());
		result.put("reviewPacketSha256", bindings.reviewPacketSha256());
		result.put("scoringPolicyId", bindings.scoringPolicyId());
		result.put("scoringPolicySha256", bindings.scoringPolicySha256());
		return result;
	}

	private static List<Map<String, Object>> fileDocuments(List<SealedFile> files) {
		return files.stream().map(file -> {
			Map<String, Object> result = new TreeMap<>();
			result.put("bytes", file.bytes());
			result.put("path", file.path());
			result.put("sha256", file.sha256());
			return result;
		}).toList();
	}

	private static String runSealId(byte[] canonicalCoreBytes) {
		MessageDigest digest = sha256Digest();
		digest.update(IDENTITY_DOMAIN.getBytes(StandardCharsets.US_ASCII));
		digest.update((byte) 0);
		digest.update(canonicalCoreBytes);
		return RUN_SEAL_ID_PREFIX + HexFormat.of().formatHex(digest.digest());
	}

	private static byte[] canonicalBytes(ObjectWriter writer, Object value) throws IOException {
		byte[] json = writer.writeValueAsBytes(value);
		if (json.length > 0 && json[json.length - 1] == '\n') {
			return json;
		}
		byte[] terminated = Arrays.copyOf(json, json.length + 1);
		terminated[terminated.length - 1] = '\n';
		return terminated;
	}

	private static JsonNode parseStrict(ObjectMapper objectMapper, byte[] bytes)
			throws IOException {
		try {
			JsonNode root = objectMapper.reader()
					.with(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
					.with(
							DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY,
							DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
					.readTree(bytes);
			if (root == null) {
				throw failure("RUN_SEAL_JSON_INVALID");
			}
			return root;
		}
		catch (VerificationException exception) {
			throw exception;
		}
		catch (RuntimeException exception) {
			throw failure("RUN_SEAL_JSON_INVALID");
		}
	}

	private static Bindings parseRetainedBindings(JsonNode seal) throws IOException {
		if (!hasExactFields(seal, SEAL_FIELDS)
				|| seal.get("schemaVersion") == null
				|| !seal.get("schemaVersion").isInt()
				|| seal.get("schemaVersion").asInt() != 1
				|| seal.get("protocolId") == null
				|| !seal.get("protocolId").isString()
				|| !PROTOCOL_ID.equals(seal.get("protocolId").asString())
				|| seal.get("payloadBytes") == null
				|| !seal.get("payloadBytes").isIntegralNumber()
				|| !seal.get("payloadBytes").canConvertToLong()
				|| seal.get("payloadBytes").longValue() < 1
				|| seal.get("payloadBytes").longValue() > MAXIMUM_PAYLOAD_BYTES
				|| seal.get("files") == null
				|| !seal.get("files").isArray()
				|| seal.get("files").size() != 11) {
			throw failure("RUN_SEAL_SCHEMA_INVALID");
		}
		JsonNode bindingNode = seal.get("bindings");
		if (!hasExactFields(bindingNode, BINDING_FIELDS)) {
			throw failure("RUN_SEAL_BINDINGS_INVALID");
		}
		try {
			return new Bindings(
					requiredText(bindingNode.get("evidenceId"), "RUN_SEAL_BINDINGS_INVALID"),
					requiredText(
							bindingNode.get("evidenceManifestSha256"),
							"RUN_SEAL_BINDINGS_INVALID"),
					requiredText(
							bindingNode.get("captureRepositoryRevision"),
							"RUN_SEAL_BINDINGS_INVALID"),
					requiredText(
							bindingNode.get("captureMeasuredAt"),
							"RUN_SEAL_BINDINGS_INVALID"),
					requiredText(bindingNode.get("querySetId"), "RUN_SEAL_BINDINGS_INVALID"),
					requiredText(
							bindingNode.get("querySetSha256"),
							"RUN_SEAL_BINDINGS_INVALID"),
					requiredText(
							bindingNode.get("scoringPolicyId"),
							"RUN_SEAL_BINDINGS_INVALID"),
					requiredText(
							bindingNode.get("scoringPolicySha256"),
							"RUN_SEAL_BINDINGS_INVALID"),
					requiredText(
							bindingNode.get("reviewPacketSha256"),
							"RUN_SEAL_BINDINGS_INVALID"),
					requiredText(
							bindingNode.get("completedWorksheetSha256"),
							"RUN_SEAL_BINDINGS_INVALID"),
					requiredText(
							bindingNode.get("judgmentsSha256"),
							"RUN_SEAL_BINDINGS_INVALID"),
					requiredText(bindingNode.get("reportId"), "RUN_SEAL_BINDINGS_INVALID"),
					requiredText(
							bindingNode.get("reportManifestSha256"),
							"RUN_SEAL_BINDINGS_INVALID"));
		}
		catch (IllegalArgumentException exception) {
			throw failure("RUN_SEAL_BINDINGS_INVALID");
		}
	}

	private static boolean hasExactFields(JsonNode node, Set<String> expected) {
		return node != null
				&& node.isObject()
				&& new LinkedHashSet<>(node.propertyNames()).equals(expected);
	}

	private static String requiredText(JsonNode node, String diagnostic) throws IOException {
		if (node == null || !node.isString()) {
			throw failure(diagnostic);
		}
		return node.asString();
	}

	private static byte[] readBytesBounded(Path path, long maximum, String diagnostic)
			throws IOException {
		if (maximum < 0 || maximum >= Integer.MAX_VALUE) {
			throw failure("RUN_SEAL_INTERNAL_LIMIT_INVALID");
		}
		try (SeekableByteChannel channel = Files.newByteChannel(
				path, Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS));
				InputStream input = Channels.newInputStream(channel)) {
			byte[] bytes = input.readNBytes((int) maximum + 1);
			if (bytes.length > maximum) {
				throw failure(diagnostic);
			}
			return bytes;
		}
		catch (VerificationException exception) {
			throw exception;
		}
		catch (IOException | SecurityException exception) {
			throw failure("RUN_SEAL_FILE_UNREADABLE");
		}
	}

	private static byte[] readBootstrapSeal(Path path, long maximum) throws IOException {
		if (Files.isSymbolicLink(path)
				|| !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
			throw failure("RUN_SEAL_FILE_INVALID");
		}
		requirePrivatePermissions(path, false);
		BasicFileAttributes before = readAttributes(path);
		if (!before.isRegularFile() || before.size() < 1 || before.size() > maximum) {
			throw failure(before.size() > maximum
					? "RUN_SEAL_TOO_LARGE"
					: "RUN_SEAL_FILE_SIZE_INVALID");
		}
		byte[] bytes = readBytesBounded(path, maximum, "RUN_SEAL_TOO_LARGE");
		BasicFileAttributes after = readAttributes(path);
		if (bytes.length != before.size()
				|| bytes.length != after.size()
				|| !Objects.equals(before.fileKey(), after.fileKey())) {
			throw failure("RUN_SEAL_FILE_CHANGED");
		}
		return bytes;
	}

	private static Map<String, Long> fileSizes(List<SealedFile> files) {
		Map<String, Long> result = new LinkedHashMap<>();
		files.forEach(file -> result.put(file.path(), file.bytes()));
		return Collections.unmodifiableMap(result);
	}

	private static long payloadBytes(List<SealedFile> files) throws IOException {
		long result = 0;
		for (SealedFile file : files) {
			result = addExact(result, file.bytes());
		}
		return result;
	}

	private static long addExact(long left, long right) throws IOException {
		try {
			return Math.addExact(left, right);
		}
		catch (ArithmeticException exception) {
			throw failure("RUN_SEAL_BYTE_COUNT_INVALID");
		}
	}

	private static BasicFileAttributes readAttributes(Path path) throws IOException {
		try {
			return Files.readAttributes(
					path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
		}
		catch (IOException | SecurityException exception) {
			throw failure("RUN_SEAL_FILE_UNREADABLE");
		}
	}

	private static Path resolveRelative(Path root, String relative) {
		Path result = root;
		for (String component : relative.split("/")) {
			result = result.resolve(component);
		}
		Path normalized = result.normalize();
		if (!normalized.startsWith(root) || normalized.equals(root)) {
			throw new IllegalArgumentException("run-seal path escaped its root");
		}
		return normalized;
	}

	private static String relativePath(Path root, Path path) {
		StringBuilder result = new StringBuilder();
		for (Path component : root.relativize(path)) {
			if (!result.isEmpty()) {
				result.append('/');
			}
			result.append(component);
		}
		return result.toString();
	}

	private static void requirePrivatePermissions(Path path, boolean directory)
			throws IOException {
		if (!supportsPosix(path)) {
			return;
		}
		Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(
				path, LinkOption.NOFOLLOW_LINKS);
		Set<PosixFilePermission> required = directory
				? DIRECTORY_PERMISSIONS
				: FILE_PERMISSIONS;
		if (!permissions.equals(required)) {
			throw failure("RUN_SEAL_PERMISSIONS_NOT_PRIVATE");
		}
	}

	private static boolean supportsPosix(Path path) {
		return path.getFileSystem().supportedFileAttributeViews().contains("posix");
	}

	private static MessageDigest sha256Digest() {
		try {
			return MessageDigest.getInstance("SHA-256");
		}
		catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException("RUN_SEAL_SHA256_UNAVAILABLE", exception);
		}
	}

	private static String sha256(byte[] bytes) {
		return HexFormat.of().formatHex(sha256Digest().digest(bytes));
	}

	private static boolean sameAscii(String left, String right) {
		return MessageDigest.isEqual(
				left.getBytes(StandardCharsets.US_ASCII),
				right.getBytes(StandardCharsets.US_ASCII));
	}

	private static VerificationException failure(String diagnostic) {
		return new VerificationException(diagnostic);
	}

	record Bindings(
			String evidenceId,
			String evidenceManifestSha256,
			String captureRepositoryRevision,
			String captureMeasuredAt,
			String querySetId,
			String querySetSha256,
			String scoringPolicyId,
			String scoringPolicySha256,
			String reviewPacketSha256,
			String completedWorksheetSha256,
			String judgmentsSha256,
			String reportId,
			String reportManifestSha256) {

		Bindings {
			requireSlug(evidenceId, "evidenceId");
			requireSha256(evidenceManifestSha256, "evidenceManifestSha256");
			if (captureRepositoryRevision == null
					|| !GIT_REVISION.matcher(captureRepositoryRevision).matches()) {
				throw new IllegalArgumentException(
						"captureRepositoryRevision must be a Git object ID");
			}
			try {
				if (!Instant.parse(captureMeasuredAt).toString().equals(captureMeasuredAt)) {
					throw new IllegalArgumentException(
							"captureMeasuredAt must be a canonical ISO-8601 instant");
				}
			}
			catch (RuntimeException exception) {
				throw new IllegalArgumentException(
						"captureMeasuredAt must be a canonical ISO-8601 instant", exception);
			}
			requireSlug(querySetId, "querySetId");
			requireSha256(querySetSha256, "querySetSha256");
			requireSlug(scoringPolicyId, "scoringPolicyId");
			requireSha256(scoringPolicySha256, "scoringPolicySha256");
			requireSha256(reviewPacketSha256, "reviewPacketSha256");
			requireSha256(completedWorksheetSha256, "completedWorksheetSha256");
			requireSha256(judgmentsSha256, "judgmentsSha256");
			if (reportId == null || !REPORT_ID.matcher(reportId).matches()) {
				throw new IllegalArgumentException("reportId must be a v2 comparative report ID");
			}
			requireSha256(reportManifestSha256, "reportManifestSha256");
			String expectedReportId = ProviderQualityComparativeScorer.reportId(
					evidenceManifestSha256, judgmentsSha256, scoringPolicySha256);
			if (!sameAscii(reportId, expectedReportId)) {
				throw new IllegalArgumentException(
						"reportId does not match the bound evidence, judgments, and policy");
			}
		}
	}

	record SealedFile(String path, long bytes, String sha256) {

		SealedFile {
			if (path == null || path.isBlank() || path.startsWith("/")
					|| path.contains("\\") || path.contains("//")
					|| Arrays.asList(path.split("/")).contains("..")
					|| Arrays.asList(path.split("/")).contains(".")) {
				throw new IllegalArgumentException("path must be a safe relative POSIX path");
			}
			if (bytes < 1) {
				throw new IllegalArgumentException("bytes must be positive");
			}
			requireSha256(sha256, "sha256");
		}
	}

	record VerifiedRunSeal(
			Path sourceDirectory,
			String sealId,
			String sealSha256,
			long payloadBytes,
			long totalBytes,
			Bindings bindings,
			List<SealedFile> files) {

		VerifiedRunSeal {
			sourceDirectory = Objects.requireNonNull(sourceDirectory, "sourceDirectory");
			if (sealId == null
					|| !sealId.startsWith(RUN_SEAL_ID_PREFIX)
					|| sealId.length() != RUN_SEAL_ID_PREFIX.length() + 64) {
				throw new IllegalArgumentException("sealId is invalid");
			}
			requireSha256(sealSha256, "sealSha256");
			if (payloadBytes < 1 || totalBytes <= payloadBytes) {
				throw new IllegalArgumentException("run-seal byte counts are invalid");
			}
			Objects.requireNonNull(bindings, "bindings");
			files = List.copyOf(Objects.requireNonNull(files, "files"));
		}
	}

	private static void requireSlug(String value, String field) {
		if (value == null || !SAFE_SLUG.matcher(value).matches()) {
			throw new IllegalArgumentException(field + " must be a safe lowercase slug");
		}
	}

	private static void requireSha256(String value, String field) {
		if (value == null || !SHA256.matcher(value).matches()) {
			throw new IllegalArgumentException(field + " must be a lowercase SHA-256 digest");
		}
	}

	static final class VerificationException extends IOException {

		private VerificationException(String diagnostic) {
			super(diagnostic);
		}
	}
}
