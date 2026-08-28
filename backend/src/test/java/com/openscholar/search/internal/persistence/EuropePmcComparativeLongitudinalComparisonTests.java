package com.openscholar.search.internal.persistence;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

import com.openscholar.search.internal.persistence.ProviderQualityComparativeLongitudinalComparison.Comparison;
import com.openscholar.search.internal.persistence.ProviderQualityComparativeLongitudinalComparison.VerifiedRun;
import com.openscholar.search.internal.persistence.ProviderQualityComparativeRunSealBundle.VerifiedRunSeal;
import com.openscholar.search.internal.persistence.ProviderQualityComparativeScorer.ScoringResult;
import com.openscholar.search.internal.persistence.ProviderQualityComparativeScoringPolicy.BoundPolicy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Manual, opt-in comparison of a bounded selection of fully retained comparative runs.
 * The runner performs no provider, Spring, database, Docker, document, UI, REST, MCP,
 * or network operation. Output is first verified below the repository's ignored
 * provider-quality target directory and can optionally be atomically promoted to an
 * explicitly configured private external root.
 */
@EnabledIfEnvironmentVariable(
		named = EuropePmcComparativeLongitudinalComparisonTests.ENABLE_ENV,
		matches = "true")
class EuropePmcComparativeLongitudinalComparisonTests {

	static final String ENABLE_ENV =
			"RUN_PROVIDER_QUALITY_COMPARATIVE_LONGITUDINAL";
	static final String SELECTION_ENV =
			"OPENSCHOLAR_PROVIDER_QUALITY_COMPARATIVE_LONGITUDINAL_SELECTION";
	static final String REPORT_ROOT_ENV =
			"OPENSCHOLAR_PROVIDER_QUALITY_COMPARATIVE_LONGITUDINAL_REPORT_ROOT";

	private static final Pattern SHA256 = Pattern.compile("^[0-9a-f]{64}$");
	private static final Pattern COMPARISON_ID = Pattern.compile(
			'^' + Pattern.quote(
					ProviderQualityComparativeLongitudinalComparison.COMPARISON_ID_PREFIX)
					+ "[0-9a-f]{64}$");

	@Test
	void writesOnePrivateDeterministicReportAfterEveryRunFullyReplays() throws Exception {
		Path repositoryRoot = EuropePmcComparativeLiveEvaluationTests.repositoryRoot();
		String repositoryRevision =
				EuropePmcComparativeLiveEvaluationTests.requiredRepositoryRevision(repositoryRoot);
		Path selectionPath = requiredSelectionPath(System.getenv(SELECTION_ENV));
		Path reportRoot = optionalReportRoot(System.getenv(REPORT_ROOT_ENV));
		ObjectMapper objectMapper = JsonMapper.builder().build();

		ProviderQualityComparativeLongitudinalSelection.Selection selection =
				ProviderQualityComparativeLongitudinalSelection.load(
						objectMapper, repositoryRoot, selectionPath);
		GenerationResult generated = generate(
				objectMapper,
				repositoryRoot,
				repositoryRevision,
				selection,
				reportRoot);

		System.out.printf(
				Locale.ROOT,
				"%s%n",
				successRecord(
						generated.mode(),
						generated.report().comparisonId(),
						generated.report().manifestSha256(),
						generated.comparison().runCount()));
	}

	/**
	 * Runs the environment-independent generation and optional custody handoff used by
	 * both the manual entry point and ordinary synthetic workflow coverage.
	 */
	static GenerationResult generate(
			ObjectMapper objectMapper,
			Path repositoryRoot,
			String repositoryRevision,
			ProviderQualityComparativeLongitudinalSelection.Selection selection,
			Path reportRoot) throws Exception {
		Path validatedReportRoot = reportRoot;
		if (validatedReportRoot != null) {
			validatedReportRoot = ProviderQualityComparativeLongitudinalReportPromotion
					.validateExternalRoot(
							repositoryRoot,
							validatedReportRoot,
							null,
							selection.runDirectories());
		}
		Comparison comparison = replaySelection(
				objectMapper, repositoryRevision, selection);
		ProviderQualityComparativeLongitudinalReportBundle localReport =
				ProviderQualityComparativeLongitudinalReportBundle.publishAndVerify(
						objectMapper, repositoryRoot, comparison);
		ProviderQualityComparativeLongitudinalReportBundle report = localReport;
		String mode = "generated";
		if (validatedReportRoot != null) {
			report = ProviderQualityComparativeLongitudinalReportPromotion.promoteAndVerify(
					objectMapper,
					repositoryRoot,
					validatedReportRoot,
					localReport.sourceDirectory(),
					comparison,
					selection.runDirectories());
			mode = "promoted";
		}
		return new GenerationResult(mode, comparison, localReport, report);
	}

	/**
	 * Rebuilds one comparison only from exact retained bytes that pass the complete
	 * semantic replay. Generation and standalone retained-report verification share
	 * this boundary so neither workflow can drift or trust report-supplied expectations.
	 */
	static Comparison replaySelection(
			ObjectMapper objectMapper,
			String repositoryRevision,
			ProviderQualityComparativeLongitudinalSelection.Selection selection)
			throws Exception {
		Objects.requireNonNull(objectMapper, "objectMapper");
		Objects.requireNonNull(selection, "selection");
		ProviderQualityLiveQuerySet.BoundQuerySet frozenQuerySet =
				ProviderQualityLiveQuerySet.loadFrozen(objectMapper);
		BoundPolicy policy = ProviderQualityComparativeScoringPolicy.loadBound(
				objectMapper, ProviderQualityComparativeScoringPolicy.RESOURCE_PATH);

		List<VerifiedRun> runs = new ArrayList<>(selection.runDirectories().size());
		for (Path runDirectory : selection.runDirectories()) {
			VerifiedRunSeal sealed = ProviderQualityComparativeRunSealBundle.verifyRetained(
					objectMapper, runDirectory);
			requireCaptureRevision(repositoryRevision, sealed);
			ScoringResult replayed = EuropePmcComparativeOfflineScoringTests
					.verifySealedSemantics(objectMapper, sealed, frozenQuerySet, policy);
			runs.add(ProviderQualityComparativeLongitudinalComparison
					.verifiedRun(sealed, replayed));
		}
		return ProviderQualityComparativeLongitudinalComparison.compare(runs);
	}

	static Path requiredSelectionPath(String value) {
		if (value == null || value.isBlank()) {
			throw new IllegalStateException(SELECTION_ENV + " must name an absolute file");
		}
		try {
			Path supplied = Path.of(value);
			if (!supplied.isAbsolute()) {
				throw new IllegalStateException(
						SELECTION_ENV + " must name an absolute file");
			}
			return supplied.toAbsolutePath().normalize();
		}
		catch (RuntimeException exception) {
			if (exception instanceof IllegalStateException state) {
				throw state;
			}
			throw new IllegalStateException(
					SELECTION_ENV + " must name an absolute file", exception);
		}
	}

	static Path optionalReportRoot(String value) {
		if (value == null) {
			return null;
		}
		if (value.isBlank()) {
			throw new IllegalStateException(
					REPORT_ROOT_ENV + " must name an absolute directory");
		}
		try {
			Path supplied = Path.of(value);
			if (!supplied.isAbsolute()) {
				throw new IllegalStateException(
						REPORT_ROOT_ENV + " must name an absolute directory");
			}
			return supplied.toAbsolutePath().normalize();
		}
		catch (RuntimeException exception) {
			if (exception instanceof IllegalStateException state) {
				throw state;
			}
			throw new IllegalStateException(
					REPORT_ROOT_ENV + " must name an absolute directory", exception);
		}
	}

	static void requireCaptureRevision(
			String repositoryRevision, VerifiedRunSeal sealed) {
		String expected = Objects.requireNonNull(
				repositoryRevision, "repositoryRevision");
		VerifiedRunSeal verified = Objects.requireNonNull(sealed, "sealed");
		if (!expected.equals(verified.bindings().captureRepositoryRevision())) {
			throw new IllegalStateException("LONGITUDINAL_REPOSITORY_REVISION_MISMATCH");
		}
	}

	static String successRecord(
			String comparisonId,
			String manifestSha256,
			int runCount) {
		return successRecord("generated", comparisonId, manifestSha256, runCount);
	}

	static String successRecord(
			String mode,
			String comparisonId,
			String manifestSha256,
			int runCount) {
		if (!("generated".equals(mode) || "promoted".equals(mode))
				|| comparisonId == null || !COMPARISON_ID.matcher(comparisonId).matches()
				|| manifestSha256 == null || !SHA256.matcher(manifestSha256).matches()
				|| runCount < ProviderQualityComparativeLongitudinalComparison.MINIMUM_RUNS
				|| runCount > ProviderQualityComparativeLongitudinalComparison.MAXIMUM_RUNS) {
			throw new IllegalArgumentException("LONGITUDINAL_SUCCESS_RECORD_INVALID");
		}
		return String.format(
				Locale.ROOT,
				"provider-quality-comparative-longitudinal-v1 mode=%s "
						+ "comparison-id=%s manifest-sha256=%s runs=%d",
				mode,
				comparisonId,
				manifestSha256,
				runCount);
	}

	record GenerationResult(
			String mode,
			Comparison comparison,
			ProviderQualityComparativeLongitudinalReportBundle localReport,
			ProviderQualityComparativeLongitudinalReportBundle report) {
	}
}
