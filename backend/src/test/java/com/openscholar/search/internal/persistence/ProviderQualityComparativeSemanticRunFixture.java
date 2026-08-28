package com.openscholar.search.internal.persistence;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import com.openscholar.provider.ProviderId;
import com.openscholar.search.internal.persistence.ProviderQualityComparativeEvaluator.ComparativeCapture;
import com.openscholar.search.internal.persistence.ProviderQualityComparativeEvaluator.ProviderCallEvidence;
import com.openscholar.search.internal.persistence.ProviderQualityComparativeEvaluator.QueryCapture;
import com.openscholar.search.internal.persistence.ProviderQualityComparativeEvaluator.ScenarioCapture;
import com.openscholar.search.internal.persistence.ProviderQualityComparativeEvaluator.ScenarioId;
import com.openscholar.search.internal.persistence.ProviderQualityComparativeReviewPacket.Generated;
import com.openscholar.search.internal.persistence.ProviderQualityComparativeReviewWorksheet.CompiledJudgments;
import com.openscholar.search.internal.persistence.ProviderQualityComparativeRunSealBundle.Bindings;
import com.openscholar.search.internal.persistence.ProviderQualityComparativeRunSealBundle.VerifiedRunSeal;
import com.openscholar.search.internal.persistence.ProviderQualityComparativeScorer.ScoringResult;
import com.openscholar.search.internal.persistence.ProviderQualityComparativeScoringPolicy.BoundPolicy;
import com.openscholar.search.internal.persistence.ProviderQualityLiveQuerySet.BoundQuerySet;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Test-only factory for fully replayable retained comparative runs. Every input is
 * synthetic and empty, but every lineage, canonical-byte, and custody boundary is real.
 */
final class ProviderQualityComparativeSemanticRunFixture {

	private static final long MAXIMUM_EVIDENCE_BYTES = 64L * 1024L * 1024L;
	private static final String REVIEW_PACKET_FILENAME = "review-packet.json";
	private static final String WORKSHEET_FILENAME = "completed-worksheet.json";
	private static final String JUDGMENTS_FILENAME = "judgments.json";

	private ProviderQualityComparativeSemanticRunFixture() {
	}

	static Cohort loadCohort(ObjectMapper objectMapper) throws Exception {
		BoundQuerySet querySet = ProviderQualityLiveQuerySet.loadFrozen(objectMapper);
		BoundPolicy policy = ProviderQualityComparativeScoringPolicy.loadBound(
				objectMapper, ProviderQualityComparativeScoringPolicy.RESOURCE_PATH);
		policy.validateReference(
				ProviderQualityComparativeScoringPolicy.POLICY_ID,
				ProviderQualityComparativeScoringPolicy.POLICY_SHA256);
		return new Cohort(querySet, policy);
	}

	static RetainedRun publish(
			ObjectMapper objectMapper,
			Path artifactRepositoryRoot,
			Path externalSealRoot,
			Cohort cohort,
			RunSpec spec) throws Exception {
		Objects.requireNonNull(objectMapper, "objectMapper");
		Path repositoryRoot = Objects.requireNonNull(
				artifactRepositoryRoot, "artifactRepositoryRoot");
		Objects.requireNonNull(externalSealRoot, "externalSealRoot");
		Cohort frozen = Objects.requireNonNull(cohort, "cohort");
		RunSpec run = Objects.requireNonNull(spec, "spec");

		ComparativeCapture capture = emptyCapture(frozen.querySet(), run.capturedAt());
		Map<String, Object> evidenceArtifacts =
				EuropePmcComparativeLiveEvaluationTests.artifacts(
						run.evidenceId(),
						run.capturedAt(),
						run.repositoryRevision(),
						frozen.querySet().sha256(),
						capture);
		Path evidenceDirectory = ProviderQualityEvidenceWriter.forRepository(
				objectMapper, repositoryRoot, MAXIMUM_EVIDENCE_BYTES)
				.write(run.evidenceId(), evidenceArtifacts)
				.directory();
		ProviderQualityComparativeEvidenceBundle evidence =
				ProviderQualityComparativeEvidenceBundle.verify(
						objectMapper, evidenceDirectory);
		ProviderQualityComparativeScorer.preflightForReview(
				objectMapper, evidence, frozen.querySet(), frozen.policy());

		Generated generated = ProviderQualityComparativeReviewPacket.generate(
				objectMapper, evidence, frozen.querySet(), frozen.policy());
		Path reviewDirectory = privateDirectory(repositoryRoot
				.resolve("backend/target/provider-quality-review-inputs")
				.resolve(run.evidenceId()));
		Path reviewPacket = writePrivateFile(
				reviewDirectory.resolve(REVIEW_PACKET_FILENAME),
				generated.reviewPacketBytes());
		ProviderQualityComparativeReviewPacket.verifyReviewedPacket(
				reviewPacket, generated);

		byte[] worksheetBytes = completedEmptyWorksheet(objectMapper, generated);
		Path worksheet = writePrivateFile(
				reviewDirectory.resolve(WORKSHEET_FILENAME), worksheetBytes);
		CompiledJudgments compiled = ProviderQualityComparativeReviewWorksheet.compile(
				objectMapper, worksheetBytes, generated.expectedReviewContext());
		Path judgments = writePrivateFile(
				reviewDirectory.resolve(JUDGMENTS_FILENAME), compiled.canonicalBytes());

		ScoringResult result = ProviderQualityComparativeScorer.score(
				evidence,
				compiled.boundJudgments(),
				frozen.policy(),
				generated.reviewPacketSha256());
		Path scoreDirectory = ProviderQualityEvidenceWriter.forRepository(
				objectMapper,
				repositoryRoot,
				ProviderQualityComparativeScoreReportBundle.MAXIMUM_REPORT_BYTES)
				.write(result.reportId(), ProviderQualityComparativeScorer.artifacts(result))
				.directory();
		ProviderQualityComparativeScoreReportBundle report =
				ProviderQualityComparativeScoreReportBundle.verifyExact(
						objectMapper, scoreDirectory, result);

		Bindings bindings = new Bindings(
				evidence.evidenceId(),
				evidence.manifestSha256(),
				result.captureRepositoryRevision(),
				result.captureMeasuredAt(),
				result.querySetId(),
				result.querySetSha256(),
				result.scoringPolicyId(),
				result.scoringPolicySha256(),
				generated.reviewPacketSha256(),
				compiled.worksheetSha256(),
				compiled.sha256(),
				report.reportId(),
				report.manifestSha256());
		VerifiedRunSeal seal = ProviderQualityComparativeRunSealBundle.publishAndVerify(
				objectMapper,
				externalSealRoot,
				bindings,
				EuropePmcComparativeOfflineScoringTests.runSealSources(
						bindings,
						evidenceDirectory,
						reviewPacket,
						worksheet,
						judgments,
						scoreDirectory));
		return new RetainedRun(seal, result);
	}

	private static ComparativeCapture emptyCapture(
			BoundQuerySet boundQuerySet, Instant capturedAt) {
		List<QueryCapture> queries = boundQuerySet.querySet().queries().stream()
				.map(query -> new QueryCapture(
						query.key(),
						query.text(),
						true,
						List.of(
								successfulEmptyCall(ProviderId.OPENALEX, capturedAt),
								successfulEmptyCall(ProviderId.EUROPE_PMC, capturedAt)),
						List.of(),
						emptyScenarios()))
				.toList();
		return new ComparativeCapture(
				2,
				boundQuerySet.querySet().querySetId(),
				boundQuerySet.querySet().sourcePolicy(),
				boundQuerySet.querySet().pageSize(),
				true,
				queries);
	}

	private static ProviderCallEvidence successfulEmptyCall(
			ProviderId provider, Instant capturedAt) {
		return new ProviderCallEvidence(
				provider, "SUCCESS", 1L, 0, 0L, capturedAt, null, false);
	}

	private static Map<ScenarioId, ScenarioCapture> emptyScenarios() {
		Map<ScenarioId, ScenarioCapture> scenarios = new LinkedHashMap<>();
		for (ScenarioId scenario : ScenarioId.values()) {
			scenarios.put(
					scenario,
					new ScenarioCapture(scenario, List.of(), List.of()));
		}
		return Map.copyOf(scenarios);
	}

	private static byte[] completedEmptyWorksheet(
			ObjectMapper objectMapper, Generated generated) throws IOException {
		ObjectNode worksheet = objectMapper.valueToTree(generated.worksheetSkeleton());
		worksheet.put(
				"independenceAttestation",
				ProviderQualityComparativeJudgments.INDEPENDENCE_ATTESTATION);
		for (JsonNode query : worksheet.required("queries")) {
			((ObjectNode) query).put("mustSeparateReviewComplete", true);
		}
		return ProviderQualityComparativeReviewPacket.canonicalBytes(
				objectMapper, worksheet);
	}

	private static Path privateDirectory(Path path) throws IOException {
		Files.createDirectories(path);
		if (supportsPosix(path)) {
			Files.setPosixFilePermissions(
					path, PosixFilePermissions.fromString("rwx------"));
		}
		return path;
	}

	private static Path writePrivateFile(Path path, byte[] bytes) throws IOException {
		Path written = Files.write(
				path,
				bytes,
				StandardOpenOption.CREATE_NEW,
				StandardOpenOption.WRITE);
		if (supportsPosix(written)) {
			Files.setPosixFilePermissions(
					written, PosixFilePermissions.fromString("rw-------"));
		}
		return written;
	}

	private static boolean supportsPosix(Path path) {
		return Files.getFileAttributeView(
				path, PosixFileAttributeView.class, LinkOption.NOFOLLOW_LINKS) != null;
	}

	record RunSpec(
			String evidenceId,
			Instant capturedAt,
			String repositoryRevision) {
	}

	record Cohort(BoundQuerySet querySet, BoundPolicy policy) {
	}

	record RetainedRun(VerifiedRunSeal seal, ScoringResult expectedResult) {
	}
}
