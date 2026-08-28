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
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Strict, bounded input boundary for selecting retained comparative run seals.
 * Filesystem paths are invocation inputs only and are never represented by an
 * output-document type in this class.
 */
final class ProviderQualityComparativeLongitudinalSelection {

	static final int MAXIMUM_SELECTION_BYTES = 16 * 1024;
	static final int MINIMUM_RUNS = 2;
	static final int MAXIMUM_RUNS = 16;
	static final String PROTOCOL_ID =
			"provider-quality-comparative-longitudinal-selection-v1";

	private static final Set<String> ROOT_FIELDS = Set.of(
			"schemaVersion", "protocolId", "runSealDirectories");
	private static final Set<PosixFilePermission> PRIVATE_FILE_PERMISSIONS =
			PosixFilePermissions.fromString("rw-------");

	private ProviderQualityComparativeLongitudinalSelection() {
	}

	static Selection load(
			ObjectMapper objectMapper, Path repositoryRoot, Path selectionFile)
			throws IOException {
		Objects.requireNonNull(objectMapper, "objectMapper");
		Path repository = resolveRepository(repositoryRoot);
		Path input = validateSelectionFile(selectionFile, repository);
		byte[] bytes = readStableBounded(input);
		JsonNode root = parseStrict(objectMapper, bytes);
		return new Selection(parseRunDirectories(root, repository));
	}

	private static Path resolveRepository(Path supplied) throws IOException {
		if (supplied == null) {
			throw failure("LONGITUDINAL_SELECTION_REPOSITORY_INVALID");
		}
		try {
			Path repository = supplied.toAbsolutePath().normalize().toRealPath();
			if (!Files.isDirectory(repository, LinkOption.NOFOLLOW_LINKS)) {
				throw failure("LONGITUDINAL_SELECTION_REPOSITORY_INVALID");
			}
			return repository;
		}
		catch (SelectionException exception) {
			throw exception;
		}
		catch (IOException | RuntimeException exception) {
			throw failure("LONGITUDINAL_SELECTION_REPOSITORY_INVALID");
		}
	}

	private static Path validateSelectionFile(Path supplied, Path repository)
			throws IOException {
		if (supplied == null || !supplied.isAbsolute()) {
			throw failure("LONGITUDINAL_SELECTION_FILE_NOT_ABSOLUTE");
		}
		Path normalized;
		try {
			normalized = supplied.toAbsolutePath().normalize();
		}
		catch (RuntimeException exception) {
			throw failure("LONGITUDINAL_SELECTION_FILE_INVALID");
		}
		if (Files.isSymbolicLink(normalized)
				|| !Files.isRegularFile(normalized, LinkOption.NOFOLLOW_LINKS)) {
			throw failure("LONGITUDINAL_SELECTION_FILE_INVALID");
		}
		Path resolved;
		try {
			resolved = normalized.toRealPath();
		}
		catch (IOException | RuntimeException exception) {
			throw failure("LONGITUDINAL_SELECTION_FILE_INVALID");
		}
		if (resolved.startsWith(repository) || repository.startsWith(resolved)) {
			throw failure("LONGITUDINAL_SELECTION_FILE_PATH_INVALID");
		}
		requirePrivateFilePermissions(resolved);
		return resolved;
	}

	private static byte[] readStableBounded(Path path) throws IOException {
		BasicFileAttributes before = readAttributes(path);
		if (!before.isRegularFile() || before.size() < 1) {
			throw failure("LONGITUDINAL_SELECTION_FILE_INVALID");
		}
		if (before.size() > MAXIMUM_SELECTION_BYTES) {
			throw failure("LONGITUDINAL_SELECTION_TOO_LARGE");
		}
		byte[] bytes;
		try (SeekableByteChannel channel = Files.newByteChannel(
				path, Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS));
				InputStream input = Channels.newInputStream(channel)) {
			bytes = input.readNBytes(MAXIMUM_SELECTION_BYTES + 1);
			if (bytes.length > MAXIMUM_SELECTION_BYTES) {
				throw failure("LONGITUDINAL_SELECTION_TOO_LARGE");
			}
			if (channel.size() != bytes.length) {
				throw failure("LONGITUDINAL_SELECTION_FILE_CHANGED");
			}
		}
		catch (SelectionException exception) {
			throw exception;
		}
		catch (IOException | SecurityException exception) {
			throw failure("LONGITUDINAL_SELECTION_FILE_UNREADABLE");
		}
		BasicFileAttributes after = readAttributes(path);
		if (before.size() != bytes.length
				|| after.size() != bytes.length
				|| !Objects.equals(before.fileKey(), after.fileKey())) {
			throw failure("LONGITUDINAL_SELECTION_FILE_CHANGED");
		}
		return bytes;
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
				throw failure("LONGITUDINAL_SELECTION_JSON_INVALID");
			}
			return root;
		}
		catch (SelectionException exception) {
			throw exception;
		}
		catch (RuntimeException exception) {
			throw failure("LONGITUDINAL_SELECTION_JSON_INVALID");
		}
	}

	private static List<Path> parseRunDirectories(JsonNode root, Path repository)
			throws IOException {
		if (!hasExactFields(root, ROOT_FIELDS)
				|| root.get("schemaVersion") == null
				|| !root.get("schemaVersion").isInt()
				|| root.get("schemaVersion").asInt() != 1
				|| root.get("protocolId") == null
				|| !root.get("protocolId").isString()
				|| !PROTOCOL_ID.equals(root.get("protocolId").asString())) {
			throw failure("LONGITUDINAL_SELECTION_SCHEMA_INVALID");
		}
		JsonNode runDirectories = root.get("runSealDirectories");
		if (runDirectories == null
				|| !runDirectories.isArray()
				|| runDirectories.size() < MINIMUM_RUNS
				|| runDirectories.size() > MAXIMUM_RUNS) {
			throw failure("LONGITUDINAL_SELECTION_RUN_COUNT_INVALID");
		}

		List<Path> resolved = new ArrayList<>(runDirectories.size());
		for (JsonNode entry : runDirectories) {
			if (entry == null || !entry.isString()) {
				throw failure("LONGITUDINAL_SELECTION_RUN_PATH_INVALID");
			}
			resolved.add(resolveRunDirectory(entry.asString(), repository));
		}
		validateDisjoint(resolved);
		return List.copyOf(resolved);
	}

	private static Path resolveRunDirectory(String value, Path repository)
			throws IOException {
		Path supplied;
		try {
			supplied = Path.of(value);
		}
		catch (RuntimeException exception) {
			throw failure("LONGITUDINAL_SELECTION_RUN_PATH_INVALID");
		}
		if (!supplied.isAbsolute()) {
			throw failure("LONGITUDINAL_SELECTION_RUN_PATH_INVALID");
		}
		Path normalized;
		try {
			normalized = supplied.toAbsolutePath().normalize();
		}
		catch (RuntimeException exception) {
			throw failure("LONGITUDINAL_SELECTION_RUN_PATH_INVALID");
		}
		if (Files.isSymbolicLink(normalized)
				|| !Files.isDirectory(normalized, LinkOption.NOFOLLOW_LINKS)) {
			throw failure("LONGITUDINAL_SELECTION_RUN_PATH_INVALID");
		}
		Path resolved;
		try {
			resolved = normalized.toRealPath();
		}
		catch (IOException | RuntimeException exception) {
			throw failure("LONGITUDINAL_SELECTION_RUN_PATH_INVALID");
		}
		if (resolved.startsWith(repository) || repository.startsWith(resolved)) {
			throw failure("LONGITUDINAL_SELECTION_RUN_PATH_INVALID");
		}
		return resolved;
	}

	private static void validateDisjoint(List<Path> paths) throws IOException {
		Set<Path> unique = new LinkedHashSet<>();
		for (Path path : paths) {
			if (!unique.add(path)) {
				throw failure("LONGITUDINAL_SELECTION_RUN_PATH_OVERLAP");
			}
		}
		for (int left = 0; left < paths.size(); left++) {
			for (int right = left + 1; right < paths.size(); right++) {
				Path first = paths.get(left);
				Path second = paths.get(right);
				if (first.startsWith(second) || second.startsWith(first)) {
					throw failure("LONGITUDINAL_SELECTION_RUN_PATH_OVERLAP");
				}
			}
		}
	}

	private static BasicFileAttributes readAttributes(Path path) throws IOException {
		try {
			return Files.readAttributes(
					path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
		}
		catch (IOException | SecurityException exception) {
			throw failure("LONGITUDINAL_SELECTION_FILE_UNREADABLE");
		}
	}

	private static void requirePrivateFilePermissions(Path path) throws IOException {
		if (!path.getFileSystem().supportedFileAttributeViews().contains("posix")) {
			return;
		}
		Set<PosixFilePermission> permissions;
		try {
			permissions = Files.getPosixFilePermissions(path, LinkOption.NOFOLLOW_LINKS);
		}
		catch (IOException | SecurityException exception) {
			throw failure("LONGITUDINAL_SELECTION_FILE_UNREADABLE");
		}
		if (!permissions.equals(PRIVATE_FILE_PERMISSIONS)) {
			throw failure("LONGITUDINAL_SELECTION_PERMISSIONS_NOT_PRIVATE");
		}
	}

	private static boolean hasExactFields(JsonNode node, Set<String> expected) {
		return node != null
				&& node.isObject()
				&& new LinkedHashSet<>(node.propertyNames()).equals(expected);
	}

	private static SelectionException failure(String diagnostic) {
		return new SelectionException(diagnostic);
	}

	record Selection(List<Path> runDirectories) {

		Selection {
			runDirectories = List.copyOf(Objects.requireNonNull(runDirectories, "runDirectories"));
		}
	}

	static final class SelectionException extends IOException {

		private SelectionException(String diagnostic) {
			super(diagnostic);
		}
	}
}
