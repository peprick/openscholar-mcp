package com.openscholar.search.internal.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.openscholar.search.internal.persistence.RelatedTopicReuseHoldoutEvaluatorSeal.RepositoryState;
import com.openscholar.search.internal.persistence.RelatedTopicReuseHoldoutEvaluatorSeal.SourceFile;
import com.openscholar.search.internal.persistence.RelatedTopicReuseHoldoutEvaluatorSeal.SourceRole;
import com.openscholar.search.internal.persistence.RelatedTopicReuseHoldoutRankingSnapshot.HiddenPerturbation;
import com.openscholar.search.internal.persistence.RelatedTopicReuseHoldoutRankingSnapshot.QueryRanking;
import com.openscholar.search.internal.persistence.RelatedTopicReuseHoldoutRankingSnapshot.RankedPaper;
import com.openscholar.search.internal.persistence.RelatedTopicReuseHoldoutRankingSnapshot.RankingRun;
import com.openscholar.search.internal.persistence.RelatedTopicReuseHoldoutRankingSnapshot.StructuralCounters;
import com.openscholar.search.internal.persistence.RelatedTopicReuseHoldoutScoringResult.AggregateMetrics;
import com.openscholar.search.internal.persistence.RelatedTopicReuseHoldoutScoringResult.GateId;
import com.openscholar.search.internal.persistence.RelatedTopicReuseHoldoutScoringResult.GateOutcome;
import com.openscholar.search.internal.persistence.RelatedTopicReuseHoldoutScoringResult.MetricDeltas;
import com.openscholar.search.internal.persistence.RelatedTopicReuseHoldoutScoringResult.QueryScore;
import com.openscholar.search.internal.persistence.RelatedTopicReuseHoldoutScoringResult.RankingMetrics;
import com.openscholar.search.internal.persistence.RelatedTopicReuseHoldoutScoringResult.RankingSummary;
import com.openscholar.search.internal.persistence.RelatedTopicReuseHoldoutScoringResult.ScoreIdentity;
import com.openscholar.search.internal.persistence.RelatedTopicReuseHoldoutScoringResult.StructuralAssessment;

class RelatedTopicReuseHoldoutEvidenceReportBundleTests {

	@TempDir
	private Path temporaryDirectory;

	@Test
	void verifiesOnlyTheExactClosedPrivateBundleAndReturnsDefensiveMetadata()
			throws Exception {
		var fixture = RelatedTopicReuseHoldoutRetentionTestFixture.create(
				temporaryDirectory, "exact");
		Path retained = RelatedTopicReuseHoldoutRetentionTestFixture.writeRetained(
				fixture.externalRoot(), fixture.expected());

		var verified = RelatedTopicReuseHoldoutEvidenceReportBundle.verifyExact(
				fixture.repository(), retained, fixture.expected());

		assertThat(verified.directory()).isEqualTo(retained.toRealPath());
		assertThat(verified.reportId()).isEqualTo(fixture.expected().reportId());
		assertThat(verified.manifestSha256()).matches("[0-9a-f]{64}");
		assertThat(verified.payloadBytes()).isEqualTo(fixture.expected().totalBytes());
		assertThat(verified.totalBytes()).isGreaterThan(verified.payloadBytes());
		assertThat(verified.files())
				.extracting(RelatedTopicReuseHoldoutEvidenceReportBundle.FileCommitment::filename)
				.containsExactly(
						"manifest.json",
						RelatedTopicReuseHoldoutEvidenceReport.EVALUATOR_SOURCE_FILENAME,
						RelatedTopicReuseHoldoutEvidenceReport.RANKING_SNAPSHOT_FILENAME,
						RelatedTopicReuseHoldoutEvidenceReport.SCORING_RESULT_FILENAME,
						RelatedTopicReuseHoldoutEvidenceReport.EVIDENCE_REPORT_FILENAME);
		assertThatThrownBy(() -> verified.files().clear())
				.isInstanceOf(UnsupportedOperationException.class);
		assertThat(verified.readerFacing()).isFalse();
		assertThat(verified.externalBundleAcceptanceAuthorized()).isFalse();
		assertThat(verified.custodyReleaseAuthorized()).isFalse();
		assertThat(verified.productActivationAuthorized()).isFalse();
		RelatedTopicReuseHoldoutRetentionTestFixture.assertPrivate(retained, true);
		for (String filename : RelatedTopicReuseHoldoutRetentionTestFixture.bundleFilenames()) {
			RelatedTopicReuseHoldoutRetentionTestFixture.assertPrivate(
					retained.resolve(filename), false);
		}
	}

	@Test
	void rejectsTamperingEvenWhenTheRetainedManifestIsRebound() throws Exception {
		var fixture = RelatedTopicReuseHoldoutRetentionTestFixture.create(
				temporaryDirectory, "tamper");
		Path retained = RelatedTopicReuseHoldoutRetentionTestFixture.writeRetained(
				fixture.externalRoot(), fixture.expected());
		String filename = RelatedTopicReuseHoldoutEvidenceReport.SCORING_RESULT_FILENAME;
		byte[] changed = Files.readAllBytes(retained.resolve(filename));
		changed[1] ^= 0x01;
		RelatedTopicReuseHoldoutRetentionTestFixture.writePrivate(
				retained.resolve(filename), changed);

		String originalDigest = fixture.expected().artifactSha256().get(filename);
		String changedDigest = RelatedTopicReuseHoldoutRetentionTestFixture.sha256(changed);
		String originalManifest = Files.readString(
				retained.resolve("manifest.json"), StandardCharsets.UTF_8);
		assertThat(originalManifest)
				.contains(originalDigest)
				.doesNotContain(changedDigest);
		byte[] reboundManifest = originalManifest
				.replace(originalDigest, changedDigest)
				.getBytes(StandardCharsets.UTF_8);
		RelatedTopicReuseHoldoutRetentionTestFixture.writePrivate(
				retained.resolve("manifest.json"), reboundManifest);

		assertThatThrownBy(() -> RelatedTopicReuseHoldoutEvidenceReportBundle.verifyExact(
				fixture.repository(), retained, fixture.expected()))
				.isInstanceOf(IOException.class)
				.hasMessageMatching("HOLDOUT_REPORT_BUNDLE_[A-Z0-9_]+");
	}

	@Test
	void rejectsMissingExtraSymlinkedAndNonprivateEntries() throws Exception {
		var missingFixture = RelatedTopicReuseHoldoutRetentionTestFixture.create(
				temporaryDirectory, "missing");
		Path missing = RelatedTopicReuseHoldoutRetentionTestFixture.writeRetained(
				missingFixture.externalRoot(), missingFixture.expected());
		Files.delete(missing.resolve(
				RelatedTopicReuseHoldoutEvidenceReport.EVIDENCE_REPORT_FILENAME));
		assertFailure(missingFixture, missing, "HOLDOUT_REPORT_BUNDLE_LAYOUT_INVALID");

		var extraFixture = RelatedTopicReuseHoldoutRetentionTestFixture.create(
				temporaryDirectory, "extra");
		Path extra = RelatedTopicReuseHoldoutRetentionTestFixture.writeRetained(
				extraFixture.externalRoot(), extraFixture.expected());
		RelatedTopicReuseHoldoutRetentionTestFixture.writePrivate(
				extra.resolve("extra.json"), "{}\n".getBytes(StandardCharsets.UTF_8));
		assertFailure(extraFixture, extra, "HOLDOUT_REPORT_BUNDLE_LAYOUT_INVALID");

		var symlinkFixture = RelatedTopicReuseHoldoutRetentionTestFixture.create(
				temporaryDirectory, "symlink");
		Path symlinked = RelatedTopicReuseHoldoutRetentionTestFixture.writeRetained(
				symlinkFixture.externalRoot(), symlinkFixture.expected());
		Path artifact = symlinked.resolve(
				RelatedTopicReuseHoldoutEvidenceReport.EVALUATOR_SOURCE_FILENAME);
		Path target = temporaryDirectory.resolve("symlink-target.json");
		Files.move(artifact, target);
		Files.createSymbolicLink(artifact, target);
		assertFailure(symlinkFixture, symlinked, "HOLDOUT_REPORT_BUNDLE_FILE_INVALID");

		var modeFixture = RelatedTopicReuseHoldoutRetentionTestFixture.create(
				temporaryDirectory, "mode");
		Path wrongMode = RelatedTopicReuseHoldoutRetentionTestFixture.writeRetained(
				modeFixture.externalRoot(), modeFixture.expected());
		if (RelatedTopicReuseHoldoutRetentionTestFixture.supportsPosix(wrongMode)) {
			Files.setPosixFilePermissions(
					wrongMode.resolve("manifest.json"),
					PosixFilePermissions.fromString("rw-r-----"));
			assertFailure(
					modeFixture,
					wrongMode,
					"HOLDOUT_REPORT_BUNDLE_PERMISSIONS_NOT_PRIVATE");
		}
	}

	@Test
	void rejectsHardLinkedBundleFilesWhenLinkCountsAreAvailable() throws Exception {
		assumeTrue(temporaryDirectory.getFileSystem()
				.supportedFileAttributeViews().contains("unix"));
		var fixture = RelatedTopicReuseHoldoutRetentionTestFixture.create(
				temporaryDirectory, "hard-link");
		Path retained = RelatedTopicReuseHoldoutRetentionTestFixture.writeRetained(
				fixture.externalRoot(), fixture.expected());
		Path artifact = retained.resolve(
				RelatedTopicReuseHoldoutEvidenceReport.SCORING_RESULT_FILENAME);
		Files.createLink(temporaryDirectory.resolve("artifact-alias.json"), artifact);

		assertFailure(
				fixture,
				retained,
				"HOLDOUT_REPORT_BUNDLE_FILE_LINK_COUNT_INVALID");
	}

	@Test
	void rejectsAFileSystemWithoutEnforceablePosixPrivacy() throws Exception {
		var fixture = RelatedTopicReuseHoldoutRetentionTestFixture.create(
				temporaryDirectory, "non-posix");
		URI archive = URI.create("jar:" + temporaryDirectory.resolve("retained.zip").toUri());
		try (FileSystem fileSystem = FileSystems.newFileSystem(
				archive, Map.of("create", "true"))) {
			Path externalRoot = Files.createDirectory(fileSystem.getPath("/external"));
			Path retained = RelatedTopicReuseHoldoutRetentionTestFixture.writeRetained(
					externalRoot, fixture.expected());

			assertFailure(
					fixture,
					retained,
					"HOLDOUT_REPORT_BUNDLE_FILESYSTEM_UNSUPPORTED");
		}
	}

	@Test
	void rejectsRelativeAliasedAndRepositoryOverlappingDirectories() throws Exception {
		var fixture = RelatedTopicReuseHoldoutRetentionTestFixture.create(
				temporaryDirectory, "boundary");
		Path retained = RelatedTopicReuseHoldoutRetentionTestFixture.writeRetained(
				fixture.externalRoot(), fixture.expected());

		assertThatThrownBy(() -> RelatedTopicReuseHoldoutEvidenceReportBundle.verifyExact(
				fixture.repository(), Path.of(retained.getFileName().toString()), fixture.expected()))
				.isInstanceOf(IOException.class)
				.hasMessage("HOLDOUT_REPORT_BUNDLE_DIRECTORY_INVALID");

		Path insideRoot = RelatedTopicReuseHoldoutRetentionTestFixture.privateDirectory(
				fixture.repository().resolve("retained"));
		Path inside = RelatedTopicReuseHoldoutRetentionTestFixture.writeRetained(
				insideRoot, fixture.expected());
		assertFailure(fixture, inside, "HOLDOUT_REPORT_BUNDLE_REPOSITORY_OVERLAP");

		Path alias = temporaryDirectory.resolve("retained-alias");
		Files.createSymbolicLink(alias, retained);
		assertThatThrownBy(() -> RelatedTopicReuseHoldoutEvidenceReportBundle.verifyExact(
				fixture.repository(), alias, fixture.expected()))
				.isInstanceOf(IOException.class)
				.hasMessage("HOLDOUT_REPORT_BUNDLE_DIRECTORY_INVALID");
	}

	private static void assertFailure(
			RelatedTopicReuseHoldoutRetentionTestFixture.Fixture fixture,
			Path retained,
			String diagnostic) {
		assertThatThrownBy(() -> RelatedTopicReuseHoldoutEvidenceReportBundle.verifyExact(
				fixture.repository(), retained, fixture.expected()))
				.isInstanceOf(IOException.class)
				.hasMessage(diagnostic)
				.hasMessageNotContaining(retained.toString());
	}
}

final class RelatedTopicReuseHoldoutRetentionTestFixture {

	private static final List<String> BUNDLE_FILENAMES = List.of(
			RelatedTopicReuseHoldoutEvidenceReportBundle.MANIFEST_FILENAME,
			RelatedTopicReuseHoldoutEvidenceReport.EVALUATOR_SOURCE_FILENAME,
			RelatedTopicReuseHoldoutEvidenceReport.RANKING_SNAPSHOT_FILENAME,
			RelatedTopicReuseHoldoutEvidenceReport.SCORING_RESULT_FILENAME,
			RelatedTopicReuseHoldoutEvidenceReport.EVIDENCE_REPORT_FILENAME);

	private RelatedTopicReuseHoldoutRetentionTestFixture() {
	}

	static Fixture create(Path temporaryDirectory, String suffix) throws IOException {
		Path repository = privateDirectory(temporaryDirectory.resolve("repo-" + suffix));
		Path externalRoot = privateDirectory(temporaryDirectory.resolve("root-" + suffix));
		return new Fixture(repository, externalRoot, expected(suffix));
	}

	static RelatedTopicReuseHoldoutEvidenceReport.VerifiedArtifacts expected(String marker) {
		String evaluatorRevision = sha256(
				("evaluator-revision-" + marker).getBytes(StandardCharsets.UTF_8))
				.substring(0, 40);
		String candidateRevision = sha256(
				("candidate-revision-" + marker).getBytes(StandardCharsets.UTF_8))
				.substring(0, 40);
		String queryKey = "query-" + marker;
		RelatedTopicReuseHoldoutEvaluatorSeal.VerifiedEvaluatorSeal evaluatorSeal = seal(
				marker, evaluatorRevision, candidateRevision);
		RelatedTopicReuseHoldoutRankingSnapshot snapshot = snapshot(
				marker, queryKey, candidateRevision);
		RelatedTopicReuseHoldoutScoringResult scoringResult = scoringResult(
				snapshot, queryKey);
		RelatedTopicReuseHoldoutEvidenceReport report =
				RelatedTopicReuseHoldoutEvidenceReport.create(
						evaluatorSeal, snapshot, scoringResult);
		return RelatedTopicReuseHoldoutEvidenceReport.verifyExact(
				evaluatorSeal, snapshot, scoringResult, report.artifacts());
	}

	private static RelatedTopicReuseHoldoutEvaluatorSeal.VerifiedEvaluatorSeal seal(
			String marker,
			String evaluatorRevision,
			String candidateRevision) {
		List<SourceFile> evaluatorSources = List.of(new SourceFile(
				100644,
				"backend/src/test/java/Evaluator.java",
				("evaluator-" + marker).getBytes(StandardCharsets.UTF_8)));
		List<SourceFile> candidateSources = List.of(new SourceFile(
				100644,
				"backend/src/main/java/Candidate.java",
				("candidate-" + marker).getBytes(StandardCharsets.UTF_8)));
		String evaluatorSha256 = RelatedTopicReuseHoldoutEvaluatorSeal.sourceSha256(
				SourceRole.EVALUATOR, evaluatorRevision, evaluatorSources);
		String candidateSha256 = RelatedTopicReuseHoldoutEvaluatorSeal.sourceSha256(
				SourceRole.CANDIDATE, candidateRevision, candidateSources);
		return RelatedTopicReuseHoldoutEvaluatorSeal.verify(
				evaluatorRevision,
				evaluatorSha256,
				candidateRevision,
				candidateSha256,
				new RepositoryState(
						evaluatorRevision,
						"",
						candidateRevision,
						candidateSha256,
						true),
				evaluatorSources,
				candidateSources);
	}

	private static RelatedTopicReuseHoldoutRankingSnapshot snapshot(
			String marker, String queryKey, String candidateRevision) {
		RankedPaper first = new RankedPaper("paper-one-" + marker, 0.0d);
		RankedPaper second = new RankedPaper("paper-two-" + marker, -0.0d);
		List<RankedPaper> papers = List.of(first, second);
		RankingRun run = new RankingRun(papers, papers, List.of(), List.of(), papers);
		HiddenPerturbation hidden = new HiddenPerturbation(
				"hidden-other-" + marker,
				"hidden-catalog-" + marker,
				List.of(),
				papers);
		QueryRanking query = new QueryRanking(queryKey, run, run, hidden);
		return RelatedTopicReuseHoldoutRankingSnapshot.seal(
				"bundle-" + marker,
				"corpus-" + marker,
				sha256(("policy-" + marker).getBytes(StandardCharsets.UTF_8)),
				sha256(("corpus-" + marker).getBytes(StandardCharsets.UTF_8)),
				sha256(("manifest-" + marker).getBytes(StandardCharsets.UTF_8)),
				sha256(("judgments-" + marker).getBytes(StandardCharsets.UTF_8)),
				4096L + marker.length(),
				candidateRevision,
				10,
				List.of(queryKey),
				List.of(query),
				new StructuralCounters(0L, 0L));
	}

	private static RelatedTopicReuseHoldoutScoringResult scoringResult(
			RelatedTopicReuseHoldoutRankingSnapshot snapshot, String queryKey) {
		RankingMetrics emptyMetrics = new RankingMetrics(
				0, 0, null, null, 0.0d, null);
		QueryScore query = new QueryScore(
				queryKey,
				RelatedTopicReuseHoldoutBundle.QueryKind.NO_SEED_FALLBACK_CONTROL,
				emptyMetrics,
				emptyMetrics,
				new MetricDeltas(null, null, 0.0d, null),
				0, 0, 0, false, 0, 0,
				true, true, true, true, true, true, true, true, true);
		RankingSummary summary = new RankingSummary(
				1, 0, 0, 1, 0, null, null, 0.0d, null);
		AggregateMetrics aggregate = new AggregateMetrics(
				null, null, 0.0d, null,
				0, 0, 0, 0.0d, 0, 0, 0, 0, 0,
				0L, 0L);
		List<GateOutcome> gates = Arrays.stream(GateId.values())
				.map(gate -> new GateOutcome(gate, true))
				.toList();
		return new RelatedTopicReuseHoldoutScoringResult(
				new ScoreIdentity(
						"related-topic-reuse-holdout-evaluation-v1",
						snapshot.bundleId(),
						snapshot.corpusId(),
						snapshot.policySha256(),
						snapshot.corpusSha256(),
						snapshot.manifestSha256(),
						snapshot.judgmentsSha256(),
						snapshot.evidenceSha256(),
						snapshot.judgmentsBytes(),
						snapshot.candidateRevision(),
						snapshot.cutoff(),
						snapshot.queryOrder()),
				List.of(query),
				summary,
				summary,
				aggregate,
				new StructuralAssessment(0, 0, 0, 0, 0, 0, 0, 0, 0),
				gates,
				true,
				false,
				false,
				false,
				false);
	}

	static Path writeRetained(
			Path root,
			RelatedTopicReuseHoldoutEvidenceReport.VerifiedArtifacts expected)
			throws IOException {
		var bundle = RelatedTopicReuseHoldoutEvidenceReportBundle.expectedBundle(expected);
		Path directory = privateDirectory(root.resolve(expected.reportId()));
		for (Map.Entry<String, byte[]> artifact : bundle.artifacts().entrySet()) {
			writePrivate(directory.resolve(artifact.getKey()), artifact.getValue());
		}
		writePrivate(directory.resolve("manifest.json"), bundle.manifestBytes());
		return directory;
	}

	static void writePrivate(Path path, byte[] content) throws IOException {
		Files.write(path, content);
		if (supportsPosix(path)) {
			Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-------"));
		}
	}

	static Path privateDirectory(Path path) throws IOException {
		Files.createDirectories(path);
		if (supportsPosix(path)) {
			Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rwx------"));
		}
		return path.toAbsolutePath().normalize();
	}

	static List<String> bundleFilenames() {
		return BUNDLE_FILENAMES;
	}

	static boolean supportsPosix(Path path) {
		return path.getFileSystem().supportedFileAttributeViews().contains("posix");
	}

	static void assertPrivate(Path path, boolean directory) throws IOException {
		PosixFileAttributeView view = Files.getFileAttributeView(
				path, PosixFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
		if (view != null) {
			assertThat(view.readAttributes().permissions()).isEqualTo(
					PosixFilePermissions.fromString(directory ? "rwx------" : "rw-------"));
		}
	}

	static List<String> entryNames(Path directory) throws IOException {
		try (Stream<Path> paths = Files.list(directory)) {
			return paths.map(path -> path.getFileName().toString()).sorted().toList();
		}
	}

	static String sha256(byte[] bytes) {
		try {
			return HexFormat.of().formatHex(
					MessageDigest.getInstance("SHA-256").digest(bytes));
		}
		catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException(exception);
		}
	}

	record Fixture(
			Path repository,
			Path externalRoot,
			RelatedTopicReuseHoldoutEvidenceReport.VerifiedArtifacts expected) {
	}
}
