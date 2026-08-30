package com.openscholar.search.internal.persistence;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Immutable, label-free output boundary for a related-topic holdout ranking phase.
 * Scores are retained as raw IEEE-754 bits so later stability checks do not depend
 * on formatting or rounded decimal projections.
 */
record RelatedTopicReuseHoldoutRankingSnapshot(
		String bundleId,
		String corpusId,
		String policySha256,
		String corpusSha256,
		String manifestSha256,
		String judgmentsSha256,
		long judgmentsBytes,
		String candidateRevision,
		int cutoff,
		List<String> queryOrder,
		List<QueryRanking> queries,
		StructuralCounters counters) {

	static final int FROZEN_CUTOFF = 10;
	static final int MAXIMUM_QUERY_COUNT = 20;
	static final int MAXIMUM_CONTROL_POOL_SIZE = 50;
	static final int MAXIMUM_ELIGIBLE_SEEDS = 2;
	static final int MAXIMUM_FEEDBACK_CANDIDATES_PER_SEED = 25;
	static final int EVIDENCE_DIGEST_VERSION = 1;

	private static final int MAXIMUM_KEY_LENGTH = 100;
	private static final String EVIDENCE_DIGEST_DOMAIN =
			"openscholar-related-topic-holdout-ranking-snapshot";
	private static final Pattern SAFE_KEY = Pattern.compile("[a-z0-9]+(?:-[a-z0-9]+)*");
	private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");
	private static final Pattern GIT_REVISION = Pattern.compile("[0-9a-f]{40}");

	RelatedTopicReuseHoldoutRankingSnapshot {
		bundleId = requireKey(bundleId, "bundleId");
		corpusId = requireKey(corpusId, "corpusId");
		policySha256 = requireDigest(policySha256, "policySha256", SHA256);
		corpusSha256 = requireDigest(corpusSha256, "corpusSha256", SHA256);
		manifestSha256 = requireDigest(manifestSha256, "manifestSha256", SHA256);
		judgmentsSha256 = requireDigest(judgmentsSha256, "judgmentsSha256", SHA256);
		if (judgmentsBytes < 1) {
			throw new IllegalArgumentException("judgmentsBytes must be positive");
		}
		candidateRevision = requireDigest(
				candidateRevision, "candidateRevision", GIT_REVISION);
		if (cutoff != FROZEN_CUTOFF) {
			throw new IllegalArgumentException(
					"related-topic holdout cutoff must remain " + FROZEN_CUTOFF);
		}
		queryOrder = orderedKeys(queryOrder, "queryOrder", MAXIMUM_QUERY_COUNT, false);
		queries = immutableValues(queries, "queries", MAXIMUM_QUERY_COUNT, false);
		counters = Objects.requireNonNull(counters, "counters");
		List<String> actualOrder = queries.stream().map(QueryRanking::queryKey).toList();
		if (!actualOrder.equals(queryOrder)) {
			throw new IllegalArgumentException(
					"sealed query rankings must exactly partition the frozen query order");
		}
	}

	static RelatedTopicReuseHoldoutRankingSnapshot seal(
			String bundleId,
			String corpusId,
			String policySha256,
			String corpusSha256,
			String manifestSha256,
			String judgmentsSha256,
			long judgmentsBytes,
			String candidateRevision,
			int cutoff,
			List<String> queryOrder,
			List<QueryRanking> queries,
			StructuralCounters counters) {
		return new RelatedTopicReuseHoldoutRankingSnapshot(
				bundleId,
				corpusId,
				policySha256,
				corpusSha256,
				manifestSha256,
				judgmentsSha256,
				judgmentsBytes,
				candidateRevision,
				cutoff,
				queryOrder,
				queries,
				counters);
	}

	/**
	 * Returns the versioned SHA-256 commitment to this exact snapshot.
	 * The canonical byte stream uses fixed schema order, big-endian integers,
	 * length-prefixed UTF-8 strings and lists, and unmodified score bits.
	 */
	String evidenceSha256() {
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			updateString(digest, EVIDENCE_DIGEST_DOMAIN);
			updateInt(digest, EVIDENCE_DIGEST_VERSION);
			updateString(digest, bundleId);
			updateString(digest, corpusId);
			updateString(digest, policySha256);
			updateString(digest, corpusSha256);
			updateString(digest, manifestSha256);
			updateString(digest, judgmentsSha256);
			updateLong(digest, judgmentsBytes);
			updateString(digest, candidateRevision);
			updateInt(digest, cutoff);
			updateStrings(digest, queryOrder);
			updateInt(digest, queries.size());
			for (QueryRanking query : queries) {
				updateQueryRanking(digest, query);
			}
			updateLong(digest, counters.providerCallCount());
			updateLong(digest, counters.experimentalSnapshotWriteCount());
			return HexFormat.of().formatHex(digest.digest());
		}
		catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException("SHA-256 is unavailable", exception);
		}
	}

	record Observation(
			String candidateRevision,
			int cutoff,
			List<String> queryOrder,
			List<QueryRanking> queries,
			StructuralCounters counters) {

		Observation {
			candidateRevision = requireDigest(
					candidateRevision, "candidateRevision", GIT_REVISION);
			if (cutoff != FROZEN_CUTOFF) {
				throw new IllegalArgumentException(
						"related-topic holdout cutoff must remain " + FROZEN_CUTOFF);
			}
			queryOrder = orderedKeys(
					queryOrder, "queryOrder", MAXIMUM_QUERY_COUNT, false);
			queries = immutableValues(
					queries, "queries", MAXIMUM_QUERY_COUNT, false);
			counters = Objects.requireNonNull(counters, "counters");
			List<String> actualOrder = queries.stream().map(QueryRanking::queryKey).toList();
			if (!actualOrder.equals(queryOrder)) {
				throw new IllegalArgumentException(
						"ranking observations must exactly partition the frozen query order");
			}
		}
	}

	record QueryRanking(
			String queryKey,
			RankingRun initialRun,
			RankingRun repeatedRun,
			HiddenPerturbation hiddenPerturbation) {

		QueryRanking {
			queryKey = requireKey(queryKey, "queryKey");
			initialRun = Objects.requireNonNull(initialRun, "initialRun");
			repeatedRun = Objects.requireNonNull(repeatedRun, "repeatedRun");
			hiddenPerturbation = Objects.requireNonNull(
					hiddenPerturbation, "hiddenPerturbation");
			validateFeedbackPartition(
					hiddenPerturbation.visibleFeedbackPools(),
					initialRun.eligibleSeedKeys(),
					"hiddenPerturbation.visibleFeedbackPools");
			validateCandidateTop(
					hiddenPerturbation.visibleCandidateTop10(),
					initialRun.controlPool(),
					hiddenPerturbation.visibleFeedbackPools(),
					"hiddenPerturbation.visibleCandidateTop10");
		}
	}

	record RankingRun(
			List<RankedPaper> controlPool,
			List<RankedPaper> controlTop10,
			List<String> eligibleSeedKeys,
			List<FeedbackPool> feedbackPools,
			List<RankedPaper> candidateTop10) {

		RankingRun {
			controlPool = rankedPapers(
					controlPool, "controlPool", MAXIMUM_CONTROL_POOL_SIZE, true);
			controlTop10 = rankedPapers(
					controlTop10, "controlTop10", FROZEN_CUTOFF, true);
			eligibleSeedKeys = orderedKeys(
					eligibleSeedKeys, "eligibleSeedKeys", MAXIMUM_ELIGIBLE_SEEDS, true);
			feedbackPools = immutableValues(
					feedbackPools, "feedbackPools", MAXIMUM_ELIGIBLE_SEEDS, true);
			candidateTop10 = rankedPapers(
					candidateTop10, "candidateTop10", FROZEN_CUTOFF, true);

			List<RankedPaper> expectedControlTop = controlPool.stream()
					.limit(FROZEN_CUTOFF)
					.toList();
			if (!controlTop10.equals(expectedControlTop)) {
				throw new IllegalArgumentException(
						"controlTop10 must be the exact scored prefix of controlPool");
			}
			validateSeedOrder(eligibleSeedKeys, controlPool);
			validateFeedbackPartition(feedbackPools, eligibleSeedKeys, "feedbackPools");
			validateCandidateTop(candidateTop10, controlPool, feedbackPools, "candidateTop10");
		}
	}

	record HiddenPerturbation(
			String otherOwnerCandidateKey,
			String catalogOnlyCandidateKey,
			List<FeedbackPool> visibleFeedbackPools,
			List<RankedPaper> visibleCandidateTop10) {

		HiddenPerturbation {
			otherOwnerCandidateKey = requireKey(
					otherOwnerCandidateKey, "otherOwnerCandidateKey");
			catalogOnlyCandidateKey = requireKey(
					catalogOnlyCandidateKey, "catalogOnlyCandidateKey");
			if (otherOwnerCandidateKey.equals(catalogOnlyCandidateKey)) {
				throw new IllegalArgumentException(
						"hidden perturbation candidates must be distinct");
			}
			visibleFeedbackPools = immutableValues(
					visibleFeedbackPools,
					"visibleFeedbackPools",
					MAXIMUM_ELIGIBLE_SEEDS,
					true);
			visibleCandidateTop10 = rankedPapers(
					visibleCandidateTop10,
					"visibleCandidateTop10",
					FROZEN_CUTOFF,
					true);
		}
	}

	record FeedbackPool(String seedPaperKey, List<RankedPaper> candidates) {

		FeedbackPool {
			seedPaperKey = requireKey(seedPaperKey, "seedPaperKey");
			candidates = rankedPapers(
					candidates,
					"feedback candidates",
					MAXIMUM_FEEDBACK_CANDIDATES_PER_SEED,
					true);
			String frozenSeed = seedPaperKey;
			if (candidates.stream().map(RankedPaper::paperKey).anyMatch(frozenSeed::equals)) {
				throw new IllegalArgumentException(
						"a feedback pool must not contain its own seed paper");
			}
		}
	}

	record RankedPaper(String paperKey, long scoreBits) {

		RankedPaper(String paperKey, double score) {
			this(paperKey, rawFiniteBits(score));
		}

		RankedPaper {
			paperKey = requireKey(paperKey, "paperKey");
			if (!Double.isFinite(Double.longBitsToDouble(scoreBits))) {
				throw new IllegalArgumentException("ranked paper score must be finite");
			}
		}

		double score() {
			return Double.longBitsToDouble(scoreBits);
		}
	}

	record StructuralCounters(long providerCallCount, long experimentalSnapshotWriteCount) {

		StructuralCounters {
			if (providerCallCount < 0 || experimentalSnapshotWriteCount < 0) {
				throw new IllegalArgumentException("ranking counters must not be negative");
			}
		}
	}

	private static void validateSeedOrder(
			List<String> seedKeys, List<RankedPaper> controlPool) {
		int nextControlIndex = 0;
		for (String seedKey : seedKeys) {
			while (nextControlIndex < controlPool.size()
					&& !controlPool.get(nextControlIndex).paperKey().equals(seedKey)) {
				nextControlIndex++;
			}
			if (nextControlIndex == controlPool.size()) {
				throw new IllegalArgumentException(
						"eligible seeds must be an ordered subsequence of controlPool");
			}
			nextControlIndex++;
		}
	}

	private static void updateQueryRanking(MessageDigest digest, QueryRanking query) {
		updateString(digest, query.queryKey());
		updateRankingRun(digest, query.initialRun());
		updateRankingRun(digest, query.repeatedRun());
		HiddenPerturbation hidden = query.hiddenPerturbation();
		updateString(digest, hidden.otherOwnerCandidateKey());
		updateString(digest, hidden.catalogOnlyCandidateKey());
		updateFeedbackPools(digest, hidden.visibleFeedbackPools());
		updateRankedPapers(digest, hidden.visibleCandidateTop10());
	}

	private static void updateRankingRun(MessageDigest digest, RankingRun run) {
		updateRankedPapers(digest, run.controlPool());
		updateRankedPapers(digest, run.controlTop10());
		updateStrings(digest, run.eligibleSeedKeys());
		updateFeedbackPools(digest, run.feedbackPools());
		updateRankedPapers(digest, run.candidateTop10());
	}

	private static void updateFeedbackPools(
			MessageDigest digest, List<FeedbackPool> feedbackPools) {
		updateInt(digest, feedbackPools.size());
		for (FeedbackPool pool : feedbackPools) {
			updateString(digest, pool.seedPaperKey());
			updateRankedPapers(digest, pool.candidates());
		}
	}

	private static void updateRankedPapers(
			MessageDigest digest, List<RankedPaper> papers) {
		updateInt(digest, papers.size());
		for (RankedPaper paper : papers) {
			updateString(digest, paper.paperKey());
			updateLong(digest, paper.scoreBits());
		}
	}

	private static void updateStrings(MessageDigest digest, List<String> values) {
		updateInt(digest, values.size());
		for (String value : values) {
			updateString(digest, value);
		}
	}

	private static void updateString(MessageDigest digest, String value) {
		byte[] encoded = value.getBytes(StandardCharsets.UTF_8);
		updateInt(digest, encoded.length);
		digest.update(encoded);
	}

	private static void updateInt(MessageDigest digest, int value) {
		digest.update((byte) (value >>> 24));
		digest.update((byte) (value >>> 16));
		digest.update((byte) (value >>> 8));
		digest.update((byte) value);
	}

	private static void updateLong(MessageDigest digest, long value) {
		digest.update((byte) (value >>> 56));
		digest.update((byte) (value >>> 48));
		digest.update((byte) (value >>> 40));
		digest.update((byte) (value >>> 32));
		digest.update((byte) (value >>> 24));
		digest.update((byte) (value >>> 16));
		digest.update((byte) (value >>> 8));
		digest.update((byte) value);
	}

	private static void validateFeedbackPartition(
			List<FeedbackPool> feedbackPools,
			List<String> seedKeys,
			String field) {
		List<String> feedbackSeeds = feedbackPools.stream()
				.map(FeedbackPool::seedPaperKey)
				.toList();
		if (!feedbackSeeds.equals(seedKeys)) {
			throw new IllegalArgumentException(
					field + " must contain exactly one ordered pool for every eligible seed");
		}
	}

	private static void validateCandidateTop(
			List<RankedPaper> candidateTop,
			List<RankedPaper> controlPool,
			List<FeedbackPool> feedbackPools,
			String field) {
		Set<String> inputKeys = new LinkedHashSet<>();
		controlPool.stream().map(RankedPaper::paperKey).forEach(inputKeys::add);
		feedbackPools.stream()
				.flatMap(pool -> pool.candidates().stream())
				.map(RankedPaper::paperKey)
				.forEach(inputKeys::add);
		if (candidateTop.stream().map(RankedPaper::paperKey)
				.anyMatch(key -> !inputKeys.contains(key))) {
			throw new IllegalArgumentException(field + " contains a paper outside its ranking inputs");
		}
		int expectedSize = Math.min(FROZEN_CUTOFF, inputKeys.size());
		if (candidateTop.size() != expectedSize) {
			throw new IllegalArgumentException(
					field + " must freeze the complete ranking through the cutoff");
		}
	}

	private static List<RankedPaper> rankedPapers(
			List<RankedPaper> values,
			String field,
			int maximumSize,
			boolean emptyAllowed) {
		List<RankedPaper> frozen = immutableValues(values, field, maximumSize, emptyAllowed);
		Set<String> keys = new HashSet<>();
		for (RankedPaper value : frozen) {
			if (!keys.add(value.paperKey())) {
				throw new IllegalArgumentException(
						field + " must not contain duplicate paper keys: " + value.paperKey());
			}
		}
		return frozen;
	}

	private static List<String> orderedKeys(
			List<String> values,
			String field,
			int maximumSize,
			boolean emptyAllowed) {
		if (values == null) {
			throw new IllegalArgumentException(field + " must not be null");
		}
		if ((!emptyAllowed && values.isEmpty()) || values.size() > maximumSize) {
			throw new IllegalArgumentException(field + " has an invalid size");
		}
		List<String> frozen = new ArrayList<>(values.size());
		Set<String> unique = new HashSet<>();
		for (String value : values) {
			String key = requireKey(value, field);
			if (!unique.add(key)) {
				throw new IllegalArgumentException(field + " must not contain duplicate keys: " + key);
			}
			frozen.add(key);
		}
		return List.copyOf(frozen);
	}

	private static <T> List<T> immutableValues(
			List<T> values,
			String field,
			int maximumSize,
			boolean emptyAllowed) {
		if (values == null) {
			throw new IllegalArgumentException(field + " must not be null");
		}
		if ((!emptyAllowed && values.isEmpty()) || values.size() > maximumSize) {
			throw new IllegalArgumentException(field + " has an invalid size");
		}
		List<T> frozen = new ArrayList<>(values.size());
		for (T value : values) {
			if (value == null) {
				throw new IllegalArgumentException(field + " must not contain null values");
			}
			frozen.add(value);
		}
		return List.copyOf(frozen);
	}

	private static String requireKey(String value, String field) {
		if (value == null
				|| value.length() < 3
				|| value.length() > MAXIMUM_KEY_LENGTH
				|| !SAFE_KEY.matcher(value).matches()) {
			throw new IllegalArgumentException(field + " must be a bounded safe key");
		}
		return value;
	}

	private static String requireDigest(String value, String field, Pattern pattern) {
		if (value == null || !pattern.matcher(value).matches()) {
			throw new IllegalArgumentException(
					field + " must be a full lowercase hexadecimal value");
		}
		return value;
	}

	private static long rawFiniteBits(double score) {
		if (!Double.isFinite(score)) {
			throw new IllegalArgumentException("ranked paper score must be finite");
		}
		return Double.doubleToRawLongBits(score);
	}
}
