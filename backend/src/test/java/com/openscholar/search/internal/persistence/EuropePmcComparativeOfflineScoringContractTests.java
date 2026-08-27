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

	private Path repository() throws Exception {
		Path repository = Files.createDirectories(temporaryDirectory.resolve("repository"));
		Files.createDirectories(repository.resolve("backend/target"));
		return repository;
	}
}
