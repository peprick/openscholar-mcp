package com.openscholar.search.internal.persistence;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.channels.Channels;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

import com.openscholar.search.internal.persistence.ProviderQualityComparativeLongitudinalComparison.Comparison;
import tools.jackson.databind.ObjectMapper;

/**
 * Fail-closed local custody handoff for one already verified longitudinal report.
 * This boundary copies only the closed two-file bundle and never derives expectations
 * from the retained bytes. It assumes operator-controlled, non-concurrently-mutated
 * paths, exposes only stable path-free failure diagnostics, and supplies integrity
 * linkage rather than authentication or retention policy.
 */
final class ProviderQualityComparativeLongitudinalReportPromotion {

	private static final String MANIFEST_FILENAME =
			ProviderQualityEvidenceWriter.MANIFEST_FILENAME;
	private static final String REPORT_FILENAME =
			ProviderQualityComparativeLongitudinalComparison.REPORT_FILENAME;
	private static final String STAGING_PREFIX =
			'.' + ProviderQualityComparativeLongitudinalComparison.PROTOCOL_ID + ".staging-";
	private static final Set<PosixFilePermission> DIRECTORY_PERMISSIONS =
			PosixFilePermissions.fromString("rwx------");
	private static final Set<PosixFilePermission> FILE_PERMISSIONS =
			PosixFilePermissions.fromString("rw-------");
	private static final Pattern STABLE_DIAGNOSTIC = Pattern.compile(
			"^LONGITUDINAL_REPORT(?:_PROMOTION)?_[A-Z0-9_]+$");

	private ProviderQualityComparativeLongitudinalReportPromotion() {
	}

	static Path validateExternalRoot(
			Path repositoryRoot,
			Path externalRoot,
			Path sourceDirectory,
			List<Path> selectedRunDirectories) throws IOException {
		try {
			return validateExternalRootInternal(
					repositoryRoot,
					externalRoot,
					sourceDirectory,
					selectedRunDirectories);
		}
		catch (IOException | RuntimeException exception) {
			throw sanitizedFailure(
					exception, "LONGITUDINAL_REPORT_PROMOTION_INPUT_INVALID");
		}
	}

	private static Path validateExternalRootInternal(
			Path repositoryRoot,
			Path externalRoot,
			Path sourceDirectory,
			List<Path> selectedRunDirectories) throws IOException {
		Path repository = realDirectory(
				Objects.requireNonNull(repositoryRoot, "repositoryRoot"),
				"LONGITUDINAL_REPORT_PROMOTION_REPOSITORY_INVALID",
				false);
		Path root = realDirectory(
				Objects.requireNonNull(externalRoot, "externalRoot"),
				"LONGITUDINAL_REPORT_PROMOTION_ROOT_INVALID",
				true);
		requireSeparated(
				root, repository, "LONGITUDINAL_REPORT_PROMOTION_ROOT_REPOSITORY_OVERLAP");
		requirePrivatePermissions(root, true);

		List<Path> runs = List.copyOf(Objects.requireNonNull(
				selectedRunDirectories, "selectedRunDirectories"));
		for (Path suppliedRun : runs) {
			Path run = realDirectory(
					Objects.requireNonNull(suppliedRun, "selectedRunDirectory"),
					"LONGITUDINAL_REPORT_PROMOTION_RUN_INVALID",
					false);
			requireSeparated(
					root, run, "LONGITUDINAL_REPORT_PROMOTION_ROOT_RUN_OVERLAP");
		}

		if (sourceDirectory != null) {
			Path source = realDirectory(
					sourceDirectory,
					"LONGITUDINAL_REPORT_PROMOTION_SOURCE_INVALID",
					true);
			requireSeparated(
					root, source, "LONGITUDINAL_REPORT_PROMOTION_ROOT_SOURCE_OVERLAP");
			for (Path suppliedRun : runs) {
				Path run = suppliedRun.toRealPath();
				requireSeparated(
						source, run, "LONGITUDINAL_REPORT_PROMOTION_SOURCE_RUN_OVERLAP");
			}
		}
		return root;
	}

	static ProviderQualityComparativeLongitudinalReportBundle promoteAndVerify(
			ObjectMapper objectMapper,
			Path repositoryRoot,
			Path externalRoot,
			Path sourceDirectory,
			Comparison expected,
			List<Path> selectedRunDirectories) throws IOException {
		return promoteAndVerify(
				objectMapper,
				repositoryRoot,
				externalRoot,
				sourceDirectory,
				expected,
				selectedRunDirectories,
				Files::move);
	}

	static ProviderQualityComparativeLongitudinalReportBundle promoteAndVerify(
			ObjectMapper objectMapper,
			Path repositoryRoot,
			Path externalRoot,
			Path sourceDirectory,
			Comparison expected,
			List<Path> selectedRunDirectories,
			AtomicMover mover) throws IOException {
		try {
			return promoteAndVerifyInternal(
					objectMapper,
					repositoryRoot,
					externalRoot,
					sourceDirectory,
					expected,
					selectedRunDirectories,
					mover);
		}
		catch (IOException | RuntimeException exception) {
			throw sanitizedFailure(
					exception, "LONGITUDINAL_REPORT_PROMOTION_FAILED");
		}
	}

	private static ProviderQualityComparativeLongitudinalReportBundle
			promoteAndVerifyInternal(
					ObjectMapper objectMapper,
					Path repositoryRoot,
					Path externalRoot,
					Path sourceDirectory,
					Comparison expected,
					List<Path> selectedRunDirectories,
					AtomicMover mover) throws IOException {
		ObjectMapper mapper = Objects.requireNonNull(objectMapper, "objectMapper");
		Comparison comparison = Objects.requireNonNull(expected, "expected");
		AtomicMover atomicMover = Objects.requireNonNull(mover, "mover");
		Path root = validateExternalRoot(
				repositoryRoot,
				externalRoot,
				sourceDirectory,
				selectedRunDirectories);
		ProviderQualityComparativeLongitudinalReportBundle source =
				ProviderQualityComparativeLongitudinalReportBundle.verifyExact(
						mapper, sourceDirectory, comparison);
		Path destination = root.resolve(comparison.comparisonId()).normalize();
		if (!root.equals(destination.getParent())) {
			throw failure("LONGITUDINAL_REPORT_PROMOTION_DESTINATION_INVALID");
		}
		if (entryExists(destination)) {
			return ProviderQualityComparativeLongitudinalReportBundle.verifyExact(
					mapper, destination, comparison);
		}

		Path staging = createPrivateStagingDirectory(root);
		try {
			Path stagedReport = staging.resolve(comparison.comparisonId()).normalize();
			if (!staging.equals(stagedReport.getParent())) {
				throw failure("LONGITUDINAL_REPORT_PROMOTION_STAGING_PATH_INVALID");
			}
			createPrivateDirectory(stagedReport);
			copyPrivateFile(
					source.sourceDirectory().resolve(MANIFEST_FILENAME),
					stagedReport.resolve(MANIFEST_FILENAME),
					ProviderQualityComparativeLongitudinalReportBundle.MAXIMUM_MANIFEST_BYTES);
			copyPrivateFile(
					source.sourceDirectory().resolve(REPORT_FILENAME),
					stagedReport.resolve(REPORT_FILENAME),
					ProviderQualityComparativeLongitudinalReportBundle.MAXIMUM_REPORT_BYTES);
			ProviderQualityComparativeLongitudinalReportBundle.verifyExact(
					mapper, stagedReport, comparison);

			if (entryExists(destination)) {
				ProviderQualityComparativeLongitudinalReportBundle existing =
						ProviderQualityComparativeLongitudinalReportBundle.verifyExact(
								mapper, destination, comparison);
				deleteStagingDirectory(root, staging);
				return existing;
			}
			try {
				atomicMover.move(stagedReport, destination, StandardCopyOption.ATOMIC_MOVE);
			}
			catch (AtomicMoveNotSupportedException exception) {
				throw failure("LONGITUDINAL_REPORT_PROMOTION_ATOMIC_MOVE_UNSUPPORTED");
			}
			catch (IOException exception) {
				if (entryExists(destination)) {
					try {
						ProviderQualityComparativeLongitudinalReportBundle existing =
								ProviderQualityComparativeLongitudinalReportBundle.verifyExact(
										mapper, destination, comparison);
						deleteStagingDirectory(root, staging);
						return existing;
					}
					catch (IOException verificationFailure) {
						throw verificationFailure;
					}
				}
				throw failure("LONGITUDINAL_REPORT_PROMOTION_ATOMIC_MOVE_FAILED");
			}
			deleteStagingDirectory(root, staging);
			return ProviderQualityComparativeLongitudinalReportBundle.verifyExact(
					mapper, destination, comparison);
		}
		catch (IOException | RuntimeException failure) {
			try {
				deleteStagingDirectory(root, staging);
			}
			catch (IOException | RuntimeException cleanupFailure) {
				throw failure("LONGITUDINAL_REPORT_PROMOTION_CLEANUP_FAILED");
			}
			throw failure;
		}
		catch (Error failure) {
			try {
				deleteStagingDirectory(root, staging);
			}
			catch (IOException | RuntimeException ignored) {
				// Preserve fatal JVM errors; ordinary failures use the sanitized branch above.
			}
			throw failure;
		}
	}

	private static boolean entryExists(Path path) throws IOException {
		return entryExists(
				path, "LONGITUDINAL_REPORT_PROMOTION_DESTINATION_STATE_INVALID");
	}

	private static boolean entryExists(Path path, String diagnostic) throws IOException {
		try {
			Files.readAttributes(
					path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
			return true;
		}
		catch (NoSuchFileException exception) {
			return false;
		}
		catch (IOException | RuntimeException exception) {
			throw failure(diagnostic);
		}
	}

	private static Path realDirectory(
			Path supplied, String diagnostic, boolean requireAbsolute) throws IOException {
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
		catch (IOException exception) {
			throw failure(diagnostic);
		}
	}

	private static void requireSeparated(Path first, Path second, String diagnostic)
			throws IOException {
		if (first.startsWith(second) || second.startsWith(first)) {
			throw failure(diagnostic);
		}
	}

	private static Path createPrivateStagingDirectory(Path root) throws IOException {
		Path staging = null;
		try {
			if (supportsPosix(root)) {
				staging = Files.createTempDirectory(
						root,
						STAGING_PREFIX,
						PosixFilePermissions.asFileAttribute(DIRECTORY_PERMISSIONS));
				requirePrivatePermissions(staging, true);
			}
			else {
				staging = Files.createTempDirectory(root, STAGING_PREFIX);
			}
			return staging;
		}
		catch (IOException | RuntimeException exception) {
			if (staging != null) {
				try {
					Files.deleteIfExists(staging);
				}
				catch (IOException | RuntimeException cleanupFailure) {
					throw failure("LONGITUDINAL_REPORT_PROMOTION_CLEANUP_FAILED");
				}
			}
			throw failure("LONGITUDINAL_REPORT_PROMOTION_STAGING_CREATE_FAILED");
		}
	}

	private static void createPrivateDirectory(Path directory) throws IOException {
		Path parent = Objects.requireNonNull(directory.getParent(), "directory.parent");
		if (supportsPosix(parent)) {
			Files.createDirectory(
					directory,
					PosixFilePermissions.asFileAttribute(DIRECTORY_PERMISSIONS));
			Files.setPosixFilePermissions(directory, DIRECTORY_PERMISSIONS);
		}
		else {
			Files.createDirectory(directory);
		}
	}

	private static void copyPrivateFile(Path source, Path target, long maximumBytes)
			throws IOException {
		if (Files.isSymbolicLink(source)
				|| !Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS)) {
			throw failure("LONGITUDINAL_REPORT_PROMOTION_SOURCE_FILE_INVALID");
		}
		BasicFileAttributes before = attributes(source);
		if (!before.isRegularFile() || before.size() < 1 || before.size() > maximumBytes) {
			throw failure("LONGITUDINAL_REPORT_PROMOTION_SOURCE_FILE_INVALID");
		}
		createPrivateFile(target);
		long copied = 0;
		try (SeekableByteChannel sourceChannel = Files.newByteChannel(
				source, Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS));
				InputStream input = Channels.newInputStream(sourceChannel);
				OutputStream output = Files.newOutputStream(target, StandardOpenOption.WRITE)) {
			byte[] buffer = new byte[16 * 1024];
			int read;
			while ((read = input.read(buffer)) != -1) {
				if (read > maximumBytes - copied) {
					throw failure("LONGITUDINAL_REPORT_PROMOTION_SOURCE_FILE_INVALID");
				}
				output.write(buffer, 0, read);
				copied += read;
			}
			if (sourceChannel.size() != copied) {
				throw failure("LONGITUDINAL_REPORT_PROMOTION_SOURCE_CHANGED");
			}
		}
		BasicFileAttributes after = attributes(source);
		if (copied != before.size()
				|| copied != after.size()
				|| !Objects.equals(before.fileKey(), after.fileKey())) {
			throw failure("LONGITUDINAL_REPORT_PROMOTION_SOURCE_CHANGED");
		}
	}

	private static void createPrivateFile(Path file) throws IOException {
		Path parent = Objects.requireNonNull(file.getParent(), "file.parent");
		if (supportsPosix(parent)) {
			Files.createFile(file, PosixFilePermissions.asFileAttribute(FILE_PERMISSIONS));
			Files.setPosixFilePermissions(file, FILE_PERMISSIONS);
		}
		else {
			Files.createFile(file);
		}
	}

	private static BasicFileAttributes attributes(Path path) throws IOException {
		return Files.readAttributes(
				path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
	}

	private static void deleteStagingDirectory(Path root, Path staging) throws IOException {
		Path normalized = staging.toAbsolutePath().normalize();
		Path name = normalized.getFileName();
		if (!root.equals(normalized.getParent())
				|| name == null
				|| !name.toString().startsWith(STAGING_PREFIX)) {
			throw failure("LONGITUDINAL_REPORT_PROMOTION_STAGING_PATH_INVALID");
		}
		if (!entryExists(
				normalized,
				"LONGITUDINAL_REPORT_PROMOTION_STAGING_STATE_INVALID")) {
			return;
		}
		try (var paths = Files.walk(normalized)) {
			for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
				Files.deleteIfExists(path);
			}
		}
	}

	private static void requirePrivatePermissions(Path path, boolean directory)
			throws IOException {
		if (!supportsPosix(path)) {
			return;
		}
		Set<PosixFilePermission> expected = directory
				? DIRECTORY_PERMISSIONS
				: FILE_PERMISSIONS;
		if (!Files.getPosixFilePermissions(path, LinkOption.NOFOLLOW_LINKS).equals(expected)) {
			throw failure("LONGITUDINAL_REPORT_PROMOTION_ROOT_PERMISSIONS_NOT_PRIVATE");
		}
	}

	private static boolean supportsPosix(Path path) throws IOException {
		return Files.getFileStore(path).supportsFileAttributeView("posix");
	}

	private static IOException failure(String diagnostic) {
		return new IOException(diagnostic);
	}

	private static IOException sanitizedFailure(Throwable failure, String fallback) {
		String message = failure.getMessage();
		return new IOException(message != null && STABLE_DIAGNOSTIC.matcher(message).matches()
				? message
				: fallback);
	}

	@FunctionalInterface
	interface AtomicMover {

		Path move(Path source, Path target, java.nio.file.CopyOption... options)
				throws IOException;
	}
}
