package com.openscholar.search.internal.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import com.openscholar.search.internal.persistence.ProviderQualityComparativeRunSealBundle.Bindings;
import com.openscholar.search.internal.persistence.ProviderQualityComparativeRunSealBundle.VerifiedRunSeal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class EuropePmcComparativeRunSealVerificationContractTests {

	@TempDir
	private Path temporaryDirectory;

	@Test
	void acceptsOnlyARealAbsoluteDirectoryOutsideTheRepository() throws Exception {
		Path repository = Files.createDirectory(temporaryDirectory.resolve("repository"));
		Path external = Files.createDirectory(temporaryDirectory.resolve("external-run"));

		assertThat(EuropePmcComparativeRunSealVerificationTests
				.validateExternalRunSealPath(repository, external))
				.isEqualTo(external.toRealPath());
		assertThatThrownBy(() -> EuropePmcComparativeRunSealVerificationTests
				.validateExternalRunSealPath(repository, Path.of("relative-run")))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("must name an absolute path");
		assertThatThrownBy(() -> EuropePmcComparativeRunSealVerificationTests
				.validateExternalRunSealPath(repository, temporaryDirectory.resolve("missing")))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("existing real directory");
	}

	@Test
	void rejectsRepositoryContainmentAndSymlinkAliases() throws Exception {
		Path repository = Files.createDirectory(temporaryDirectory.resolve("repository"));
		Path inside = Files.createDirectory(repository.resolve("inside-run"));
		Path external = Files.createDirectory(temporaryDirectory.resolve("external-run"));
		Path directLink = temporaryDirectory.resolve("direct-link");
		Files.createSymbolicLink(directLink, external);
		Path repositoryAlias = temporaryDirectory.resolve("repository-alias");
		Files.createSymbolicLink(repositoryAlias, repository);

		for (Path rejected : List.of(
				inside,
				temporaryDirectory,
				directLink,
				repositoryAlias.resolve("inside-run"))) {
			assertThatThrownBy(() -> EuropePmcComparativeRunSealVerificationTests
					.validateExternalRunSealPath(repository, rejected))
					.as(rejected.toString())
					.isInstanceOf(IllegalStateException.class);
		}
	}

	@Test
	void successRecordIsAnExactPrivacyAllowlist() {
		String evidenceDigest = "1".repeat(64);
		String judgmentsDigest = "2".repeat(64);
		String policyDigest = "3".repeat(64);
		String reportId = ProviderQualityComparativeScorer.reportId(
				evidenceDigest, judgmentsDigest, policyDigest);
		Bindings bindings = new Bindings(
				"synthetic-evidence",
				evidenceDigest,
				"4".repeat(40),
				"2026-08-27T06:00:00Z",
				"synthetic-query-set",
				"5".repeat(64),
				"synthetic-policy",
				policyDigest,
				"6".repeat(64),
				"7".repeat(64),
				judgmentsDigest,
				reportId,
				"8".repeat(64));
		String sealId = ProviderQualityComparativeRunSealBundle.RUN_SEAL_ID_PREFIX
				+ "9".repeat(64);
		VerifiedRunSeal sealed = new VerifiedRunSeal(
				Path.of("unused"),
				sealId,
				"a".repeat(64),
				1,
				2,
				bindings,
				List.of());

		assertThat(EuropePmcComparativeRunSealVerificationTests.successRecord(sealed))
				.isEqualTo(
						"provider-quality-comparative-run-seal-v1 mode=verified "
								+ "run-seal-id=" + sealId
								+ " run-seal-sha256=" + "a".repeat(64)
								+ " report-id=" + reportId);
	}
}
