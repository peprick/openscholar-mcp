package com.openscholar.search.internal.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.Channels;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import com.openscholar.search.internal.persistence.ProviderQualityComparativeJudgments.BoundJudgments;
import com.openscholar.search.internal.persistence.ProviderQualityComparativeReviewPacket.Generated;
import com.openscholar.search.internal.persistence.ProviderQualityComparativeReviewWorksheet.CompiledJudgments;
import com.openscholar.search.internal.persistence.ProviderQualityComparativeScorer.ScoringResult;
import com.openscholar.search.internal.persistence.ProviderQualityComparativeScoringPolicy.BoundPolicy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Manual, opt-in offline scoring entry point. The class has no Spring context,
 * provider adapter, database, Docker, document, or network dependency.
 */
@EnabledIfEnvironmentVariable(
		named = "RUN_PROVIDER_QUALITY_COMPARATIVE_SCORING",
		matches = "true")
class EuropePmcComparativeOfflineScoringTests {

	private static final String EVIDENCE_DIRECTORY_ENV =
			"OPENSCHOLAR_PROVIDER_QUALITY_COMPARATIVE_EVIDENCE";
	private static final String JUDGMENT_PACKET_ENV =
			"OPENSCHOLAR_PROVIDER_QUALITY_COMPARATIVE_JUDGMENTS";
	private static final String REVIEW_PACKET_ENV =
			"OPENSCHOLAR_PROVIDER_QUALITY_COMPARATIVE_REVIEW_PACKET";
	private static final String SCORE_REPORT_ENV =
			"OPENSCHOLAR_PROVIDER_QUALITY_COMPARATIVE_SCORE_REPORT";
	private static final String WORKSHEET_ENV =
			"OPENSCHOLAR_PROVIDER_QUALITY_COMPARATIVE_WORKSHEET";
	private static final String RUN_SEAL_ROOT_ENV =
			"OPENSCHOLAR_PROVIDER_QUALITY_COMPARATIVE_RUN_SEAL_ROOT";

	@Test
	void scoresVerifiedEvidenceWithoutContactingProviders() throws Exception {
		Path repositoryRoot = EuropePmcComparativeLiveEvaluationTests.repositoryRoot();
		String repositoryRevision =
				EuropePmcComparativeLiveEvaluationTests.requiredRepositoryRevision(repositoryRoot);
		Path evidenceDirectory = requiredAbsolutePath(EVIDENCE_DIRECTORY_ENV);
		Path judgmentPacket = requiredAbsolutePath(JUDGMENT_PACKET_ENV);
		Path reviewPacket = requiredAbsolutePath(REVIEW_PACKET_ENV);
		Path retainedReport = optionalAbsolutePath(SCORE_REPORT_ENV);
		Path worksheet = optionalAbsolutePath(WORKSHEET_ENV);
		Path runSealRoot = optionalAbsolutePath(RUN_SEAL_ROOT_ENV);
		boolean runSealRequested = validateRunSealOptionPair(worksheet, runSealRoot);
		if (retainedReport != null) {
			retainedReport = validateExternalReplayPath(repositoryRoot, retainedReport);
		}
		if (runSealRequested) {
			runSealRoot = validateExternalRunSealRoot(repositoryRoot, runSealRoot);
		}
		ObjectMapper objectMapper = JsonMapper.builder().build();

		ProviderQualityComparativeEvidenceBundle evidence =
				ProviderQualityComparativeEvidenceBundle.verify(objectMapper, evidenceDirectory);
		BoundJudgments judgments =
				ProviderQualityComparativeJudgments.loadBound(objectMapper, judgmentPacket);
		BoundPolicy policy = ProviderQualityComparativeScoringPolicy.loadBound(
				objectMapper, ProviderQualityComparativeScoringPolicy.RESOURCE_PATH);
		policy.validateReference(
				ProviderQualityComparativeScoringPolicy.POLICY_ID,
				ProviderQualityComparativeScoringPolicy.POLICY_SHA256);
		ProviderQualityLiveQuerySet.BoundQuerySet frozenQuerySet =
				ProviderQualityLiveQuerySet.loadFrozen(objectMapper);
		ProviderQualityComparativeScorer.preflightForReview(
				objectMapper, evidence, frozenQuerySet, policy);
		Generated expectedReviewPacket = ProviderQualityComparativeReviewPacket.generate(
				objectMapper, evidence, frozenQuerySet, policy);
		ProviderQualityComparativeReviewPacket.verifyReviewedPacket(
				reviewPacket, expectedReviewPacket);
		CompiledJudgments compiledWorksheet = null;
		if (worksheet != null) {
			compiledWorksheet = ProviderQualityComparativeReviewWorksheet.compile(
					objectMapper, worksheet, expectedReviewPacket.expectedReviewContext());
			requireExactJudgmentBytes(judgmentPacket, compiledWorksheet.canonicalBytes());
			assertThat(compiledWorksheet.sha256())
					.as("seal only the exact judgments compiled from the retained worksheet")
					.isEqualTo(judgments.sha256());
		}

		ScoringResult result = ProviderQualityComparativeScorer.score(
				evidence, judgments, policy, expectedReviewPacket.reviewPacketSha256());
		assertThat(result.querySetId()).isEqualTo(frozenQuerySet.querySet().querySetId());
		assertThat(result.querySetSha256()).isEqualTo(frozenQuerySet.sha256());
		assertThat(result.queries())
				.extracting(ProviderQualityComparativeScorer.QueryScore::queryKey)
				.containsExactlyElementsOf(frozenQuerySet.querySet().queries().stream()
						.map(ProviderQualityLiveQuerySet.Query::key)
						.toList());
		assertThat(result.captureRepositoryRevision())
				.as("score only with the exact clean capture/evaluator revision")
				.isEqualTo(repositoryRevision);
		assertThat(result.captureMeasuredAt())
				.as("carry the exact capture time through the score-report lineage")
				.isEqualTo(Instant.parse(
						evidence.summary().required("measuredAt").asString()).toString());
		assertThat(result.schemaVersion())
				.isEqualTo(ProviderQualityComparativeScorer.REPORT_SCHEMA_VERSION);
		assertThat(result.readerFacing()).isFalse();
		assertThat(result.defaultEnablementDecision()).isFalse();

		String mode;
		Path reportDirectory;
		if (retainedReport == null) {
			mode = "generated";
			reportDirectory = ProviderQualityEvidenceWriter.forRepository(
					objectMapper,
					repositoryRoot,
					ProviderQualityComparativeScoreReportBundle.MAXIMUM_REPORT_BYTES)
					.write(result.reportId(), ProviderQualityComparativeScorer.artifacts(result))
					.directory();
		}
		else {
			mode = "replayed";
			reportDirectory = retainedReport;
		}
		ProviderQualityComparativeScoreReportBundle verified =
				ProviderQualityComparativeScoreReportBundle.verifyExact(
						objectMapper, reportDirectory, result);
		ProviderQualityComparativeRunSealBundle.VerifiedRunSeal runSeal = null;
		if (runSealRequested) {
			ProviderQualityComparativeRunSealBundle.Bindings bindings =
					new ProviderQualityComparativeRunSealBundle.Bindings(
							evidence.evidenceId(),
							evidence.manifestSha256(),
							result.captureRepositoryRevision(),
							result.captureMeasuredAt(),
							result.querySetId(),
							result.querySetSha256(),
							result.scoringPolicyId(),
							result.scoringPolicySha256(),
							expectedReviewPacket.reviewPacketSha256(),
							compiledWorksheet.worksheetSha256(),
							judgments.sha256(),
							verified.reportId(),
							verified.manifestSha256());
			runSeal = ProviderQualityComparativeRunSealBundle.publishAndVerify(
					objectMapper,
					runSealRoot,
					bindings,
					runSealSources(
							bindings,
							evidence.sourceDirectory(),
							reviewPacket,
							worksheet,
							judgmentPacket,
							reportDirectory));
			verifySealedSemantics(
					objectMapper, runSeal, result, frozenQuerySet, policy);
		}

		if (runSeal == null) {
			System.out.printf(
					Locale.ROOT,
					"provider-quality-comparative-score-v2 mode=%s report-id=%s "
							+ "report-manifest-sha256=%s evidence=%s captured-at=%s "
							+ "revision=%s queries=%d bytes=%d%n",
					mode,
					verified.reportId(),
					verified.manifestSha256(),
					result.evidenceId(),
					result.captureMeasuredAt(),
					repositoryRevision,
					result.queryCount(),
					verified.totalBytes());
		}
		else {
			System.out.printf(
					Locale.ROOT,
					"provider-quality-comparative-run-seal-v1 mode=%s run-seal-id=%s "
							+ "run-seal-sha256=%s report-id=%s%n",
					mode,
					runSeal.sealId(),
					runSeal.sealSha256(),
					verified.reportId());
		}
	}

	private static Map<String, Path> runSealSources(
			ProviderQualityComparativeRunSealBundle.Bindings bindings,
			Path evidenceDirectory,
			Path reviewPacket,
			Path worksheet,
			Path judgments,
			Path scoreDirectory) {
		Map<String, Path> sources = new LinkedHashMap<>();
		Path capture = Path.of("capture", bindings.evidenceId());
		for (String filename : List.of(
				"manifest.json",
				"summary.json",
				"blinded-candidates.json",
				"provenance-map.json",
				"reconciliation-trace.json")) {
			sources.put(
					capture.resolve(filename).toString().replace('\\', '/'),
					evidenceDirectory.resolve(filename));
		}
		sources.put("review/review-packet.json", reviewPacket);
		sources.put("review/completed-worksheet.json", worksheet);
		sources.put("review/judgments.json", judgments);
		Path score = Path.of("score", bindings.reportId());
		for (String filename : List.of(
				"manifest.json", "query-scores.json", "score-summary.json")) {
			sources.put(
					score.resolve(filename).toString().replace('\\', '/'),
					scoreDirectory.resolve(filename));
		}
		return Map.copyOf(sources);
	}

	private static void verifySealedSemantics(
			ObjectMapper objectMapper,
			ProviderQualityComparativeRunSealBundle.VerifiedRunSeal sealed,
			ScoringResult expectedResult,
			ProviderQualityLiveQuerySet.BoundQuerySet frozenQuerySet,
			BoundPolicy policy) throws Exception {
		ProviderQualityComparativeRunSealBundle.Bindings bindings = sealed.bindings();
		Path root = sealed.sourceDirectory();
		Path captureDirectory = root.resolve("capture").resolve(bindings.evidenceId());
		Path packetPath = root.resolve("review/review-packet.json");
		Path worksheetPath = root.resolve("review/completed-worksheet.json");
		Path judgmentPath = root.resolve("review/judgments.json");
		Path scoreDirectory = root.resolve("score").resolve(bindings.reportId());

		ProviderQualityComparativeEvidenceBundle evidence =
				ProviderQualityComparativeEvidenceBundle.verify(objectMapper, captureDirectory);
		ProviderQualityComparativeScorer.preflightForReview(
				objectMapper, evidence, frozenQuerySet, policy);
		Generated generated = ProviderQualityComparativeReviewPacket.generate(
				objectMapper, evidence, frozenQuerySet, policy);
		ProviderQualityComparativeReviewPacket.verifyReviewedPacket(packetPath, generated);
		CompiledJudgments compiled = ProviderQualityComparativeReviewWorksheet.compile(
				objectMapper, worksheetPath, generated.expectedReviewContext());
		requireExactJudgmentBytes(judgmentPath, compiled.canonicalBytes());
		BoundJudgments judgments =
				ProviderQualityComparativeJudgments.loadBound(objectMapper, judgmentPath);
		assertThat(compiled.worksheetSha256()).isEqualTo(bindings.completedWorksheetSha256());
		assertThat(compiled.sha256()).isEqualTo(bindings.judgmentsSha256());
		assertThat(judgments.sha256()).isEqualTo(bindings.judgmentsSha256());

		ScoringResult result = ProviderQualityComparativeScorer.score(
				evidence, judgments, policy, generated.reviewPacketSha256());
		assertThat(result)
				.as("the promoted bytes must reproduce the exact scored result")
				.isEqualTo(expectedResult);
		ProviderQualityComparativeScoreReportBundle.verifyExact(
				objectMapper, scoreDirectory, result);
		ProviderQualityComparativeRunSealBundle.verifyExact(
				objectMapper, root, bindings);
	}

	private static Path optionalAbsolutePath(String environmentName) {
		if (System.getenv(environmentName) == null) {
			return null;
		}
		return requiredAbsolutePath(environmentName);
	}

	static Path validateExternalReplayPath(Path repositoryRoot, Path reportDirectory) {
		Path repository = repositoryRoot.toAbsolutePath().normalize();
		Path report = reportDirectory.toAbsolutePath().normalize();
		Path target = repository.resolve("backend/target").normalize();
		if (report.startsWith(target)) {
			throw new IllegalStateException(
					SCORE_REPORT_ENV + " must resolve outside backend/target");
		}
		if (!Files.exists(report, LinkOption.NOFOLLOW_LINKS)) {
			throw new IllegalStateException(SCORE_REPORT_ENV + " must name an existing directory");
		}
		try {
			Path resolvedReport = report.toRealPath();
			Path resolvedTarget = target.toRealPath();
			if (resolvedReport.startsWith(resolvedTarget)) {
				throw new IllegalStateException(
						SCORE_REPORT_ENV + " must resolve outside backend/target");
			}
		}
		catch (IOException exception) {
			throw new IllegalStateException(
					SCORE_REPORT_ENV + " must name a resolvable external directory",
					exception);
		}
		return report;
	}

	static boolean validateRunSealOptionPair(Path worksheet, Path runSealRoot) {
		if ((worksheet == null) != (runSealRoot == null)) {
			throw new IllegalStateException(
					WORKSHEET_ENV + " and " + RUN_SEAL_ROOT_ENV
							+ " must either both be set or both be unset");
		}
		return worksheet != null;
	}

	static Path validateExternalRunSealRoot(Path repositoryRoot, Path suppliedRoot) {
		Path repository = repositoryRoot.toAbsolutePath().normalize();
		Path root = suppliedRoot.toAbsolutePath().normalize();
		if (Files.isSymbolicLink(root)
				|| !Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
			throw new IllegalStateException(
					RUN_SEAL_ROOT_ENV + " must name an existing real directory");
		}
		try {
			Path resolvedRepository = repository.toRealPath();
			Path resolvedRoot = root.toRealPath();
			if (resolvedRoot.startsWith(resolvedRepository)
					|| resolvedRepository.startsWith(resolvedRoot)) {
				throw new IllegalStateException(
						RUN_SEAL_ROOT_ENV + " must resolve outside the repository");
			}
			return resolvedRoot;
		}
		catch (IOException exception) {
			throw new IllegalStateException(
					RUN_SEAL_ROOT_ENV + " must name a resolvable external directory",
					exception);
		}
	}

	private static void requireExactJudgmentBytes(Path path, byte[] expected) throws IOException {
		if (Files.isSymbolicLink(path)
				|| !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
			throw new IOException("judgment packet must be a real regular file");
		}
		try (SeekableByteChannel channel = Files.newByteChannel(
				path, Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS));
				InputStream input = Channels.newInputStream(channel)) {
			if (channel.size() != expected.length) {
				throw new IOException(
						"judgment packet does not exactly match the compiled worksheet");
			}
			byte[] actual = input.readNBytes(expected.length + 1);
			if (channel.size() != expected.length
					|| actual.length != expected.length
					|| !MessageDigest.isEqual(actual, expected)) {
				throw new IOException(
						"judgment packet does not exactly match the compiled worksheet");
			}
		}
	}

	private static Path requiredAbsolutePath(String environmentName) {
		String value = System.getenv(environmentName);
		if (value == null || value.isBlank()) {
			throw new IllegalStateException(environmentName + " must name an absolute path");
		}
		Path path;
		try {
			path = Path.of(value).toAbsolutePath().normalize();
		}
		catch (RuntimeException exception) {
			throw new IllegalStateException(
					environmentName + " must name an absolute path", exception);
		}
		if (!Path.of(value).isAbsolute()) {
			throw new IllegalStateException(environmentName + " must name an absolute path");
		}
		return path;
	}
}
