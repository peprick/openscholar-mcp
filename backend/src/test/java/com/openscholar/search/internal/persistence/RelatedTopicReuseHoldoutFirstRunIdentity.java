package com.openscholar.search.internal.persistence;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.regex.Pattern;

import com.openscholar.search.internal.persistence.RelatedTopicReuseHoldoutBundle.VerifiedFirstRunCommitment;
import com.openscholar.search.internal.persistence.RelatedTopicReuseHoldoutGitCollector.VerifiedCleanCheckout;

/** Canonical, label-free identity committed before the first holdout ranking starts. */
final class RelatedTopicReuseHoldoutFirstRunIdentity {

	static final int SCHEMA_VERSION = 1;
	static final String RUN_KEY_DOMAIN =
			"openscholar.related-topic-reuse-holdout.first-run-key.v1";

	private static final String BUNDLE_PROTOCOL_ID =
			"related-topic-reuse-holdout-bundle-v1";
	private static final long MAXIMUM_MANIFEST_BYTES = 65_536L;
	private static final long MAXIMUM_CORPUS_BYTES = 786_432L;
	private static final long MAXIMUM_JUDGMENTS_BYTES = 196_608L;
	private static final Pattern SAFE_ID =
			Pattern.compile("[a-z0-9]+(?:-[a-z0-9]+)*");
	private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");
	private static final Pattern REVISION = Pattern.compile("[0-9a-f]{40}");

	private final VerifiedFirstRunCommitment commitment;
	private final VerifiedCleanCheckout checkout;
	private final FinalityKey finalityKey;
	private final byte[] runKey;

	private RelatedTopicReuseHoldoutFirstRunIdentity(
			VerifiedFirstRunCommitment commitment,
			VerifiedCleanCheckout checkout) {
		this.commitment = Objects.requireNonNull(commitment, "commitment");
		this.checkout = Objects.requireNonNull(checkout, "checkout");
		validate();
		this.finalityKey = new FinalityKey(
				commitment.evaluationProtocolId(), commitment.policyId());
		this.runKey = calculateRunKey();
	}

	static RelatedTopicReuseHoldoutFirstRunIdentity fromVerified(
			VerifiedFirstRunCommitment commitment,
			VerifiedCleanCheckout checkout) {
		return new RelatedTopicReuseHoldoutFirstRunIdentity(commitment, checkout);
	}

	private void validate() {
		requireId(commitment.evaluationProtocolId(), "evaluationProtocolId");
		requireId(commitment.bundleProtocolId(), "bundleProtocolId");
		requireId(commitment.bundleId(), "bundleId");
		requireId(commitment.corpusId(), "corpusId");
		requireId(commitment.policyId(), "policyId");
		requireDigest(commitment.policySha256(), "policySha256");
		requireDigest(commitment.manifestSha256(), "manifestSha256");
		requireDigest(commitment.corpusSha256(), "corpusSha256");
		requireDigest(commitment.judgmentsSha256(), "judgmentsSha256");
		requireSize(commitment.manifestBytes(), MAXIMUM_MANIFEST_BYTES, "manifestBytes");
		requireSize(commitment.corpusBytes(), MAXIMUM_CORPUS_BYTES, "corpusBytes");
		requireSize(
				commitment.judgmentsBytes(), MAXIMUM_JUDGMENTS_BYTES, "judgmentsBytes");
		requireId(checkout.inventoryId(), "inventoryId");
		requireRevision(checkout.evaluatorRevision(), "evaluatorRevision");
		requireDigest(checkout.evaluatorSourceSha256(), "evaluatorSourceSha256");
		requireRevision(checkout.candidateRevision(), "candidateRevision");
		requireDigest(checkout.candidateSourceSha256(), "candidateSourceSha256");

		if (!RelatedTopicReuseHoldoutPolicy.EVALUATION_PROTOCOL_ID
				.equals(commitment.evaluationProtocolId())
				|| !BUNDLE_PROTOCOL_ID.equals(commitment.bundleProtocolId())
				|| !RelatedTopicReuseHoldoutPolicy.POLICY_ID.equals(commitment.policyId())
				|| !RelatedTopicReuseHoldoutPolicy.POLICY_SHA256
						.equals(commitment.policySha256())
				|| checkout.freezeSchemaVersion()
						!= RelatedTopicReuseHoldoutGitCollector.FREEZE_SCHEMA_VERSION
				|| !RelatedTopicReuseHoldoutGitCollector.INVENTORY_ID
						.equals(checkout.inventoryId())
				|| !RelatedTopicReuseHoldoutPolicy.CANDIDATE_FREEZE_REVISION
						.equals(checkout.candidateRevision())
				|| checkout.externalBundleAcceptanceAuthorized()
				|| checkout.custodyReleaseAuthorized()) {
			throw new IllegalArgumentException("first-run inputs do not match the frozen policy");
		}
	}

	private byte[] calculateRunKey() {
		MessageDigest digest = sha256();
		updateString(digest, RUN_KEY_DOMAIN);
		updateInt(digest, SCHEMA_VERSION);
		updateString(digest, commitment.evaluationProtocolId());
		updateString(digest, commitment.policyId());
		updateString(digest, commitment.policySha256());
		updateString(digest, commitment.bundleProtocolId());
		updateString(digest, commitment.bundleId());
		updateString(digest, commitment.corpusId());
		updateString(digest, commitment.manifestSha256());
		updateLong(digest, commitment.manifestBytes());
		updateString(digest, commitment.corpusSha256());
		updateLong(digest, commitment.corpusBytes());
		updateString(digest, commitment.judgmentsSha256());
		updateLong(digest, commitment.judgmentsBytes());
		updateInt(digest, checkout.freezeSchemaVersion());
		updateString(digest, checkout.inventoryId());
		updateString(digest, checkout.evaluatorRevision());
		updateString(digest, checkout.evaluatorSourceSha256());
		updateString(digest, checkout.candidateRevision());
		updateString(digest, checkout.candidateSourceSha256());
		return digest.digest();
	}

	FinalityKey finalityKey() {
		return finalityKey;
	}

	String runKey() {
		return HexFormat.of().formatHex(runKey);
	}

	byte[] runKeyBytes() {
		return runKey.clone();
	}

	VerifiedFirstRunCommitment commitment() {
		return commitment;
	}

	VerifiedCleanCheckout checkout() {
		return checkout;
	}

	private static void requireId(String value, String field) {
		if (value == null || value.length() < 3 || value.length() > 160
				|| !SAFE_ID.matcher(value).matches()) {
			throw new IllegalArgumentException(field + " must be a bounded safe identifier");
		}
	}

	private static void requireDigest(String value, String field) {
		if (value == null || !SHA256.matcher(value).matches()) {
			throw new IllegalArgumentException(field + " must be a lowercase SHA-256 digest");
		}
	}

	private static void requireRevision(String value, String field) {
		if (value == null || !REVISION.matcher(value).matches()) {
			throw new IllegalArgumentException(field + " must be a lowercase Git revision");
		}
	}

	private static void requireSize(long value, long maximum, String field) {
		if (value < 1 || value > maximum) {
			throw new IllegalArgumentException(field + " is outside the frozen byte budget");
		}
	}

	private static MessageDigest sha256() {
		try {
			return MessageDigest.getInstance("SHA-256");
		}
		catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException("SHA-256 is unavailable", exception);
		}
	}

	private static void updateString(MessageDigest digest, String value) {
		byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
		updateInt(digest, bytes.length);
		digest.update(bytes);
	}

	private static void updateInt(MessageDigest digest, int value) {
		digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(value).array());
	}

	private static void updateLong(MessageDigest digest, long value) {
		digest.update(ByteBuffer.allocate(Long.BYTES).putLong(value).array());
	}

	record FinalityKey(String evaluationProtocolId, String policyId) {

		FinalityKey {
			requireId(evaluationProtocolId, "evaluationProtocolId");
			requireId(policyId, "policyId");
		}
	}
}
