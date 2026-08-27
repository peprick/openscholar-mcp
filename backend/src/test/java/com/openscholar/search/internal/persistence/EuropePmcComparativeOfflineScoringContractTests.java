package com.openscholar.search.internal.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class EuropePmcComparativeOfflineScoringContractTests {

	@TempDir
	private Path temporaryDirectory;

	@Test
	void requiresTheWorksheetAndRunSealRootTogether() {
		Path worksheet = temporaryDirectory.resolve("worksheet.json");
		Path runSealRoot = temporaryDirectory.resolve("run-seals");

		assertThat(EuropePmcComparativeOfflineScoringTests.validateRunSealOptionPair(
				null, null)).isFalse();
		assertThat(EuropePmcComparativeOfflineScoringTests.validateRunSealOptionPair(
				worksheet, runSealRoot)).isTrue();
		assertThatThrownBy(() ->
				EuropePmcComparativeOfflineScoringTests.validateRunSealOptionPair(
						worksheet, null))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("must either both be set or both be unset");
		assertThatThrownBy(() ->
				EuropePmcComparativeOfflineScoringTests.validateRunSealOptionPair(
						null, runSealRoot))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("must either both be set or both be unset");
	}

	@Test
	void acceptsAnExistingReportOutsideTheBuildTarget() throws Exception {
		Path repository = repository();
		Path externalReport = Files.createDirectories(
				temporaryDirectory.resolve("retained").resolve("report"));

		assertThat(EuropePmcComparativeOfflineScoringTests.validateExternalReplayPath(
				repository, externalReport))
				.isEqualTo(externalReport.toAbsolutePath().normalize());
	}

	@Test
	void rejectsLexicalAndResolvedAliasesIntoTheBuildTarget() throws Exception {
		Path repository = repository();
		Path targetReport = Files.createDirectories(
				repository.resolve("backend/target/provider-quality/report"));

		assertThatThrownBy(() ->
				EuropePmcComparativeOfflineScoringTests.validateExternalReplayPath(
						repository, targetReport))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("outside backend/target");

		Path aliasParent = temporaryDirectory.resolve("retained-alias");
		Files.createSymbolicLink(aliasParent, targetReport.getParent());
		Path aliasedReport = aliasParent.resolve(targetReport.getFileName());
		assertThatThrownBy(() ->
				EuropePmcComparativeOfflineScoringTests.validateExternalReplayPath(
						repository, aliasedReport))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("outside backend/target");
	}

	@Test
	void acceptsAnExistingRunSealRootOutsideTheRepository() throws Exception {
		Path repository = repository();
		Path externalRoot = Files.createDirectories(
				temporaryDirectory.resolve("external-run-seals"));

		assertThat(EuropePmcComparativeOfflineScoringTests.validateExternalRunSealRoot(
				repository, externalRoot))
				.isEqualTo(externalRoot.toRealPath());
	}

	@Test
	void rejectsRunSealRootsInsideOrContainingTheRepository() throws Exception {
		Path repository = repository();
		Path inside = Files.createDirectories(repository.resolve("retained-runs"));

		assertThatThrownBy(() ->
				EuropePmcComparativeOfflineScoringTests.validateExternalRunSealRoot(
						repository, inside))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("outside the repository");
		assertThatThrownBy(() ->
				EuropePmcComparativeOfflineScoringTests.validateExternalRunSealRoot(
						repository, temporaryDirectory))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("outside the repository");
	}

	@Test
	void rejectsLinkedOrResolvedRunSealRootAliases() throws Exception {
		Path repository = repository();
		Path externalRoot = Files.createDirectories(
				temporaryDirectory.resolve("external-run-seals"));
		Path linkedRoot = temporaryDirectory.resolve("linked-run-seals");
		Files.createSymbolicLink(linkedRoot, externalRoot.getFileName());

		assertThatThrownBy(() ->
				EuropePmcComparativeOfflineScoringTests.validateExternalRunSealRoot(
						repository, linkedRoot))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("existing real directory");

		Path repositoryCustody = Files.createDirectories(repository.resolve("custody"));
		Path aliasParent = temporaryDirectory.resolve("repository-alias");
		Files.createSymbolicLink(aliasParent, repository);
		assertThatThrownBy(() ->
				EuropePmcComparativeOfflineScoringTests.validateExternalRunSealRoot(
						repository, aliasParent.resolve(repositoryCustody.getFileName())))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("outside the repository");
	}

	private Path repository() throws Exception {
		Path repository = Files.createDirectories(temporaryDirectory.resolve("repository"));
		Files.createDirectories(repository.resolve("backend/target"));
		return repository;
	}
}
