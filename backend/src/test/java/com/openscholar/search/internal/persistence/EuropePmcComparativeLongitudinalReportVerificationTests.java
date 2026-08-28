package com.openscholar.search.internal.persistence;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

import com.openscholar.search.internal.persistence.ProviderQualityComparativeLongitudinalComparison.Comparison;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Manual, opt-in, no-write verification of one retained longitudinal report.
 * The expected report is rebuilt only from fully replayed retained run seals.
 */
@EnabledIfEnvironmentVariable(
		named = EuropePmcComparativeLongitudinalReportVerificationTests.ENABLE_ENV,
		matches = "true")
class EuropePmcComparativeLongitudinalReportVerificationTests {

	static final String ENABLE_ENV =
			"RUN_PROVIDER_QUALITY_COMPARATIVE_LONGITUDINAL_REPORT_VERIFY";
	static final String SELECTION_ENV =
			"OPENSCHOLAR_PROVIDER_QUALITY_COMPARATIVE_LONGITUDINAL_SELECTION";
	static final String REPORT_DIRECTORY_ENV =
			"OPENSCHOLAR_PROVIDER_QUALITY_COMPARATIVE_LONGITUDINAL_REPORT_DIRECTORY";

	private static final Pattern SHA256 = Pattern.compile("^[0-9a-f]{64}$");
	private static final Pattern COMPARISON_ID = Pattern.compile(
			'^' + Pattern.quote(
					ProviderQualityComparativeLongitudinalComparison.COMPARISON_ID_PREFIX)
					+ "[0-9a-f]{64}$");

	@Test
	void verifiesOneRetainedReportWithoutWritingArtifacts() throws Exception {
		Path repositoryRoot = EuropePmcComparativeLiveEvaluationTests.repositoryRoot();
		String repositoryRevision =
				EuropePmcComparativeLiveEvaluationTests.requiredRepositoryRevision(repositoryRoot);
		Path selectionPath = requiredAbsolutePath(
				SELECTION_ENV, System.getenv(SELECTION_ENV));
		Path reportDirectory = validateExternalReportDirectory(
				repositoryRoot,
				requiredAbsolutePath(
						REPORT_DIRECTORY_ENV, System.getenv(REPORT_DIRECTORY_ENV)));
		ObjectMapper objectMapper = JsonMapper.builder().build();

		ProviderQualityComparativeLongitudinalSelection.Selection selection =
				ProviderQualityComparativeLongitudinalSelection.load(
						objectMapper, repositoryRoot, selectionPath);
		validateReportRunSeparation(reportDirectory, selection.runDirectories());
		Comparison comparison = EuropePmcComparativeLongitudinalComparisonTests
				.replaySelection(objectMapper, repositoryRevision, selection);
		ProviderQualityComparativeLongitudinalReportBundle report =
				ProviderQualityComparativeLongitudinalReportBundle.verifyExact(
						objectMapper, reportDirectory, comparison);

		System.out.printf(
				Locale.ROOT,
				"%s%n",
				successRecord(
						report.comparisonId(),
						report.manifestSha256(),
						comparison.runCount()));
	}

	static Path requiredAbsolutePath(String environmentName, String value) {
		String name = Objects.requireNonNull(environmentName, "environmentName");
		if (value == null || value.isBlank()) {
			throw new IllegalStateException(name + " must name an absolute path");
		}
		try {
			Path supplied = Path.of(value);
			if (!supplied.isAbsolute()) {
				throw new IllegalStateException(name + " must name an absolute path");
			}
			return supplied.toAbsolutePath().normalize();
		}
		catch (RuntimeException exception) {
			if (exception instanceof IllegalStateException state) {
				throw state;
			}
			throw new IllegalStateException(
					name + " must name an absolute path", exception);
		}
	}

	static Path validateExternalReportDirectory(
			Path repositoryRoot, Path suppliedReportDirectory) {
		Path repository = Objects.requireNonNull(repositoryRoot, "repositoryRoot")
				.toAbsolutePath().normalize();
		if (suppliedReportDirectory == null || !suppliedReportDirectory.isAbsolute()) {
			throw new IllegalStateException(
					REPORT_DIRECTORY_ENV + " must name an absolute path");
		}
		Path reportDirectory;
		try {
			reportDirectory = suppliedReportDirectory.toAbsolutePath().normalize();
		}
		catch (RuntimeException exception) {
			throw new IllegalStateException(
					REPORT_DIRECTORY_ENV + " must name an existing real directory",
					exception);
		}
		if (Files.isSymbolicLink(reportDirectory)
				|| !Files.isDirectory(reportDirectory, LinkOption.NOFOLLOW_LINKS)) {
			throw new IllegalStateException(
					REPORT_DIRECTORY_ENV + " must name an existing real directory");
		}
		try {
			Path resolvedRepository = repository.toRealPath();
			Path resolvedReportDirectory = reportDirectory.toRealPath();
			if (resolvedReportDirectory.startsWith(resolvedRepository)
					|| resolvedRepository.startsWith(resolvedReportDirectory)) {
				throw new IllegalStateException(
						REPORT_DIRECTORY_ENV + " must resolve outside the repository");
			}
			return resolvedReportDirectory;
		}
		catch (IOException exception) {
			throw new IllegalStateException(
					REPORT_DIRECTORY_ENV + " must name a resolvable external directory",
					exception);
		}
	}

	static void validateReportRunSeparation(
			Path reportDirectory, List<Path> runDirectories) {
		Objects.requireNonNull(reportDirectory, "reportDirectory");
		Objects.requireNonNull(runDirectories, "runDirectories");
		try {
			Path resolvedReportDirectory = reportDirectory.toRealPath();
			for (Path runDirectory : runDirectories) {
				Path resolvedRunDirectory = Objects.requireNonNull(
						runDirectory, "runDirectory").toRealPath();
				if (resolvedReportDirectory.startsWith(resolvedRunDirectory)
						|| resolvedRunDirectory.startsWith(resolvedReportDirectory)) {
					throw new IllegalStateException(
							REPORT_DIRECTORY_ENV
									+ " must not overlap a selected run directory");
				}
			}
		}
		catch (IOException exception) {
			throw new IllegalStateException(
					"Longitudinal report and run directories must be resolvable",
					exception);
		}
	}

	static String successRecord(
			String comparisonId,
			String manifestSha256,
			int runCount) {
		if (comparisonId == null || !COMPARISON_ID.matcher(comparisonId).matches()
				|| manifestSha256 == null || !SHA256.matcher(manifestSha256).matches()
				|| runCount < ProviderQualityComparativeLongitudinalComparison.MINIMUM_RUNS
				|| runCount > ProviderQualityComparativeLongitudinalComparison.MAXIMUM_RUNS) {
			throw new IllegalArgumentException(
					"LONGITUDINAL_REPORT_VERIFY_SUCCESS_RECORD_INVALID");
		}
		return String.format(
				Locale.ROOT,
				"provider-quality-comparative-longitudinal-v1 mode=verified "
						+ "comparison-id=%s manifest-sha256=%s runs=%d",
				comparisonId,
				manifestSha256,
				runCount);
	}
}
