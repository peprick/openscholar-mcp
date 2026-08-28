package com.openscholar.search.internal.persistence;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.ObjectWriter;
import tools.jackson.databind.SerializationFeature;

final class ProviderQualityEvidenceWriter {

	static final String MANIFEST_FILENAME = "manifest.json";
	private static final int MAX_ARTIFACT_FILES = 32;
	private static final Pattern EVIDENCE_ID = Pattern.compile("^[a-z0-9][a-z0-9-]{2,127}$");
	private static final Pattern ARTIFACT_FILENAME =
			Pattern.compile("^[a-z0-9][a-z0-9-]{0,79}\\.json$");
	private static final Set<PosixFilePermission> DIRECTORY_PERMISSIONS =
			PosixFilePermissions.fromString("rwx------");
	private static final Set<PosixFilePermission> FILE_PERMISSIONS =
			PosixFilePermissions.fromString("rw-------");

	private final ObjectWriter canonicalWriter;
	private final Path outputRoot;
	private final long maximumTotalBytes;

	ProviderQualityEvidenceWriter(
			ObjectMapper objectMapper, Path outputRoot, long maximumTotalBytes) {
		this.canonicalWriter = Objects.requireNonNull(objectMapper, "objectMapper")
				.writer()
				.with(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
				.without(SerializationFeature.INDENT_OUTPUT);
		this.outputRoot = Objects.requireNonNull(outputRoot, "outputRoot")
				.toAbsolutePath().normalize();
		if (!this.outputRoot.endsWith(Path.of("backend", "target", "provider-quality"))) {
			throw new IllegalArgumentException(
					"outputRoot must end with backend/target/provider-quality");
		}
		if (maximumTotalBytes < 1) {
			throw new IllegalArgumentException("maximumTotalBytes must be positive");
		}
		this.maximumTotalBytes = maximumTotalBytes;
	}

	static ProviderQualityEvidenceWriter forRepository(
			ObjectMapper objectMapper, Path repositoryRoot, long maximumTotalBytes) {
		Path root = Objects.requireNonNull(repositoryRoot, "repositoryRoot")
				.toAbsolutePath().normalize();
		return new ProviderQualityEvidenceWriter(
				objectMapper,
				root.resolve("backend").resolve("target").resolve("provider-quality"),
				maximumTotalBytes);
	}

	WriteResult write(String evidenceId, Map<String, ?> artifacts) throws IOException {
		String boundedEvidenceId = validateEvidenceId(evidenceId);
		Map<String, byte[]> serialized = serializeArtifacts(artifacts);
		List<FileDigest> payloadDigests = serialized.entrySet().stream()
				.map(entry -> digest(entry.getKey(), entry.getValue()))
				.toList();
		long payloadBytes = payloadDigests.stream().mapToLong(FileDigest::bytes).sum();
		EvidenceManifest manifest = new EvidenceManifest(
				1, boundedEvidenceId, payloadBytes, payloadDigests);
		byte[] manifestBytes = serialize(manifest);
		FileDigest manifestDigest = digest(MANIFEST_FILENAME, manifestBytes);
		long totalBytes;
		try {
			totalBytes = Math.addExact(payloadBytes, manifestBytes.length);
		}
		catch (ArithmeticException exception) {
			throw new IOException("provider-quality evidence byte count overflowed", exception);
		}
		if (totalBytes > maximumTotalBytes) {
			throw new IOException(
					"provider-quality evidence requires " + totalBytes
							+ " bytes; maximum is " + maximumTotalBytes);
		}

		ensurePrivateOutputRoot();
		Path outputDirectory = outputRoot.resolve(boundedEvidenceId).normalize();
		if (!outputDirectory.getParent().equals(outputRoot)) {
			throw new IllegalArgumentException("evidenceId resolved outside the output root");
		}
		if (Files.exists(outputDirectory, LinkOption.NOFOLLOW_LINKS)) {
			throw new FileAlreadyExistsException(outputDirectory.toString());
		}

		Path stagingDirectory = createPrivateStagingDirectory(boundedEvidenceId);
		try {
			for (Map.Entry<String, byte[]> entry : serialized.entrySet()) {
				writeNewPrivateFile(stagingDirectory.resolve(entry.getKey()), entry.getValue());
			}
			writeNewPrivateFile(stagingDirectory.resolve(MANIFEST_FILENAME), manifestBytes);
			publish(stagingDirectory, outputDirectory);
			return new WriteResult(
					outputDirectory, manifest, manifestDigest, totalBytes);
		}
		catch (IOException | RuntimeException | Error failure) {
			try {
				deletePrivateStagingDirectory(stagingDirectory);
			}
			catch (IOException cleanupFailure) {
				failure.addSuppressed(cleanupFailure);
			}
			throw failure;
		}
	}

	private Map<String, byte[]> serializeArtifacts(Map<String, ?> artifacts) throws IOException {
		Objects.requireNonNull(artifacts, "artifacts");
		if (artifacts.isEmpty() || artifacts.size() > MAX_ARTIFACT_FILES) {
			throw new IllegalArgumentException(
					"artifacts must contain 1 through " + MAX_ARTIFACT_FILES + " files");
		}
		Map<String, ?> sorted = new TreeMap<>(artifacts);
		Map<String, byte[]> result = new LinkedHashMap<>();
		for (Map.Entry<String, ?> entry : sorted.entrySet()) {
			String filename = validateArtifactFilename(entry.getKey());
			if (entry.getValue() == null) {
				throw new IllegalArgumentException(filename + " artifact must not be null");
			}
			result.put(filename, serialize(entry.getValue()));
		}
		return Collections.unmodifiableMap(new LinkedHashMap<>(result));
	}

	private byte[] serialize(Object value) throws IOException {
		byte[] json = canonicalWriter.writeValueAsBytes(value);
		if (json.length > 0 && json[json.length - 1] == '\n') {
			return json;
		}
		byte[] terminated = Arrays.copyOf(json, json.length + 1);
		terminated[terminated.length - 1] = '\n';
		return terminated;
	}

	private void ensurePrivateOutputRoot() throws IOException {
		if (supportsPosix()) {
			Files.createDirectories(
					outputRoot, PosixFilePermissions.asFileAttribute(DIRECTORY_PERMISSIONS));
		}
		else {
			Files.createDirectories(outputRoot);
		}
		if (Files.isSymbolicLink(outputRoot)
				|| !Files.isDirectory(outputRoot, LinkOption.NOFOLLOW_LINKS)) {
			throw new IOException("provider-quality output root must be a real directory");
		}
		if (supportsPosix()) {
			Files.setPosixFilePermissions(outputRoot, DIRECTORY_PERMISSIONS);
		}
	}

	private Path createPrivateStagingDirectory(String evidenceId) throws IOException {
		Path staging;
		if (supportsPosix()) {
			staging = Files.createTempDirectory(
					outputRoot,
					'.' + evidenceId + ".staging-",
					PosixFilePermissions.asFileAttribute(DIRECTORY_PERMISSIONS));
			Files.setPosixFilePermissions(staging, DIRECTORY_PERMISSIONS);
		}
		else {
			staging = Files.createTempDirectory(outputRoot, '.' + evidenceId + ".staging-");
		}
		return staging;
	}

	private static void publish(Path stagingDirectory, Path outputDirectory) throws IOException {
		try {
			Files.move(stagingDirectory, outputDirectory, StandardCopyOption.ATOMIC_MOVE);
		}
		catch (AtomicMoveNotSupportedException exception) {
			Files.move(stagingDirectory, outputDirectory);
		}
	}

	private void writeNewPrivateFile(Path path, byte[] content) throws IOException {
		if (supportsPosix()) {
			Files.createFile(path, PosixFilePermissions.asFileAttribute(FILE_PERMISSIONS));
			Files.setPosixFilePermissions(path, FILE_PERMISSIONS);
		}
		else {
			Files.createFile(path);
		}
		Files.write(path, content, StandardOpenOption.WRITE);
	}

	private void deletePrivateStagingDirectory(Path stagingDirectory) throws IOException {
		Path normalized = stagingDirectory.toAbsolutePath().normalize();
		if (!normalized.getParent().equals(outputRoot)
				|| !normalized.getFileName().toString().startsWith(".")
				|| !normalized.getFileName().toString().contains(".staging-")) {
			throw new IOException("refusing to clean an unexpected staging directory");
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

	private boolean supportsPosix() {
		return outputRoot.getFileSystem().supportedFileAttributeViews().contains("posix");
	}

	private static String validateEvidenceId(String value) {
		if (value == null || !EVIDENCE_ID.matcher(value).matches()) {
			throw new IllegalArgumentException("evidenceId must be a safe lowercase slug");
		}
		return value;
	}

	private static String validateArtifactFilename(String value) {
		if (value == null || !ARTIFACT_FILENAME.matcher(value).matches()
				|| MANIFEST_FILENAME.equals(value)) {
			throw new IllegalArgumentException(
					"artifact filename must be a safe JSON basename other than " + MANIFEST_FILENAME);
		}
		return value;
	}

	private static FileDigest digest(String filename, byte[] content) {
		try {
			return new FileDigest(
					filename,
					content.length,
					HexFormat.of().formatHex(
							MessageDigest.getInstance("SHA-256").digest(content)));
		}
		catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException("SHA-256 is unavailable", exception);
		}
	}

	record FileDigest(String filename, long bytes, String sha256) {
	}

	record EvidenceManifest(
			int schemaVersion,
			String evidenceId,
			long payloadBytes,
			List<FileDigest> files) {

		EvidenceManifest {
			files = List.copyOf(Objects.requireNonNull(files, "files"));
		}
	}

	record WriteResult(
			Path directory,
			EvidenceManifest manifest,
			FileDigest manifestFile,
			long totalBytes) {
	}
}
