package com.openscholar.search.internal.persistence;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Objects;

import com.openscholar.search.internal.persistence.ProviderQualityComparativeRunSealBundle.Bindings;
import com.openscholar.search.internal.persistence.ProviderQualityComparativeRunSealBundle.VerifiedRunSeal;
import com.openscholar.search.internal.persistence.ProviderQualityComparativeScoringPolicy.BoundPolicy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Manual, opt-in, no-write verification of one externally retained comparative run seal.
 * The class has no Spring, provider, database, Docker, document, or network dependency.
 */
@EnabledIfEnvironmentVariable(
		named = "RUN_PROVIDER_QUALITY_COMPARATIVE_RUN_SEAL_VERIFY",
		matches = "true")
class EuropePmcComparativeRunSealVerificationTests {

	private static final String RUN_SEAL_ENV =
			"OPENSCHOLAR_PROVIDER_QUALITY_COMPARATIVE_RUN_SEAL_DIRECTORY";

	@Test
	void verifiesOneRetainedRunFromItsSealedBytesWithoutWritingArtifacts() throws Exception {
		Path repositoryRoot = EuropePmcComparativeLiveEvaluationTests.repositoryRoot();
		String repositoryRevision =
				EuropePmcComparativeLiveEvaluationTests.requiredRepositoryRevision(repositoryRoot);
		Path runDirectory = validateExternalRunSealPath(
				repositoryRoot,
				EuropePmcComparativeReviewPacketGenerationTests.requiredAbsolutePath(
						RUN_SEAL_ENV));
		ObjectMapper objectMapper = JsonMapper.builder().build();

		VerifiedRunSeal sealed = ProviderQualityComparativeRunSealBundle.verifyRetained(
				objectMapper, runDirectory);
		Bindings bindings = sealed.bindings();
		if (!bindings.captureRepositoryRevision().equals(repositoryRevision)) {
			throw new IllegalStateException("RUN_SEAL_REPOSITORY_REVISION_MISMATCH");
		}

		ProviderQualityLiveQuerySet.BoundQuerySet frozenQuerySet =
				ProviderQualityLiveQuerySet.loadFrozen(objectMapper);
		BoundPolicy policy = ProviderQualityComparativeScoringPolicy.loadBound(
				objectMapper, ProviderQualityComparativeScoringPolicy.RESOURCE_PATH);
		EuropePmcComparativeOfflineScoringTests.verifySealedSemantics(
				objectMapper, sealed, frozenQuerySet, policy);

		System.out.printf(Locale.ROOT, "%s%n", successRecord(sealed));
	}

	static Path validateExternalRunSealPath(Path repositoryRoot, Path suppliedRunDirectory) {
		Path repository = Objects.requireNonNull(repositoryRoot, "repositoryRoot")
				.toAbsolutePath().normalize();
		if (suppliedRunDirectory == null || !suppliedRunDirectory.isAbsolute()) {
			throw new IllegalStateException(RUN_SEAL_ENV + " must name an absolute path");
		}
		Path runDirectory = suppliedRunDirectory.toAbsolutePath().normalize();
		if (Files.isSymbolicLink(runDirectory)
				|| !Files.isDirectory(runDirectory, LinkOption.NOFOLLOW_LINKS)) {
			throw new IllegalStateException(
					RUN_SEAL_ENV + " must name an existing real directory");
		}
		try {
			Path resolvedRepository = repository.toRealPath();
			Path resolvedRunDirectory = runDirectory.toRealPath();
			if (resolvedRunDirectory.startsWith(resolvedRepository)
					|| resolvedRepository.startsWith(resolvedRunDirectory)) {
				throw new IllegalStateException(
						RUN_SEAL_ENV + " must resolve outside the repository");
			}
			return resolvedRunDirectory;
		}
		catch (IOException exception) {
			throw new IllegalStateException(
					RUN_SEAL_ENV + " must name a resolvable external directory",
					exception);
		}
	}

	static String successRecord(VerifiedRunSeal sealed) {
		VerifiedRunSeal verified = Objects.requireNonNull(sealed, "sealed");
		return String.format(
				Locale.ROOT,
				"provider-quality-comparative-run-seal-v1 mode=verified run-seal-id=%s "
						+ "run-seal-sha256=%s report-id=%s",
				verified.sealId(),
				verified.sealSha256(),
				verified.bindings().reportId());
	}
}
