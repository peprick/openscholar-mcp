package com.openscholar.search.internal.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.util.EnumMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.openscholar.search.internal.persistence.ProviderQualityComparativeLongitudinalComparison.Comparison;
import com.openscholar.search.internal.persistence.ProviderQualityComparativeLongitudinalComparison.CountChange;
import com.openscholar.search.internal.persistence.ProviderQualityComparativeLongitudinalComparison.CoverageChange;
import com.openscholar.search.internal.persistence.ProviderQualityComparativeLongitudinalComparison.DeduplicationChange;
import com.openscholar.search.internal.persistence.ProviderQualityComparativeLongitudinalComparison.FieldRecoveryChange;
import com.openscholar.search.internal.persistence.ProviderQualityComparativeLongitudinalComparison.MetadataRecoveryChange;
import com.openscholar.search.internal.persistence.ProviderQualityComparativeLongitudinalComparison.MustSeparateChange;
import com.openscholar.search.internal.persistence.ProviderQualityComparativeLongitudinalComparison.QueryChange;
import com.openscholar.search.internal.persistence.ProviderQualityComparativeLongitudinalComparison.QueryRankingChange;
import com.openscholar.search.internal.persistence.ProviderQualityComparativeLongitudinalComparison.QueryScenarioChange;
import com.openscholar.search.internal.persistence.ProviderQualityComparativeLongitudinalComparison.RankingChange;
import com.openscholar.search.internal.persistence.ProviderQualityComparativeLongitudinalComparison.RateChange;
import com.openscholar.search.internal.persistence.ProviderQualityComparativeLongitudinalComparison.RunReference;
import com.openscholar.search.internal.persistence.ProviderQualityComparativeLongitudinalComparison.RunSnapshot;
import com.openscholar.search.internal.persistence.ProviderQualityComparativeLongitudinalComparison.ScenarioChange;
import com.openscholar.search.internal.persistence.ProviderQualityComparativeLongitudinalComparison.Transition;
import com.openscholar.search.internal.persistence.ProviderQualityComparativeLongitudinalComparison.Use;
import com.openscholar.search.internal.persistence.ProviderQualityComparativeScorer.DeduplicationScore;
import com.openscholar.search.internal.persistence.ProviderQualityComparativeScorer.ExpectedFieldRecovery;
import com.openscholar.search.internal.persistence.ProviderQualityComparativeScorer.FieldRecovery;
import com.openscholar.search.internal.persistence.ProviderQualityComparativeScorer.MustSeparateMeasurement;
import com.openscholar.search.internal.persistence.ProviderQualityComparativeScorer.QueryScenarioScore;
import com.openscholar.search.internal.persistence.ProviderQualityComparativeScorer.QueryScore;
import com.openscholar.search.internal.persistence.ProviderQualityComparativeScorer.RankingScore;
import com.openscholar.search.internal.persistence.ProviderQualityComparativeScorer.RankingSummaryScore;
import com.openscholar.search.internal.persistence.ProviderQualityComparativeScorer.ScenarioSummary;
import com.openscholar.search.internal.persistence.ProviderQualityComparativeScorer.UniqueRelevantQueryCoverage;
import com.openscholar.search.internal.persistence.ProviderQualityComparativeScoringPolicy.Scenario;
import com.openscholar.search.internal.persistence.ProviderQualityMetrics.MetadataField;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

class ProviderQualityComparativeLongitudinalReportBundleTests {

	private static final ObjectMapper OBJECT_MAPPER = JsonMapper.builder().build();
	private static final String SHA_A = "a".repeat(64);
	private static final String SHA_B = "b".repeat(64);
	private static final String SHA_C = "c".repeat(64);
	private static final String SHA_D = "d".repeat(64);
	private static final String SHA_E = "e".repeat(64);

	@TempDir
	private Path temporaryDirectory;

	@Test
	void publishesAndExactlyVerifiesDeterministicPrivateBytesWithoutInputMutation()
			throws Exception {
		Comparison expected = comparison();
		byte[] inputBefore = canonical(expected);
		ProviderQualityComparativeLongitudinalReportBundle published = publish("happy", expected);
		Map<String, byte[]> retainedBefore = snapshot(published.sourceDirectory());

		ProviderQualityComparativeLongitudinalReportBundle verified =
				ProviderQualityComparativeLongitudinalReportBundle.verifyExact(
						OBJECT_MAPPER, published.sourceDirectory(), expected);
		ProviderQualityComparativeLongitudinalReportBundle repeated = publish(
				"repeat", expected);

		assertThat(published.comparisonId()).isEqualTo(expected.comparisonId());
		assertThat(verified.comparisonId()).isEqualTo(expected.comparisonId());
		assertThat(repeated.comparisonId()).isEqualTo(expected.comparisonId());
		assertThat(published.manifestSha256()).isEqualTo(verified.manifestSha256());
		assertThat(repeated.manifestSha256()).isEqualTo(published.manifestSha256());
		assertThat(published.payloadBytes()).isPositive();
		assertThat(published.totalBytes()).isGreaterThan(published.payloadBytes());
		assertThat(relativeEntries(published.sourceDirectory()))
				.containsExactly("longitudinal-report.json", "manifest.json");
		assertSnapshot(retainedBefore, snapshot(repeated.sourceDirectory()));
		assertSnapshot(retainedBefore, snapshot(published.sourceDirectory()));
		assertThat(canonical(expected)).isEqualTo(inputBefore);
		assertPrivateTreeWhenPosix(published.sourceDirectory());
	}

	@Test
	void canonicalBytesIgnoreCallerPrettyPrintConfiguration() throws Exception {
		Comparison expected = comparison();
		ProviderQualityComparativeLongitudinalReportBundle compact =
				publish("compact-mapper", expected);
		ObjectMapper prettyMapper = JsonMapper.builder()
				.enable(SerializationFeature.INDENT_OUTPUT)
				.build();
		ProviderQualityComparativeLongitudinalReportBundle pretty =
				ProviderQualityComparativeLongitudinalReportBundle.publishAndVerify(
						prettyMapper,
						temporaryDirectory.resolve("pretty-mapper"),
						expected);

		assertThat(pretty.manifestSha256()).isEqualTo(compact.manifestSha256());
		assertSnapshot(
				snapshot(compact.sourceDirectory()),
				snapshot(pretty.sourceDirectory()));
		ProviderQualityComparativeLongitudinalReportBundle.verifyExact(
				OBJECT_MAPPER, pretty.sourceDirectory(), expected);
		ProviderQualityComparativeLongitudinalReportBundle.verifyExact(
				prettyMapper, compact.sourceDirectory(), expected);
	}

	@Test
	void rejectsMissingAndExtraEntries() throws Exception {
		Comparison expected = comparison();
		Path missing = publish("missing", expected).sourceDirectory();
		Files.delete(missing.resolve("longitudinal-report.json"));
		assertFailure(missing, expected, "LONGITUDINAL_REPORT_LAYOUT_INVALID");

		Path extra = publish("extra", expected).sourceDirectory();
		Files.writeString(extra.resolve("unexpected.json"), "{}\n", StandardCharsets.UTF_8);
		assertFailure(extra, expected, "LONGITUDINAL_REPORT_LAYOUT_INVALID");
	}

	@Test
	void rejectsDirectoryManifestAndPayloadSymlinks() throws Exception {
		Comparison expected = comparison();
		Path target = publish("directory-target", expected).sourceDirectory();
		Path directoryLink = temporaryDirectory.resolve(expected.comparisonId());
		Files.createSymbolicLink(directoryLink, target);
		assertFailure(directoryLink, expected, "LONGITUDINAL_REPORT_DIRECTORY_INVALID");

		Path manifestLinked = publish("manifest-link", expected).sourceDirectory();
		replaceWithLink(manifestLinked.resolve("manifest.json"), "outside-manifest.json");
		assertFailure(manifestLinked, expected, "LONGITUDINAL_REPORT_FILE_INVALID");

		Path payloadLinked = publish("payload-link", expected).sourceDirectory();
		replaceWithLink(
				payloadLinked.resolve("longitudinal-report.json"), "outside-report.json");
		assertFailure(payloadLinked, expected, "LONGITUDINAL_REPORT_FILE_INVALID");
	}

	@Test
	void rejectsOversizedManifestPayloadAndTotal() throws Exception {
		Comparison expected = comparison();
		Path manifest = publish("large-manifest", expected).sourceDirectory();
		Files.write(
				manifest.resolve("manifest.json"),
				new byte[ProviderQualityComparativeLongitudinalReportBundle.MAXIMUM_MANIFEST_BYTES + 1]);
		assertFailure(
				manifest, expected, "LONGITUDINAL_REPORT_MANIFEST_TOO_LARGE");

		Path payload = publish("large-payload", expected).sourceDirectory();
		Files.write(
				payload.resolve("longitudinal-report.json"),
				new byte[(int) ProviderQualityComparativeLongitudinalReportBundle.MAXIMUM_REPORT_BYTES + 1]);
		assertFailure(payload, expected, "LONGITUDINAL_REPORT_PAYLOAD_TOO_LARGE");

		Path total = publish("large-total", expected).sourceDirectory();
		long manifestBytes = Files.size(total.resolve("manifest.json"));
		Files.write(
				total.resolve("longitudinal-report.json"),
				new byte[(int) (ProviderQualityComparativeLongitudinalReportBundle.MAXIMUM_REPORT_BYTES
						- manifestBytes + 1)]);
		assertFailure(total, expected, "LONGITUDINAL_REPORT_TOO_LARGE");
	}

	@Test
	void rejectsPayloadAndManifestDigestTampering() throws Exception {
		Comparison expected = comparison();
		Path payload = publish("payload-tampered", expected).sourceDirectory();
		String changed = Files.readString(payload.resolve("longitudinal-report.json"))
				.replace("\"runCount\":2", "\"runCount\":3");
		Files.writeString(payload.resolve("longitudinal-report.json"), changed);
		assertFailure(
				payload, expected, "LONGITUDINAL_REPORT_PAYLOAD_DIGEST_MISMATCH");

		Path digest = publish("digest-tampered", expected).sourceDirectory();
		String manifest = Files.readString(digest.resolve("manifest.json"));
		String digestChanged = manifest.replaceFirst(
				"(\"sha256\":\")[0-9a-f]{64}(\")", "$1" + "0".repeat(64) + "$2");
		assertThat(digestChanged).isNotEqualTo(manifest);
		Files.writeString(digest.resolve("manifest.json"), digestChanged);
		assertFailure(
				digest, expected, "LONGITUDINAL_REPORT_PAYLOAD_DIGEST_MISMATCH");
	}

	@Test
	void rejectsMalformedDuplicateAndTrailingManifestJson() throws Exception {
		Comparison expected = comparison();
		Path malformed = publish("malformed-manifest", expected).sourceDirectory();
		Files.writeString(malformed.resolve("manifest.json"), "{\n");
		assertFailure(
				malformed, expected, "LONGITUDINAL_REPORT_MANIFEST_JSON_INVALID");

		Path duplicate = publish("duplicate-manifest", expected).sourceDirectory();
		String duplicateJson = Files.readString(duplicate.resolve("manifest.json"))
				.replaceFirst(
						"\"schemaVersion\":1",
						"\"schemaVersion\":1,\"schemaVersion\":1");
		Files.writeString(duplicate.resolve("manifest.json"), duplicateJson);
		assertFailure(
				duplicate, expected, "LONGITUDINAL_REPORT_MANIFEST_JSON_INVALID");

		Path trailing = publish("trailing-manifest", expected).sourceDirectory();
		Files.writeString(
				trailing.resolve("manifest.json"),
				Files.readString(trailing.resolve("manifest.json")) + "{}\n");
		assertFailure(
				trailing, expected, "LONGITUDINAL_REPORT_MANIFEST_JSON_INVALID");
	}

	@Test
	void rejectsStrictJsonViolationsInSelfConsistentPayloads() throws Exception {
		Comparison expected = comparison();
		Path malformed = publish("malformed-payload", expected).sourceDirectory();
		replacePayloadAndRebindManifest(
				malformed, expected.comparisonId(), "{\n".getBytes(StandardCharsets.UTF_8));
		assertFailure(
				malformed, expected, "LONGITUDINAL_REPORT_PAYLOAD_JSON_INVALID");

		Path duplicate = publish("duplicate-payload", expected).sourceDirectory();
		String duplicateJson = Files.readString(duplicate.resolve("longitudinal-report.json"))
				.replaceFirst(
						"\"schemaVersion\":1",
						"\"schemaVersion\":1,\"schemaVersion\":1");
		replacePayloadAndRebindManifest(
				duplicate,
				expected.comparisonId(),
				duplicateJson.getBytes(StandardCharsets.UTF_8));
		assertFailure(
				duplicate, expected, "LONGITUDINAL_REPORT_PAYLOAD_JSON_INVALID");

		Path trailing = publish("trailing-payload", expected).sourceDirectory();
		byte[] trailingBytes = (Files.readString(trailing.resolve("longitudinal-report.json"))
				+ "{}\n").getBytes(StandardCharsets.UTF_8);
		replacePayloadAndRebindManifest(trailing, expected.comparisonId(), trailingBytes);
		assertFailure(
				trailing, expected, "LONGITUDINAL_REPORT_PAYLOAD_JSON_INVALID");
	}

	@Test
	void rejectsValidNoncanonicalAndDifferentExpectedPayloads() throws Exception {
		Comparison expected = comparison();
		Path noncanonical = publish("noncanonical-payload", expected).sourceDirectory();
		String canonical = Files.readString(noncanonical.resolve("longitudinal-report.json"));
		byte[] spaced = (canonical.stripTrailing() + " \n").getBytes(StandardCharsets.UTF_8);
		replacePayloadAndRebindManifest(noncanonical, expected.comparisonId(), spaced);
		assertFailure(
				noncanonical, expected, "LONGITUDINAL_REPORT_PAYLOAD_NOT_CANONICAL");

		Path different = publish("different-payload", expected).sourceDirectory();
		Comparison other = copyWithQuerySetId(expected, "other-query-set");
		assertFailure(
				different, other, "LONGITUDINAL_REPORT_PAYLOAD_NOT_EXPECTED");
	}

	@Test
	void rejectsAValidButNoncanonicalManifest() throws Exception {
		Comparison expected = comparison();
		Path directory = publish("noncanonical-manifest", expected).sourceDirectory();
		Path manifest = directory.resolve("manifest.json");
		Files.writeString(manifest, Files.readString(manifest).stripTrailing() + " \n");

		assertFailure(
				directory, expected, "LONGITUDINAL_REPORT_MANIFEST_NOT_CANONICAL");
	}

	@Test
	void rejectsWrongDirectoryIdentityAndInvalidExpectedIdentity() throws Exception {
		Comparison expected = comparison();
		Path directory = publish("wrong-directory", expected).sourceDirectory();
		Path renamed = directory.resolveSibling("wrong-longitudinal-report-id");
		Files.move(directory, renamed, StandardCopyOption.ATOMIC_MOVE);
		assertFailure(renamed, expected, "LONGITUDINAL_REPORT_ID_INVALID");

		Comparison invalid = copyWithComparisonId(
				expected,
				ProviderQualityComparativeLongitudinalComparison.COMPARISON_ID_PREFIX
						+ "0".repeat(64));
		assertFailure(
				temporaryDirectory.resolve(invalid.comparisonId()),
				invalid,
				"LONGITUDINAL_REPORT_EXPECTED_INVALID");

		RunSnapshot first = expected.runs().getFirst();
		RunReference malformedReference = new RunReference(
				ProviderQualityComparativeRunSealBundle.RUN_SEAL_ID_PREFIX + "a",
				first.run().runSealSha256(),
				first.run().evidenceId(),
				first.run().evidenceManifestSha256(),
				first.run().reportId(),
				first.run().reportManifestSha256(),
				first.run().captureMeasuredAt());
		Comparison malformedExpected = new Comparison(
				expected.schemaVersion(),
				expected.protocolId(),
				expected.comparisonId(),
				expected.runCount(),
				expected.captureRepositoryRevision(),
				expected.querySetId(),
				expected.querySetSha256(),
				expected.scoringPolicyId(),
				expected.scoringPolicySha256(),
				expected.queryCount(),
				List.of(
						copySnapshot(first, malformedReference),
						expected.runs().get(1)),
				expected.transitions(),
				expected.use(),
				expected.readerFacing(),
				expected.defaultEnablementDecision());
		assertFailure(
				temporaryDirectory.resolve(malformedExpected.comparisonId()),
				malformedExpected,
				"LONGITUDINAL_REPORT_EXPECTED_INVALID");
	}

	@Test
	void rejectsDuplicateLineageChronologyAndMismatchedElapsedDuration() {
		Comparison expected = comparison();
		RunSnapshot first = expected.runs().get(0);
		RunSnapshot second = expected.runs().get(1);

		RunReference duplicateEvidenceReference = copyReference(
				second.run(),
				first.run().evidenceId(),
				second.run().reportId(),
				second.run().captureMeasuredAt());
		Comparison duplicateEvidence = copyWithRunsAndTransitions(
				expected,
				List.of(first, copySnapshot(second, duplicateEvidenceReference)),
				expected.transitions());
		assertFailure(
				temporaryDirectory.resolve(duplicateEvidence.comparisonId()),
				duplicateEvidence,
				"LONGITUDINAL_REPORT_EXPECTED_INVALID");

		RunReference duplicateReportReference = copyReference(
				second.run(),
				second.run().evidenceId(),
				first.run().reportId(),
				second.run().captureMeasuredAt());
		Comparison duplicateReport = copyWithRunsAndTransitions(
				expected,
				List.of(first, copySnapshot(second, duplicateReportReference)),
				expected.transitions());
		assertFailure(
				temporaryDirectory.resolve(duplicateReport.comparisonId()),
				duplicateReport,
				"LONGITUDINAL_REPORT_EXPECTED_INVALID");

		RunReference duplicateTimeReference = copyReference(
				second.run(),
				second.run().evidenceId(),
				second.run().reportId(),
				first.run().captureMeasuredAt());
		Comparison duplicateTime = copyWithRunsAndTransitions(
				expected,
				List.of(first, copySnapshot(second, duplicateTimeReference)),
				expected.transitions());
		assertFailure(
				temporaryDirectory.resolve(duplicateTime.comparisonId()),
				duplicateTime,
				"LONGITUDINAL_REPORT_EXPECTED_INVALID");

		Transition original = expected.transitions().getFirst();
		Transition wrongElapsed = new Transition(
				original.fromOrdinal(),
				original.toOrdinal(),
				original.fromRunSealId(),
				original.toRunSealId(),
				"PT1H",
				original.europePmcUniqueRelevantQueryCoverage(),
				original.scenarios(),
				original.queries());
		Comparison elapsedMismatch = copyWithRunsAndTransitions(
				expected, expected.runs(), List.of(wrongElapsed));
		assertFailure(
				temporaryDirectory.resolve(elapsedMismatch.comparisonId()),
				elapsedMismatch,
				"LONGITUDINAL_REPORT_EXPECTED_INVALID");
	}

	@Test
	void rejectsNonPrivatePublishedModesOnPosix() throws Exception {
		assumeTrue(temporaryDirectory.getFileSystem()
				.supportedFileAttributeViews().contains("posix"));
		Comparison expected = comparison();
		Path directory = publish("directory-mode", expected).sourceDirectory();
		Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("rwxr-xr-x"));
		assertFailure(
				directory, expected, "LONGITUDINAL_REPORT_PERMISSIONS_NOT_PRIVATE");

		Path fileMode = publish("file-mode", expected).sourceDirectory();
		Files.setPosixFilePermissions(
				fileMode.resolve("longitudinal-report.json"),
				PosixFilePermissions.fromString("rw-r--r--"));
		assertFailure(
				fileMode, expected, "LONGITUDINAL_REPORT_PERMISSIONS_NOT_PRIVATE");
	}

	private ProviderQualityComparativeLongitudinalReportBundle publish(
			String repositoryName, Comparison expected) throws Exception {
		return ProviderQualityComparativeLongitudinalReportBundle.publishAndVerify(
				OBJECT_MAPPER, temporaryDirectory.resolve(repositoryName), expected);
	}

	private void replaceWithLink(Path file, String outsideName) throws Exception {
		Path outside = temporaryDirectory.resolve(outsideName);
		Files.copy(file, outside);
		Files.delete(file);
		Files.createSymbolicLink(file, outside);
	}

	private static void replacePayloadAndRebindManifest(
			Path directory, String comparisonId, byte[] payload) throws Exception {
		Files.write(directory.resolve("longitudinal-report.json"), payload);
		ProviderQualityEvidenceWriter.FileDigest digest =
				new ProviderQualityEvidenceWriter.FileDigest(
						"longitudinal-report.json", payload.length, sha256(payload));
		ProviderQualityEvidenceWriter.EvidenceManifest manifest =
				new ProviderQualityEvidenceWriter.EvidenceManifest(
						1, comparisonId, payload.length, List.of(digest));
		Files.write(
				directory.resolve("manifest.json"),
				ProviderQualityComparativeReviewPacket.canonicalBytes(
						OBJECT_MAPPER, manifest));
	}

	static Comparison comparison() {
		Map<Scenario, ScenarioSummary> scenarios = scenarioSummaries();
		List<QueryScore> queries = List.of(new QueryScore("query-one", queryScenarios()));
		RunSnapshot first = new RunSnapshot(
				1,
				runReference('1', "2026-08-01T00:00:00Z"),
				new UniqueRelevantQueryCoverage(1, 1, 1.0d, List.of("query-one")),
				scenarios,
				queries);
		RunSnapshot second = new RunSnapshot(
				2,
				runReference('2', "2026-08-15T00:00:00Z"),
				new UniqueRelevantQueryCoverage(1, 1, 1.0d, List.of("query-one")),
				scenarios,
				queries);
		List<RunSnapshot> runs = List.of(first, second);
		Transition transition = transition(first, second);
		return new Comparison(
				ProviderQualityComparativeLongitudinalComparison.SCHEMA_VERSION,
				ProviderQualityComparativeLongitudinalComparison.PROTOCOL_ID,
				ProviderQualityComparativeLongitudinalReportBundle.derivedComparisonId(runs),
				2,
				"3".repeat(40),
				"provider-quality-live-v1",
				SHA_A,
				"provider-quality-comparative-scoring-v1",
				SHA_B,
				1,
				runs,
				List.of(transition),
				Use.OBSERVATIONAL_ONLY,
				false,
				false);
	}

	private static RunReference runReference(char value, String capturedAt) {
		String repeated = String.valueOf(value).repeat(64);
		return new RunReference(
				ProviderQualityComparativeRunSealBundle.RUN_SEAL_ID_PREFIX + repeated,
				repeated,
				"evidence-" + value + value,
				SHA_C,
				ProviderQualityComparativeScorer.REPORT_ID_PREFIX + repeated,
				SHA_D,
				capturedAt);
	}

	private static Map<Scenario, ScenarioSummary> scenarioSummaries() {
		Map<Scenario, ScenarioSummary> result = new EnumMap<>(Scenario.class);
		for (Scenario scenario : Scenario.values()) {
			result.put(scenario, new ScenarioSummary(
					new RankingSummaryScore(1, 1, 0, 0.5d, 0.6d, 0.4d, 0.7d),
					new DeduplicationScore(2, 1, 1, 0, 0, 0, 1.0d, 1.0d, 1.0d),
					new ExpectedFieldRecovery(
							1, 1, 1, 1, 1.0d, fieldRecoveries()),
					new MustSeparateMeasurement(1, 0, 1.0d)));
		}
		return result;
	}

	private static Map<Scenario, QueryScenarioScore> queryScenarios() {
		Map<Scenario, QueryScenarioScore> result = new EnumMap<>(Scenario.class);
		for (Scenario scenario : Scenario.values()) {
			result.put(scenario, new QueryScenarioScore(
					1,
					1,
					new RankingScore(1, 0.5d, 0.6d, 0.4d, 0.7d),
					new DeduplicationScore(2, 1, 1, 0, 0, 0, 1.0d, 1.0d, 1.0d),
					new ExpectedFieldRecovery(
							1, 1, 1, 1, 1.0d, fieldRecoveries()),
					new MustSeparateMeasurement(1, 0, 1.0d)));
		}
		return result;
	}

	private static Map<MetadataField, FieldRecovery> fieldRecoveries() {
		Map<MetadataField, FieldRecovery> result = new EnumMap<>(MetadataField.class);
		for (MetadataField field : MetadataField.values()) {
			result.put(field, new FieldRecovery(1, 1, 1.0d));
		}
		return result;
	}

	private static Transition transition(RunSnapshot first, RunSnapshot second) {
		Map<Scenario, ScenarioChange> scenarios = new EnumMap<>(Scenario.class);
		Map<Scenario, QueryScenarioChange> queryChanges = new EnumMap<>(Scenario.class);
		for (Scenario scenario : Scenario.values()) {
			scenarios.put(scenario, scenarioChange());
			queryChanges.put(scenario, queryScenarioChange());
		}
		return new Transition(
				1,
				2,
				first.run().runSealId(),
				second.run().runSealId(),
				"PT336H",
				new CoverageChange(
						count(), count(), rate(), List.of(), List.of()),
				scenarios,
				List.of(new QueryChange("query-one", queryChanges)));
	}

	private static ScenarioChange scenarioChange() {
		return new ScenarioChange(
				new RankingChange(count(), count(), rate(), rate(), rate(), rate()),
				deduplicationChange(),
				metadataChange(),
				new MustSeparateChange(count(), count(), rate()));
	}

	private static QueryScenarioChange queryScenarioChange() {
		return new QueryScenarioChange(
				count(),
				count(),
				new QueryRankingChange(count(), rate(), rate(), rate(), rate()),
				deduplicationChange(),
				metadataChange(),
				new MustSeparateChange(count(), count(), rate()));
	}

	private static DeduplicationChange deduplicationChange() {
		return new DeduplicationChange(
				count(), count(), count(), count(), count(), count(), rate(), rate(), rate());
	}

	private static MetadataRecoveryChange metadataChange() {
		Map<MetadataField, FieldRecoveryChange> fields = new EnumMap<>(MetadataField.class);
		for (MetadataField field : MetadataField.values()) {
			fields.put(field, new FieldRecoveryChange(count(), count(), rate()));
		}
		return new MetadataRecoveryChange(
				count(), count(), count(), count(), rate(), fields);
	}

	private static CountChange count() {
		return new CountChange(1, 1, 0);
	}

	private static RateChange rate() {
		return new RateChange(0.5d, 0.5d, BigDecimal.ZERO);
	}

	private static Comparison copyWithQuerySetId(Comparison source, String querySetId) {
		return new Comparison(
				source.schemaVersion(),
				source.protocolId(),
				source.comparisonId(),
				source.runCount(),
				source.captureRepositoryRevision(),
				querySetId,
				source.querySetSha256(),
				source.scoringPolicyId(),
				source.scoringPolicySha256(),
				source.queryCount(),
				source.runs(),
				source.transitions(),
				source.use(),
				source.readerFacing(),
				source.defaultEnablementDecision());
	}

	private static Comparison copyWithComparisonId(Comparison source, String comparisonId) {
		return new Comparison(
				source.schemaVersion(),
				source.protocolId(),
				comparisonId,
				source.runCount(),
				source.captureRepositoryRevision(),
				source.querySetId(),
				source.querySetSha256(),
				source.scoringPolicyId(),
				source.scoringPolicySha256(),
				source.queryCount(),
				source.runs(),
				source.transitions(),
				source.use(),
				source.readerFacing(),
				source.defaultEnablementDecision());
	}

	private static RunReference copyReference(
			RunReference source, String evidenceId, String reportId, String capturedAt) {
		return new RunReference(
				source.runSealId(),
				source.runSealSha256(),
				evidenceId,
				source.evidenceManifestSha256(),
				reportId,
				source.reportManifestSha256(),
				capturedAt);
	}

	private static RunSnapshot copySnapshot(RunSnapshot source, RunReference reference) {
		return new RunSnapshot(
				source.ordinal(),
				reference,
				source.europePmcUniqueRelevantQueryCoverage(),
				source.scenarios(),
				source.queries());
	}

	private static Comparison copyWithRunsAndTransitions(
			Comparison source, List<RunSnapshot> runs, List<Transition> transitions) {
		return new Comparison(
				source.schemaVersion(),
				source.protocolId(),
				ProviderQualityComparativeLongitudinalReportBundle.derivedComparisonId(runs),
				runs.size(),
				source.captureRepositoryRevision(),
				source.querySetId(),
				source.querySetSha256(),
				source.scoringPolicyId(),
				source.scoringPolicySha256(),
				source.queryCount(),
				runs,
				transitions,
				source.use(),
				source.readerFacing(),
				source.defaultEnablementDecision());
	}

	private static byte[] canonical(Comparison comparison) throws Exception {
		return ProviderQualityComparativeReviewPacket.canonicalBytes(
				OBJECT_MAPPER, comparison);
	}

	private static Map<String, byte[]> snapshot(Path directory) throws IOException {
		Map<String, byte[]> result = new LinkedHashMap<>();
		try (var paths = Files.list(directory)) {
			for (Path path : paths.sorted().toList()) {
				result.put(path.getFileName().toString(), Files.readAllBytes(path));
			}
		}
		return result;
	}

	private static List<String> relativeEntries(Path directory) throws IOException {
		try (var paths = Files.list(directory)) {
			return paths.map(path -> path.getFileName().toString()).sorted().toList();
		}
	}

	private static void assertSnapshot(
			Map<String, byte[]> expected, Map<String, byte[]> actual) {
		assertThat(actual.keySet()).containsExactlyElementsOf(expected.keySet());
		expected.forEach((filename, bytes) -> assertThat(actual.get(filename))
				.as(filename)
				.isEqualTo(bytes));
	}

	private static void assertPrivateTreeWhenPosix(Path directory) throws Exception {
		if (!directory.getFileSystem().supportedFileAttributeViews().contains("posix")) {
			return;
		}
		assertThat(Files.getPosixFilePermissions(directory))
				.isEqualTo(PosixFilePermissions.fromString("rwx------"));
		for (String filename : List.of("manifest.json", "longitudinal-report.json")) {
			assertThat(Files.getPosixFilePermissions(directory.resolve(filename)))
					.as(filename)
					.isEqualTo(PosixFilePermissions.fromString("rw-------"));
		}
	}

	private static String sha256(byte[] bytes) throws Exception {
		return HexFormat.of().formatHex(
				MessageDigest.getInstance("SHA-256").digest(bytes));
	}

	private static void assertFailure(
			Path directory, Comparison expected, String diagnostic) {
		assertThatThrownBy(() -> ProviderQualityComparativeLongitudinalReportBundle.verifyExact(
				OBJECT_MAPPER, directory, expected))
				.isInstanceOf(
						ProviderQualityComparativeLongitudinalReportBundle.VerificationException.class)
				.hasMessage(diagnostic);
	}
}
