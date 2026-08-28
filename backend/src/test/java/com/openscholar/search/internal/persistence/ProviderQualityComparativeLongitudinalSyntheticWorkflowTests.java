package com.openscholar.search.internal.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

import com.openscholar.search.internal.persistence.EuropePmcComparativeLongitudinalComparisonTests.GenerationResult;
import com.openscholar.search.internal.persistence.EuropePmcComparativeLongitudinalReportVerificationTests.VerificationResult;
import com.openscholar.search.internal.persistence.ProviderQualityComparativeLongitudinalComparison.Comparison;
import com.openscholar.search.internal.persistence.ProviderQualityComparativeSemanticRunFixture.Cohort;
import com.openscholar.search.internal.persistence.ProviderQualityComparativeSemanticRunFixture.RetainedRun;
import com.openscholar.search.internal.persistence.ProviderQualityComparativeSemanticRunFixture.RunSpec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Ordinary-CI composition coverage for the private retained-run workflow. It
 * creates no real labels and performs no Spring, database, Docker, provider, or
 * network operation.
 */
class ProviderQualityComparativeLongitudinalSyntheticWorkflowTests {

	private static final ObjectMapper OBJECT_MAPPER = JsonMapper.builder().build();
	private static final String REPOSITORY_REVISION = "a".repeat(40);
	private static final Instant FIRST_CAPTURE = Instant.parse("2026-08-01T00:00:00Z");
	private static final Instant SECOND_CAPTURE = Instant.parse("2026-08-02T00:00:00Z");
	private static final Instant THIRD_CAPTURE = Instant.parse("2026-08-03T00:00:00Z");

	@TempDir
	private Path temporaryDirectory;

	@Test
	void replaysGeneratesPromotesAndVerifiesSyntheticRetainedRunsWithoutMutation()
			throws Exception {
		Path repository = privateDirectory(temporaryDirectory.resolve("repository"));
		Path localOnlyRepository = privateDirectory(
				temporaryDirectory.resolve("local-only-repository"));
		Path runSealRoot = privateDirectory(temporaryDirectory.resolve("retained-runs"));
		Path reportRoot = privateDirectory(temporaryDirectory.resolve("retained-reports"));
		Cohort cohort = ProviderQualityComparativeSemanticRunFixture.loadCohort(
				OBJECT_MAPPER);

		RetainedRun first = publishRun(
				repository, runSealRoot, "synthetic-longitudinal-run-a", FIRST_CAPTURE, cohort);
		RetainedRun second = publishRun(
				repository, runSealRoot, "synthetic-longitudinal-run-b", SECOND_CAPTURE, cohort);
		RetainedRun third = publishRun(
				repository, runSealRoot, "synthetic-longitudinal-run-c", THIRD_CAPTURE, cohort);
		Path selectionFile = writeSelection(
				"selection-b-a.json",
				List.of(second.seal().sourceDirectory(), first.seal().sourceDirectory()));
		Path wrongSelectionFile = writeSelection(
				"selection-c-a.json",
				List.of(third.seal().sourceDirectory(), first.seal().sourceDirectory()));
		ProviderQualityComparativeLongitudinalSelection.Selection selection =
				ProviderQualityComparativeLongitudinalSelection.load(
						OBJECT_MAPPER, repository, selectionFile);
		ProviderQualityComparativeLongitudinalSelection.Selection wrongSelection =
				ProviderQualityComparativeLongitudinalSelection.load(
						OBJECT_MAPPER, repository, wrongSelectionFile);

		byte[] selectionBefore = Files.readAllBytes(selectionFile);
		byte[] wrongSelectionBefore = Files.readAllBytes(wrongSelectionFile);
		Map<Path, Map<String, EntrySnapshot>> runsBefore = snapshots(first, second, third);
		Path providerQualityTarget = repository.resolve("backend/target/provider-quality");
		Map<String, EntrySnapshot> targetBeforeReplay = snapshot(providerQualityTarget);

		Comparison replayed = EuropePmcComparativeLongitudinalComparisonTests
				.replaySelection(OBJECT_MAPPER, REPOSITORY_REVISION, selection);
		assertThat(snapshot(providerQualityTarget)).isEqualTo(targetBeforeReplay);
		assertThat(replayed.runCount()).isEqualTo(2);
		assertThat(replayed.runs())
				.extracting(run -> run.run().captureMeasuredAt())
				.containsExactly(FIRST_CAPTURE.toString(), SECOND_CAPTURE.toString());
		assertThat(replayed.transitions()).singleElement()
				.extracting(transition -> transition.elapsed())
				.isEqualTo("PT24H");

		GenerationResult localOnly = EuropePmcComparativeLongitudinalComparisonTests.generate(
				OBJECT_MAPPER,
				localOnlyRepository,
				REPOSITORY_REVISION,
				selection,
				null);
		assertThat(localOnly.mode()).isEqualTo("generated");
		assertThat(localOnly.comparison()).isEqualTo(replayed);
		assertThat(localOnly.report().sourceDirectory())
				.isEqualTo(localOnly.localReport().sourceDirectory());

		GenerationResult generated = EuropePmcComparativeLongitudinalComparisonTests.generate(
				OBJECT_MAPPER,
				repository,
				REPOSITORY_REVISION,
				selection,
				reportRoot);
		assertThat(generated.mode()).isEqualTo("promoted");
		assertThat(generated.comparison()).isEqualTo(replayed);
		assertThat(generated.localReport().sourceDirectory())
				.isNotEqualTo(localOnly.localReport().sourceDirectory());
		assertThat(generated.localReport().manifestSha256())
				.isEqualTo(localOnly.localReport().manifestSha256());
		assertSameReportBytes(
				localOnly.localReport().sourceDirectory(),
				generated.localReport().sourceDirectory());
		assertThat(generated.localReport().sourceDirectory())
				.isNotEqualTo(generated.report().sourceDirectory());
		assertThat(generated.report().sourceDirectory().getParent())
				.isEqualTo(reportRoot.toRealPath());
		assertSameReportBytes(
				generated.localReport().sourceDirectory(),
				generated.report().sourceDirectory());
		assertPrivateReport(generated.report().sourceDirectory());

		Map<String, EntrySnapshot> localReportBeforeVerification =
				snapshot(generated.localReport().sourceDirectory());
		Map<String, EntrySnapshot> retainedReportBeforeVerification =
				snapshot(generated.report().sourceDirectory());
		Map<String, EntrySnapshot> targetBeforeVerification =
				snapshot(repository.resolve("backend/target"));

		VerificationResult firstVerification =
				EuropePmcComparativeLongitudinalReportVerificationTests.verifyRetainedReport(
						OBJECT_MAPPER,
						repository,
						REPOSITORY_REVISION,
						selection,
						generated.report().sourceDirectory());
		VerificationResult repeatedVerification =
				EuropePmcComparativeLongitudinalReportVerificationTests.verifyRetainedReport(
						OBJECT_MAPPER,
						repository,
						REPOSITORY_REVISION,
						selection,
						generated.report().sourceDirectory());

		assertThat(firstVerification.comparison()).isEqualTo(generated.comparison());
		assertThat(repeatedVerification.comparison()).isEqualTo(generated.comparison());
		assertThat(firstVerification.report().comparisonId())
				.isEqualTo(generated.report().comparisonId());
		assertThat(firstVerification.report().manifestSha256())
				.isEqualTo(generated.report().manifestSha256());
		assertPathFreeSuccessRecords(localOnly, generated, firstVerification);

		assertThatThrownBy(() ->
				EuropePmcComparativeLongitudinalReportVerificationTests.verifyRetainedReport(
						OBJECT_MAPPER,
						repository,
						REPOSITORY_REVISION,
						wrongSelection,
						generated.report().sourceDirectory()))
				.isInstanceOf(IOException.class)
				.hasMessage("LONGITUDINAL_REPORT_ID_INVALID")
				.hasNoCause();

		assertThat(Files.readAllBytes(selectionFile)).isEqualTo(selectionBefore);
		assertThat(Files.readAllBytes(wrongSelectionFile)).isEqualTo(wrongSelectionBefore);
		assertThat(snapshots(first, second, third)).isEqualTo(runsBefore);
		assertThat(snapshot(generated.localReport().sourceDirectory()))
				.isEqualTo(localReportBeforeVerification);
		assertThat(snapshot(generated.report().sourceDirectory()))
				.isEqualTo(retainedReportBeforeVerification);
		assertThat(snapshot(repository.resolve("backend/target")))
				.isEqualTo(targetBeforeVerification);
		assertPrivateOutputShape(reportRoot, generated.report().comparisonId());
		assertReportPrivacy(generated.report().sourceDirectory());
	}

	private RetainedRun publishRun(
			Path repository,
			Path runSealRoot,
			String evidenceId,
			Instant capturedAt,
			Cohort cohort) throws Exception {
		return ProviderQualityComparativeSemanticRunFixture.publish(
				OBJECT_MAPPER,
				repository,
				runSealRoot,
				cohort,
				new RunSpec(evidenceId, capturedAt, REPOSITORY_REVISION));
	}

	private Path writeSelection(String filename, List<Path> runDirectories)
			throws Exception {
		Map<String, Object> document = new LinkedHashMap<>();
		document.put("schemaVersion", 1);
		document.put(
				"protocolId", ProviderQualityComparativeLongitudinalSelection.PROTOCOL_ID);
		document.put(
				"runSealDirectories",
				runDirectories.stream().map(Path::toString).toList());
		Path file = temporaryDirectory.resolve(filename);
		Files.write(
				file,
				ProviderQualityComparativeReviewPacket.canonicalBytes(
						OBJECT_MAPPER, document));
		if (supportsPosix(file)) {
			Files.setPosixFilePermissions(
					file, PosixFilePermissions.fromString("rw-------"));
		}
		return file;
	}

	private static void assertPathFreeSuccessRecords(
			GenerationResult localOnly,
			GenerationResult generated,
			VerificationResult verified) {
		String local = EuropePmcComparativeLongitudinalComparisonTests.successRecord(
				localOnly.mode(),
				localOnly.report().comparisonId(),
				localOnly.report().manifestSha256(),
				localOnly.comparison().runCount());
		String promoted = EuropePmcComparativeLongitudinalComparisonTests.successRecord(
				generated.mode(),
				generated.report().comparisonId(),
				generated.report().manifestSha256(),
				generated.comparison().runCount());
		String verification =
				EuropePmcComparativeLongitudinalReportVerificationTests.successRecord(
						verified.report().comparisonId(),
						verified.report().manifestSha256(),
						verified.comparison().runCount());
		assertThat(local)
				.isEqualTo("provider-quality-comparative-longitudinal-v1 mode=generated "
						+ "comparison-id=" + localOnly.report().comparisonId()
						+ " manifest-sha256=" + localOnly.report().manifestSha256()
						+ " runs=2");
		assertThat(promoted)
				.isEqualTo("provider-quality-comparative-longitudinal-v1 mode=promoted "
						+ "comparison-id=" + generated.report().comparisonId()
						+ " manifest-sha256=" + generated.report().manifestSha256()
						+ " runs=2");
		assertThat(verification)
				.isEqualTo("provider-quality-comparative-longitudinal-v1 mode=verified "
						+ "comparison-id=" + verified.report().comparisonId()
						+ " manifest-sha256=" + verified.report().manifestSha256()
						+ " runs=2");
		assertThat(local + promoted + verification).doesNotContain(
				REPOSITORY_REVISION,
				FIRST_CAPTURE.toString(),
				SECOND_CAPTURE.toString(),
				"synthetic-longitudinal-run-a",
				"synthetic-longitudinal-run-b",
				"review-packet.json");
	}

	private static void assertPrivateOutputShape(Path root, String comparisonId)
			throws Exception {
		try (var entries = Files.list(root)) {
			assertThat(entries.map(path -> path.getFileName().toString()).toList())
					.containsExactly(comparisonId);
		}
		try (var entries = Files.list(root.resolve(comparisonId))) {
			assertThat(entries.map(path -> path.getFileName().toString()).sorted().toList())
					.containsExactly("longitudinal-report.json", "manifest.json");
		}
	}

	private static void assertPrivateReport(Path reportDirectory) throws Exception {
		if (!supportsPosix(reportDirectory)) {
			return;
		}
		assertThat(Files.getPosixFilePermissions(reportDirectory))
				.isEqualTo(PosixFilePermissions.fromString("rwx------"));
		for (String filename : List.of("manifest.json", "longitudinal-report.json")) {
			assertThat(Files.getPosixFilePermissions(reportDirectory.resolve(filename)))
					.isEqualTo(PosixFilePermissions.fromString("rw-------"));
		}
	}

	private static void assertSameReportBytes(Path expected, Path actual)
			throws IOException {
		for (String filename : List.of("manifest.json", "longitudinal-report.json")) {
			assertThat(Files.readAllBytes(actual.resolve(filename)))
					.isEqualTo(Files.readAllBytes(expected.resolve(filename)));
		}
	}

	private void assertReportPrivacy(Path reportDirectory) throws IOException {
		String report = Files.readString(
				reportDirectory.resolve("longitudinal-report.json"));
		assertThat(report).doesNotContain(
				temporaryDirectory.toString(),
				"providerConfiguration",
				"review-packet.json",
				"completed-worksheet.json",
				"reconciliation-trace.json",
				ProviderQualityComparativeJudgments.INDEPENDENCE_ATTESTATION,
				"Keep this file hidden during blinded relevance grading.");
	}

	private static Map<Path, Map<String, EntrySnapshot>> snapshots(RetainedRun... runs)
			throws IOException {
		Map<Path, Map<String, EntrySnapshot>> result = new LinkedHashMap<>();
		for (RetainedRun run : runs) {
			Path directory = run.seal().sourceDirectory();
			result.put(directory, snapshot(directory));
		}
		return Map.copyOf(result);
	}

	private static Map<String, EntrySnapshot> snapshot(Path root) throws IOException {
		Map<String, EntrySnapshot> result = new TreeMap<>();
		try (var paths = Files.walk(root)) {
			for (Path path : paths.sorted().toList()) {
				BasicFileAttributes attributes = Files.readAttributes(
						path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
				String relative = root.relativize(path).toString().replace('\\', '/');
				String digest = attributes.isRegularFile()
						? sha256(Files.readAllBytes(path))
						: null;
				String permissions = supportsPosix(path)
						? PosixFilePermissions.toString(Files.getPosixFilePermissions(
								path, LinkOption.NOFOLLOW_LINKS))
						: null;
				result.put(
						relative.isEmpty() ? "." : relative,
						new EntrySnapshot(
								attributes.isDirectory() ? "directory" : "file",
								attributes.size(),
								attributes.lastModifiedTime().toMillis(),
								Objects.toString(attributes.fileKey(), null),
								permissions,
								digest));
			}
		}
		return Map.copyOf(result);
	}

	private static Path privateDirectory(Path path) throws IOException {
		Files.createDirectory(path);
		if (supportsPosix(path)) {
			Files.setPosixFilePermissions(
					path, PosixFilePermissions.fromString("rwx------"));
		}
		return path;
	}

	private static boolean supportsPosix(Path path) {
		return Files.getFileAttributeView(
				path, PosixFileAttributeView.class, LinkOption.NOFOLLOW_LINKS) != null;
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

	private record EntrySnapshot(
			String type,
			long bytes,
			long modifiedAt,
			String fileKey,
			String permissions,
			String sha256) {
	}
}
