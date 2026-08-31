package com.openscholar.search.internal.persistence;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

import com.openscholar.search.internal.persistence.RelatedTopicReuseHoldoutBundle.VerifiedFirstRunCommitment;
import com.openscholar.search.internal.persistence.RelatedTopicReuseHoldoutEvaluatorSeal.RepositoryState;
import com.openscholar.search.internal.persistence.RelatedTopicReuseHoldoutEvaluatorSeal.SourceFile;
import com.openscholar.search.internal.persistence.RelatedTopicReuseHoldoutEvaluatorSeal.SourceRole;
import com.openscholar.search.internal.persistence.RelatedTopicReuseHoldoutGitCollector.FreezeRecord;
import com.openscholar.search.internal.persistence.RelatedTopicReuseHoldoutGitCollector.VerifiedCleanCheckout;

/** Reflection-only synthetic inputs for exercising the durable first-run boundary. */
final class RelatedTopicReuseHoldoutFirstRunTestFixture {

	private static final String BUNDLE_PROTOCOL_ID =
			"related-topic-reuse-holdout-bundle-v1";

	private RelatedTopicReuseHoldoutFirstRunTestFixture() {
	}

	static Inputs inputs(String marker) {
		String safeMarker = marker == null ? "" : marker;
		if (!safeMarker.matches("[a-z0-9]+(?:-[a-z0-9]+)*")) {
			throw new IllegalArgumentException("synthetic marker must be a safe identifier");
		}
		try {
			return new Inputs(commitment(safeMarker), checkout(safeMarker));
		}
		catch (ReflectiveOperationException exception) {
			throw new AssertionError("synthetic first-run fixture is unavailable", exception);
		}
	}

	private static VerifiedFirstRunCommitment commitment(String marker)
			throws ReflectiveOperationException {
		byte[] manifest = ("manifest-" + marker).getBytes(StandardCharsets.UTF_8);
		byte[] corpus = ("corpus-" + marker).getBytes(StandardCharsets.UTF_8);
		byte[] judgments = ("judgments-" + marker).getBytes(StandardCharsets.UTF_8);
		Constructor<VerifiedFirstRunCommitment> constructor =
				VerifiedFirstRunCommitment.class.getDeclaredConstructor(
						Object.class,
						String.class,
						String.class,
						String.class,
						String.class,
						String.class,
						String.class,
						String.class,
						long.class,
						String.class,
						long.class,
						String.class,
						long.class);
		constructor.setAccessible(true);
		try {
			return constructor.newInstance(
					new Object(),
					RelatedTopicReuseHoldoutPolicy.EVALUATION_PROTOCOL_ID,
					BUNDLE_PROTOCOL_ID,
					"synthetic-" + marker + "-bundle",
					"synthetic-" + marker + "-corpus",
					RelatedTopicReuseHoldoutPolicy.POLICY_ID,
					RelatedTopicReuseHoldoutPolicy.POLICY_SHA256,
					sha256(manifest),
					(long) manifest.length,
					sha256(corpus),
					(long) corpus.length,
					sha256(judgments),
					(long) judgments.length);
		}
		catch (InvocationTargetException exception) {
			throw reflectedFailure(exception);
		}
	}

	private static VerifiedCleanCheckout checkout(String marker)
			throws ReflectiveOperationException {
		String evaluatorRevision = sha1ShapedRevision(marker);
		String candidateRevision =
				RelatedTopicReuseHoldoutPolicy.CANDIDATE_FREEZE_REVISION;
		List<SourceFile> evaluatorFiles = List.of(new SourceFile(
				100644,
				"backend/src/test/java/SyntheticHoldoutEvaluator.java",
				("evaluator-" + marker).getBytes(StandardCharsets.UTF_8)));
		List<SourceFile> candidateFiles = List.of(new SourceFile(
				100644,
				"backend/src/main/java/SyntheticRelatedTopicCandidate.java",
				("candidate-" + marker).getBytes(StandardCharsets.UTF_8)));
		String evaluatorDigest = RelatedTopicReuseHoldoutEvaluatorSeal.sourceSha256(
				SourceRole.EVALUATOR, evaluatorRevision, evaluatorFiles);
		String candidateDigest = RelatedTopicReuseHoldoutEvaluatorSeal.sourceSha256(
				SourceRole.CANDIDATE, candidateRevision, candidateFiles);
		var seal = RelatedTopicReuseHoldoutEvaluatorSeal.verify(
				evaluatorRevision,
				evaluatorDigest,
				candidateRevision,
				candidateDigest,
				new RepositoryState(
						evaluatorRevision,
						"",
						candidateRevision,
						candidateDigest,
						true),
				evaluatorFiles,
				candidateFiles);
		FreezeRecord freeze = new FreezeRecord(
				RelatedTopicReuseHoldoutGitCollector.FREEZE_SCHEMA_VERSION,
				RelatedTopicReuseHoldoutGitCollector.INVENTORY_ID,
				evaluatorRevision,
				evaluatorDigest,
				candidateRevision,
				candidateDigest);
		Constructor<VerifiedCleanCheckout> constructor =
				VerifiedCleanCheckout.class.getDeclaredConstructor(
						FreezeRecord.class,
						RelatedTopicReuseHoldoutEvaluatorSeal.VerifiedEvaluatorSeal.class);
		constructor.setAccessible(true);
		try {
			return constructor.newInstance(freeze, seal);
		}
		catch (InvocationTargetException exception) {
			throw reflectedFailure(exception);
		}
	}

	private static String sha1ShapedRevision(String marker) {
		String digest = sha256(marker.getBytes(StandardCharsets.UTF_8));
		return digest.substring(0, 40);
	}

	private static String sha256(byte[] bytes) {
		try {
			return HexFormat.of().formatHex(
					MessageDigest.getInstance("SHA-256").digest(bytes));
		}
		catch (NoSuchAlgorithmException exception) {
			throw new AssertionError("SHA-256 is unavailable", exception);
		}
	}

	private static ReflectiveOperationException reflectedFailure(
			InvocationTargetException exception) {
		Throwable cause = exception.getCause();
		if (cause instanceof RuntimeException runtimeException) {
			throw runtimeException;
		}
		if (cause instanceof Error error) {
			throw error;
		}
		return exception;
	}

	record Inputs(
			VerifiedFirstRunCommitment commitment,
			VerifiedCleanCheckout checkout) {
	}
}
