package com.openscholar.search.internal.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import com.openscholar.search.internal.persistence.ProviderQualityComparativeLongitudinalComparison.Comparison;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

class ProviderQualityComparativeLongitudinalReportPromotionTests {

	private static final JsonMapper OBJECT_MAPPER = JsonMapper.builder().build();

	@TempDir
	private Path temporaryDirectory;

	@Test
	void atomicallyPromotesExactPrivateBytesWithoutMutatingTheSource() throws Exception {
		Fixture fixture = fixture("happy");
		Path root = privateDirectory("retention-root");
		Map<String, String> sourceBefore = snapshot(fixture.report().sourceDirectory());

		ProviderQualityComparativeLongitudinalReportBundle promoted =
				ProviderQualityComparativeLongitudinalReportPromotion.promoteAndVerify(
						OBJECT_MAPPER,
						fixture.repository(),
						root,
						fixture.report().sourceDirectory(),
						fixture.comparison(),
						fixture.runDirectories());

		assertThat(promoted.sourceDirectory())
				.isEqualTo(root.toRealPath().resolve(fixture.comparison().comparisonId()));
		assertThat(promoted.comparisonId()).isEqualTo(fixture.comparison().comparisonId());
		assertThat(promoted.manifestSha256())
				.isEqualTo(fixture.report().manifestSha256());
		assertThat(snapshot(promoted.sourceDirectory())).isEqualTo(sourceBefore);
		assertThat(snapshot(fixture.report().sourceDirectory())).isEqualTo(sourceBefore);
		assertThat(entryNames(root)).containsExactly(fixture.comparison().comparisonId());
		assertPrivateWhenPosix(promoted.sourceDirectory(), true);
		assertPrivateWhenPosix(
				promoted.sourceDirectory().resolve("manifest.json"), false);
		assertPrivateWhenPosix(
				promoted.sourceDirectory().resolve("longitudinal-report.json"), false);
	}

	@Test
	void exactExistingDestinationIsIdempotentAndNeverRewritten() throws Exception {
		Fixture fixture = fixture("idempotent");
		Path root = privateDirectory("idempotent-root");
		ProviderQualityComparativeLongitudinalReportBundle first = promote(fixture, root);
		Map<String, EntryIdentity> before = identities(first.sourceDirectory());
		AtomicBoolean moved = new AtomicBoolean();

		ProviderQualityComparativeLongitudinalReportBundle second =
				ProviderQualityComparativeLongitudinalReportPromotion.promoteAndVerify(
						OBJECT_MAPPER,
						fixture.repository(),
						root,
						fixture.report().sourceDirectory(),
						fixture.comparison(),
						fixture.runDirectories(),
						(source, target, options) -> {
							moved.set(true);
							return Files.move(source, target, options);
						});

		assertThat(second.sourceDirectory()).isEqualTo(first.sourceDirectory());
		assertThat(identities(second.sourceDirectory())).isEqualTo(before);
		assertThat(moved).isFalse();
		assertThat(entryNames(root)).containsExactly(fixture.comparison().comparisonId());
	}

	@Test
	void mismatchedExistingDestinationIsRejectedWithoutReplacement() throws Exception {
		Fixture fixture = fixture("mismatch");
		Path root = privateDirectory("mismatch-root");
		Path destination = promote(fixture, root).sourceDirectory();
		Files.writeString(
				destination.resolve("longitudinal-report.json"),
				"tampered\n",
				StandardCharsets.UTF_8);
		Map<String, String> before = snapshot(destination);

		assertThatThrownBy(() -> promote(fixture, root))
				.isInstanceOf(IOException.class)
				.hasMessageContaining("LONGITUDINAL_REPORT_");
		assertThat(snapshot(destination)).isEqualTo(before);
		assertThat(entryNames(root)).containsExactly(fixture.comparison().comparisonId());
	}

	@Test
	void rootMustBeAbsoluteRealPrivateAndOutsideTheRepository() throws Exception {
		Fixture fixture = fixture("roots");
		Path valid = privateDirectory("valid-root");
		assertThat(ProviderQualityComparativeLongitudinalReportPromotion.validateExternalRoot(
				fixture.repository(), valid, null, fixture.runDirectories()))
				.isEqualTo(valid.toRealPath());

		Path insideRepository = privateDirectory(
				fixture.repository().resolve("inside-root"));
		Path repositoryParent = fixture.repository().getParent();
		Path missing = temporaryDirectory.resolve("missing-root");
		assertFailure(
				() -> ProviderQualityComparativeLongitudinalReportPromotion.validateExternalRoot(
						fixture.repository(), Path.of("relative-root"), null,
						fixture.runDirectories()),
				"LONGITUDINAL_REPORT_PROMOTION_ROOT_INVALID");
		assertFailure(
				() -> ProviderQualityComparativeLongitudinalReportPromotion.validateExternalRoot(
						fixture.repository(), missing, null, fixture.runDirectories()),
				"LONGITUDINAL_REPORT_PROMOTION_ROOT_INVALID");
		assertFailure(
				() -> ProviderQualityComparativeLongitudinalReportPromotion.validateExternalRoot(
						fixture.repository(), insideRepository, null,
						fixture.runDirectories()),
				"LONGITUDINAL_REPORT_PROMOTION_ROOT_REPOSITORY_OVERLAP");
		assertFailure(
				() -> ProviderQualityComparativeLongitudinalReportPromotion.validateExternalRoot(
						fixture.repository(), repositoryParent, null,
						fixture.runDirectories()),
				"LONGITUDINAL_REPORT_PROMOTION_ROOT_REPOSITORY_OVERLAP");

		if (supportsPosix(valid)) {
			Files.setPosixFilePermissions(valid, PosixFilePermissions.fromString("rwxr-x---"));
			assertFailure(
					() -> ProviderQualityComparativeLongitudinalReportPromotion
							.validateExternalRoot(
									fixture.repository(), valid, null,
									fixture.runDirectories()),
					"LONGITUDINAL_REPORT_PROMOTION_ROOT_PERMISSIONS_NOT_PRIVATE");
		}
	}

	@Test
	void rejectsDirectSymlinksResolvedRepositoryAliasesAndOverlappingRuns()
			throws Exception {
		Fixture fixture = fixture("aliases");
		Path valid = privateDirectory("alias-target");
		Path directLink = temporaryDirectory.resolve("direct-root-link");
		Files.createSymbolicLink(directLink, valid);
		Path repositoryAlias = temporaryDirectory.resolve("repository-alias");
		Files.createSymbolicLink(repositoryAlias, fixture.repository());
		Path aliasInside = repositoryAlias.resolve("alias-inside");
		privateDirectory(fixture.repository().resolve("alias-inside"));

		assertFailure(
				() -> ProviderQualityComparativeLongitudinalReportPromotion.validateExternalRoot(
						fixture.repository(), directLink, null, fixture.runDirectories()),
				"LONGITUDINAL_REPORT_PROMOTION_ROOT_INVALID");
		assertFailure(
				() -> ProviderQualityComparativeLongitudinalReportPromotion.validateExternalRoot(
						fixture.repository(), aliasInside, null, fixture.runDirectories()),
				"LONGITUDINAL_REPORT_PROMOTION_ROOT_REPOSITORY_OVERLAP");

		Path runParent = privateDirectory("run-parent");
		Path nestedRoot = privateDirectory(runParent.resolve("nested-root"));
		assertFailure(
				() -> ProviderQualityComparativeLongitudinalReportPromotion.validateExternalRoot(
						fixture.repository(), nestedRoot, null, List.of(runParent)),
				"LONGITUDINAL_REPORT_PROMOTION_ROOT_RUN_OVERLAP");
		Path rootParent = privateDirectory("root-parent");
		Path nestedRun = privateDirectory(rootParent.resolve("nested-run"));
		assertFailure(
				() -> ProviderQualityComparativeLongitudinalReportPromotion.validateExternalRoot(
						fixture.repository(), rootParent, null, List.of(nestedRun)),
				"LONGITUDINAL_REPORT_PROMOTION_ROOT_RUN_OVERLAP");
	}

	@Test
	void rejectsSourceOverlapAndInvalidSourceBeforeCreatingStaging() throws Exception {
		Fixture fixture = fixture("source-boundary");
		Path root = privateDirectory("source-boundary-root");
		Path nestedSource = privateDirectory(root.resolve("source"));

		assertFailure(
				() -> ProviderQualityComparativeLongitudinalReportPromotion.validateExternalRoot(
						fixture.repository(), root, nestedSource, fixture.runDirectories()),
				"LONGITUDINAL_REPORT_PROMOTION_ROOT_SOURCE_OVERLAP");
		assertThatThrownBy(() -> ProviderQualityComparativeLongitudinalReportPromotion
				.promoteAndVerify(
						OBJECT_MAPPER,
						fixture.repository(),
						root,
						temporaryDirectory.resolve("missing-source"),
						fixture.comparison(),
						fixture.runDirectories()))
				.isInstanceOf(IOException.class)
				.hasMessage("LONGITUDINAL_REPORT_PROMOTION_SOURCE_INVALID");
		assertThat(entryNames(root)).containsExactly("source");
	}

	@Test
	void unsupportedAtomicMoveFailsClosedAndCleansOnlyItsStagingDirectory()
			throws Exception {
		Fixture fixture = fixture("atomic-unsupported");
		Path root = privateDirectory("atomic-unsupported-root");
		Map<String, String> sourceBefore = snapshot(fixture.report().sourceDirectory());

		Throwable failure = catchThrowable(
				() -> ProviderQualityComparativeLongitudinalReportPromotion
				.promoteAndVerify(
						OBJECT_MAPPER,
						fixture.repository(),
						root,
						fixture.report().sourceDirectory(),
						fixture.comparison(),
						fixture.runDirectories(),
						(source, target, options) -> {
							assertThat(options)
									.containsExactly(StandardCopyOption.ATOMIC_MOVE);
							throw new AtomicMoveNotSupportedException(
									source.toString(), target.toString(), "synthetic");
						}));
		assertPathFreeFailure(
				failure,
				"LONGITUDINAL_REPORT_PROMOTION_ATOMIC_MOVE_UNSUPPORTED",
				fixture.repository(), root, fixture.report().sourceDirectory());
		assertThat(entryNames(root)).isEmpty();
		assertThat(snapshot(fixture.report().sourceDirectory())).isEqualTo(sourceBefore);
	}

	@Test
	void unexpectedAtomicMoveFailureFailsClosedAndLeavesUnrelatedRootEntries()
			throws Exception {
		Fixture fixture = fixture("atomic-failure");
		Path root = privateDirectory("atomic-failure-root");
		Files.writeString(root.resolve("operator-note.txt"), "keep\n", StandardCharsets.UTF_8);

		Throwable failure = catchThrowable(
				() -> ProviderQualityComparativeLongitudinalReportPromotion
				.promoteAndVerify(
						OBJECT_MAPPER,
						fixture.repository(),
						root,
						fixture.report().sourceDirectory(),
						fixture.comparison(),
						fixture.runDirectories(),
						(source, target, options) -> {
							assertThat(options)
									.containsExactly(StandardCopyOption.ATOMIC_MOVE);
							throw new IOException(
									"synthetic move failure: " + source + " -> " + target);
						}));
		assertPathFreeFailure(
				failure,
				"LONGITUDINAL_REPORT_PROMOTION_ATOMIC_MOVE_FAILED",
				fixture.repository(), root, fixture.report().sourceDirectory());
		assertThat(entryNames(root)).containsExactly("operator-note.txt");
		assertThat(Files.readString(root.resolve("operator-note.txt")))
				.isEqualTo("keep\n");
	}

	@Test
	void exactDestinationAppearingDuringMoveIsAcceptedOnlyAfterExactVerification()
			throws Exception {
		Fixture fixture = fixture("exact-race");
		Path root = privateDirectory("exact-race-root");
		AtomicBoolean moverInvoked = new AtomicBoolean();

		ProviderQualityComparativeLongitudinalReportBundle promoted =
				ProviderQualityComparativeLongitudinalReportPromotion.promoteAndVerify(
						OBJECT_MAPPER,
						fixture.repository(),
						root,
						fixture.report().sourceDirectory(),
						fixture.comparison(),
						fixture.runDirectories(),
						(source, target, options) -> {
							moverInvoked.set(true);
							assertThat(options)
									.containsExactly(StandardCopyOption.ATOMIC_MOVE);
							copyPrivateBundle(source, target);
							throw new FileAlreadyExistsException(
									source.toString(), target.toString(), "synthetic race");
						});

		assertThat(moverInvoked).isTrue();
		assertThat(promoted.sourceDirectory())
				.isEqualTo(root.toRealPath().resolve(fixture.comparison().comparisonId()));
		assertThat(snapshot(promoted.sourceDirectory()))
				.isEqualTo(snapshot(fixture.report().sourceDirectory()));
		assertThat(entryNames(root)).containsExactly(fixture.comparison().comparisonId());
	}

	@Test
	void mismatchedDestinationAppearingDuringMoveIsPreservedAndRejected()
			throws Exception {
		Fixture fixture = fixture("mismatch-race");
		Path root = privateDirectory("mismatch-race-root");
		Path destination = root.resolve(fixture.comparison().comparisonId());

		Throwable failure = catchThrowable(
				() -> ProviderQualityComparativeLongitudinalReportPromotion.promoteAndVerify(
						OBJECT_MAPPER,
						fixture.repository(),
						root,
						fixture.report().sourceDirectory(),
						fixture.comparison(),
						fixture.runDirectories(),
						(source, target, options) -> {
							assertThat(options)
									.containsExactly(StandardCopyOption.ATOMIC_MOVE);
							privateDirectory(target);
							Files.writeString(
									target.resolve("operator-note.txt"),
									"do not replace\n",
									StandardCharsets.UTF_8);
							throw new FileAlreadyExistsException(
									source.toString(), target.toString(), "synthetic race");
						}));

		assertPathFreeFailure(
				failure,
				"LONGITUDINAL_REPORT_LAYOUT_INVALID",
				fixture.repository(), root, fixture.report().sourceDirectory());
		assertThat(entryNames(root)).containsExactly(fixture.comparison().comparisonId());
		assertThat(entryNames(destination)).containsExactly("operator-note.txt");
		assertThat(Files.readString(destination.resolve("operator-note.txt")))
				.isEqualTo("do not replace\n");
	}

	private ProviderQualityComparativeLongitudinalReportBundle promote(
			Fixture fixture, Path root) throws Exception {
		return ProviderQualityComparativeLongitudinalReportPromotion.promoteAndVerify(
				OBJECT_MAPPER,
				fixture.repository(),
				root,
				fixture.report().sourceDirectory(),
				fixture.comparison(),
				fixture.runDirectories());
	}

	private Fixture fixture(String name) throws Exception {
		Comparison comparison =
				ProviderQualityComparativeLongitudinalReportBundleTests.comparison();
		Path repository = temporaryDirectory.resolve(name + "-repository");
		ProviderQualityComparativeLongitudinalReportBundle report =
				ProviderQualityComparativeLongitudinalReportBundle.publishAndVerify(
						OBJECT_MAPPER, repository, comparison);
		List<Path> runs = List.of(
				privateDirectory(name + "-run-a"),
				privateDirectory(name + "-run-b"));
		return new Fixture(repository.toRealPath(), report, comparison, runs);
	}

	private Path privateDirectory(String name) throws Exception {
		return privateDirectory(temporaryDirectory.resolve(name));
	}

	private static Path privateDirectory(Path path) throws IOException {
		Files.createDirectory(path);
		if (supportsPosix(path)) {
			Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rwx------"));
		}
		return path;
	}

	private static void copyPrivateBundle(Path source, Path target) throws IOException {
		privateDirectory(target);
		for (String filename : List.of("manifest.json", "longitudinal-report.json")) {
			Path copied = Files.copy(source.resolve(filename), target.resolve(filename));
			if (supportsPosix(copied)) {
				Files.setPosixFilePermissions(
						copied, PosixFilePermissions.fromString("rw-------"));
			}
		}
	}

	private static Set<String> entryNames(Path directory) throws Exception {
		try (var paths = Files.list(directory)) {
			return paths.map(path -> path.getFileName().toString())
					.collect(java.util.stream.Collectors.toCollection(java.util.TreeSet::new));
		}
	}

	private static Map<String, String> snapshot(Path directory) throws Exception {
		Map<String, String> result = new LinkedHashMap<>();
		try (var paths = Files.list(directory)) {
			for (Path path : paths.sorted().toList()) {
				result.put(
						path.getFileName().toString(),
						Base64.getEncoder().encodeToString(Files.readAllBytes(path)));
			}
		}
		return result;
	}

	private static Map<String, EntryIdentity> identities(Path directory) throws Exception {
		Map<String, EntryIdentity> result = new LinkedHashMap<>();
		try (var paths = Files.list(directory)) {
			for (Path path : paths.sorted().toList()) {
				BasicFileAttributes attributes = Files.readAttributes(
						path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
				result.put(
						path.getFileName().toString(),
						new EntryIdentity(
								attributes.fileKey(),
								attributes.size(),
								attributes.lastModifiedTime().toMillis(),
								Base64.getEncoder().encodeToString(Files.readAllBytes(path))));
			}
		}
		return result;
	}

	private static void assertPrivateWhenPosix(Path path, boolean directory)
			throws Exception {
		if (!supportsPosix(path)) {
			return;
		}
		assertThat(Files.getPosixFilePermissions(path, LinkOption.NOFOLLOW_LINKS))
				.isEqualTo(PosixFilePermissions.fromString(directory ? "rwx------" : "rw-------"));
	}

	private static boolean supportsPosix(Path path) {
		return Files.getFileAttributeView(
				path, PosixFileAttributeView.class, LinkOption.NOFOLLOW_LINKS) != null;
	}

	private static void assertFailure(ThrowingAction action, String diagnostic) {
		assertPathFreeFailure(catchThrowable(action::run), diagnostic);
	}

	private static void assertPathFreeFailure(
			Throwable failure, String diagnostic, Path... sensitivePaths) {
		assertThat(failure)
				.isInstanceOf(IOException.class)
				.hasMessage(diagnostic);
		assertThat(failure.getCause()).isNull();
		assertThat(failure.getSuppressed()).isEmpty();
		for (Path sensitive : sensitivePaths) {
			assertThat(failure.getMessage()).doesNotContain(sensitive.toString());
		}
	}

	private record Fixture(
			Path repository,
			ProviderQualityComparativeLongitudinalReportBundle report,
			Comparison comparison,
			List<Path> runDirectories) {
	}

	private record EntryIdentity(
			Object fileKey,
			long size,
			long modifiedMillis,
			String base64Bytes) {
	}

	@FunctionalInterface
	private interface ThrowingAction {

		void run() throws Exception;
	}
}
