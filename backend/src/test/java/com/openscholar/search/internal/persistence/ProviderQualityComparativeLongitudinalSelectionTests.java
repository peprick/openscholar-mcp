package com.openscholar.search.internal.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

class ProviderQualityComparativeLongitudinalSelectionTests {

	private static final ObjectMapper OBJECT_MAPPER = JsonMapper.builder().build();

	@TempDir
	private Path temporaryDirectory;

	@Test
	void loadsTwoThroughSixteenUniqueResolvedExternalDirectoriesWithoutMutation()
			throws Exception {
		Path repository = directory("repository");
		Path first = directory("run-a");
		Path second = directory("run-b");
		Path selectionFile = selectionFile("selection.json", List.of(first, second));
		byte[] selectionBefore = Files.readAllBytes(selectionFile);
		List<String> firstBefore = entries(first);
		List<String> secondBefore = entries(second);

		ProviderQualityComparativeLongitudinalSelection.Selection selection =
				ProviderQualityComparativeLongitudinalSelection.load(
						OBJECT_MAPPER, repository, selectionFile);

		assertThat(selection.runDirectories())
				.containsExactly(first.toRealPath(), second.toRealPath());
		assertThat(selection.runDirectories()).isUnmodifiable();
		assertThat(Files.readAllBytes(selectionFile)).isEqualTo(selectionBefore);
		assertThat(entries(first)).isEqualTo(firstBefore);
		assertThat(entries(second)).isEqualTo(secondBefore);

		List<Path> maximum = new ArrayList<>();
		for (int index = 0;
				index < ProviderQualityComparativeLongitudinalSelection.MAXIMUM_RUNS;
				index++) {
			maximum.add(directory("maximum-" + index));
		}
		Path maximumFile = selectionFile("maximum.json", maximum);
		assertThat(ProviderQualityComparativeLongitudinalSelection.load(
				OBJECT_MAPPER, repository, maximumFile).runDirectories())
				.hasSize(ProviderQualityComparativeLongitudinalSelection.MAXIMUM_RUNS);
	}

	@Test
	void rejectsMissingUnknownWrongVersionProtocolAndArrayShape() throws Exception {
		Path repository = directory("repository");
		Path first = directory("run-a");
		Path second = directory("run-b");
		Map<String, Object> valid = document(List.of(first, second));

		Map<String, Object> missing = new LinkedHashMap<>(valid);
		missing.remove("protocolId");
		assertFailure(repository, rawFile("missing.json", json(missing)),
				"LONGITUDINAL_SELECTION_SCHEMA_INVALID");

		Map<String, Object> unknown = new LinkedHashMap<>(valid);
		unknown.put("unknown", true);
		assertFailure(repository, rawFile("unknown.json", json(unknown)),
				"LONGITUDINAL_SELECTION_SCHEMA_INVALID");

		Map<String, Object> version = new LinkedHashMap<>(valid);
		version.put("schemaVersion", 2);
		assertFailure(repository, rawFile("version.json", json(version)),
				"LONGITUDINAL_SELECTION_SCHEMA_INVALID");

		Map<String, Object> protocol = new LinkedHashMap<>(valid);
		protocol.put("protocolId", "provider-quality-comparative-longitudinal-selection-v2");
		assertFailure(repository, rawFile("protocol.json", json(protocol)),
				"LONGITUDINAL_SELECTION_SCHEMA_INVALID");

		Map<String, Object> wrongArray = new LinkedHashMap<>(valid);
		wrongArray.put("runSealDirectories", "not-an-array");
		assertFailure(repository, rawFile("array.json", json(wrongArray)),
				"LONGITUDINAL_SELECTION_RUN_COUNT_INVALID");
	}

	@Test
	void rejectsMalformedDuplicateAndTrailingJson() throws Exception {
		Path repository = directory("repository");
		Path first = directory("run-a");
		Path second = directory("run-b");
		String paths = OBJECT_MAPPER.writeValueAsString(
				List.of(first.toString(), second.toString()));

		assertFailure(repository, rawFile("malformed.json", "{"),
				"LONGITUDINAL_SELECTION_JSON_INVALID");
		assertFailure(repository, rawFile(
				"duplicate.json",
				"{\"schemaVersion\":1,\"schemaVersion\":1,"
						+ "\"protocolId\":\""
						+ ProviderQualityComparativeLongitudinalSelection.PROTOCOL_ID
						+ "\",\"runSealDirectories\":" + paths + "}"),
				"LONGITUDINAL_SELECTION_JSON_INVALID");
		assertFailure(repository, rawFile(
				"trailing.json", json(document(List.of(first, second))) + "{}"),
				"LONGITUDINAL_SELECTION_JSON_INVALID");
	}

	@Test
	void enforcesRunCountBoundsAndStringEntries() throws Exception {
		Path repository = directory("repository");
		Path first = directory("run-a");

		assertFailure(repository, selectionFile("one.json", List.of(first)),
				"LONGITUDINAL_SELECTION_RUN_COUNT_INVALID");

		List<Path> excessive = new ArrayList<>();
		for (int index = 0;
				index <= ProviderQualityComparativeLongitudinalSelection.MAXIMUM_RUNS;
				index++) {
			excessive.add(directory("excessive-" + index));
		}
		assertFailure(repository, selectionFile("excessive.json", excessive),
				"LONGITUDINAL_SELECTION_RUN_COUNT_INVALID");

		Map<String, Object> nonString = document(List.of(first, directory("run-b")));
		nonString.put("runSealDirectories", List.of(first.toString(), 42));
		assertFailure(repository, rawFile("non-string.json", json(nonString)),
				"LONGITUDINAL_SELECTION_RUN_PATH_INVALID");
	}

	@Test
	void requiresAnAbsolutePrivateBoundedRealSelectionFileOutsideRepository()
			throws Exception {
		Path repository = directory("repository");
		Path first = directory("run-a");
		Path second = directory("run-b");
		Path valid = selectionFile("valid.json", List.of(first, second));

		assertFailure(repository, Path.of("relative-selection.json"),
				"LONGITUDINAL_SELECTION_FILE_NOT_ABSOLUTE");
		assertFailure(repository, temporaryDirectory.resolve("missing.json"),
				"LONGITUDINAL_SELECTION_FILE_INVALID");

		Path insideRepository = repository.resolve("selection.json");
		writePrivate(insideRepository, json(document(List.of(first, second))));
		assertFailure(repository, insideRepository,
				"LONGITUDINAL_SELECTION_FILE_PATH_INVALID");

		Path linked = temporaryDirectory.resolve("selection-link.json");
		Files.createSymbolicLink(linked, valid);
		assertFailure(repository, linked, "LONGITUDINAL_SELECTION_FILE_INVALID");

		Path oversized = temporaryDirectory.resolve("oversized.json");
		writePrivate(
				oversized,
				"x".repeat(
						ProviderQualityComparativeLongitudinalSelection.MAXIMUM_SELECTION_BYTES + 1));
		assertFailure(repository, oversized, "LONGITUDINAL_SELECTION_TOO_LARGE");
	}

	@Test
	void rejectsNonPrivateSelectionFilePermissionsOnPosix() throws Exception {
		assumeTrue(temporaryDirectory.getFileSystem()
				.supportedFileAttributeViews().contains("posix"));
		Path repository = directory("repository");
		Path first = directory("run-a");
		Path second = directory("run-b");
		Path selection = selectionFile("selection.json", List.of(first, second));
		Files.setPosixFilePermissions(selection, PosixFilePermissions.fromString("rw-r--r--"));

		assertFailure(repository, selection,
				"LONGITUDINAL_SELECTION_PERMISSIONS_NOT_PRIVATE");
	}

	@Test
	void rejectsRelativeMissingDirectSymlinkAndRepositoryRunPaths() throws Exception {
		Path repository = directory("repository");
		Path insideRepository = Files.createDirectory(repository.resolve("inside"));
		Path external = directory("external");
		Path directLink = temporaryDirectory.resolve("direct-run-link");
		Files.createSymbolicLink(directLink, external);

		assertRunPathFailure(repository, List.of("relative-run", external.toString()));
		assertRunPathFailure(repository, List.of(
				temporaryDirectory.resolve("missing-run").toString(), external.toString()));
		assertRunPathFailure(repository, List.of(directLink.toString(), external.toString()));
		assertRunPathFailure(repository, List.of(
				insideRepository.toString(), external.toString()));
		assertRunPathFailure(repository, List.of(
				temporaryDirectory.toString(), external.toString()));
	}

	@Test
	void rejectsResolvedDuplicatesAncestorPairsAndRepositoryAliases() throws Exception {
		Path repository = directory("repository");
		Path insideRepository = Files.createDirectory(repository.resolve("inside"));
		Path first = directory("run-a");
		Path second = directory("run-b");
		Path firstAliasParent = temporaryDirectory.resolve("alias-parent");
		Files.createSymbolicLink(firstAliasParent, temporaryDirectory);
		Path repositoryAlias = temporaryDirectory.resolve("repository-alias");
		Files.createSymbolicLink(repositoryAlias, repository);

		assertOverlapFailure(repository, List.of(first, first));
		assertOverlapFailure(repository, List.of(first, firstAliasParent.resolve("run-a")));

		Path parent = directory("parent-run");
		Path child = Files.createDirectory(parent.resolve("child-run"));
		assertOverlapFailure(repository, List.of(parent, child));

		Path selection = rawFile(
				"repository-alias-selection.json",
				json(documentStrings(List.of(
						repositoryAlias.resolve("inside").toString(), second.toString()))));
		assertFailure(repository, selection,
				"LONGITUDINAL_SELECTION_RUN_PATH_INVALID");
		assertThat(insideRepository).exists();
	}

	private Path directory(String name) throws Exception {
		return Files.createDirectory(temporaryDirectory.resolve(name));
	}

	private Path selectionFile(String name, List<Path> runDirectories) throws Exception {
		return rawFile(name, json(document(runDirectories)));
	}

	private Path rawFile(String name, String contents) throws Exception {
		Path path = temporaryDirectory.resolve(name);
		writePrivate(path, contents);
		return path;
	}

	private static void writePrivate(Path path, String contents) throws Exception {
		Files.writeString(path, contents, StandardCharsets.UTF_8);
		if (path.getFileSystem().supportedFileAttributeViews().contains("posix")) {
			Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-------"));
		}
	}

	private static Map<String, Object> document(List<Path> runDirectories) {
		return documentStrings(runDirectories.stream().map(Path::toString).toList());
	}

	private static Map<String, Object> documentStrings(List<String> runDirectories) {
		Map<String, Object> document = new LinkedHashMap<>();
		document.put("schemaVersion", 1);
		document.put(
				"protocolId", ProviderQualityComparativeLongitudinalSelection.PROTOCOL_ID);
		document.put("runSealDirectories", runDirectories);
		return document;
	}

	private static String json(Map<String, Object> document) throws Exception {
		return OBJECT_MAPPER.writeValueAsString(document) + '\n';
	}

	private void assertRunPathFailure(Path repository, List<String> paths) throws Exception {
		Path selection = rawFile(
				"invalid-run-" + Math.abs(paths.hashCode()) + ".json",
				json(documentStrings(paths)));
		assertFailure(repository, selection, "LONGITUDINAL_SELECTION_RUN_PATH_INVALID");
	}

	private void assertOverlapFailure(Path repository, List<Path> paths) throws Exception {
		Path selection = rawFile(
				"overlap-" + Math.abs(paths.hashCode()) + ".json",
				json(document(paths)));
		assertFailure(repository, selection, "LONGITUDINAL_SELECTION_RUN_PATH_OVERLAP");
	}

	private static void assertFailure(Path repository, Path selection, String diagnostic) {
		assertThatThrownBy(() -> ProviderQualityComparativeLongitudinalSelection.load(
				OBJECT_MAPPER, repository, selection))
				.isInstanceOf(
						ProviderQualityComparativeLongitudinalSelection.SelectionException.class)
				.hasMessage(diagnostic);
	}

	private static List<String> entries(Path directory) throws Exception {
		try (var paths = Files.list(directory)) {
			return paths.map(path -> path.getFileName().toString()).sorted().toList();
		}
	}
}
