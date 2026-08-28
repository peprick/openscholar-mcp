package com.openscholar.search.internal.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.util.List;

import com.openscholar.search.internal.persistence.ProviderQualityComparativeRunSealBundle.Bindings;
import com.openscholar.search.internal.persistence.ProviderQualityComparativeRunSealBundle.VerifiedRunSeal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;

class EuropePmcComparativeLongitudinalComparisonContractTests {

	@TempDir
	private Path temporaryDirectory;

	@Test
	void manualRunnerHasTheExactOptInGateAndSelectionVariable() {
		EnabledIfEnvironmentVariable gate =
				EuropePmcComparativeLongitudinalComparisonTests.class
						.getAnnotation(EnabledIfEnvironmentVariable.class);

		assertThat(gate).isNotNull();
		assertThat(gate.named())
				.isEqualTo("RUN_PROVIDER_QUALITY_COMPARATIVE_LONGITUDINAL");
		assertThat(gate.matches()).isEqualTo("true");
		assertThat(EuropePmcComparativeLongitudinalComparisonTests.SELECTION_ENV)
				.isEqualTo(
						"OPENSCHOLAR_PROVIDER_QUALITY_COMPARATIVE_LONGITUDINAL_SELECTION");
	}

	@Test
	void selectionInputMustBeAnAbsolutePath() {
		Path absolute = temporaryDirectory.resolve("private-longitudinal-selection.json");

		assertThat(EuropePmcComparativeLongitudinalComparisonTests
				.requiredSelectionPath(absolute.toString()))
				.isEqualTo(absolute.toAbsolutePath().normalize());
		for (String rejected : List.of("", "selection.json", "   ")) {
			assertThatThrownBy(() -> EuropePmcComparativeLongitudinalComparisonTests
					.requiredSelectionPath(rejected))
					.as(rejected)
					.isInstanceOf(IllegalStateException.class)
					.hasMessageContaining("must name an absolute file");
		}
		assertThatThrownBy(() -> EuropePmcComparativeLongitudinalComparisonTests
				.requiredSelectionPath(null))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("must name an absolute file");
	}

	@Test
	void everyRunMustBindTheExactCleanCheckoutRevision() {
		String revision = "1".repeat(40);
		VerifiedRunSeal sealed = retainedSeal(revision);

		assertThatCode(() -> EuropePmcComparativeLongitudinalComparisonTests
				.requireCaptureRevision(revision, sealed))
				.doesNotThrowAnyException();
		assertThatThrownBy(() -> EuropePmcComparativeLongitudinalComparisonTests
				.requireCaptureRevision("2".repeat(40), sealed))
				.isInstanceOf(IllegalStateException.class)
				.hasMessage("LONGITUDINAL_REPOSITORY_REVISION_MISMATCH");
	}

	@Test
	void successRecordIsAnExactPrivacyAllowlist() {
		String comparisonId =
				ProviderQualityComparativeLongitudinalComparison.COMPARISON_ID_PREFIX
						+ "a".repeat(64);
		String manifestSha256 = "b".repeat(64);

		assertThat(EuropePmcComparativeLongitudinalComparisonTests.successRecord(
				comparisonId, manifestSha256, 2))
				.isEqualTo(
						"provider-quality-comparative-longitudinal-v1 mode=generated "
								+ "comparison-id=" + comparisonId
								+ " manifest-sha256=" + manifestSha256
								+ " runs=2")
				.doesNotContain(
						"path=",
						"directory=",
						"revision=",
						"timestamp=",
						"report-id=",
						"evidence-id=",
						"query=",
						"metric=",
						"label=",
						"bytes=");
	}

	@Test
	void successRecordRejectsInvalidOrInjectableValues() {
		String comparisonId =
				ProviderQualityComparativeLongitudinalComparison.COMPARISON_ID_PREFIX
						+ "a".repeat(64);
		String manifestSha256 = "b".repeat(64);

		assertThatThrownBy(() -> EuropePmcComparativeLongitudinalComparisonTests
				.successRecord(comparisonId + "\npath=/private", manifestSha256, 2))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessage("LONGITUDINAL_SUCCESS_RECORD_INVALID");
		assertThatThrownBy(() -> EuropePmcComparativeLongitudinalComparisonTests
				.successRecord(comparisonId, "B".repeat(64), 2))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessage("LONGITUDINAL_SUCCESS_RECORD_INVALID");
		assertThatThrownBy(() -> EuropePmcComparativeLongitudinalComparisonTests
				.successRecord(comparisonId, manifestSha256, 1))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessage("LONGITUDINAL_SUCCESS_RECORD_INVALID");
	}

	private static VerifiedRunSeal retainedSeal(String revision) {
		String evidenceManifestSha256 = "2".repeat(64);
		String judgmentsSha256 = "3".repeat(64);
		String scoringPolicySha256 = "4".repeat(64);
		String reportId = ProviderQualityComparativeScorer.reportId(
				evidenceManifestSha256, judgmentsSha256, scoringPolicySha256);
		Bindings bindings = new Bindings(
				"synthetic-evidence",
				evidenceManifestSha256,
				revision,
				"2026-08-27T06:00:00Z",
				"synthetic-query-set",
				"5".repeat(64),
				"synthetic-policy",
				scoringPolicySha256,
				"6".repeat(64),
				"7".repeat(64),
				judgmentsSha256,
				reportId,
				"8".repeat(64));
		return new VerifiedRunSeal(
				Path.of("unused"),
				ProviderQualityComparativeRunSealBundle.RUN_SEAL_ID_PREFIX
						+ "9".repeat(64),
				"a".repeat(64),
				1,
				2,
				bindings,
				List.of());
	}
}
