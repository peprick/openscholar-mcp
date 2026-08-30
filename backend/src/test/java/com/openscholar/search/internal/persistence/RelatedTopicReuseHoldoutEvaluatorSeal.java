package com.openscholar.search.internal.persistence;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Pure, test-only integrity boundary for a future committed holdout evaluator.
 *
 * <p>This type deliberately does not discover Git state or bless the current
 * worktree. A future operator must obtain the repository-state facts and exact
 * committed bytes independently, then supply an externally retained expected
 * digest. This avoids a self-referential source seal.</p>
 */
final class RelatedTopicReuseHoldoutEvaluatorSeal {

	static final int SCHEMA_VERSION = 1;
	static final int MAXIMUM_SOURCE_FILES = 1_024;
	static final int MAXIMUM_SOURCE_FILE_BYTES = 1024 * 1024;
	static final long MAXIMUM_TOTAL_SOURCE_BYTES = 32L * 1024L * 1024L;

	private static final String DIGEST_DOMAIN =
			"openscholar-related-topic-holdout-evaluator-source";
	private static final Pattern GIT_REVISION = Pattern.compile("[0-9a-f]{40}");
	private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");
	private static final Pattern PATH_SEGMENT = Pattern.compile("[A-Za-z0-9._-]{1,100}");

	private RelatedTopicReuseHoldoutEvaluatorSeal() {
	}

	static VerifiedEvaluatorSeal verify(
			String evaluatorRevision,
			String expectedEvaluatorSourceSha256,
			String candidateRevision,
			String expectedCandidateSourceSha256,
			RepositoryState repositoryState,
			List<SourceFile> evaluatorSources,
			List<SourceFile> candidateSources) {
		requireRevision(evaluatorRevision, "evaluatorRevision");
		requireDigest(expectedEvaluatorSourceSha256, "expectedEvaluatorSourceSha256");
		requireRevision(candidateRevision, "candidateRevision");
		requireDigest(expectedCandidateSourceSha256, "expectedCandidateSourceSha256");

		PreparedSources evaluator = prepare(
				SourceRole.EVALUATOR, evaluatorRevision, evaluatorSources);
		PreparedSources candidate = prepare(
				SourceRole.CANDIDATE, candidateRevision, candidateSources);
		verifyCrossRoleConsistency(evaluator.commitments(), candidate.commitments());
		verifyRepositoryState(
				evaluatorRevision,
				candidateRevision,
				expectedCandidateSourceSha256,
				repositoryState);
		if (!constantTimeEquals(
				expectedEvaluatorSourceSha256, evaluator.aggregateSha256())) {
			throw new IllegalArgumentException(
					"evaluator source digest does not match the external freeze record");
		}
		if (!constantTimeEquals(
				expectedCandidateSourceSha256, candidate.aggregateSha256())) {
			throw new IllegalArgumentException(
					"candidate source digest does not match the frozen candidate footprint");
		}

		List<SourceFileCommitment> commitments = new ArrayList<>(
				evaluator.commitments().size() + candidate.commitments().size());
		commitments.addAll(evaluator.commitments());
		commitments.addAll(candidate.commitments());
		return new VerifiedEvaluatorSeal(
				evaluatorRevision,
				evaluator.aggregateSha256(),
				candidateRevision,
				candidate.aggregateSha256(),
				commitments);
	}

	static String sourceSha256(
			SourceRole role, String revision, List<SourceFile> sources) {
		requireRevision(revision, "revision");
		return prepare(role, revision, sources).aggregateSha256();
	}

	static void verifyRepositoryState(
			String expectedEvaluatorRevision,
			String expectedCandidateRevision,
			String expectedCandidateFootprintSha256,
			RepositoryState state) {
		requireRevision(expectedEvaluatorRevision, "expectedEvaluatorRevision");
		requireRevision(expectedCandidateRevision, "expectedCandidateRevision");
		requireDigest(expectedCandidateFootprintSha256,
				"expectedCandidateFootprintSha256");
		Objects.requireNonNull(state, "repositoryState");
		requireRevision(state.actualHeadRevision(), "actualHeadRevision");
		requireRevision(state.checkedCandidateRevision(), "checkedCandidateRevision");
		requireDigest(state.observedCandidateFootprintSha256(),
				"observedCandidateFootprintSha256");
		if (!expectedEvaluatorRevision.equals(state.actualHeadRevision())) {
			throw new IllegalArgumentException(
					"the checked-out Git revision does not match the evaluator freeze");
		}
		if (!state.porcelainStatus().isEmpty()) {
			throw new IllegalArgumentException(
					"evaluator verification requires a clean worktree including untracked files");
		}
		if (!state.candidateRevisionIsAncestor()) {
			throw new IllegalArgumentException(
					"the frozen candidate revision is not an ancestor of the evaluator revision");
		}
		if (!expectedCandidateRevision.equals(state.checkedCandidateRevision())) {
			throw new IllegalArgumentException(
					"repository-state facts do not describe the frozen candidate revision");
		}
		if (!constantTimeEquals(
				expectedCandidateFootprintSha256,
				state.observedCandidateFootprintSha256())) {
			throw new IllegalArgumentException(
					"candidate source footprint drifted after the frozen candidate revision");
		}
	}

	private static PreparedSources prepare(
			SourceRole role, String revision, List<SourceFile> sources) {
		Objects.requireNonNull(role, "role");
		Objects.requireNonNull(sources, "sources");
		if (sources.isEmpty() || sources.size() > MAXIMUM_SOURCE_FILES) {
			throw new IllegalArgumentException(
					"source inventory must contain 1 through "
							+ MAXIMUM_SOURCE_FILES + " files");
		}
		List<SourceFile> ordered = sources.stream()
				.map(source -> Objects.requireNonNull(source, "source"))
				.sorted(Comparator.comparing(SourceFile::path))
				.toList();
		Set<String> paths = new HashSet<>();
		long totalBytes = 0L;
		List<SourceFileCommitment> commitments = new ArrayList<>(ordered.size());
		MessageDigest aggregate = sha256Digest();
		updateString(aggregate, DIGEST_DOMAIN);
		updateInt(aggregate, SCHEMA_VERSION);
		updateString(aggregate, role.name());
		updateString(aggregate, revision);
		updateInt(aggregate, ordered.size());
		for (SourceFile source : ordered) {
			if (!paths.add(source.path())) {
				throw new IllegalArgumentException(
						"source inventory contains a duplicate path: " + source.path());
			}
			byte[] bytes = source.content();
			totalBytes = Math.addExact(totalBytes, bytes.length);
			if (totalBytes > MAXIMUM_TOTAL_SOURCE_BYTES) {
				throw new IllegalArgumentException("source inventory exceeds its byte budget");
			}
			updateString(aggregate, role.name());
			updateInt(aggregate, source.gitMode());
			updateString(aggregate, source.path());
			updateLong(aggregate, bytes.length);
			updateBytes(aggregate, bytes);
			commitments.add(new SourceFileCommitment(
					role,
					source.gitMode(),
					source.path(),
					bytes.length,
					sha256(bytes)));
		}
		return new PreparedSources(
				HexFormat.of().formatHex(aggregate.digest()), commitments);
	}

	private static void verifyCrossRoleConsistency(
			List<SourceFileCommitment> evaluatorCommitments,
			List<SourceFileCommitment> candidateCommitments) {
		Map<String, SourceFileCommitment> evaluatorByPath =
				evaluatorCommitments.stream().collect(Collectors.toUnmodifiableMap(
						SourceFileCommitment::path,
						commitment -> commitment));
		for (SourceFileCommitment candidate : candidateCommitments) {
			SourceFileCommitment evaluator = evaluatorByPath.get(candidate.path());
			if (evaluator != null
					&& (evaluator.gitMode() != candidate.gitMode()
							|| evaluator.bytes() != candidate.bytes()
							|| !constantTimeEquals(evaluator.sha256(), candidate.sha256()))) {
				throw new IllegalArgumentException(
						"source inventories contain contradictory commitments for path: "
								+ candidate.path());
			}
		}
	}

	private static String requireSafePath(String value) {
		Objects.requireNonNull(value, "path");
		if (value.isEmpty() || value.length() > 240 || value.startsWith("/")
				|| value.endsWith("/") || value.contains("\\") || value.contains("//")) {
			throw new IllegalArgumentException("source path must be a bounded POSIX-relative path");
		}
		for (String segment : value.split("/", -1)) {
			if (segment.equals(".") || segment.equals("..")
					|| !PATH_SEGMENT.matcher(segment).matches()) {
				throw new IllegalArgumentException("source path contains an unsafe segment");
			}
		}
		return value;
	}

	private static void requireRevision(String value, String field) {
		if (value == null || !GIT_REVISION.matcher(value).matches()) {
			throw new IllegalArgumentException(field + " must be a lowercase full Git revision");
		}
	}

	private static void requireDigest(String value, String field) {
		if (value == null || !SHA256.matcher(value).matches()) {
			throw new IllegalArgumentException(field + " must be a lowercase SHA-256 digest");
		}
	}

	private static boolean constantTimeEquals(String left, String right) {
		return MessageDigest.isEqual(
				left.getBytes(StandardCharsets.US_ASCII),
				right.getBytes(StandardCharsets.US_ASCII));
	}

	private static MessageDigest sha256Digest() {
		try {
			return MessageDigest.getInstance("SHA-256");
		}
		catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException("SHA-256 is unavailable", exception);
		}
	}

	private static String sha256(byte[] bytes) {
		return HexFormat.of().formatHex(sha256Digest().digest(bytes));
	}

	private static void updateString(MessageDigest digest, String value) {
		updateBytes(digest, value.getBytes(StandardCharsets.UTF_8));
	}

	private static void updateBytes(MessageDigest digest, byte[] value) {
		updateInt(digest, value.length);
		digest.update(value);
	}

	private static void updateInt(MessageDigest digest, int value) {
		digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(value).array());
	}

	private static void updateLong(MessageDigest digest, long value) {
		digest.update(ByteBuffer.allocate(Long.BYTES).putLong(value).array());
	}

	enum SourceRole {
		EVALUATOR,
		CANDIDATE
	}

	static final class SourceFile {

		private final int gitMode;
		private final String path;
		private final byte[] content;

		SourceFile(int gitMode, String path, byte[] content) {
			if (gitMode != 100644 && gitMode != 100755) {
				throw new IllegalArgumentException(
						"source files must use Git mode 100644 or 100755");
			}
			this.gitMode = gitMode;
			this.path = requireSafePath(path);
			this.content = Objects.requireNonNull(content, "content").clone();
			if (this.content.length < 1 || this.content.length > MAXIMUM_SOURCE_FILE_BYTES) {
				throw new IllegalArgumentException("source file exceeds its byte budget");
			}
		}

		int gitMode() {
			return gitMode;
		}

		String path() {
			return path;
		}

		byte[] content() {
			return content.clone();
		}
	}

	record RepositoryState(
			String actualHeadRevision,
			String porcelainStatus,
			String checkedCandidateRevision,
			String observedCandidateFootprintSha256,
			boolean candidateRevisionIsAncestor) {

		RepositoryState {
			Objects.requireNonNull(actualHeadRevision, "actualHeadRevision");
			Objects.requireNonNull(porcelainStatus, "porcelainStatus");
			Objects.requireNonNull(checkedCandidateRevision, "checkedCandidateRevision");
			Objects.requireNonNull(
					observedCandidateFootprintSha256,
					"observedCandidateFootprintSha256");
		}
	}

	record SourceFileCommitment(
			SourceRole role, int gitMode, String path, long bytes, String sha256) {

		SourceFileCommitment {
			Objects.requireNonNull(role, "role");
			if (gitMode != 100644 && gitMode != 100755) {
				throw new IllegalArgumentException("invalid committed Git mode");
			}
			path = requireSafePath(path);
			if (bytes < 1 || bytes > MAXIMUM_SOURCE_FILE_BYTES) {
				throw new IllegalArgumentException("invalid committed source byte count");
			}
			requireDigest(sha256, "sha256");
		}
	}

	static final class VerifiedEvaluatorSeal {

		private final String evaluatorRevision;
		private final String evaluatorSourceSha256;
		private final String candidateRevision;
		private final String candidateSourceSha256;
		private final List<SourceFileCommitment> files;

		private VerifiedEvaluatorSeal(
				String evaluatorRevision,
				String evaluatorSourceSha256,
				String candidateRevision,
				String candidateSourceSha256,
				List<SourceFileCommitment> files) {
			this.evaluatorRevision = evaluatorRevision;
			this.evaluatorSourceSha256 = evaluatorSourceSha256;
			this.candidateRevision = candidateRevision;
			this.candidateSourceSha256 = candidateSourceSha256;
			this.files = List.copyOf(files);
		}

		String evaluatorRevision() {
			return evaluatorRevision;
		}

		String evaluatorSourceSha256() {
			return evaluatorSourceSha256;
		}

		String candidateRevision() {
			return candidateRevision;
		}

		String candidateSourceSha256() {
			return candidateSourceSha256;
		}

		List<SourceFileCommitment> files() {
			return files;
		}

		boolean externalBundleAcceptanceAuthorized() {
			return false;
		}

		boolean custodyReleaseAuthorized() {
			return false;
		}
	}

	private record PreparedSources(
			String aggregateSha256, List<SourceFileCommitment> commitments) {

		private PreparedSources {
			commitments = List.copyOf(commitments);
		}
	}
}
