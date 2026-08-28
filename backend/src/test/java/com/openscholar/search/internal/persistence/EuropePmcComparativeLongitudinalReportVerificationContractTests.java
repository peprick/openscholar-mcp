package com.openscholar.search.internal.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;

class EuropePmcComparativeLongitudinalReportVerificationContractTests {

	@TempDir
	private Path temporaryDirectory;

	@Test
	void manualVerifierHasTheExactOptInGateAndInputVariables() {
		EnabledIfEnvironmentVariable gate =
				EuropePmcComparativeLongitudinalReportVerificationTests.class
						.getAnnotation(EnabledIfEnvironmentVariable.class);

		assertThat(gate).isNotNull();
		assertThat(gate.named())
				.isEqualTo("RUN_PROVIDER_QUALITY_COMPARATIVE_LONGITUDINAL_REPORT_VERIFY");
		assertThat(gate.matches()).isEqualTo("true");
		assertThat(EuropePmcComparativeLongitudinalReportVerificationTests.SELECTION_ENV)
				.isEqualTo(
						"OPENSCHOLAR_PROVIDER_QUALITY_COMPARATIVE_LONGITUDINAL_SELECTION");
		assertThat(EuropePmcComparativeLongitudinalReportVerificationTests.REPORT_DIRECTORY_ENV)
				.isEqualTo(
						"OPENSCHOLAR_PROVIDER_QUALITY_COMPARATIVE_LONGITUDINAL_REPORT_DIRECTORY");
	}

	@Test
	void requiredInputsMustBeAbsolutePaths() {
		Path absolute = temporaryDirectory.resolve("input.json");
		String environment = "SYNTHETIC_LONGITUDINAL_INPUT";

		assertThat(EuropePmcComparativeLongitudinalReportVerificationTests
				.requiredAbsolutePath(environment, absolute.toString()))
				.isEqualTo(absolute.toAbsolutePath().normalize());
		for (String rejected : List.of("", "relative-input", "   ")) {
			assertThatThrownBy(() -> EuropePmcComparativeLongitudinalReportVerificationTests
					.requiredAbsolutePath(environment, rejected))
					.isInstanceOf(IllegalStateException.class)
					.hasMessageContaining("must name an absolute path");
		}
		assertThatThrownBy(() -> EuropePmcComparativeLongitudinalReportVerificationTests
				.requiredAbsolutePath(environment, null))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("must name an absolute path");
	}

	@Test
	void acceptsOnlyAnExistingRealExternalReportDirectoryWithoutMutation()
			throws Exception {
		Path repository = Files.createDirectory(temporaryDirectory.resolve("repository"));
		Path report = Files.createDirectory(temporaryDirectory.resolve("retained-report"));
		Files.writeString(
				report.resolve("manifest.json"),
				"private-manifest-canary\n",
				StandardCharsets.UTF_8);
		Files.writeString(
				report.resolve("longitudinal-report.json"),
				"private-report-canary\n",
				StandardCharsets.UTF_8);
		Map<String, byte[]> before = snapshot(report);

		assertThat(EuropePmcComparativeLongitudinalReportVerificationTests
				.validateExternalReportDirectory(repository, report))
				.isEqualTo(report.toRealPath());
		assertSnapshot(before, snapshot(report));
		assertThatThrownBy(() -> EuropePmcComparativeLongitudinalReportVerificationTests
				.validateExternalReportDirectory(repository, Path.of("relative-report")))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("absolute path");
		assertThatThrownBy(() -> EuropePmcComparativeLongitudinalReportVerificationTests
				.validateExternalReportDirectory(
						repository, temporaryDirectory.resolve("missing-report")))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("existing real directory");
	}

	@Test
	void rejectsRepositoryContainmentDirectSymlinksAndResolvedAliases()
			throws Exception {
		Path repository = Files.createDirectory(temporaryDirectory.resolve("repository"));
		Path insideRepository = Files.createDirectory(repository.resolve("inside-report"));
		Path external = Files.createDirectory(temporaryDirectory.resolve("external-report"));
		Path directLink = temporaryDirectory.resolve("direct-report-link");
		Files.createSymbolicLink(directLink, external);
		Path repositoryAlias = temporaryDirectory.resolve("repository-alias");
		Files.createSymbolicLink(repositoryAlias, repository);

		for (Path rejected : List.of(
				insideRepository,
				temporaryDirectory,
				directLink,
				repositoryAlias.resolve("inside-report"))) {
			assertThatThrownBy(() -> EuropePmcComparativeLongitudinalReportVerificationTests
					.validateExternalReportDirectory(repository, rejected))
					.as(rejected.toString())
					.isInstanceOf(IllegalStateException.class);
		}
	}

	@Test
	void reportDirectoryMustBeDisjointFromEverySelectedRunDirectory()
			throws Exception {
		Path report = Files.createDirectory(temporaryDirectory.resolve("report"));
		Path run = Files.createDirectory(temporaryDirectory.resolve("run"));
		Path nestedRun = Files.createDirectory(report.resolve("nested-run"));
		Path nestedReport = Files.createDirectory(run.resolve("nested-report"));

		EuropePmcComparativeLongitudinalReportVerificationTests
				.validateReportRunSeparation(report, List.of(run));
		assertThatThrownBy(() -> EuropePmcComparativeLongitudinalReportVerificationTests
				.validateReportRunSeparation(report, List.of(nestedRun)))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("must not overlap a selected run directory");
		assertThatThrownBy(() -> EuropePmcComparativeLongitudinalReportVerificationTests
				.validateReportRunSeparation(nestedReport, List.of(run)))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("must not overlap a selected run directory");
	}

	@Test
	void successRecordIsAnExactPrivacyAllowlist() {
		String comparisonId =
				ProviderQualityComparativeLongitudinalComparison.COMPARISON_ID_PREFIX
						+ "a".repeat(64);
		String manifestSha256 = "b".repeat(64);

		assertThat(EuropePmcComparativeLongitudinalReportVerificationTests.successRecord(
				comparisonId, manifestSha256, 2))
				.isEqualTo(
						"provider-quality-comparative-longitudinal-v1 mode=verified "
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

		assertThatThrownBy(() -> EuropePmcComparativeLongitudinalReportVerificationTests
				.successRecord(comparisonId + "\npath=/private", manifestSha256, 2))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessage("LONGITUDINAL_REPORT_VERIFY_SUCCESS_RECORD_INVALID");
		assertThatThrownBy(() -> EuropePmcComparativeLongitudinalReportVerificationTests
				.successRecord(comparisonId, "B".repeat(64), 2))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessage("LONGITUDINAL_REPORT_VERIFY_SUCCESS_RECORD_INVALID");
		assertThatThrownBy(() -> EuropePmcComparativeLongitudinalReportVerificationTests
				.successRecord(comparisonId, manifestSha256, 1))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessage("LONGITUDINAL_REPORT_VERIFY_SUCCESS_RECORD_INVALID");
	}

	private static Map<String, byte[]> snapshot(Path directory) throws Exception {
		Map<String, byte[]> result = new LinkedHashMap<>();
		try (var paths = Files.list(directory)) {
			for (Path path : paths.sorted().toList()) {
				result.put(path.getFileName().toString(), Files.readAllBytes(path));
			}
		}
		return result;
	}

	private static void assertSnapshot(
			Map<String, byte[]> expected, Map<String, byte[]> actual) {
		assertThat(actual.keySet()).containsExactlyElementsOf(expected.keySet());
		expected.forEach((filename, bytes) -> assertThat(actual.get(filename))
				.as(filename)
				.isEqualTo(bytes));
	}
}
