package com.openscholar.search.internal.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.openscholar.search.internal.persistence.RelatedTopicReuseHoldoutEvaluatorSeal.RepositoryState;
import com.openscholar.search.internal.persistence.RelatedTopicReuseHoldoutEvaluatorSeal.SourceFile;
import com.openscholar.search.internal.persistence.RelatedTopicReuseHoldoutEvaluatorSeal.SourceRole;

class RelatedTopicReuseHoldoutEvaluatorSealTests {

	private static final String EVALUATOR_REVISION = "a".repeat(40);
	private static final String CANDIDATE_REVISION = "b".repeat(40);

	@Test
	void canonicalSourceDigestIsStableAndIndependentOfInputOrder() {
		List<SourceFile> ordered = evaluatorSources();
		List<SourceFile> reversed = List.of(ordered.get(1), ordered.get(0));

		String first = RelatedTopicReuseHoldoutEvaluatorSeal.sourceSha256(
				SourceRole.EVALUATOR, EVALUATOR_REVISION, ordered);
		String second = RelatedTopicReuseHoldoutEvaluatorSeal.sourceSha256(
				SourceRole.EVALUATOR, EVALUATOR_REVISION, reversed);

		assertThat(first).isEqualTo(second);
		assertThat(first)
				.isEqualTo("825d0f2a99cc32a949d674ed6ffd7e205a2d7e759634acae057ed7f64d909f0b");
	}

	@Test
	void digestBindsRoleRevisionModePathAndExactBytes() {
		String baseline = digest(
				SourceRole.EVALUATOR,
				EVALUATOR_REVISION,
				new SourceFile(100644, "backend/pom.xml", bytes("alpha")));

		assertThat(digest(
				SourceRole.CANDIDATE,
				EVALUATOR_REVISION,
				new SourceFile(100644, "backend/pom.xml", bytes("alpha"))))
				.isNotEqualTo(baseline);
		assertThat(digest(
				SourceRole.EVALUATOR,
				"c".repeat(40),
				new SourceFile(100644, "backend/pom.xml", bytes("alpha"))))
				.isNotEqualTo(baseline);
		assertThat(digest(
				SourceRole.EVALUATOR,
				EVALUATOR_REVISION,
				new SourceFile(100755, "backend/pom.xml", bytes("alpha"))))
				.isNotEqualTo(baseline);
		assertThat(digest(
				SourceRole.EVALUATOR,
				EVALUATOR_REVISION,
				new SourceFile(100644, "backend/pom-copy.xml", bytes("alpha"))))
				.isNotEqualTo(baseline);
		assertThat(digest(
				SourceRole.EVALUATOR,
				EVALUATOR_REVISION,
				new SourceFile(100644, "backend/pom.xml", bytes("alphb"))))
				.isNotEqualTo(baseline);
	}

	@Test
	void verificationBindsBothExternallySuppliedSourceDigests() {
		List<SourceFile> evaluator = evaluatorSources();
		List<SourceFile> candidate = candidateSources();
		String evaluatorSha = RelatedTopicReuseHoldoutEvaluatorSeal.sourceSha256(
				SourceRole.EVALUATOR, EVALUATOR_REVISION, evaluator);
		String candidateSha = RelatedTopicReuseHoldoutEvaluatorSeal.sourceSha256(
				SourceRole.CANDIDATE, CANDIDATE_REVISION, candidate);

		var verified = RelatedTopicReuseHoldoutEvaluatorSeal.verify(
				EVALUATOR_REVISION,
				evaluatorSha,
				CANDIDATE_REVISION,
				candidateSha,
				cleanState(),
				evaluator,
				candidate);

		assertThat(verified.evaluatorRevision()).isEqualTo(EVALUATOR_REVISION);
		assertThat(verified.evaluatorSourceSha256()).isEqualTo(evaluatorSha);
		assertThat(verified.candidateRevision()).isEqualTo(CANDIDATE_REVISION);
		assertThat(verified.candidateSourceSha256()).isEqualTo(candidateSha);
		assertThat(verified.files())
				.extracting(RelatedTopicReuseHoldoutEvaluatorSeal.SourceFileCommitment::path)
				.containsExactly(
						"backend/pom.xml",
						"backend/src/test/java/Evaluator.java",
						"backend/src/main/java/Candidate.java");
		assertThat(verified.externalBundleAcceptanceAuthorized()).isFalse();
		assertThat(verified.custodyReleaseAuthorized()).isFalse();

		assertThatThrownBy(() -> RelatedTopicReuseHoldoutEvaluatorSeal.verify(
				EVALUATOR_REVISION,
				"0".repeat(64),
				CANDIDATE_REVISION,
				candidateSha,
				cleanState(),
				evaluator,
				candidate))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("external freeze record");
	}

	@Test
	void repositoryStateFailsClosedForMismatchDirtAncestorOrCandidateDrift() {
		String candidateSha = RelatedTopicReuseHoldoutEvaluatorSeal.sourceSha256(
				SourceRole.CANDIDATE, CANDIDATE_REVISION, candidateSources());
		assertThatThrownBy(() -> RelatedTopicReuseHoldoutEvaluatorSeal
				.verifyRepositoryState(
						EVALUATOR_REVISION,
						CANDIDATE_REVISION,
						candidateSha,
						new RepositoryState(
								"c".repeat(40), "", CANDIDATE_REVISION, candidateSha, true)))
				.hasMessageContaining("does not match");
		assertThatThrownBy(() -> RelatedTopicReuseHoldoutEvaluatorSeal
				.verifyRepositoryState(
						EVALUATOR_REVISION,
						CANDIDATE_REVISION,
						candidateSha,
						new RepositoryState(
								EVALUATOR_REVISION,
								"?? untracked.java\n",
								CANDIDATE_REVISION,
								candidateSha,
								true)))
				.hasMessageContaining("clean worktree");
		assertThatThrownBy(() -> RelatedTopicReuseHoldoutEvaluatorSeal
				.verifyRepositoryState(
						EVALUATOR_REVISION,
						CANDIDATE_REVISION,
						candidateSha,
						new RepositoryState(
								EVALUATOR_REVISION, "", CANDIDATE_REVISION, candidateSha, false)))
				.hasMessageContaining("not an ancestor");
		assertThatThrownBy(() -> RelatedTopicReuseHoldoutEvaluatorSeal
				.verifyRepositoryState(
						EVALUATOR_REVISION,
						CANDIDATE_REVISION,
						candidateSha,
						new RepositoryState(
								EVALUATOR_REVISION,
								"",
								CANDIDATE_REVISION,
								"f".repeat(64),
								true)))
				.hasMessageContaining("footprint drifted");
		assertThatThrownBy(() -> RelatedTopicReuseHoldoutEvaluatorSeal
				.verifyRepositoryState(
						EVALUATOR_REVISION,
						CANDIDATE_REVISION,
						candidateSha,
						new RepositoryState(
								EVALUATOR_REVISION,
								"",
								"e".repeat(40),
								candidateSha,
								true)))
				.hasMessageContaining("do not describe");
	}

	@Test
	void inventoriesRejectDuplicatesUnsafePathsSymlinkModesAndMutableBytes() {
		SourceFile first = new SourceFile(100644, "backend/pom.xml", bytes("alpha"));
		SourceFile duplicate = new SourceFile(100644, "backend/pom.xml", bytes("beta"));
		assertThatThrownBy(() -> RelatedTopicReuseHoldoutEvaluatorSeal.sourceSha256(
				SourceRole.EVALUATOR,
				EVALUATOR_REVISION,
				List.of(first, duplicate)))
				.hasMessageContaining("duplicate path");
		assertThatThrownBy(() -> new SourceFile(
				100644, "backend/../outside.java", bytes("x")))
				.hasMessageContaining("unsafe segment");
		assertThatThrownBy(() -> new SourceFile(
				120000, "backend/link", bytes("target")))
				.hasMessageContaining("100644 or 100755");

		byte[] mutable = bytes("alpha");
		SourceFile copied = new SourceFile(100644, "backend/pom.xml", mutable);
		String before = RelatedTopicReuseHoldoutEvaluatorSeal.sourceSha256(
				SourceRole.EVALUATOR, EVALUATOR_REVISION, List.of(copied));
		mutable[0] = 'z';
		byte[] exposed = copied.content();
		exposed[0] = 'y';
		assertThat(RelatedTopicReuseHoldoutEvaluatorSeal.sourceSha256(
				SourceRole.EVALUATOR, EVALUATOR_REVISION, List.of(copied)))
				.isEqualTo(before);
	}

	@Test
	void crossRoleDuplicatePathsMustDescribeTheSameCommittedFile() {
		SourceFile evaluatorShared = new SourceFile(
				100644, "backend/shared.gradle", bytes("shared-content"));
		SourceFile candidateShared = new SourceFile(
				100644, "backend/shared.gradle", bytes("shared-content"));
		assertThat(verify(List.of(evaluatorShared), List.of(candidateShared)).files())
				.extracting(RelatedTopicReuseHoldoutEvaluatorSeal.SourceFileCommitment::path)
				.containsExactly("backend/shared.gradle", "backend/shared.gradle");

		assertContradictoryCrossRoleDuplicateRejected(
				evaluatorShared,
				new SourceFile(100755, "backend/shared.gradle", bytes("shared-content")));
		assertContradictoryCrossRoleDuplicateRejected(
				evaluatorShared,
				new SourceFile(100644, "backend/shared.gradle", bytes("changed-content")));
		assertContradictoryCrossRoleDuplicateRejected(
				evaluatorShared,
				new SourceFile(100644, "backend/shared.gradle", bytes("short")));
	}

	private static void assertContradictoryCrossRoleDuplicateRejected(
			SourceFile evaluator, SourceFile candidate) {
		assertThatThrownBy(() -> verify(List.of(evaluator), List.of(candidate)))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("contradictory commitments");
	}

	private static RelatedTopicReuseHoldoutEvaluatorSeal.VerifiedEvaluatorSeal verify(
			List<SourceFile> evaluator, List<SourceFile> candidate) {
		String evaluatorSha = RelatedTopicReuseHoldoutEvaluatorSeal.sourceSha256(
				SourceRole.EVALUATOR, EVALUATOR_REVISION, evaluator);
		String candidateSha = RelatedTopicReuseHoldoutEvaluatorSeal.sourceSha256(
				SourceRole.CANDIDATE, CANDIDATE_REVISION, candidate);
		return RelatedTopicReuseHoldoutEvaluatorSeal.verify(
				EVALUATOR_REVISION,
				evaluatorSha,
				CANDIDATE_REVISION,
				candidateSha,
				new RepositoryState(
						EVALUATOR_REVISION,
						"",
						CANDIDATE_REVISION,
						candidateSha,
						true),
				evaluator,
				candidate);
	}

	private static String digest(SourceRole role, String revision, SourceFile source) {
		return RelatedTopicReuseHoldoutEvaluatorSeal.sourceSha256(
				role, revision, List.of(source));
	}

	private static List<SourceFile> evaluatorSources() {
		return List.of(
				new SourceFile(
						100644,
						"backend/src/test/java/Evaluator.java",
						bytes("class Evaluator {}\n")),
				new SourceFile(100644, "backend/pom.xml", bytes("<project/>\n")));
	}

	private static List<SourceFile> candidateSources() {
		return List.of(new SourceFile(
				100644,
				"backend/src/main/java/Candidate.java",
				bytes("class Candidate {}\n")));
	}

	private static RepositoryState cleanState() {
		String candidateSha = RelatedTopicReuseHoldoutEvaluatorSeal.sourceSha256(
				SourceRole.CANDIDATE, CANDIDATE_REVISION, candidateSources());
		return new RepositoryState(
				EVALUATOR_REVISION,
				"",
				CANDIDATE_REVISION,
				candidateSha,
				true);
	}

	private static byte[] bytes(String value) {
		return value.getBytes(StandardCharsets.UTF_8);
	}
}
