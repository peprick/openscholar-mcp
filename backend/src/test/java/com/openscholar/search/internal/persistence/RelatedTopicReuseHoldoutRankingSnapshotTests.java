package com.openscholar.search.internal.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;

import com.openscholar.search.internal.persistence.RelatedTopicReuseHoldoutRankingSnapshot.FeedbackPool;
import com.openscholar.search.internal.persistence.RelatedTopicReuseHoldoutRankingSnapshot.HiddenPerturbation;
import com.openscholar.search.internal.persistence.RelatedTopicReuseHoldoutRankingSnapshot.QueryRanking;
import com.openscholar.search.internal.persistence.RelatedTopicReuseHoldoutRankingSnapshot.RankedPaper;
import com.openscholar.search.internal.persistence.RelatedTopicReuseHoldoutRankingSnapshot.RankingRun;
import com.openscholar.search.internal.persistence.RelatedTopicReuseHoldoutRankingSnapshot.StructuralCounters;
import org.junit.jupiter.api.Test;

class RelatedTopicReuseHoldoutRankingSnapshotTests {

	private static final String BUNDLE_ID = "holdout-bundle";
	private static final String CORPUS_ID = "holdout-corpus";
	private static final String POLICY_SHA256 = "a".repeat(64);
	private static final String CORPUS_SHA256 = "b".repeat(64);
	private static final String MANIFEST_SHA256 = "d".repeat(64);
	private static final String JUDGMENTS_SHA256 = "e".repeat(64);
	private static final long JUDGMENTS_BYTES = 4096;
	private static final String CANDIDATE_REVISION = "c".repeat(40);
	private static final String EXPECTED_EVIDENCE_SHA256 =
			"d0cbb70b4c25c5ea1b820874ccedfe4aa36e9c62c6fa3bde2aa039c325070eb9";

	@Test
	void sealsTheExactOrderedQueryPartitionAndRawScoreBits() {
		RankingRun first = seededRun(0.0d);
		RankingRun repeated = seededRun(-0.0d);
		QueryRanking firstQuery = query("query-one", first, repeated);
		QueryRanking secondQuery = query("query-two", first, repeated);

		RelatedTopicReuseHoldoutRankingSnapshot snapshot =
				snapshot(
						10,
						List.of("query-one", "query-two"),
						List.of(firstQuery, secondQuery),
						new StructuralCounters(0, 0));

		assertThat(snapshot.bundleId()).isEqualTo(BUNDLE_ID);
		assertThat(snapshot.corpusId()).isEqualTo(CORPUS_ID);
		assertThat(snapshot.policySha256()).isEqualTo(POLICY_SHA256);
		assertThat(snapshot.corpusSha256()).isEqualTo(CORPUS_SHA256);
		assertThat(snapshot.manifestSha256()).isEqualTo(MANIFEST_SHA256);
		assertThat(snapshot.judgmentsSha256()).isEqualTo(JUDGMENTS_SHA256);
		assertThat(snapshot.judgmentsBytes()).isEqualTo(JUDGMENTS_BYTES);
		assertThat(snapshot.candidateRevision()).isEqualTo(CANDIDATE_REVISION);
		assertThat(snapshot.cutoff()).isEqualTo(10);
		assertThat(snapshot.queryOrder()).containsExactly("query-one", "query-two");
		assertThat(snapshot.queries()).containsExactly(firstQuery, secondQuery);
		long positiveZero = snapshot.queries().getFirst().initialRun()
				.controlPool().getFirst().scoreBits();
		long negativeZero = snapshot.queries().getFirst().repeatedRun()
				.controlPool().getFirst().scoreBits();
		assertThat(positiveZero).isEqualTo(Double.doubleToRawLongBits(0.0d));
		assertThat(negativeZero).isEqualTo(Double.doubleToRawLongBits(-0.0d));
		assertThat(negativeZero).isNotEqualTo(positiveZero);
		assertThat(Double.doubleToRawLongBits(
				snapshot.queries().getFirst().repeatedRun().controlPool().getFirst().score()))
				.isEqualTo(negativeZero);
	}

	@Test
	void producesAStableVersionedCanonicalEvidenceDigest() {
		RankingRun firstRun = seededRun(1.0d);
		RelatedTopicReuseHoldoutRankingSnapshot first = snapshot(
				10,
				List.of("query-one"),
				List.of(query("query-one", firstRun, firstRun)),
				new StructuralCounters(0, 0));
		RankingRun independentlyBuiltRun = seededRun(1.0d);
		RelatedTopicReuseHoldoutRankingSnapshot equivalent = snapshot(
				10,
				List.of("query-one"),
				List.of(query(
						"query-one", independentlyBuiltRun, independentlyBuiltRun)),
				new StructuralCounters(0, 0));

		assertThat(RelatedTopicReuseHoldoutRankingSnapshot.EVIDENCE_DIGEST_VERSION)
				.isEqualTo(1);
		assertThat(first.evidenceSha256())
				.matches("[0-9a-f]{64}")
				.isEqualTo(first.evidenceSha256())
				.isEqualTo(equivalent.evidenceSha256())
				.isEqualTo(EXPECTED_EVIDENCE_SHA256);
	}

	@Test
	void evidenceDigestBindsOneRawScoreBitOneRankedKeyAndEveryCounter() {
		RankingRun baseRun = denseRun();
		RelatedTopicReuseHoldoutRankingSnapshot baseline = snapshotWithRun(
				baseRun, new StructuralCounters(0, 0));

		List<RankedPaper> scoreChangedTop = new ArrayList<>(baseRun.candidateTop10());
		RankedPaper originalPaper = scoreChangedTop.getFirst();
		RankedPaper scoreChangedPaper = new RankedPaper(
				originalPaper.paperKey(), originalPaper.scoreBits() ^ 1L);
		scoreChangedTop.set(0, scoreChangedPaper);
		RankingRun scoreChangedRun = new RankingRun(
				baseRun.controlPool(),
				baseRun.controlTop10(),
				baseRun.eligibleSeedKeys(),
				baseRun.feedbackPools(),
				scoreChangedTop);
		RelatedTopicReuseHoldoutRankingSnapshot scoreChanged = snapshotWithInitialRun(
				baseRun, scoreChangedRun, new StructuralCounters(0, 0));

		List<RankedPaper> keyChangedTop = new ArrayList<>(baseRun.candidateTop10());
		RankedPaper finalRankedPaper = keyChangedTop.getLast();
		keyChangedTop.set(
				keyChangedTop.size() - 1,
				new RankedPaper("control-10", finalRankedPaper.scoreBits()));
		RankingRun keyChangedRun = new RankingRun(
				baseRun.controlPool(),
				baseRun.controlTop10(),
				baseRun.eligibleSeedKeys(),
				baseRun.feedbackPools(),
				keyChangedTop);
		RelatedTopicReuseHoldoutRankingSnapshot keyChanged = snapshotWithInitialRun(
				baseRun, keyChangedRun, new StructuralCounters(0, 0));
		RelatedTopicReuseHoldoutRankingSnapshot providerCounterChanged = snapshotWithRun(
				baseRun, new StructuralCounters(1, 0));
		RelatedTopicReuseHoldoutRankingSnapshot snapshotCounterChanged = snapshotWithRun(
				baseRun, new StructuralCounters(0, 1));

		assertThat(Long.bitCount(originalPaper.scoreBits() ^ scoreChangedPaper.scoreBits()))
				.isOne();
		assertThat(keyChangedTop)
				.extracting(RankedPaper::paperKey)
				.endsWith("control-10");
		assertThat(List.of(
				baseline.evidenceSha256(),
				scoreChanged.evidenceSha256(),
				keyChanged.evidenceSha256(),
				providerCounterChanged.evidenceSha256(),
				snapshotCounterChanged.evidenceSha256()))
				.doesNotHaveDuplicates();
	}

	@Test
	void deeplyFreezesEveryOrderedList() {
		List<RankedPaper> feedbackCandidates = new ArrayList<>(
				List.of(paper("paper-c", 0.7d)));
		FeedbackPool feedback = new FeedbackPool("paper-a", feedbackCandidates);
		List<RankedPaper> control = new ArrayList<>(List.of(
				paper("paper-a", 1.0d), paper("paper-b", 0.9d)));
		List<RankedPaper> controlTop = new ArrayList<>(control);
		List<String> seeds = new ArrayList<>(List.of("paper-a"));
		List<FeedbackPool> feedbackPools = new ArrayList<>(List.of(feedback));
		List<RankedPaper> candidate = new ArrayList<>(List.of(
				paper("paper-a", 1.2d), paper("paper-c", 1.1d), paper("paper-b", 0.8d)));
		RankingRun run = new RankingRun(
				control, controlTop, seeds, feedbackPools, candidate);
		List<FeedbackPool> hiddenFeedback = new ArrayList<>(List.of(feedback));
		List<RankedPaper> hiddenCandidate = new ArrayList<>(candidate);
		HiddenPerturbation hidden = new HiddenPerturbation(
				"hidden-other", "hidden-catalog", hiddenFeedback, hiddenCandidate);
		List<QueryRanking> queries = new ArrayList<>(List.of(
				new QueryRanking("query-one", run, run, hidden)));
		List<String> queryOrder = new ArrayList<>(List.of("query-one"));

		RelatedTopicReuseHoldoutRankingSnapshot snapshot =
				snapshot(
						10, queryOrder, queries, new StructuralCounters(0, 0));
		feedbackCandidates.clear();
		control.clear();
		controlTop.clear();
		seeds.clear();
		feedbackPools.clear();
		candidate.clear();
		hiddenFeedback.clear();
		hiddenCandidate.clear();
		queries.clear();
		queryOrder.clear();

		QueryRanking sealed = snapshot.queries().getFirst();
		assertThat(snapshot.queryOrder()).containsExactly("query-one");
		assertThat(sealed.initialRun().controlPool())
				.extracting(RankedPaper::paperKey)
				.containsExactly("paper-a", "paper-b");
		assertThat(sealed.initialRun().feedbackPools().getFirst().candidates())
				.extracting(RankedPaper::paperKey)
				.containsExactly("paper-c");
		assertThat(sealed.hiddenPerturbation().visibleCandidateTop10()).hasSize(3);
		assertThatThrownBy(() -> snapshot.queryOrder().add("query-two"))
				.isInstanceOf(UnsupportedOperationException.class);
		assertThatThrownBy(() -> snapshot.queries().clear())
				.isInstanceOf(UnsupportedOperationException.class);
		assertThatThrownBy(() -> sealed.initialRun().controlPool().clear())
				.isInstanceOf(UnsupportedOperationException.class);
		assertThatThrownBy(() -> sealed.initialRun().eligibleSeedKeys().clear())
				.isInstanceOf(UnsupportedOperationException.class);
		assertThatThrownBy(() -> sealed.initialRun().feedbackPools().clear())
				.isInstanceOf(UnsupportedOperationException.class);
		assertThatThrownBy(() -> sealed.initialRun().feedbackPools().getFirst()
				.candidates().clear())
				.isInstanceOf(UnsupportedOperationException.class);
		assertThatThrownBy(() -> sealed.hiddenPerturbation().visibleFeedbackPools().clear())
				.isInstanceOf(UnsupportedOperationException.class);
	}

	@Test
	void rejectsNonfiniteScoresEvenWhenSuppliedAsRawBits() {
		assertThatThrownBy(() -> new RankedPaper("paper-a", Double.NaN))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("finite");
		assertThatThrownBy(() -> new RankedPaper("paper-a", Double.POSITIVE_INFINITY))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("finite");
		assertThatThrownBy(() -> new RankedPaper(
				"paper-a", Double.doubleToRawLongBits(Double.NEGATIVE_INFINITY)))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("finite");
	}

	@Test
	void rejectsDuplicateKeysAndEveryFrozenBound() {
		RankedPaper paper = paper("paper-a", 1.0d);
		assertThatThrownBy(() -> new RankingRun(
				List.of(paper, paper), List.of(paper), List.of(), List.of(), List.of(paper)))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("duplicate");
		assertThatThrownBy(() -> new RankingRun(
				papers(51, "control"),
				papers(10, "control"),
				List.of(),
				List.of(),
				papers(10, "control")))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("controlPool");
		assertThatThrownBy(() -> new RankingRun(
				papers(11, "control"),
				papers(11, "control"),
				List.of(),
				List.of(),
				papers(10, "control")))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("controlTop10");
		assertThatThrownBy(() -> new RankingRun(
				papers(3, "seed"),
				papers(3, "seed"),
				List.of("seed-0", "seed-1", "seed-2"),
				List.of(),
				papers(3, "seed")))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("eligibleSeedKeys");
		assertThatThrownBy(() -> new FeedbackPool("seed-paper", papers(26, "feedback")))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("feedback candidates");
		assertThatThrownBy(() -> snapshot(
				10,
				papers(21, "query").stream().map(RankedPaper::paperKey).toList(),
				List.of(),
				new StructuralCounters(0, 0)))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("queryOrder");
	}

	@Test
	void rejectsMalformedRunPartitionsAndPartialTopTens() {
		List<RankedPaper> control = List.of(
				paper("paper-a", 1.0d), paper("paper-b", 0.9d));
		assertThatThrownBy(() -> new RankingRun(
				control,
				List.of(control.get(1)),
				List.of(),
				List.of(),
				control))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("prefix");
		assertThatThrownBy(() -> new RankingRun(
				control,
				control,
				List.of("paper-missing"),
				List.of(new FeedbackPool("paper-missing", List.of())),
				control))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("ordered subsequence");
		assertThatThrownBy(() -> new RankingRun(
				control,
				control,
				List.of("paper-a"),
				List.of(),
				control))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("one ordered pool");
		assertThatThrownBy(() -> new FeedbackPool(
				"paper-a", List.of(paper("paper-a", 0.5d))))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("own seed");
		assertThatThrownBy(() -> new RankingRun(
				control,
				control,
				List.of(),
				List.of(),
				List.of(control.getFirst())))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("complete ranking");
		assertThatThrownBy(() -> new RankingRun(
				control,
				control,
				List.of(),
				List.of(),
				List.of(control.getFirst(), paper("paper-outside", 0.1d))))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("outside its ranking inputs");
	}

	@Test
	void rejectsMalformedHiddenPerturbationPartitionsAndVisibleRankings() {
		RankingRun initial = seededRun(1.0d);
		assertThatThrownBy(() -> new HiddenPerturbation(
				"hidden-same", "hidden-same", List.of(), List.of()))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("distinct");

		HiddenPerturbation missingFeedback = new HiddenPerturbation(
				"hidden-other", "hidden-catalog", List.of(), List.of());
		assertThatThrownBy(() -> new QueryRanking(
				"query-one", initial, initial, missingFeedback))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("one ordered pool");

		HiddenPerturbation outsideVisibleTop = new HiddenPerturbation(
				"hidden-other",
				"hidden-catalog",
				initial.feedbackPools(),
				List.of(
						paper("paper-a", 1.2d),
						paper("paper-c", 1.1d),
						paper("paper-outside", 0.8d)));
		assertThatThrownBy(() -> new QueryRanking(
				"query-one", initial, initial, outsideVisibleTop))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("outside its ranking inputs");

		HiddenPerturbation partialVisibleTop = new HiddenPerturbation(
				"hidden-other",
				"hidden-catalog",
				initial.feedbackPools(),
				List.of(paper("paper-a", 1.2d), paper("paper-c", 1.1d)));
		assertThatThrownBy(() -> new QueryRanking(
				"query-one", initial, initial, partialVisibleTop))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("complete ranking");
	}

	@Test
	void rejectsCutoffQueryPartitionCounterAndPartialSealDrift() {
		QueryRanking query = query("query-one", seededRun(1.0d), seededRun(1.0d));
		assertThatThrownBy(() -> snapshot(
				9, List.of("query-one"), List.of(query), new StructuralCounters(0, 0)))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("cutoff");
		assertThatThrownBy(() -> snapshot(
				10, List.of("query-one", "query-two"), List.of(query),
				new StructuralCounters(0, 0)))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("partition");
		assertThatThrownBy(() -> snapshot(
				10, List.of("query-one", "query-one"), List.of(query, query),
				new StructuralCounters(0, 0)))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("duplicate");
		assertThatThrownBy(() -> new QueryRanking("query-one", null, seededRun(1.0d),
				hiddenFor(seededRun(1.0d))))
				.isInstanceOf(NullPointerException.class)
				.hasMessageContaining("initialRun");
		assertThatThrownBy(() -> snapshot(
				10, List.of("query-one"), List.of(query), null))
				.isInstanceOf(NullPointerException.class)
				.hasMessageContaining("counters");
		assertThatThrownBy(() -> new StructuralCounters(-1, 0))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("negative");
		assertThatThrownBy(() -> new StructuralCounters(0, -1))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("negative");
	}

	@Test
	void rejectsMissingOrMalformedSnapshotIdentityBindings() {
		QueryRanking query = query("query-one", seededRun(1.0d), seededRun(1.0d));
		List<String> queryOrder = List.of("query-one");
		List<QueryRanking> queries = List.of(query);
		StructuralCounters counters = new StructuralCounters(0, 0);

		assertThatThrownBy(() -> RelatedTopicReuseHoldoutRankingSnapshot.seal(
				null,
				CORPUS_ID,
				POLICY_SHA256,
				CORPUS_SHA256,
				MANIFEST_SHA256,
				JUDGMENTS_SHA256,
				JUDGMENTS_BYTES,
				CANDIDATE_REVISION,
				10,
				queryOrder,
				queries,
				counters))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("bundleId");
		assertThatThrownBy(() -> RelatedTopicReuseHoldoutRankingSnapshot.seal(
				BUNDLE_ID,
				"Unsafe_Corpus",
				POLICY_SHA256,
				CORPUS_SHA256,
				MANIFEST_SHA256,
				JUDGMENTS_SHA256,
				JUDGMENTS_BYTES,
				CANDIDATE_REVISION,
				10,
				queryOrder,
				queries,
				counters))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("corpusId");
		assertThatThrownBy(() -> RelatedTopicReuseHoldoutRankingSnapshot.seal(
				BUNDLE_ID,
				CORPUS_ID,
				"A".repeat(64),
				CORPUS_SHA256,
				MANIFEST_SHA256,
				JUDGMENTS_SHA256,
				JUDGMENTS_BYTES,
				CANDIDATE_REVISION,
				10,
				queryOrder,
				queries,
				counters))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("policySha256");
		assertThatThrownBy(() -> RelatedTopicReuseHoldoutRankingSnapshot.seal(
				BUNDLE_ID,
				CORPUS_ID,
				POLICY_SHA256,
				"b".repeat(63),
				MANIFEST_SHA256,
				JUDGMENTS_SHA256,
				JUDGMENTS_BYTES,
				CANDIDATE_REVISION,
				10,
				queryOrder,
				queries,
				counters))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("corpusSha256");
		assertThatThrownBy(() -> RelatedTopicReuseHoldoutRankingSnapshot.seal(
				BUNDLE_ID,
				CORPUS_ID,
				POLICY_SHA256,
				CORPUS_SHA256,
				MANIFEST_SHA256,
				JUDGMENTS_SHA256,
				JUDGMENTS_BYTES,
				"g".repeat(40),
				10,
				queryOrder,
				queries,
				counters))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("candidateRevision");
		assertThatThrownBy(() -> RelatedTopicReuseHoldoutRankingSnapshot.seal(
				BUNDLE_ID,
				CORPUS_ID,
				POLICY_SHA256,
				CORPUS_SHA256,
				"d".repeat(63),
				JUDGMENTS_SHA256,
				JUDGMENTS_BYTES,
				CANDIDATE_REVISION,
				10,
				queryOrder,
				queries,
				counters))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("manifestSha256");
		assertThatThrownBy(() -> RelatedTopicReuseHoldoutRankingSnapshot.seal(
				BUNDLE_ID,
				CORPUS_ID,
				POLICY_SHA256,
				CORPUS_SHA256,
				MANIFEST_SHA256,
				"e".repeat(63),
				JUDGMENTS_BYTES,
				CANDIDATE_REVISION,
				10,
				queryOrder,
				queries,
				counters))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("judgmentsSha256");
		assertThatThrownBy(() -> RelatedTopicReuseHoldoutRankingSnapshot.seal(
				BUNDLE_ID,
				CORPUS_ID,
				POLICY_SHA256,
				CORPUS_SHA256,
				MANIFEST_SHA256,
				JUDGMENTS_SHA256,
				0,
				CANDIDATE_REVISION,
				10,
				queryOrder,
				queries,
				counters))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("judgmentsBytes");
	}

	@Test
	void permitsACompleteEmptyNoSeedSealAndRetainsFailedStabilityObservations() {
		RankingRun empty = new RankingRun(
				List.of(), List.of(), List.of(), List.of(), List.of());
		HiddenPerturbation hidden = new HiddenPerturbation(
				"hidden-other", "hidden-catalog", List.of(), List.of());
		QueryRanking emptyQuery = new QueryRanking(
				"query-empty", empty, empty, hidden);
		RelatedTopicReuseHoldoutRankingSnapshot emptySnapshot =
				snapshot(
						10,
						List.of("query-empty"),
						List.of(emptyQuery),
						new StructuralCounters(2, 3));
		assertThat(emptySnapshot.queries().getFirst().initialRun().candidateTop10()).isEmpty();
		assertThat(emptySnapshot.counters()).isEqualTo(new StructuralCounters(2, 3));

		RankingRun initial = seededRun(1.0d);
		RankingRun repeated = seededRun(2.0d);
		HiddenPerturbation changedHidden = new HiddenPerturbation(
				"hidden-other",
				"hidden-catalog",
				initial.feedbackPools(),
				List.of(
						paper("paper-c", 1.1d),
						paper("paper-a", 1.0d),
						paper("paper-b", 0.9d)));
		RelatedTopicReuseHoldoutRankingSnapshot failedObservation =
				snapshot(
						10,
						List.of("query-one"),
						List.of(new QueryRanking(
								"query-one", initial, repeated, changedHidden)),
						new StructuralCounters(1, 1));
		assertThat(failedObservation.queries().getFirst().repeatedRun())
				.isNotEqualTo(initial);
		assertThat(failedObservation.queries().getFirst().hiddenPerturbation()
				.visibleCandidateTop10()).isNotEqualTo(initial.candidateTop10());
	}

	private static QueryRanking query(
			String key, RankingRun initial, RankingRun repeated) {
		return new QueryRanking(key, initial, repeated, hiddenFor(initial));
	}

	private static RelatedTopicReuseHoldoutRankingSnapshot snapshot(
			int cutoff,
			List<String> queryOrder,
			List<QueryRanking> queries,
			StructuralCounters counters) {
		return RelatedTopicReuseHoldoutRankingSnapshot.seal(
				BUNDLE_ID,
				CORPUS_ID,
				POLICY_SHA256,
				CORPUS_SHA256,
				MANIFEST_SHA256,
				JUDGMENTS_SHA256,
				JUDGMENTS_BYTES,
				CANDIDATE_REVISION,
				cutoff,
				queryOrder,
				queries,
				counters);
	}

	private static RelatedTopicReuseHoldoutRankingSnapshot snapshotWithRun(
			RankingRun run, StructuralCounters counters) {
		return snapshotWithInitialRun(run, run, counters);
	}

	private static RelatedTopicReuseHoldoutRankingSnapshot snapshotWithInitialRun(
			RankingRun baseRun, RankingRun initialRun, StructuralCounters counters) {
		return snapshot(
				10,
				List.of("query-one"),
				List.of(new QueryRanking(
						"query-one", initialRun, baseRun, hiddenFor(baseRun))),
				counters);
	}

	private static HiddenPerturbation hiddenFor(RankingRun run) {
		return new HiddenPerturbation(
				"hidden-other",
				"hidden-catalog",
				run.feedbackPools(),
				run.candidateTop10());
	}

	private static RankingRun seededRun(double firstControlScore) {
		RankedPaper first = paper("paper-a", firstControlScore);
		RankedPaper second = paper("paper-b", 0.9d);
		FeedbackPool feedback = new FeedbackPool(
				"paper-a", List.of(paper("paper-c", 0.7d)));
		return new RankingRun(
				List.of(first, second),
				List.of(first, second),
				List.of("paper-a"),
				List.of(feedback),
				List.of(
						paper("paper-a", 1.2d),
						paper("paper-c", 1.1d),
						paper("paper-b", 0.8d)));
	}

	private static RankingRun denseRun() {
		List<RankedPaper> control = papers(11, "control");
		List<RankedPaper> topTen = control.subList(0, 10);
		return new RankingRun(
				control,
				topTen,
				List.of(),
				List.of(),
				topTen);
	}

	private static List<RankedPaper> papers(int count, String prefix) {
		List<RankedPaper> papers = new ArrayList<>(count);
		for (int index = 0; index < count; index++) {
			papers.add(paper(prefix + "-" + index, count - index));
		}
		return List.copyOf(papers);
	}

	private static RankedPaper paper(String key, double score) {
		return new RankedPaper(key, score);
	}
}
