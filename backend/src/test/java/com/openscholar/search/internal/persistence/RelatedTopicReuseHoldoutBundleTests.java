package com.openscholar.search.internal.persistence;

import static com.openscholar.search.internal.persistence.RelatedTopicReuseHoldoutEvidenceTestFixture.createReport;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.core.json.JsonReadFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

class RelatedTopicReuseHoldoutBundleTests {

	private static final String CORPUS_FILENAME = "holdout-corpus.json";
	private static final String JUDGMENTS_FILENAME = "judgments.json";
	private static final String MANIFEST_FILENAME = "manifest.json";
	private static final String TARGET_SEARCH = "target-owner-search";
	private static final String TARGET_COLLECTION = "target-owner-collection";
	private static final String OTHER_SEARCH = "other-owner-search";
	private static final String OTHER_COLLECTION = "other-owner-collection";
	private static final String CATALOG = "catalog-only";

	private final ObjectMapper objectMapper = new ObjectMapper();

	@TempDir
	private Path temporaryDirectory;

	private Path repositoryRoot;
	private RelatedTopicReuseHoldoutPolicy.BoundPolicy boundPolicy;
	private RelatedTopicReuseEvaluationFixture.BoundFixture developmentFixture;
	private int bundleSequence;

	@BeforeEach
	void loadFrozenInputs() throws Exception {
		temporaryDirectory = temporaryDirectory.toRealPath();
		repositoryRoot = findRepositoryRoot();
		boundPolicy = RelatedTopicReuseHoldoutPolicy.loadFrozen(objectMapper);
		developmentFixture = RelatedTopicReuseEvaluationFixture.loadFrozen(objectMapper);
	}

	@Test
	void validExternalBundleLoadsExactContentsAndExposesOnlyImmutableValues() throws Exception {
		BundleFiles files = validBundle();

		RelatedTopicReuseHoldoutBundle bundle = verify(files);

		assertThat(bundle.bundleId()).isEqualTo(files.bundleId());
		assertThat(bundle.corpusId()).isEqualTo(files.corpusId());
		assertThat(bundle.manifestSha256())
				.isEqualTo(sha256(Files.readAllBytes(files.manifestFile())));
		assertThat(bundle.corpus().corpusId()).isEqualTo(files.corpusId());
		assertThat(bundle.corpus().lineages()).hasSize(5);
		assertThat(bundle.corpus().candidates()).hasSize(40);
		assertThat(bundle.corpus().queries()).hasSize(8);
		assertThat(bundle.judgments().queries()).hasSize(8)
				.allSatisfy(query -> assertThat(query.grades()).hasSize(30));
		assertThat(bundle.corpus().candidates().stream()
				.filter(candidate -> candidate.lineageKey().startsWith("target-owner")))
				.hasSize(30);

		assertThatThrownBy(() -> bundle.corpus().candidates().clear())
				.isInstanceOf(UnsupportedOperationException.class);
		assertThatThrownBy(() -> bundle.corpus().candidates().get(0).authors().add("Mutation"))
				.isInstanceOf(UnsupportedOperationException.class);
		assertThatThrownBy(() -> bundle.corpus().queries().get(3).filters().languages().add("fr"))
				.isInstanceOf(UnsupportedOperationException.class);
		assertThatThrownBy(() -> bundle.judgments().queries().get(0).grades()
				.put(candidateKey(1), 0))
				.isInstanceOf(UnsupportedOperationException.class);
		assertThatThrownBy(() -> bundle.judgments().queries().get(0).adversaries().clear())
				.isInstanceOf(UnsupportedOperationException.class);
	}

	@Test
	void sealedPostRankingInputsProduceACompletePassingDeterministicScore() throws Exception {
		BundleFiles files = validBundle();
		RelatedTopicReuseHoldoutBundle.VerifiedCorpus verified =
				RelatedTopicReuseHoldoutBundle.verifyCorpus(objectMapper, files.directory());
		var completion = completeRanking(
				verified, ignored -> passingRankingObservation(verified));
		var scoringInputs = RelatedTopicReuseHoldoutBundle.verifyAfterRanking(
				objectMapper, files.directory(), completion);

		var first = RelatedTopicReuseHoldoutScorer.score(scoringInputs).result();
		var second = RelatedTopicReuseHoldoutScorer.score(scoringInputs).result();

		assertThat(second).isEqualTo(first);
		assertThat(first.policyGatesPassed()).isTrue();
		assertThat(first.gates()).hasSize(22).allMatch(
				RelatedTopicReuseHoldoutScoringResult.GateOutcome::passed);
		assertThat(first.queries()).hasSize(8);
		assertThat(first.control().recallQueryCount()).isEqualTo(7);
		assertThat(first.control().precisionAt1QueryCount()).isEqualTo(8);
		assertThat(first.control().macroRecallAt10()).isEqualTo(5.0d / 7.0d);
		assertThat(first.candidate().macroRecallAt10()).isEqualTo(1.0d);
		assertThat(first.aggregate().macroRecallAt10Delta()).isEqualTo(2.0d / 7.0d);
		assertThat(first.aggregate().strictOpportunityRecallImprovementCount()).isEqualTo(4);
		assertThat(first.aggregate().novelRelevantAt10()).isEqualTo(4);
		assertThat(first.aggregate().ownerScopeLeakCount()).isZero();
		assertThat(first.aggregate().filterViolationCount()).isZero();
		assertThat(first.identity().rankingSnapshotSha256())
				.isEqualTo(completion.rankingSnapshot().evidenceSha256());
		assertThat(first.readerFacing()).isFalse();
		assertThat(first.externalBundleAcceptanceAuthorized()).isFalse();
		assertThat(first.custodyReleaseAuthorized()).isFalse();
		assertThat(first.productActivationAuthorized()).isFalse();
		var evidenceReport = createReport(
				syntheticEvaluatorSeal(completion.rankingSnapshot().candidateRevision()),
				completion.rankingSnapshot(),
				first);
		assertThat(evidenceReport.reportId())
				.startsWith("related-topic-reuse-holdout-report-v2-");
		assertThat(new String(
				evidenceReport.evidenceReportJson(), StandardCharsets.UTF_8))
				.contains("\"evaluationProtocolId\":\"related-topic-reuse-holdout-evaluation-v1\"")
				.contains("\"externalBundleAcceptanceAuthorized\":false")
				.contains("\"custodyReleaseAuthorized\":false");
		assertThatThrownBy(() -> first.queries().clear())
				.isInstanceOf(UnsupportedOperationException.class);
		assertThatThrownBy(() -> first.gates().clear())
				.isInstanceOf(UnsupportedOperationException.class);
	}

	@Test
	void qualityFailureReturnsEveryMetricAndGateWithoutGrantingAuthorization()
			throws Exception {
		BundleFiles files = validBundle();
		RelatedTopicReuseHoldoutBundle.VerifiedCorpus verified =
				RelatedTopicReuseHoldoutBundle.verifyCorpus(objectMapper, files.directory());
		var scoringInputs = RelatedTopicReuseHoldoutBundle.verifyAfterRanking(
				objectMapper,
				files.directory(),
				completeRanking(
						verified, ignored -> emptyRankingObservation(verified)));

		var result = RelatedTopicReuseHoldoutScorer.score(scoringInputs).result();

		assertThat(result.policyGatesPassed()).isFalse();
		assertThat(result.queries()).hasSize(8);
		assertThat(result.gates()).hasSize(22);
		assertThat(result.gates())
				.filteredOn(outcome -> outcome.gate()
						== RelatedTopicReuseHoldoutScoringResult.GateId.MINIMUM_MACRO_NDCG_DELTA)
				.singleElement()
				.satisfies(outcome -> assertThat(outcome.passed()).isFalse());
		assertThat(result.aggregate().macroNdcgAt10Delta()).isZero();
		assertThat(result.structural().filteredOpportunityFailureCount()).isOne();
		assertThat(result.structural().authorRelevantBaselineFailureCount()).isEqualTo(3);
		assertThat(result.readerFacing()).isFalse();
		assertThat(result.productActivationAuthorized()).isFalse();
	}

	@Test
	void scorerApiAcceptsOnlyTheOpaquePostRankingCapability() {
		assertThat(Arrays.stream(RelatedTopicReuseHoldoutScorer.class.getDeclaredMethods())
				.filter(method -> !Modifier.isPrivate(method.getModifiers())))
				.singleElement()
				.satisfies(method -> {
					assertThat(method.getName()).isEqualTo("score");
					assertThat(method.getParameterTypes()).containsExactly(
							RelatedTopicReuseHoldoutBundle.VerifiedScoringInputs.class);
					assertThat(method.getReturnType()).isEqualTo(
							RelatedTopicReuseHoldoutScorer.VerifiedScoringOutcome.class);
				});
		assertThat(RelatedTopicReuseHoldoutScorer.class.getDeclaredFields()).isEmpty();
		assertThat(Arrays.stream(
				RelatedTopicReuseHoldoutBundle.VerifiedScoringInputs.class.getDeclaredFields()))
				.noneMatch(field -> Path.class.isAssignableFrom(field.getType()));
		assertThatThrownBy(() -> RelatedTopicReuseHoldoutScorer.score(null))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("verified holdout scoring input");
	}

	@Test
	void scorerComparisonBoundariesUseTheFrozenInclusiveEpsilonRule()
			throws Exception {
		double epsilon = 0.000000000001d;
		var gain = RelatedTopicReuseHoldoutScorer.class.getDeclaredMethod(
				"gain", Double.class, double.class);
		var regression = RelatedTopicReuseHoldoutScorer.class.getDeclaredMethod(
				"regression", Double.class, double.class);
		var minimum = RelatedTopicReuseHoldoutScorer.class.getDeclaredMethod(
				"minimum", double.class, double.class, double.class);
		var maximum = RelatedTopicReuseHoldoutScorer.class.getDeclaredMethod(
				"maximum", double.class, double.class, double.class);
		gain.setAccessible(true);
		regression.setAccessible(true);
		minimum.setAccessible(true);
		maximum.setAccessible(true);

		assertThat((boolean) gain.invoke(null, epsilon, epsilon)).isFalse();
		assertThat((boolean) gain.invoke(null, Math.nextUp(epsilon), epsilon)).isTrue();
		assertThat((boolean) regression.invoke(null, -epsilon, epsilon)).isFalse();
		assertThat((boolean) regression.invoke(null, Math.nextDown(-epsilon), epsilon)).isTrue();
		assertThat((boolean) minimum.invoke(null, -epsilon, 0.0d, epsilon)).isTrue();
		assertThat((boolean) minimum.invoke(
				null, Math.nextDown(-epsilon), 0.0d, epsilon)).isFalse();
		assertThat((boolean) maximum.invoke(null, epsilon, 0.0d, epsilon)).isTrue();
		assertThat((boolean) maximum.invoke(
				null, Math.nextUp(epsilon), 0.0d, epsilon)).isFalse();
	}

	@Test
	void scorerUsesRawBitsForStabilityAndExactRecordsForHiddenNoninterference()
			throws Exception {
		BundleFiles files = validBundle();
		RelatedTopicReuseHoldoutBundle.VerifiedCorpus verified =
				RelatedTopicReuseHoldoutBundle.verifyCorpus(objectMapper, files.directory());
		var passing = passingRankingObservation(verified);
		var original = passing.queries().getFirst();
		var positiveZero = withFirstControlScore(original.initialRun(), +0.0d);
		var negativeZero = withFirstControlScore(original.initialRun(), -0.0d);
		var unstable = replaceQuery(
				passing,
				0,
				new RelatedTopicReuseHoldoutRankingSnapshot.QueryRanking(
						original.queryKey(), positiveZero, negativeZero, hiddenFor(positiveZero)));

		var unstableScore = score(files, unstable);

		assertThat(unstableScore.structural().repeatedInstabilityCount()).isOne();
		assertGate(unstableScore,
				RelatedTopicReuseHoldoutScoringResult.GateId.REPEATED_ORDER_AND_SCORES,
				false);

		List<RelatedTopicReuseHoldoutRankingSnapshot.RankedPaper> hiddenTop =
				new ArrayList<>(original.initialRun().candidateTop10());
		hiddenTop.set(0, new RelatedTopicReuseHoldoutRankingSnapshot.RankedPaper(
				hiddenTop.getFirst().paperKey(), Math.nextUp(hiddenTop.getFirst().score())));
		var hiddenChanged = replaceQuery(
				passing,
				0,
				new RelatedTopicReuseHoldoutRankingSnapshot.QueryRanking(
						original.queryKey(),
						original.initialRun(),
						original.repeatedRun(),
						new RelatedTopicReuseHoldoutRankingSnapshot.HiddenPerturbation(
								original.hiddenPerturbation().otherOwnerCandidateKey(),
								original.hiddenPerturbation().catalogOnlyCandidateKey(),
								original.initialRun().feedbackPools(),
								hiddenTop)));

		var hiddenScore = score(files, hiddenChanged);

		assertThat(hiddenScore.structural().hiddenInterferenceCount()).isOne();
		assertGate(hiddenScore,
				RelatedTopicReuseHoldoutScoringResult.GateId.HIDDEN_CANDIDATE_NONINTERFERENCE,
				false);
		var stableScore = RelatedTopicReuseHoldoutScorer.score(
				verifiedScoringInputs(files, passing)).result();
		assertThat(stableScore.queries().get(4).exactFallback()).isTrue();
		assertThat(unstableScore.identity().rankingSnapshotSha256())
				.isNotEqualTo(stableScore.identity().rankingSnapshotSha256());
		assertThat(hiddenScore.identity().rankingSnapshotSha256())
				.isNotEqualTo(stableScore.identity().rankingSnapshotSha256());
		assertThat(passing.queries().get(4).initialRun().controlTop10().getFirst().scoreBits())
				.isNotEqualTo(
						passing.queries().get(4).initialRun().candidateTop10()
								.getFirst().scoreBits());
	}

	@Test
	void scopeCountsUseUniqueQueryCandidatePairsAcrossEveryInspectedLocation()
			throws Exception {
		BundleFiles files = validBundle();
		RelatedTopicReuseHoldoutBundle.VerifiedCorpus verified =
				RelatedTopicReuseHoldoutBundle.verifyCorpus(objectMapper, files.directory());
		var passing = passingRankingObservation(verified);
		var corpus = verified.rankingCorpus();
		String relevantOne = corpus.corpus().candidates().get(0).key();
		String relevantTwo = corpus.corpus().candidates().get(1).key();
		String otherOwner = corpus.corpus().candidates().get(30).key();
		var ownerLeakRun = threePaperRun(relevantOne, relevantTwo, otherOwner);
		var ownerLeak = replaceQuery(
				passing,
				0,
				new RelatedTopicReuseHoldoutRankingSnapshot.QueryRanking(
						passing.queryOrder().get(0),
						ownerLeakRun,
						ownerLeakRun,
						hiddenFor(ownerLeakRun)));

		var ownerScore = score(files, ownerLeak);

		assertThat(ownerScore.queries().getFirst().ownerScopeViolationCount()).isOne();
		assertThat(ownerScore.aggregate().ownerScopeLeakCount()).isOne();
		assertGate(ownerScore,
				RelatedTopicReuseHoldoutScoringResult.GateId.MAXIMUM_OWNER_SCOPE_LEAK_COUNT,
				false);

		String filterNegative = corpus.corpus().candidates().get(9).key();
		var filterRun = threePaperRun(relevantOne, relevantTwo, filterNegative);
		var filterLeak = replaceQuery(
				passing,
				3,
				new RelatedTopicReuseHoldoutRankingSnapshot.QueryRanking(
						passing.queryOrder().get(3),
						filterRun,
						filterRun,
						hiddenFor(filterRun)));

		var filterScore = score(files, filterLeak);

		assertThat(filterScore.queries().get(3).filterViolationCount()).isOne();
		assertThat(filterScore.aggregate().filterViolationCount()).isOne();
		assertGate(filterScore,
				RelatedTopicReuseHoldoutScoringResult.GateId.MAXIMUM_FILTER_VIOLATION_COUNT,
				false);
	}

	@Test
	void anAuthorBaselineHitMayAppearAnywhereInTheFrozenControlTopTen()
			throws Exception {
		BundleFiles files = validBundle();
		RelatedTopicReuseHoldoutBundle.VerifiedCorpus verified =
				RelatedTopicReuseHoldoutBundle.verifyCorpus(objectMapper, files.directory());
		var passing = passingRankingObservation(verified);
		String relevant = verified.rankingCorpus().corpus().candidates().get(0).key();
		String irrelevant = verified.rankingCorpus().corpus().candidates().get(2).key();
		var rankTwoRelevant = new RelatedTopicReuseHoldoutRankingSnapshot.RankingRun(
				List.of(
						new RelatedTopicReuseHoldoutRankingSnapshot.RankedPaper(irrelevant, 2.0d),
						new RelatedTopicReuseHoldoutRankingSnapshot.RankedPaper(relevant, 1.0d)),
				List.of(
						new RelatedTopicReuseHoldoutRankingSnapshot.RankedPaper(irrelevant, 2.0d),
						new RelatedTopicReuseHoldoutRankingSnapshot.RankedPaper(relevant, 1.0d)),
				List.of(),
				List.of(),
				List.of(
						new RelatedTopicReuseHoldoutRankingSnapshot.RankedPaper(irrelevant, 2.0d),
						new RelatedTopicReuseHoldoutRankingSnapshot.RankedPaper(relevant, 1.0d)));
		var observation = replaceQuery(
				passing,
				4,
				new RelatedTopicReuseHoldoutRankingSnapshot.QueryRanking(
						passing.queryOrder().get(4),
						rankTwoRelevant,
						rankTwoRelevant,
						hiddenFor(rankTwoRelevant)));

		var result = score(files, observation);

		assertThat(result.queries().get(4).authorRelevantBaselineHit()).isTrue();
		assertThat(result.queries().get(4).rankOneIrrelevant()).isTrue();
		assertThat(result.structural().authorRelevantBaselineFailureCount()).isZero();
		assertGate(result,
				RelatedTopicReuseHoldoutScoringResult.GateId.AUTHOR_CONTROL_RELEVANT_BASELINE_HIT,
				true);
	}

	@Test
	void scorerUsesTheFrozenGradedMetricsCutoffAndNoRelevantRules()
			throws Exception {
		BundleFiles files = validBundle();
		RelatedTopicReuseHoldoutBundle.VerifiedCorpus verified =
				RelatedTopicReuseHoldoutBundle.verifyCorpus(objectMapper, files.directory());
		var observation = passingRankingObservation(verified);
		var candidates = verified.rankingCorpus().corpus().candidates();

		var gradeTwo = new RelatedTopicReuseHoldoutRankingSnapshot.RankedPaper(
				candidates.get(1).key(), 3.0d);
		var unjudgedOtherOwner = new RelatedTopicReuseHoldoutRankingSnapshot.RankedPaper(
				candidates.get(30).key(), 2.0d);
		var gradeThree = new RelatedTopicReuseHoldoutRankingSnapshot.RankedPaper(
				candidates.get(0).key(), 1.0d);
		var gradedOrder = List.of(gradeTwo, unjudgedOtherOwner, gradeThree);
		var gradedRun = new RelatedTopicReuseHoldoutRankingSnapshot.RankingRun(
				gradedOrder, gradedOrder, List.of(), List.of(), gradedOrder);
		observation = replaceQuery(
				observation,
				0,
				new RelatedTopicReuseHoldoutRankingSnapshot.QueryRanking(
						observation.queryOrder().get(0),
						gradedRun,
						gradedRun,
						hiddenFor(gradedRun)));

		List<RelatedTopicReuseHoldoutRankingSnapshot.RankedPaper> cutoffPool =
				new ArrayList<>();
		cutoffPool.add(new RelatedTopicReuseHoldoutRankingSnapshot.RankedPaper(
				candidates.get(1).key(), 20.0d));
		for (int index = 2; index <= 10; index++) {
			cutoffPool.add(new RelatedTopicReuseHoldoutRankingSnapshot.RankedPaper(
					candidates.get(index).key(), 20.0d - index));
		}
		cutoffPool.add(new RelatedTopicReuseHoldoutRankingSnapshot.RankedPaper(
				candidates.get(0).key(), 1.0d));
		var cutoffTop = cutoffPool.stream().limit(10).toList();
		var cutoffRun = new RelatedTopicReuseHoldoutRankingSnapshot.RankingRun(
				cutoffPool, cutoffTop, List.of(), List.of(), cutoffTop);
		observation = replaceQuery(
				observation,
				1,
				new RelatedTopicReuseHoldoutRankingSnapshot.QueryRanking(
						observation.queryOrder().get(1),
						cutoffRun,
						cutoffRun,
						hiddenFor(cutoffRun)));

		var irrelevant = new RelatedTopicReuseHoldoutRankingSnapshot.RankedPaper(
				candidates.get(0).key(), 1.0d);
		var noRelevantRun = new RelatedTopicReuseHoldoutRankingSnapshot.RankingRun(
				List.of(irrelevant),
				List.of(irrelevant),
				List.of(),
				List.of(),
				List.of(irrelevant));
		observation = replaceQuery(
				observation,
				7,
				new RelatedTopicReuseHoldoutRankingSnapshot.QueryRanking(
						observation.queryOrder().get(7),
						noRelevantRun,
						noRelevantRun,
						hiddenFor(noRelevantRun)));

		var result = score(files, observation);

		var graded = result.queries().get(0).control();
		double expectedIdealDcg = 7.0d + 3.0d / (StrictMath.log(3.0d) / StrictMath.log(2.0d));
		assertThat(graded.relevantCandidateCount()).isEqualTo(2);
		assertThat(graded.retrievedRelevantCount()).isEqualTo(2);
		assertThat(graded.recallAt10()).isEqualTo(1.0d);
		assertThat(graded.ndcgAt10()).isEqualTo(6.5d / expectedIdealDcg);
		assertThat(graded.precisionAt1()).isEqualTo(1.0d);
		assertThat(graded.reciprocalRankAt10()).isEqualTo(1.0d);
		assertThat(result.queries().get(0).ownerScopeViolationCount()).isOne();

		var cutoff = result.queries().get(1).control();
		assertThat(cutoff.relevantCandidateCount()).isEqualTo(2);
		assertThat(cutoff.retrievedRelevantCount()).isOne();
		assertThat(cutoff.recallAt10()).isEqualTo(0.5d);
		assertThat(cutoff.precisionAt1()).isEqualTo(1.0d);
		assertThat(cutoff.reciprocalRankAt10()).isEqualTo(1.0d);

		var noRelevant = result.queries().get(7).control();
		assertThat(noRelevant.relevantCandidateCount()).isZero();
		assertThat(noRelevant.retrievedRelevantCount()).isZero();
		assertThat(noRelevant.recallAt10()).isNull();
		assertThat(noRelevant.ndcgAt10()).isNull();
		assertThat(noRelevant.precisionAt1()).isZero();
		assertThat(noRelevant.reciprocalRankAt10()).isNull();
		assertThat(result.control().recallQueryCount()).isEqualTo(7);
		assertThat(result.control().precisionAt1QueryCount()).isEqualTo(8);
	}

	@Test
	void scorerReportsCounterAdversaryRankOneAndFallbackFailures()
			throws Exception {
		BundleFiles files = validBundle();
		RelatedTopicReuseHoldoutBundle.VerifiedCorpus verified =
				RelatedTopicReuseHoldoutBundle.verifyCorpus(objectMapper, files.directory());
		var observation = passingRankingObservation(verified);
		var candidates = verified.rankingCorpus().corpus().candidates();
		var relevantOne = new RelatedTopicReuseHoldoutRankingSnapshot.RankedPaper(
				candidates.get(0).key(), 3.0d);
		var relevantTwo = new RelatedTopicReuseHoldoutRankingSnapshot.RankedPaper(
				candidates.get(1).key(), 2.0d);
		var adversary = new RelatedTopicReuseHoldoutRankingSnapshot.RankedPaper(
				candidates.get(2).key(), 1.0d);
		var adversaryRun = new RelatedTopicReuseHoldoutRankingSnapshot.RankingRun(
				List.of(relevantOne, relevantTwo, adversary),
				List.of(relevantOne, relevantTwo, adversary),
				List.of(),
				List.of(),
				List.of(adversary, relevantOne, relevantTwo));
		observation = replaceQuery(
				observation,
				0,
				new RelatedTopicReuseHoldoutRankingSnapshot.QueryRanking(
						observation.queryOrder().get(0),
						adversaryRun,
						adversaryRun,
						hiddenFor(adversaryRun)));

		var fallbackRun = new RelatedTopicReuseHoldoutRankingSnapshot.RankingRun(
				List.of(relevantOne, relevantTwo),
				List.of(relevantOne, relevantTwo),
				List.of(),
				List.of(),
				List.of(relevantTwo, relevantOne));
		observation = replaceQuery(
				observation,
				7,
				new RelatedTopicReuseHoldoutRankingSnapshot.QueryRanking(
						observation.queryOrder().get(7),
						fallbackRun,
						fallbackRun,
						hiddenFor(fallbackRun)));
		observation = new RelatedTopicReuseHoldoutRankingSnapshot.Observation(
				observation.candidateRevision(),
				observation.cutoff(),
				observation.queryOrder(),
				observation.queries(),
				new RelatedTopicReuseHoldoutRankingSnapshot.StructuralCounters(1, 1));

		var result = score(files, observation);

		assertThat(result.queries().get(0).candidateExplicitAdversaryAt10Count()).isOne();
		assertThat(result.queries().get(0).rankOneIrrelevant()).isTrue();
		assertThat(result.queries().get(7).exactFallback()).isFalse();
		assertThat(result.aggregate().providerCallCount()).isOne();
		assertThat(result.aggregate().experimentalSnapshotWriteCount()).isOne();
		assertGate(result,
				RelatedTopicReuseHoldoutScoringResult.GateId.MAXIMUM_RANK_ONE_IRRELEVANT_COUNT,
				false);
		assertGate(result,
				RelatedTopicReuseHoldoutScoringResult.GateId.MAXIMUM_PROVIDER_CALL_COUNT,
				false);
		assertGate(result,
				RelatedTopicReuseHoldoutScoringResult.GateId.MAXIMUM_EXPERIMENTAL_SNAPSHOT_WRITE_COUNT,
				false);
		assertGate(result,
				RelatedTopicReuseHoldoutScoringResult.GateId.EXACT_FALLBACK_WITHOUT_FEEDBACK,
				false);
	}

	@Test
	void scorerSeparatesNdcgRegressionCountFromMaximumMagnitude()
			throws Exception {
		BundleFiles files = validBundle();
		RelatedTopicReuseHoldoutBundle.VerifiedCorpus verified =
				RelatedTopicReuseHoldoutBundle.verifyCorpus(objectMapper, files.directory());
		var passing = passingRankingObservation(verified);
		var candidates = verified.rankingCorpus().corpus().candidates();
		var gradeThree = new RelatedTopicReuseHoldoutRankingSnapshot.RankedPaper(
				candidates.get(0).key(), 2.0d);
		var gradeTwo = new RelatedTopicReuseHoldoutRankingSnapshot.RankedPaper(
				candidates.get(1).key(), 1.0d);
		var regressingRun = new RelatedTopicReuseHoldoutRankingSnapshot.RankingRun(
				List.of(gradeThree, gradeTwo),
				List.of(gradeThree, gradeTwo),
				List.of(),
				List.of(),
				List.of(gradeTwo, gradeThree));
		var oneRegression = replaceQuery(
				passing,
				0,
				new RelatedTopicReuseHoldoutRankingSnapshot.QueryRanking(
						passing.queryOrder().get(0),
						regressingRun,
						regressingRun,
						hiddenFor(regressingRun)));

		var one = score(files, oneRegression);

		assertThat(one.aggregate().perQueryNdcgRegressionCount()).isOne();
		assertThat(one.aggregate().maximumPerQueryNdcgRegression()).isGreaterThan(0.1d);
		assertGate(one,
				RelatedTopicReuseHoldoutScoringResult.GateId.MAXIMUM_PER_QUERY_NDCG_REGRESSION_COUNT,
				true);
		assertGate(one,
				RelatedTopicReuseHoldoutScoringResult.GateId.MAXIMUM_PER_QUERY_NDCG_REGRESSION_MAGNITUDE,
				false);

		var twoRegressions = replaceQuery(
				oneRegression,
				1,
				new RelatedTopicReuseHoldoutRankingSnapshot.QueryRanking(
						oneRegression.queryOrder().get(1),
						regressingRun,
						regressingRun,
						hiddenFor(regressingRun)));
		var two = score(files, twoRegressions);

		assertThat(two.aggregate().perQueryNdcgRegressionCount()).isEqualTo(2);
		assertGate(two,
				RelatedTopicReuseHoldoutScoringResult.GateId.MAXIMUM_PER_QUERY_NDCG_REGRESSION_COUNT,
				false);
	}

	@Test
	void authorAndNoSeedControlsRejectAnySeedOrFeedbackSignal()
			throws Exception {
		BundleFiles files = validBundle();
		RelatedTopicReuseHoldoutBundle.VerifiedCorpus verified =
				RelatedTopicReuseHoldoutBundle.verifyCorpus(objectMapper, files.directory());
		var observation = passingRankingObservation(verified);
		var candidates = verified.rankingCorpus().corpus().candidates();
		String seedKey = candidates.get(0).key();
		var seed = new RelatedTopicReuseHoldoutRankingSnapshot.RankedPaper(seedKey, 1.0d);
		var feedbackPaper = new RelatedTopicReuseHoldoutRankingSnapshot.RankedPaper(
				candidates.get(1).key(), 0.5d);
		var feedback = new RelatedTopicReuseHoldoutRankingSnapshot.FeedbackPool(
				seedKey, List.of(feedbackPaper));
		var signaledRun = new RelatedTopicReuseHoldoutRankingSnapshot.RankingRun(
				List.of(seed),
				List.of(seed),
				List.of(seedKey),
				List.of(feedback),
				List.of(seed, feedbackPaper));
		observation = replaceQuery(
				observation,
				4,
				new RelatedTopicReuseHoldoutRankingSnapshot.QueryRanking(
						observation.queryOrder().get(4),
						signaledRun,
						signaledRun,
						hiddenFor(signaledRun)));
		observation = replaceQuery(
				observation,
				7,
				new RelatedTopicReuseHoldoutRankingSnapshot.QueryRanking(
						observation.queryOrder().get(7),
						signaledRun,
						signaledRun,
						hiddenFor(signaledRun)));

		var result = score(files, observation);

		assertThat(result.queries().get(4).authorRelevantBaselineHit()).isTrue();
		assertThat(result.queries().get(4).authorZeroEligibleSeedsAndFeedback()).isFalse();
		assertThat(result.queries().get(7).noSeedZeroEligibleSeedsAndFeedback()).isFalse();
		assertGate(result,
				RelatedTopicReuseHoldoutScoringResult.GateId.AUTHOR_CONTROL_ZERO_ELIGIBLE_SEEDS_AND_FEEDBACK,
				false);
		assertGate(result,
				RelatedTopicReuseHoldoutScoringResult.GateId.NO_SEED_ZERO_ELIGIBLE_SEEDS_AND_FEEDBACK,
				false);
	}

	@Test
	void corpusStageDoesNotParseJudgmentsAndReturnsAPathFreeImmutableValue()
			throws Exception {
		BundleFiles files = validBundle();
		files.replacePayload(JUDGMENTS_FILENAME, "{".getBytes(StandardCharsets.UTF_8));

		RelatedTopicReuseHoldoutBundle.VerifiedCorpus verified =
				RelatedTopicReuseHoldoutBundle.verifyCorpus(objectMapper, files.directory());
		RelatedTopicReuseHoldoutBundle.RankingCorpus rankingCorpus =
				verified.rankingCorpus();

		assertThat(rankingCorpus.protocolId())
				.isEqualTo(boundPolicy.policy().bundle().protocolId());
		assertThat(rankingCorpus.bundleId()).isEqualTo(files.bundleId());
		assertThat(rankingCorpus.corpusId()).isEqualTo(files.corpusId());
		assertThat(rankingCorpus.policyId()).isEqualTo(boundPolicy.policy().policyId());
		assertThat(rankingCorpus.policySha256()).isEqualTo(boundPolicy.sha256());
		assertThat(rankingCorpus.corpusSha256())
				.isEqualTo(sha256(Files.readAllBytes(files.corpusFile())));
		assertThat(rankingCorpus.corpus().candidates()).hasSize(40);
		assertThat(rankingCorpus.corpus().queries()).hasSize(8);
		assertThat(Arrays.stream(verified.getClass().getDeclaredFields())
				.anyMatch(field -> Path.class.isAssignableFrom(field.getType())))
				.isFalse();
		assertThat(Arrays.stream(verified.getClass().getDeclaredMethods())
				.anyMatch(method -> Path.class.isAssignableFrom(method.getReturnType())))
				.isFalse();
		assertThat(Arrays.stream(rankingCorpus.getClass().getDeclaredFields())
				.map(field -> field.getName().toLowerCase())
				.noneMatch(name -> name.contains("judgment")
						|| name.contains("manifest")
						|| name.contains("payload")))
				.isTrue();
		assertThatThrownBy(() -> rankingCorpus.corpus().candidates().clear())
				.isInstanceOf(UnsupportedOperationException.class);
		assertThatThrownBy(() -> rankingCorpus.corpus().candidates().get(0)
				.authors().add("Mutation"))
				.isInstanceOf(UnsupportedOperationException.class);
		assertThatThrownBy(() -> rankingCorpus.corpus().queries().get(3)
				.filters().languages().add("fr"))
				.isInstanceOf(UnsupportedOperationException.class);

		var completion = rankingCompletion(verified);
		assertThatThrownBy(() -> RelatedTopicReuseHoldoutBundle.verifyAfterRanking(
				objectMapper, files.directory(), completion))
				.isInstanceOf(RelatedTopicReuseHoldoutBundle.VerificationException.class)
				.hasMessage("HOLDOUT_JUDGMENTS_JSON_INVALID");
		assertThatThrownBy(() -> RelatedTopicReuseHoldoutBundle.verifyAfterRanking(
				objectMapper, files.directory(), completion))
				.isInstanceOf(RelatedTopicReuseHoldoutBundle.VerificationException.class)
				.hasMessage("HOLDOUT_FIRST_RUN_ALREADY_CONSUMED");
	}

	@Test
	void postRankingStageRequiresASealAndRejectsStagedByteDrift() throws Exception {
		BundleFiles noSeal = validBundle();
		RelatedTopicReuseHoldoutBundle.VerifiedCorpus noSealCorpus =
				RelatedTopicReuseHoldoutBundle.verifyCorpus(objectMapper, noSeal.directory());
		assertThatThrownBy(() -> RelatedTopicReuseHoldoutBundle.verifyAfterRanking(
				objectMapper, noSeal.directory(), null))
				.isInstanceOf(RelatedTopicReuseHoldoutBundle.VerificationException.class)
				.hasMessage("HOLDOUT_INPUT_INVALID");

		BundleFiles manifestDrift = validBundle();
		RelatedTopicReuseHoldoutBundle.VerifiedCorpus stagedManifest =
				RelatedTopicReuseHoldoutBundle.verifyCorpus(objectMapper, manifestDrift.directory());
		Files.writeString(
				manifestDrift.manifestFile(),
				Files.readString(manifestDrift.manifestFile()) + "\n");
		assertPostRankingRejected(
				manifestDrift, stagedManifest, "HOLDOUT_STAGED_MANIFEST_CHANGED");

		BundleFiles corpusDrift = validBundle();
		RelatedTopicReuseHoldoutBundle.VerifiedCorpus stagedCorpus =
				RelatedTopicReuseHoldoutBundle.verifyCorpus(objectMapper, corpusDrift.directory());
		String changedCorpus = Files.readString(corpusDrift.corpusFile())
				.replaceFirst("abstract number 1\\.", "abstract number 9.");
		Files.writeString(corpusDrift.corpusFile(), changedCorpus);
		assertPostRankingRejected(corpusDrift, stagedCorpus, "HOLDOUT_STAGED_CORPUS_CHANGED");

		BundleFiles judgmentDrift = validBundle();
		RelatedTopicReuseHoldoutBundle.VerifiedCorpus stagedJudgments =
				RelatedTopicReuseHoldoutBundle.verifyCorpus(objectMapper, judgmentDrift.directory());
		String changedJudgments = Files.readString(judgmentDrift.judgmentsFile())
				.replaceFirst("holdout-query-1", "holdout-query-9");
		Files.writeString(judgmentDrift.judgmentsFile(), changedJudgments);
		assertPostRankingRejected(
				judgmentDrift, stagedJudgments, "HOLDOUT_PAYLOAD_DIGEST_MISMATCH");
	}

	@Test
	void postRankingStageAloneLoadsAndValidatesJudgmentIdentity() throws Exception {
		BundleFiles files = validBundle();
		files.judgments().put("corpusId", "different-corpus-identity");
		files.writePayloadsAndManifest();

		RelatedTopicReuseHoldoutBundle.VerifiedCorpus verified =
				RelatedTopicReuseHoldoutBundle.verifyCorpus(objectMapper, files.directory());

		assertPostRankingRejected(files, verified, "HOLDOUT_DOCUMENT_IDENTITY_INVALID");
	}

	@Test
	void coordinatorCompletionBindsTheExactCommitmentsAndRankedKeys() throws Exception {
		BundleFiles files = validBundle();
		RelatedTopicReuseHoldoutBundle.VerifiedCorpus verified =
				RelatedTopicReuseHoldoutBundle.verifyCorpus(objectMapper, files.directory());
		boolean[] rankingPhaseCalled = {false};
		RelatedTopicReuseHoldoutBundle.CompletedRanking validCompletion =
				completeRanking(verified, corpus -> {
					rankingPhaseCalled[0] = true;
					assertThat(corpus).isSameAs(verified.rankingCorpus());
					return emptyRankingObservation(verified);
				});
		RelatedTopicReuseHoldoutRankingSnapshot valid =
				validCompletion.rankingSnapshot();
		assertThat(rankingPhaseCalled[0]).isTrue();
		assertThat(valid.manifestSha256())
				.isEqualTo(sha256(Files.readAllBytes(files.manifestFile())));
		assertThat(valid.judgmentsSha256())
				.isEqualTo(sha256(Files.readAllBytes(files.judgmentsFile())));
		assertThat(valid.judgmentsBytes()).isEqualTo(Files.size(files.judgmentsFile()));
		assertThatThrownBy(() -> completeRanking(
				verified, ignored -> null))
				.isInstanceOf(RelatedTopicReuseHoldoutBundle.VerificationException.class)
				.hasMessage("HOLDOUT_RANKING_OBSERVATION_INVALID");

		RelatedTopicReuseHoldoutRankingSnapshot.Observation wrongRevision =
				new RelatedTopicReuseHoldoutRankingSnapshot.Observation(
						"f".repeat(40),
						valid.cutoff(),
						valid.queryOrder(),
						valid.queries(),
						valid.counters());
		assertThatThrownBy(() -> completeRanking(
				verified, ignored -> wrongRevision))
				.isInstanceOf(RelatedTopicReuseHoldoutBundle.VerificationException.class)
				.hasMessage("HOLDOUT_FIRST_RUN_CLAIM_INVALID");

		List<RelatedTopicReuseHoldoutRankingSnapshot.QueryRanking> reorderedQueries =
				new ArrayList<>(valid.queries());
		java.util.Collections.swap(reorderedQueries, 0, 1);
		RelatedTopicReuseHoldoutRankingSnapshot.Observation reordered =
				new RelatedTopicReuseHoldoutRankingSnapshot.Observation(
						valid.candidateRevision(),
						valid.cutoff(),
						reorderedQueries.stream()
								.map(RelatedTopicReuseHoldoutRankingSnapshot.QueryRanking::queryKey)
								.toList(),
						reorderedQueries,
						valid.counters());
		RelatedTopicReuseHoldoutBundle.CompletedRanking wrongQueryOrder =
				completeRanking(
						verified, ignored -> reordered);
		assertThatThrownBy(() -> RelatedTopicReuseHoldoutBundle.verifyAfterRanking(
				objectMapper, files.directory(), wrongQueryOrder))
				.isInstanceOf(RelatedTopicReuseHoldoutBundle.VerificationException.class)
				.hasMessage("HOLDOUT_RANKING_SEAL_IDENTITY_INVALID");

		var outside = new RelatedTopicReuseHoldoutRankingSnapshot.RankedPaper(
				"outside-corpus", 1.0d);
		var outsideRun = new RelatedTopicReuseHoldoutRankingSnapshot.RankingRun(
				List.of(outside),
				List.of(outside),
				List.of(),
				List.of(),
				List.of(outside));
		List<RelatedTopicReuseHoldoutRankingSnapshot.QueryRanking> outsideQueries =
				new ArrayList<>(valid.queries());
		outsideQueries.set(0, new RelatedTopicReuseHoldoutRankingSnapshot.QueryRanking(
				valid.queryOrder().getFirst(),
				outsideRun,
				outsideRun,
				new RelatedTopicReuseHoldoutRankingSnapshot.HiddenPerturbation(
						"hidden-other-owner",
						"hidden-catalog-only",
						List.of(),
						List.of(outside))));
		RelatedTopicReuseHoldoutRankingSnapshot.Observation wrongScopeObservation =
				new RelatedTopicReuseHoldoutRankingSnapshot.Observation(
						valid.candidateRevision(),
						valid.cutoff(),
						valid.queryOrder(),
						outsideQueries,
						valid.counters());
		RelatedTopicReuseHoldoutBundle.CompletedRanking wrongScope =
				completeRanking(
						verified, ignored -> wrongScopeObservation);
		assertThatThrownBy(() -> RelatedTopicReuseHoldoutBundle.verifyAfterRanking(
				objectMapper, files.directory(), wrongScope))
				.isInstanceOf(RelatedTopicReuseHoldoutBundle.VerificationException.class)
				.hasMessage("HOLDOUT_RANKING_SEAL_SCOPE_INVALID");
	}

	@Test
	void onlyACoordinatorIssuedCompletionCanReachJudgmentLoading() {
		var completionMethods = Arrays.stream(
				RelatedTopicReuseHoldoutBundle.class.getDeclaredMethods())
				.filter(method -> method.getName().equals("completeRanking"))
				.toList();
		assertThat(completionMethods).singleElement().satisfies(method ->
				assertThat(method.getParameterTypes()).containsExactly(
						RelatedTopicReuseHoldoutBundle.VerifiedCorpus.class,
						RelatedTopicReuseHoldoutPostgresFirstRunLedger
								.CommittedFirstRun.class,
						RelatedTopicReuseHoldoutBundle.LabelFreeRankingPhase.class));
		var verifyMethods = Arrays.stream(
				RelatedTopicReuseHoldoutBundle.class.getDeclaredMethods())
				.filter(method -> method.getName().equals("verifyAfterRanking"))
				.toList();
		assertThat(verifyMethods).singleElement().satisfies(method ->
				assertThat(method.getParameterTypes()).containsExactly(
						ObjectMapper.class,
						Path.class,
						RelatedTopicReuseHoldoutBundle.CompletedRanking.class));
		assertThat(RelatedTopicReuseHoldoutBundle.CompletedRanking.class
				.getDeclaredConstructors()).allSatisfy(constructor ->
						assertThat(Modifier.isPrivate(constructor.getModifiers())).isTrue());
	}

	@Test
	void committedFirstRunIsExactlyBoundOneShotAndConsumedBeforeRanking()
			throws Exception {
		BundleFiles files = validBundle();
		RelatedTopicReuseHoldoutBundle.VerifiedCorpus verified =
				RelatedTopicReuseHoldoutBundle.verifyCorpus(
						objectMapper, files.directory());
		RelatedTopicReuseHoldoutBundle.VerifiedCorpus independentlyVerified =
				RelatedTopicReuseHoldoutBundle.verifyCorpus(
						objectMapper, files.directory());
		var committedFirstRun = syntheticCommittedFirstRun(verified);
		int[] rankingCalls = {0};

		assertThatThrownBy(() -> RelatedTopicReuseHoldoutBundle.completeRanking(
				independentlyVerified,
				committedFirstRun,
				ignored -> {
					rankingCalls[0]++;
					return emptyRankingObservation(independentlyVerified);
				}))
				.isInstanceOf(RelatedTopicReuseHoldoutBundle.VerificationException.class)
				.hasMessage("HOLDOUT_FIRST_RUN_CLAIM_INVALID");
		assertThat(rankingCalls[0]).isZero();

		var completion = RelatedTopicReuseHoldoutBundle.completeRanking(
				verified,
				committedFirstRun,
				ignored -> {
					rankingCalls[0]++;
					assertThatThrownBy(() ->
							RelatedTopicReuseHoldoutBundle.completeRanking(
									verified,
									committedFirstRun,
									nested -> emptyRankingObservation(verified)))
							.isInstanceOf(
									RelatedTopicReuseHoldoutBundle.VerificationException.class)
							.hasMessage("HOLDOUT_FIRST_RUN_CLAIM_INVALID");
					return emptyRankingObservation(verified);
				});

		assertThat(completion).isNotNull();
		assertThat(rankingCalls[0]).isOne();
		assertThatThrownBy(() -> RelatedTopicReuseHoldoutBundle.completeRanking(
				verified,
				committedFirstRun,
				ignored -> {
					rankingCalls[0]++;
					return emptyRankingObservation(verified);
				}))
				.isInstanceOf(RelatedTopicReuseHoldoutBundle.VerificationException.class)
				.hasMessage("HOLDOUT_FIRST_RUN_CLAIM_INVALID");
		assertThat(rankingCalls[0]).isOne();
	}

	@Test
	void aVerifiedCorpusReleasesJudgmentsForOnlyItsFirstValidRanking()
			throws Exception {
		BundleFiles files = validBundle();
		RelatedTopicReuseHoldoutBundle.VerifiedCorpus verified =
				RelatedTopicReuseHoldoutBundle.verifyCorpus(objectMapper, files.directory());
		var observation = passingRankingObservation(verified);
		var firstCompletion = completeRanking(
				verified, ignored -> observation);

		var scoringInputs = RelatedTopicReuseHoldoutBundle.verifyAfterRanking(
				objectMapper, files.directory(), firstCompletion);

		assertThat(RelatedTopicReuseHoldoutScorer.score(scoringInputs).result())
				.isEqualTo(RelatedTopicReuseHoldoutScorer.score(scoringInputs).result());
		var replayedCompletion = completeRanking(
				verified, ignored -> observation);
		assertThatThrownBy(() -> RelatedTopicReuseHoldoutBundle.verifyAfterRanking(
				objectMapper, files.directory(), replayedCompletion))
				.isInstanceOf(RelatedTopicReuseHoldoutBundle.VerificationException.class)
				.hasMessage("HOLDOUT_FIRST_RUN_ALREADY_CONSUMED");
	}

	@Test
	void completionCannotBeReplayedAgainstASecondJudgmentCommitment() throws Exception {
		BundleFiles first = validBundle();
		RelatedTopicReuseHoldoutBundle.VerifiedCorpus firstCorpus =
				RelatedTopicReuseHoldoutBundle.verifyCorpus(objectMapper, first.directory());
		RelatedTopicReuseHoldoutBundle.CompletedRanking firstCompletion =
				rankingCompletion(firstCorpus);

		Path alternateParent = temporaryDirectory.resolve("alternate-custody");
		Files.createDirectory(alternateParent);
		Path alternateDirectory = alternateParent.resolve(first.bundleId());
		Files.createDirectory(alternateDirectory);
		ObjectNode alternateJudgments = first.judgments().deepCopy();
		grades(alternateJudgments, 0).put(candidateKey(2), 3);
		BundleFiles second = new BundleFiles(
				alternateDirectory,
				first.bundleId(),
				first.corpusId(),
				first.corpus().deepCopy(),
				alternateJudgments);
		second.writePayloadsAndManifest();
		RelatedTopicReuseHoldoutBundle.VerifiedCorpus secondCorpus =
				RelatedTopicReuseHoldoutBundle.verifyCorpus(objectMapper, second.directory());
		assertThat(secondCorpus.rankingCorpus().corpusSha256())
				.isEqualTo(firstCorpus.rankingCorpus().corpusSha256());
		assertThat(rankingCompletion(secondCorpus).rankingSnapshot().judgmentsSha256())
				.isNotEqualTo(firstCompletion.rankingSnapshot().judgmentsSha256());

		assertThatThrownBy(() -> RelatedTopicReuseHoldoutBundle.verifyAfterRanking(
				objectMapper, second.directory(), firstCompletion))
				.isInstanceOf(RelatedTopicReuseHoldoutBundle.VerificationException.class)
				.hasMessage("HOLDOUT_STAGED_MANIFEST_CHANGED");
	}

	@Test
	void validatesEveryIndependentlyReachableRankingScope() throws Exception {
		BundleFiles files = validBundle();
		RelatedTopicReuseHoldoutBundle.VerifiedCorpus verified =
				RelatedTopicReuseHoldoutBundle.verifyCorpus(objectMapper, files.directory());
		RelatedTopicReuseHoldoutRankingSnapshot.Observation valid =
				nonEmptyRankingObservation(verified);
		RelatedTopicReuseHoldoutBundle.CompletedRanking validCompletion =
				completeRanking(verified, ignored -> valid);
		assertThat(RelatedTopicReuseHoldoutBundle.verifyAfterRanking(
				objectMapper, files.directory(), validCompletion)).isNotNull();

		var outside = new RelatedTopicReuseHoldoutRankingSnapshot.RankedPaper(
				"outside-corpus", 1.0d);
		List<RelatedTopicReuseHoldoutRankingSnapshot.RankedPaper> outsideControl =
				new ArrayList<>(controlTen(verified.rankingCorpus()));
		outsideControl.set(outsideControl.size() - 1, outside);
		var badControlRun = new RelatedTopicReuseHoldoutRankingSnapshot.RankingRun(
				outsideControl,
				outsideControl,
				List.of(),
				List.of(),
				outsideControl);
		var initial = valid.queries().getFirst().initialRun();
		String firstQuery = valid.queryOrder().getFirst();
		assertScopeRejected(
				files,
				verified,
				replaceFirstQuery(
						valid,
						new RelatedTopicReuseHoldoutRankingSnapshot.QueryRanking(
								firstQuery,
								badControlRun,
								initial,
								hiddenFor(badControlRun))));
		assertScopeRejected(
				files,
				verified,
				replaceFirstQuery(
						valid,
						new RelatedTopicReuseHoldoutRankingSnapshot.QueryRanking(
								firstQuery,
								initial,
								badControlRun,
								hiddenFor(initial))));

		var badFeedbackRun = rankedRun(verified.rankingCorpus(), "outside-corpus");
		assertScopeRejected(
				files,
				verified,
				replaceFirstQuery(
						valid,
						new RelatedTopicReuseHoldoutRankingSnapshot.QueryRanking(
								firstQuery,
								badFeedbackRun,
								initial,
								hiddenFor(badFeedbackRun))));
		assertScopeRejected(
				files,
				verified,
				replaceFirstQuery(
						valid,
						new RelatedTopicReuseHoldoutRankingSnapshot.QueryRanking(
								firstQuery,
								initial,
								badFeedbackRun,
								hiddenFor(initial))));

		String seed = initial.eligibleSeedKeys().getFirst();
		var hiddenBadFeedback = new RelatedTopicReuseHoldoutRankingSnapshot.HiddenPerturbation(
				"hidden-other-owner",
				"hidden-catalog-only",
				List.of(new RelatedTopicReuseHoldoutRankingSnapshot.FeedbackPool(
						seed, List.of(outside))),
				initial.controlTop10());
		assertScopeRejected(
				files,
				verified,
				replaceFirstQuery(
						valid,
						new RelatedTopicReuseHoldoutRankingSnapshot.QueryRanking(
								firstQuery, initial, initial, hiddenBadFeedback)));

		var hiddenCollision = new RelatedTopicReuseHoldoutRankingSnapshot.HiddenPerturbation(
				verified.rankingCorpus().corpus().candidates().get(30).key(),
				"hidden-catalog-only",
				initial.feedbackPools(),
				initial.candidateTop10());
		assertScopeRejected(
				files,
				verified,
				replaceFirstQuery(
						valid,
						new RelatedTopicReuseHoldoutRankingSnapshot.QueryRanking(
								firstQuery, initial, initial, hiddenCollision)));
	}

	@Test
	void pathsMustBeAbsoluteExternalRealDirectories() throws Exception {
		BundleFiles files = validBundle();

		assertRejected(Path.of("relative-holdout"), "HOLDOUT_PATH_MUST_BE_ABSOLUTE");
		assertRejected(repositoryRoot, "HOLDOUT_DIRECTORY_NOT_EXTERNAL");
		assertRejected(repositoryRoot.getParent(), "HOLDOUT_DIRECTORY_NOT_EXTERNAL");

		Path link = temporaryDirectory.resolve("holdout-directory-link");
		Files.createSymbolicLink(link, files.directory());
		assertRejected(link, "HOLDOUT_DIRECTORY_INVALID");

		Path ancestorLink = temporaryDirectory.resolve("holdout-ancestor-link");
		Files.createSymbolicLink(ancestorLink, temporaryDirectory);
		assertRejected(
				ancestorLink.resolve(files.bundleId()), "HOLDOUT_DIRECTORY_SYMLINKED");
	}

	@Test
	void exactLayoutRejectsExtraMissingAndSymlinkedFiles() throws Exception {
		BundleFiles extra = validBundle();
		Files.writeString(extra.directory().resolve("notes.txt"), "not part of the bundle");
		assertRejected(extra, "HOLDOUT_LAYOUT_INVALID");

		BundleFiles missing = validBundle();
		Files.delete(missing.judgmentsFile());
		assertRejected(missing, "HOLDOUT_LAYOUT_INVALID");

		BundleFiles linked = validBundle();
		Path externalJudgments = temporaryDirectory.resolve("external-judgments.json");
		Files.copy(linked.judgmentsFile(), externalJudgments);
		Files.delete(linked.judgmentsFile());
		Files.createSymbolicLink(linked.judgmentsFile(), externalJudgments);
		assertRejected(linked, "HOLDOUT_FILE_INVALID");
	}

	@Test
	void repositoryDiscoverySkipsNestedGitLookalikesWithoutProjectMarkers()
			throws Exception {
		Path syntheticRoot = temporaryDirectory.resolve("synthetic-worktree");
		Path nestedStart = syntheticRoot.resolve("backend/nested-run-directory");
		Files.createDirectories(syntheticRoot.resolve(".git"));
		Files.createDirectories(nestedStart.resolve(".git"));
		Files.createDirectories(syntheticRoot.resolve("frontend"));
		Files.writeString(syntheticRoot.resolve("backend/pom.xml"), "<project />");
		Files.writeString(syntheticRoot.resolve("frontend/package.json"), "{}");

		assertThat(RelatedTopicReuseHoldoutBundle.findRepositoryRoot(nestedStart))
				.isEqualTo(syntheticRoot.toRealPath());
	}

	@Test
	void strictJsonRejectsDuplicatesTrailingTokensUnknownFieldsAndOracleFields()
			throws Exception {
		BundleFiles duplicate = validBundle();
		String duplicateCorpus = Files.readString(duplicate.corpusFile())
				.replaceFirst("\\\"schemaVersion\\\":1", "\\\"schemaVersion\\\":1,\\\"schemaVersion\\\":1");
		duplicate.replacePayload(CORPUS_FILENAME, duplicateCorpus.getBytes(StandardCharsets.UTF_8));
		assertRejected(duplicate, "HOLDOUT_CORPUS_JSON_INVALID");

		BundleFiles trailing = validBundle();
		byte[] trailingBytes = (Files.readString(trailing.judgmentsFile()) + "\n{}").getBytes(
				StandardCharsets.UTF_8);
		trailing.replacePayload(JUDGMENTS_FILENAME, trailingBytes);
		assertRejected(trailing, "HOLDOUT_JUDGMENTS_JSON_INVALID");

		BundleFiles unknown = validBundle();
		unknown.manifest().put("unexpected", true);
		unknown.writeManifest();
		assertRejected(unknown, "HOLDOUT_SCHEMA_INVALID_AT_$");

		BundleFiles oracle = validBundle();
		candidate(oracle.corpus(), 0).put("relatedScore", 0.99d);
		oracle.writePayloadsAndManifest();
		assertRejected(oracle, "HOLDOUT_SCHEMA_INVALID_AT_$.candidates[0]");

		BundleFiles outputOracle = validBundle();
		queryJudgments(outputOracle.judgments(), 0).put("candidateRanking", "candidate-output");
		outputOracle.writePayloadsAndManifest();
		assertRejected(outputOracle, "HOLDOUT_SCHEMA_INVALID_AT_$.queries[0]");

		BundleFiles comments = validBundle();
		byte[] commentedCorpus = Files.readString(comments.corpusFile())
				.replaceFirst("\\{", "{/* caller-enabled comment */")
				.getBytes(StandardCharsets.UTF_8);
		comments.replacePayload(CORPUS_FILENAME, commentedCorpus);
		ObjectMapper permissiveMapper = JsonMapper.builder()
				.enable(JsonReadFeature.ALLOW_JAVA_COMMENTS)
				.build();
		assertThatThrownBy(() -> verify(permissiveMapper, comments.directory()))
				.isInstanceOf(RelatedTopicReuseHoldoutBundle.VerificationException.class)
				.hasMessage("HOLDOUT_CORPUS_JSON_INVALID");

		BundleFiles singleQuotes = validBundle();
		byte[] singleQuotedCorpus = Files.readString(singleQuotes.corpusFile())
				.replaceFirst("\"schemaVersion\"", "'schemaVersion'")
				.getBytes(StandardCharsets.UTF_8);
		singleQuotes.replacePayload(CORPUS_FILENAME, singleQuotedCorpus);
		ObjectMapper singleQuoteMapper = JsonMapper.builder()
				.enable(JsonReadFeature.ALLOW_SINGLE_QUOTES)
				.build();
		assertThatThrownBy(() -> verify(singleQuoteMapper, singleQuotes.directory()))
				.isInstanceOf(RelatedTopicReuseHoldoutBundle.VerificationException.class)
				.hasMessage("HOLDOUT_CORPUS_JSON_INVALID");

		BundleFiles trailingComma = validBundle();
		byte[] trailingCommaCorpus = Files.readString(trailingComma.corpusFile())
				.replaceFirst("\\}$", ",}")
				.getBytes(StandardCharsets.UTF_8);
		trailingComma.replacePayload(CORPUS_FILENAME, trailingCommaCorpus);
		ObjectMapper trailingCommaMapper = JsonMapper.builder()
				.enable(JsonReadFeature.ALLOW_TRAILING_COMMA)
				.build();
		assertThatThrownBy(() -> verify(trailingCommaMapper, trailingComma.directory()))
				.isInstanceOf(RelatedTopicReuseHoldoutBundle.VerificationException.class)
				.hasMessage("HOLDOUT_CORPUS_JSON_INVALID");
	}

	@Test
	void manifestAndPayloadSizeDigestIdentityAndDeclarationDriftFailClosed()
			throws Exception {
		BundleFiles declaredSize = validBundle();
		declaredSize.manifest().put(
				"payloadBytes", declaredSize.manifest().required("payloadBytes").longValue() + 1);
		declaredSize.writeManifest();
		assertRejected(declaredSize, "HOLDOUT_MANIFEST_SIZE_INVALID");

		BundleFiles oversized = validBundle();
		oversized.replacePayloadWithoutManifest(
				CORPUS_FILENAME,
				new byte[boundPolicy.policy().bundle().maximumCorpusBytes() + 1]);
		assertRejected(oversized, "HOLDOUT_BUNDLE_TOO_LARGE");

		BundleFiles digest = validBundle();
		manifestFile(digest.manifest(), 0).put("sha256", "0".repeat(64));
		digest.writeManifest();
		assertRejected(digest, "HOLDOUT_PAYLOAD_DIGEST_MISMATCH");

		BundleFiles identity = validBundle();
		identity.corpus().put("corpusId", "different-corpus-identity");
		identity.writePayloadsAndManifest();
		assertRejected(identity, "HOLDOUT_DOCUMENT_IDENTITY_INVALID");

		BundleFiles policyIdentity = validBundle();
		policyIdentity.manifest().put("policySha256", "0".repeat(64));
		policyIdentity.writeManifest();
		assertRejected(policyIdentity, "HOLDOUT_MANIFEST_IDENTITY_INVALID");

		BundleFiles declarations = validBundle();
		((ObjectNode) declarations.manifest().required("declarations"))
				.put("firstRunRule", "A_DIFFERENT_FIRST_RUN_RULE");
		declarations.writeManifest();
		assertRejected(declarations, "HOLDOUT_DECLARATIONS_INVALID");
	}

	@Test
	void everyQueryMustGradeEveryTargetCandidateWithinTheFrozenRange() throws Exception {
		BundleFiles missingGrade = validBundle();
		grades(missingGrade.judgments(), 0).remove(candidateKey(30));
		missingGrade.writePayloadsAndManifest();
		assertRejected(missingGrade, "HOLDOUT_TARGET_JUDGMENTS_INCOMPLETE");

		BundleFiles extraGrade = validBundle();
		grades(extraGrade.judgments(), 0).put(candidateKey(31), 0);
		extraGrade.writePayloadsAndManifest();
		assertRejected(extraGrade, "HOLDOUT_TARGET_JUDGMENTS_INCOMPLETE");

		BundleFiles outOfRange = validBundle();
		grades(outOfRange.judgments(), 0).put(candidateKey(1), 4);
		outOfRange.writePayloadsAndManifest();
		assertRejected(outOfRange, "HOLDOUT_GRADE_OUT_OF_RANGE");
	}

	@Test
	void lineageQueryAndAdversaryKindsAndEveryFilterDimensionAreRequired()
			throws Exception {
		BundleFiles unusedLineage = validBundle();
		unusedLineage.corpus().withArray("lineages").addObject()
				.put("key", "unused-target-lineage")
				.put("kind", "TARGET_OWNER_SEARCH");
		unusedLineage.writePayloadsAndManifest();
		assertRejected(unusedLineage, "HOLDOUT_UNUSED_LINEAGE_INVALID");

		BundleFiles missingLineageKind = validBundle();
		lineage(missingLineageKind.corpus(), 3).put("kind", "OTHER_OWNER_SEARCH");
		missingLineageKind.writePayloadsAndManifest();
		assertRejected(missingLineageKind, "HOLDOUT_REQUIRED_LINEAGE_KIND_MISSING");

		BundleFiles missingQueryKind = validBundle();
		query(missingQueryKind.corpus(), 7).put("kind", "AUTHOR_NO_RELATED_SIGNAL_CONTROL");
		grades(missingQueryKind.judgments(), 7).put(candidateKey(1), 3);
		ArrayNode noSeedAdversaries = adversaries(missingQueryKind.judgments(), 7);
		noSeedAdversaries.removeAll();
		addAdversary(
				noSeedAdversaries,
				candidateKey(3),
				"AUTHOR_SUBSTRING_COLLISION",
				"Independent author substring collision annotation.");
		missingQueryKind.writePayloadsAndManifest();
		assertRejected(missingQueryKind, "HOLDOUT_QUERY_KIND_SHAPE_INVALID");

		BundleFiles missingAdversaryKind = validBundle();
		adversaries(missingAdversaryKind.judgments(), 0).remove(2);
		missingAdversaryKind.writePayloadsAndManifest();
		assertRejected(missingAdversaryKind, "HOLDOUT_ADVERSARY_KIND_MISSING");

		BundleFiles missingFilterDimension = validBundle();
		adversaries(missingFilterDimension.judgments(), 3).remove(5);
		missingFilterDimension.writePayloadsAndManifest();
		assertRejected(missingFilterDimension, "HOLDOUT_FILTER_DIMENSION_COVERAGE_INVALID");

		BundleFiles nonIsolatedFilterDimension = validBundle();
		candidate(nonIsolatedFilterDimension.corpus(), 9).put("reportedOpenAccess", false);
		nonIsolatedFilterDimension.writePayloadsAndManifest();
		assertRejected(
				nonIsolatedFilterDimension, "HOLDOUT_FILTER_ADVERSARY_NOT_ISOLATED");
	}

	@Test
	void candidateQueryKeyQueryTextAndTitleMustBeDisjointFromDevelopment()
			throws Exception {
		var development = developmentFixture.fixture();

		BundleFiles candidateKeyOverlap = validBundle();
		String oldCandidateKey = candidateKey(30);
		String developmentCandidateKey = development.candidates().get(0).key();
		candidate(candidateKeyOverlap.corpus(), 29).put("key", developmentCandidateKey);
		for (int index = 0; index < 8; index++) {
			ObjectNode queryGrades = grades(candidateKeyOverlap.judgments(), index);
			int grade = queryGrades.remove(oldCandidateKey).intValue();
			queryGrades.put(developmentCandidateKey, grade);
		}
		candidateKeyOverlap.writePayloadsAndManifest();
		assertRejected(candidateKeyOverlap, "HOLDOUT_CANDIDATE_KEY_OVERLAP");

		BundleFiles queryKeyOverlap = validBundle();
		String developmentQueryKey = development.queries().get(0).key();
		query(queryKeyOverlap.corpus(), 0).put("key", developmentQueryKey);
		queryJudgments(queryKeyOverlap.judgments(), 0).put("queryKey", developmentQueryKey);
		queryKeyOverlap.writePayloadsAndManifest();
		assertRejected(queryKeyOverlap, "HOLDOUT_QUERY_KEY_OVERLAP");

		BundleFiles queryTextOverlap = validBundle();
		query(queryTextOverlap.corpus(), 0).put("text", development.queries().get(0).text());
		queryTextOverlap.writePayloadsAndManifest();
		assertRejected(queryTextOverlap, "HOLDOUT_QUERY_TEXT_OVERLAP");

		BundleFiles titleOverlap = validBundle();
		candidate(titleOverlap.corpus(), 0).put("title", development.candidates().get(0).title());
		titleOverlap.writePayloadsAndManifest();
		assertRejected(titleOverlap, "HOLDOUT_TITLE_OVERLAP");
	}

	private BundleFiles validBundle() throws Exception {
		int sequence = ++bundleSequence;
		String bundleId = "external-holdout-bundle-" + sequence;
		String corpusId = "external-holdout-corpus-" + sequence;
		Path directory = temporaryDirectory.resolve(bundleId);
		Files.createDirectory(directory);
		BundleFiles files = new BundleFiles(
				directory,
				bundleId,
				corpusId,
				buildCorpus(bundleId, corpusId),
				buildJudgments(bundleId, corpusId));
		files.writePayloadsAndManifest();
		return files;
	}

	private ObjectNode buildCorpus(String bundleId, String corpusId) {
		ObjectNode root = identity(bundleId, corpusId);
		root.put("split", String.valueOf(boundPolicy.policy().corpus().split()));
		root.put("labelUnit", String.valueOf(boundPolicy.policy().labelUnit()));
		root.put("sourcePolicy", String.valueOf(boundPolicy.policy().sourcePolicy()));

		ArrayNode lineages = root.putArray("lineages");
		addLineage(lineages, TARGET_SEARCH, "TARGET_OWNER_SEARCH");
		addLineage(lineages, TARGET_COLLECTION, "TARGET_OWNER_COLLECTION");
		addLineage(lineages, OTHER_SEARCH, "OTHER_OWNER_SEARCH");
		addLineage(lineages, OTHER_COLLECTION, "OTHER_OWNER_COLLECTION");
		addLineage(lineages, CATALOG, "CATALOG_ONLY");

		ArrayNode candidates = root.putArray("candidates");
		for (int index = 1; index <= 40; index++) {
			addCandidate(candidates, index);
		}

		ArrayNode queries = root.putArray("queries");
		for (int index = 1; index <= 8; index++) {
			addQuery(queries, index);
		}
		return root;
	}

	private ObjectNode buildJudgments(String bundleId, String corpusId) {
		ObjectNode root = identity(bundleId, corpusId);
		root.put("labelUnit", String.valueOf(boundPolicy.policy().labelUnit()));
		ArrayNode queries = root.putArray("queries");
		for (int index = 1; index <= 8; index++) {
			ObjectNode query = queries.addObject();
			query.put("queryKey", queryKey(index));
			ObjectNode grades = query.putObject("grades");
			for (int candidate = 1; candidate <= 30; candidate++) {
				grades.put(candidateKey(candidate), grade(index, candidate));
			}
			ArrayNode adversaries = query.putArray("adversaries");
			addQueryAdversaries(adversaries, index);
		}
		return root;
	}

	private ObjectNode identity(String bundleId, String corpusId) {
		ObjectNode root = objectMapper.createObjectNode();
		root.put("schemaVersion", 1);
		root.put("protocolId", boundPolicy.policy().bundle().protocolId());
		root.put("bundleId", bundleId);
		root.put("policyId", boundPolicy.policy().policyId());
		root.put("policySha256", boundPolicy.sha256());
		root.put("corpusId", corpusId);
		return root;
	}

	private static void addLineage(ArrayNode lineages, String key, String kind) {
		lineages.addObject().put("key", key).put("kind", kind);
	}

	private static void addCandidate(ArrayNode candidates, int index) {
		ObjectNode candidate = candidates.addObject();
		candidate.put("key", candidateKey(index));
		candidate.put("lineageKey", lineageKey(index));
		candidate.put("title", "Independent Holdout Metadata Study " + index);
		candidate.put("abstractText", "Synthetic external metadata abstract number " + index + ".");
		candidate.put("venueName", "External Review Venue");
		candidate.put("publicationYear", publicationYear(index));
		candidate.put("documentType", index == 12 ? "PREPRINT" : "ARTICLE");
		candidate.put("language", index == 15 ? "fr" : "en");
		candidate.put("citationCount", index == 14 ? 1 : 50);
		candidate.put("reportedOpenAccess", index != 13);
		candidate.putArray("authors").add("External Author " + index);
	}

	private void addQuery(ArrayNode queries, int index) {
		ObjectNode query = queries.addObject();
		query.put("key", queryKey(index));
		query.put("text", "Independent external holdout research topic " + index);
		query.put("kind", queryKind(index));
		query.put("cutoff", boundPolicy.policy().gates().cutoff());
		ObjectNode filters = query.putObject("filters");
		if (index == 4) {
			filters.put("yearFrom", 2015);
			filters.put("yearTo", 2025);
			filters.putArray("documentTypes").add("ARTICLE");
			filters.put("openAccessOnly", true);
			filters.put("minimumCitations", 20);
			filters.putArray("languages").add("en");
		}
		else {
			filters.putNull("yearFrom");
			filters.putNull("yearTo");
			filters.putArray("documentTypes");
			filters.put("openAccessOnly", false);
			filters.put("minimumCitations", 0);
			filters.putArray("languages");
		}
	}

	private static int grade(int queryIndex, int candidateIndex) {
		if (queryIndex <= 4) {
			return candidateIndex == 1 ? 3 : candidateIndex == 2 ? 2 : 0;
		}
		if (queryIndex <= 7) {
			return candidateIndex == 1 ? 3 : 0;
		}
		return 0;
	}

	private static void addQueryAdversaries(ArrayNode adversaries, int queryIndex) {
		if (queryIndex == 1) {
			addAdversary(
					adversaries,
					candidateKey(3),
					"OWNER_VISIBLE_TOPIC_DRIFT",
					"Target-visible metadata is a deliberate topic-drift negative.");
			addAdversary(
					adversaries,
					candidateKey(31),
					"OTHER_OWNER_TOPIC_MATCH",
					"Topical metadata belongs exclusively to another owner.");
			addAdversary(
					adversaries,
					candidateKey(36),
					"CATALOG_ONLY_TOPIC_MATCH",
					"Topical metadata has no owner-visible lineage.");
		}
		else if (queryIndex <= 3) {
			addAdversary(
					adversaries,
					candidateKey(3),
					"OWNER_VISIBLE_TOPIC_DRIFT",
					"Target-visible metadata is a deliberate topic-drift negative.");
		}
		else if (queryIndex == 4) {
			for (int candidate = 10; candidate <= 15; candidate++) {
				addAdversary(
						adversaries,
						candidateKey(candidate),
						"FILTER_VIOLATION",
						"Candidate deliberately violates one frozen filter dimension.");
			}
		}
		else if (queryIndex <= 7) {
			addAdversary(
					adversaries,
					candidateKey(3),
					"AUTHOR_SUBSTRING_COLLISION",
					"Author text is a deliberate substring-collision negative.");
		}
		else {
			addAdversary(
					adversaries,
					candidateKey(31),
					"OTHER_OWNER_TOPIC_MATCH",
					"Topical metadata belongs exclusively to another owner.");
		}
	}

	private static void addAdversary(
			ArrayNode adversaries, String candidateKey, String kind, String reason) {
		adversaries.addObject()
				.put("candidateKey", candidateKey)
				.put("kind", kind)
				.put("reason", reason);
	}

	private RelatedTopicReuseHoldoutBundle verify(BundleFiles files) throws Exception {
		return verify(objectMapper, files.directory());
	}

	private RelatedTopicReuseHoldoutBundle verify(
			ObjectMapper mapper, Path sourceDirectory) throws Exception {
		RelatedTopicReuseHoldoutBundle.VerifiedCorpus verified =
				RelatedTopicReuseHoldoutBundle.verifyCorpus(mapper, sourceDirectory);
		return RelatedTopicReuseHoldoutBundle.verifyAfterRanking(
				mapper, sourceDirectory, rankingCompletion(verified)).bundle();
	}

	private void assertPostRankingRejected(
			BundleFiles files,
			RelatedTopicReuseHoldoutBundle.VerifiedCorpus verifiedCorpus,
			String diagnostic) {
		assertThatThrownBy(() -> RelatedTopicReuseHoldoutBundle.verifyAfterRanking(
				objectMapper, files.directory(), rankingCompletion(verifiedCorpus)))
				.isInstanceOf(RelatedTopicReuseHoldoutBundle.VerificationException.class)
				.hasMessage(diagnostic);
	}

	private void assertRejected(BundleFiles files, String diagnostic) {
		assertRejected(files.directory(), diagnostic);
	}

	private void assertRejected(Path sourceDirectory, String diagnostic) {
		assertThatThrownBy(() -> verify(objectMapper, sourceDirectory))
				.isInstanceOf(RelatedTopicReuseHoldoutBundle.VerificationException.class)
				.hasMessage(diagnostic);
	}

	private static RelatedTopicReuseHoldoutBundle.CompletedRanking rankingCompletion(
			RelatedTopicReuseHoldoutBundle.VerifiedCorpus verified) throws IOException {
		return completeRanking(
				verified, ignored -> emptyRankingObservation(verified));
	}

	private static RelatedTopicReuseHoldoutBundle.CompletedRanking completeRanking(
			RelatedTopicReuseHoldoutBundle.VerifiedCorpus verified,
			RelatedTopicReuseHoldoutBundle.LabelFreeRankingPhase rankingPhase)
			throws IOException {
		return RelatedTopicReuseHoldoutBundle.completeRanking(
				verified, syntheticCommittedFirstRun(verified), rankingPhase);
	}

	private static RelatedTopicReuseHoldoutPostgresFirstRunLedger.CommittedFirstRun
			syntheticCommittedFirstRun(
					RelatedTopicReuseHoldoutBundle.VerifiedCorpus verified) {
		try {
			var evaluatorSeal = syntheticEvaluatorSeal(
					RelatedTopicReuseHoldoutPolicy.CANDIDATE_FREEZE_REVISION);
			var freeze = new RelatedTopicReuseHoldoutGitCollector.FreezeRecord(
					RelatedTopicReuseHoldoutGitCollector.FREEZE_SCHEMA_VERSION,
					RelatedTopicReuseHoldoutGitCollector.INVENTORY_ID,
					evaluatorSeal.evaluatorRevision(),
					evaluatorSeal.evaluatorSourceSha256(),
					evaluatorSeal.candidateRevision(),
					evaluatorSeal.candidateSourceSha256());
			Constructor<RelatedTopicReuseHoldoutGitCollector.VerifiedCleanCheckout>
					checkoutConstructor = RelatedTopicReuseHoldoutGitCollector
							.VerifiedCleanCheckout.class.getDeclaredConstructor(
									RelatedTopicReuseHoldoutGitCollector.FreezeRecord.class,
									RelatedTopicReuseHoldoutEvaluatorSeal
											.VerifiedEvaluatorSeal.class);
			checkoutConstructor.setAccessible(true);
			var checkout = checkoutConstructor.newInstance(freeze, evaluatorSeal);
			var identity = RelatedTopicReuseHoldoutFirstRunIdentity.fromVerified(
					verified.firstRunCommitment(), checkout);
			Constructor<RelatedTopicReuseHoldoutPostgresFirstRunLedger.CommittedFirstRun>
					capabilityConstructor = RelatedTopicReuseHoldoutPostgresFirstRunLedger
							.CommittedFirstRun.class.getDeclaredConstructor(
									RelatedTopicReuseHoldoutFirstRunIdentity.class,
									RelatedTopicReuseHoldoutBundle
											.VerifiedFirstRunCommitment.class,
									RelatedTopicReuseHoldoutGitCollector
											.VerifiedCleanCheckout.class);
			capabilityConstructor.setAccessible(true);
			return capabilityConstructor.newInstance(
					identity, verified.firstRunCommitment(), checkout);
		}
		catch (ReflectiveOperationException exception) {
			throw new IllegalStateException(
					"synthetic committed first-run capability is unavailable", exception);
		}
	}

	private void assertScopeRejected(
			BundleFiles files,
			RelatedTopicReuseHoldoutBundle.VerifiedCorpus verified,
			RelatedTopicReuseHoldoutRankingSnapshot.Observation observation) {
		assertThatThrownBy(() -> RelatedTopicReuseHoldoutBundle.verifyAfterRanking(
				objectMapper,
				files.directory(),
				completeRanking(
						verified, ignored -> observation)))
				.isInstanceOf(RelatedTopicReuseHoldoutBundle.VerificationException.class)
				.hasMessage("HOLDOUT_RANKING_SEAL_SCOPE_INVALID");
	}

	private static RelatedTopicReuseHoldoutRankingSnapshot.Observation
			nonEmptyRankingObservation(RelatedTopicReuseHoldoutBundle.VerifiedCorpus verified) {
		RelatedTopicReuseHoldoutBundle.RankingCorpus corpus = verified.rankingCorpus();
		String feedbackKey = corpus.corpus().candidates().get(10).key();
		var run = rankedRun(corpus, feedbackKey);
		List<RelatedTopicReuseHoldoutRankingSnapshot.QueryRanking> queries =
				corpus.corpus().queries().stream()
						.map(query -> new RelatedTopicReuseHoldoutRankingSnapshot.QueryRanking(
								query.key(), run, run, hiddenFor(run)))
						.toList();
		return new RelatedTopicReuseHoldoutRankingSnapshot.Observation(
				RelatedTopicReuseHoldoutPolicy.CANDIDATE_FREEZE_REVISION,
				10,
				queries.stream()
						.map(RelatedTopicReuseHoldoutRankingSnapshot.QueryRanking::queryKey)
						.toList(),
				queries,
				new RelatedTopicReuseHoldoutRankingSnapshot.StructuralCounters(0, 0));
	}

	private static RelatedTopicReuseHoldoutRankingSnapshot.Observation
			passingRankingObservation(RelatedTopicReuseHoldoutBundle.VerifiedCorpus verified) {
		RelatedTopicReuseHoldoutBundle.RankingCorpus corpus = verified.rankingCorpus();
		String firstRelevant = corpus.corpus().candidates().get(0).key();
		String secondRelevant = corpus.corpus().candidates().get(1).key();
		var controlPaper = new RelatedTopicReuseHoldoutRankingSnapshot.RankedPaper(
				firstRelevant, 1.0d);
		var promotedPaper = new RelatedTopicReuseHoldoutRankingSnapshot.RankedPaper(
				secondRelevant, 0.5d);
		var opportunityControl = List.of(controlPaper);
		var opportunityFeedback = new RelatedTopicReuseHoldoutRankingSnapshot.FeedbackPool(
				firstRelevant, List.of(promotedPaper));
		var opportunityRun = new RelatedTopicReuseHoldoutRankingSnapshot.RankingRun(
				opportunityControl,
				opportunityControl,
				List.of(firstRelevant),
				List.of(opportunityFeedback),
				List.of(
						new RelatedTopicReuseHoldoutRankingSnapshot.RankedPaper(
								firstRelevant, 2.0d),
						promotedPaper));
		var authorRun = new RelatedTopicReuseHoldoutRankingSnapshot.RankingRun(
				List.of(controlPaper),
				List.of(controlPaper),
				List.of(),
				List.of(),
				List.of(new RelatedTopicReuseHoldoutRankingSnapshot.RankedPaper(
						firstRelevant, 1.0d / 61.0d)));
		var emptyRun = new RelatedTopicReuseHoldoutRankingSnapshot.RankingRun(
				List.of(), List.of(), List.of(), List.of(), List.of());
		List<RelatedTopicReuseHoldoutRankingSnapshot.QueryRanking> queries =
				new ArrayList<>();
		for (int index = 0; index < corpus.corpus().queries().size(); index++) {
			var query = corpus.corpus().queries().get(index);
			var run = index <= 3 ? opportunityRun : index <= 6 ? authorRun : emptyRun;
			queries.add(new RelatedTopicReuseHoldoutRankingSnapshot.QueryRanking(
					query.key(), run, run, hiddenFor(run)));
		}
		return new RelatedTopicReuseHoldoutRankingSnapshot.Observation(
				RelatedTopicReuseHoldoutPolicy.CANDIDATE_FREEZE_REVISION,
				10,
				queries.stream()
						.map(RelatedTopicReuseHoldoutRankingSnapshot.QueryRanking::queryKey)
						.toList(),
				queries,
				new RelatedTopicReuseHoldoutRankingSnapshot.StructuralCounters(0, 0));
	}

	private static RelatedTopicReuseHoldoutRankingSnapshot.Observation replaceFirstQuery(
			RelatedTopicReuseHoldoutRankingSnapshot.Observation observation,
			RelatedTopicReuseHoldoutRankingSnapshot.QueryRanking replacement) {
		return replaceQuery(observation, 0, replacement);
	}

	private static RelatedTopicReuseHoldoutRankingSnapshot.Observation replaceQuery(
			RelatedTopicReuseHoldoutRankingSnapshot.Observation observation,
			int queryIndex,
			RelatedTopicReuseHoldoutRankingSnapshot.QueryRanking replacement) {
		List<RelatedTopicReuseHoldoutRankingSnapshot.QueryRanking> queries =
				new ArrayList<>(observation.queries());
		queries.set(queryIndex, replacement);
		return new RelatedTopicReuseHoldoutRankingSnapshot.Observation(
				observation.candidateRevision(),
				observation.cutoff(),
				observation.queryOrder(),
				queries,
				observation.counters());
	}

	private RelatedTopicReuseHoldoutScoringResult score(
			BundleFiles files,
			RelatedTopicReuseHoldoutRankingSnapshot.Observation observation)
			throws Exception {
		return RelatedTopicReuseHoldoutScorer.score(
				verifiedScoringInputs(files, observation)).result();
	}

	private RelatedTopicReuseHoldoutBundle.VerifiedScoringInputs verifiedScoringInputs(
			BundleFiles files,
			RelatedTopicReuseHoldoutRankingSnapshot.Observation observation)
			throws Exception {
		RelatedTopicReuseHoldoutBundle.VerifiedCorpus freshVerification =
				RelatedTopicReuseHoldoutBundle.verifyCorpus(objectMapper, files.directory());
		return RelatedTopicReuseHoldoutBundle.verifyAfterRanking(
				objectMapper,
				files.directory(),
				completeRanking(
						freshVerification, ignored -> observation));
	}

	private static void assertGate(
			RelatedTopicReuseHoldoutScoringResult result,
			RelatedTopicReuseHoldoutScoringResult.GateId gate,
			boolean passed) {
		assertThat(result.gates())
				.filteredOn(outcome -> outcome.gate() == gate)
				.singleElement()
				.satisfies(outcome -> assertThat(outcome.passed()).isEqualTo(passed));
	}

	private static RelatedTopicReuseHoldoutRankingSnapshot.RankingRun
			withFirstControlScore(
					RelatedTopicReuseHoldoutRankingSnapshot.RankingRun run,
					double score) {
		List<RelatedTopicReuseHoldoutRankingSnapshot.RankedPaper> control =
				new ArrayList<>(run.controlPool());
		control.set(0, new RelatedTopicReuseHoldoutRankingSnapshot.RankedPaper(
				control.getFirst().paperKey(), score));
		return new RelatedTopicReuseHoldoutRankingSnapshot.RankingRun(
				control,
				control.stream().limit(10).toList(),
				run.eligibleSeedKeys(),
				run.feedbackPools(),
				run.candidateTop10());
	}

	private static RelatedTopicReuseHoldoutRankingSnapshot.RankingRun threePaperRun(
			String seedKey, String promotedKey, String inspectedKey) {
		var seed = new RelatedTopicReuseHoldoutRankingSnapshot.RankedPaper(seedKey, 1.0d);
		var inspected = new RelatedTopicReuseHoldoutRankingSnapshot.RankedPaper(
				inspectedKey, 0.25d);
		var promoted = new RelatedTopicReuseHoldoutRankingSnapshot.RankedPaper(
				promotedKey, 0.5d);
		var feedback = new RelatedTopicReuseHoldoutRankingSnapshot.FeedbackPool(
				seedKey, List.of(promoted, inspected));
		return new RelatedTopicReuseHoldoutRankingSnapshot.RankingRun(
				List.of(seed, inspected),
				List.of(seed, inspected),
				List.of(seedKey),
				List.of(feedback),
				List.of(seed, promoted, inspected));
	}

	private static RelatedTopicReuseHoldoutRankingSnapshot.RankingRun rankedRun(
			RelatedTopicReuseHoldoutBundle.RankingCorpus corpus,
			String feedbackCandidateKey) {
		List<RelatedTopicReuseHoldoutRankingSnapshot.RankedPaper> control =
				controlTen(corpus);
		String seed = control.getFirst().paperKey();
		var feedback = new RelatedTopicReuseHoldoutRankingSnapshot.FeedbackPool(
				seed,
				List.of(new RelatedTopicReuseHoldoutRankingSnapshot.RankedPaper(
						feedbackCandidateKey, 0.5d)));
		return new RelatedTopicReuseHoldoutRankingSnapshot.RankingRun(
				control,
				control,
				List.of(seed),
				List.of(feedback),
				control);
	}

	private static List<RelatedTopicReuseHoldoutRankingSnapshot.RankedPaper> controlTen(
			RelatedTopicReuseHoldoutBundle.RankingCorpus corpus) {
		List<RelatedTopicReuseHoldoutRankingSnapshot.RankedPaper> control =
				new ArrayList<>();
		for (int index = 0; index < 10; index++) {
			control.add(new RelatedTopicReuseHoldoutRankingSnapshot.RankedPaper(
					corpus.corpus().candidates().get(index).key(), 100.0d - index));
		}
		return List.copyOf(control);
	}

	private static RelatedTopicReuseHoldoutRankingSnapshot.HiddenPerturbation hiddenFor(
			RelatedTopicReuseHoldoutRankingSnapshot.RankingRun run) {
		return new RelatedTopicReuseHoldoutRankingSnapshot.HiddenPerturbation(
				"hidden-other-owner",
				"hidden-catalog-only",
				run.feedbackPools(),
				run.candidateTop10());
	}

	private static RelatedTopicReuseHoldoutRankingSnapshot.Observation
			emptyRankingObservation(RelatedTopicReuseHoldoutBundle.VerifiedCorpus verified) {
		RelatedTopicReuseHoldoutBundle.RankingCorpus rankingCorpus =
				verified.rankingCorpus();
		var emptyRun = new RelatedTopicReuseHoldoutRankingSnapshot.RankingRun(
				List.of(), List.of(), List.of(), List.of(), List.of());
		List<RelatedTopicReuseHoldoutRankingSnapshot.QueryRanking> queries =
				rankingCorpus.corpus().queries().stream()
						.map(query -> new RelatedTopicReuseHoldoutRankingSnapshot.QueryRanking(
								query.key(),
								emptyRun,
								emptyRun,
								new RelatedTopicReuseHoldoutRankingSnapshot.HiddenPerturbation(
										"hidden-other-owner",
										"hidden-catalog-only",
										List.of(),
										List.of())))
						.toList();
		return new RelatedTopicReuseHoldoutRankingSnapshot.Observation(
				RelatedTopicReuseHoldoutPolicy.CANDIDATE_FREEZE_REVISION,
				10,
				rankingCorpus.corpus().queries().stream()
						.map(RelatedTopicReuseHoldoutBundle.Query::key)
						.toList(),
				queries,
				new RelatedTopicReuseHoldoutRankingSnapshot.StructuralCounters(0, 0));
	}

	private static Path findRepositoryRoot() throws IOException {
		Path candidate = Path.of("").toAbsolutePath().normalize();
		while (candidate != null && !Files.exists(candidate.resolve(".git"))) {
			candidate = candidate.getParent();
		}
		if (candidate == null) {
			throw new IOException("repository root is unavailable");
		}
		return candidate.toRealPath();
	}

	private static ObjectNode lineage(ObjectNode corpus, int index) {
		return (ObjectNode) corpus.required("lineages").get(index);
	}

	private static ObjectNode candidate(ObjectNode corpus, int index) {
		return (ObjectNode) corpus.required("candidates").get(index);
	}

	private static ObjectNode query(ObjectNode corpus, int index) {
		return (ObjectNode) corpus.required("queries").get(index);
	}

	private static ObjectNode queryJudgments(ObjectNode judgments, int index) {
		return (ObjectNode) judgments.required("queries").get(index);
	}

	private static ObjectNode grades(ObjectNode judgments, int index) {
		return (ObjectNode) queryJudgments(judgments, index).required("grades");
	}

	private static ArrayNode adversaries(ObjectNode judgments, int index) {
		return (ArrayNode) queryJudgments(judgments, index).required("adversaries");
	}

	private static ObjectNode manifestFile(ObjectNode manifest, int index) {
		return (ObjectNode) manifest.required("files").get(index);
	}

	private static int publicationYear(int candidateIndex) {
		return candidateIndex == 10 ? 2010 : candidateIndex == 11 ? 2030 : 2020;
	}

	private static String lineageKey(int candidateIndex) {
		if (candidateIndex <= 15) {
			return TARGET_SEARCH;
		}
		if (candidateIndex <= 30) {
			return TARGET_COLLECTION;
		}
		if (candidateIndex <= 33) {
			return OTHER_SEARCH;
		}
		if (candidateIndex <= 35) {
			return OTHER_COLLECTION;
		}
		return CATALOG;
	}

	private static String queryKind(int queryIndex) {
		if (queryIndex <= 3) {
			return "LEXICAL_BRIDGE_OPPORTUNITY";
		}
		if (queryIndex == 4) {
			return "FILTERED_LEXICAL_BRIDGE_OPPORTUNITY";
		}
		if (queryIndex <= 7) {
			return "AUTHOR_NO_RELATED_SIGNAL_CONTROL";
		}
		return "NO_SEED_FALLBACK_CONTROL";
	}

	private static String candidateKey(int index) {
		return "holdout-candidate-" + index;
	}

	private static String queryKey(int index) {
		return "holdout-query-" + index;
	}

	private static String sha256(byte[] bytes) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
		}
		catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException("SHA-256 is unavailable", exception);
		}
	}

	private static RelatedTopicReuseHoldoutEvaluatorSeal.VerifiedEvaluatorSeal
			syntheticEvaluatorSeal(String candidateRevision) {
		String evaluatorRevision = "e".repeat(40);
		List<RelatedTopicReuseHoldoutEvaluatorSeal.SourceFile> evaluatorSources = List.of(
				new RelatedTopicReuseHoldoutEvaluatorSeal.SourceFile(
						100644,
						"backend/src/test/java/HoldoutEvaluator.java",
						"synthetic evaluator source\n".getBytes(StandardCharsets.UTF_8)));
		List<RelatedTopicReuseHoldoutEvaluatorSeal.SourceFile> candidateSources = List.of(
				new RelatedTopicReuseHoldoutEvaluatorSeal.SourceFile(
						100644,
						"backend/src/main/java/FrozenCandidate.java",
						"synthetic candidate source\n".getBytes(StandardCharsets.UTF_8)));
		String evaluatorSha256 = RelatedTopicReuseHoldoutEvaluatorSeal.sourceSha256(
				RelatedTopicReuseHoldoutEvaluatorSeal.SourceRole.EVALUATOR,
				evaluatorRevision,
				evaluatorSources);
		String candidateSha256 = RelatedTopicReuseHoldoutEvaluatorSeal.sourceSha256(
				RelatedTopicReuseHoldoutEvaluatorSeal.SourceRole.CANDIDATE,
				candidateRevision,
				candidateSources);
		return RelatedTopicReuseHoldoutEvaluatorSeal.verify(
				evaluatorRevision,
				evaluatorSha256,
				candidateRevision,
				candidateSha256,
				new RelatedTopicReuseHoldoutEvaluatorSeal.RepositoryState(
						evaluatorRevision,
						"",
						candidateRevision,
						candidateSha256,
						true),
				evaluatorSources,
				candidateSources);
	}

	private final class BundleFiles {

		private final Path directory;
		private final String bundleId;
		private final String corpusId;
		private final ObjectNode corpus;
		private final ObjectNode judgments;
		private ObjectNode manifest;

		private BundleFiles(
				Path directory,
				String bundleId,
				String corpusId,
				ObjectNode corpus,
				ObjectNode judgments) {
			this.directory = directory;
			this.bundleId = bundleId;
			this.corpusId = corpusId;
			this.corpus = corpus;
			this.judgments = judgments;
		}

		private void writePayloadsAndManifest() throws IOException {
			Files.write(corpusFile(), objectMapper.writeValueAsBytes(corpus));
			Files.write(judgmentsFile(), objectMapper.writeValueAsBytes(judgments));
			rebuildManifestFromPayloads();
		}

		private void replacePayload(String filename, byte[] bytes) throws IOException {
			Files.write(directory.resolve(filename), bytes);
			rebuildManifestFromPayloads();
		}

		private void replacePayloadWithoutManifest(String filename, byte[] bytes)
				throws IOException {
			Files.write(directory.resolve(filename), bytes);
		}

		private void rebuildManifestFromPayloads() throws IOException {
			byte[] corpusBytes = Files.readAllBytes(corpusFile());
			byte[] judgmentBytes = Files.readAllBytes(judgmentsFile());
			manifest = objectMapper.createObjectNode();
			manifest.put("schemaVersion", 1);
			manifest.put("protocolId", boundPolicy.policy().bundle().protocolId());
			manifest.put("bundleId", bundleId);
			manifest.put("policyId", boundPolicy.policy().policyId());
			manifest.put("policySha256", boundPolicy.sha256());
			manifest.put("corpusId", corpusId);
			manifest.put("payloadBytes", corpusBytes.length + judgmentBytes.length);
			ArrayNode files = manifest.putArray("files");
			addManifestFile(files, CORPUS_FILENAME, corpusBytes);
			addManifestFile(files, JUDGMENTS_FILENAME, judgmentBytes);
			addDeclarations(manifest.putObject("declarations"));
			writeManifest();
		}

		private void addDeclarations(ObjectNode declarations) {
			var required = boundPolicy.policy().requiredDeclarations();
			declarations.put("corpusAuthorship", required.corpusAuthorship());
			declarations.put("judgmentAuthorship", required.judgmentAuthorship());
			declarations.put("firstRunRule", required.firstRunRule());
			declarations.put("noRetuningRule", required.noRetuningRule());
			declarations.put("externalCustodyRule", required.externalCustodyRule());
			declarations.put("evaluatorFreezeRule", required.evaluatorFreezeRule());
			ArrayNode limitations = declarations.putArray("limitations");
			required.requiredLimitations().forEach(limitations::add);
		}

		private void writeManifest() throws IOException {
			Files.write(manifestFile(), objectMapper.writeValueAsBytes(manifest));
		}

		private Path directory() {
			return directory;
		}

		private String bundleId() {
			return bundleId;
		}

		private String corpusId() {
			return corpusId;
		}

		private ObjectNode corpus() {
			return corpus;
		}

		private ObjectNode judgments() {
			return judgments;
		}

		private ObjectNode manifest() {
			return manifest;
		}

		private Path corpusFile() {
			return directory.resolve(CORPUS_FILENAME);
		}

		private Path judgmentsFile() {
			return directory.resolve(JUDGMENTS_FILENAME);
		}

		private Path manifestFile() {
			return directory.resolve(MANIFEST_FILENAME);
		}
	}

	private static void addManifestFile(ArrayNode files, String filename, byte[] bytes) {
		files.addObject()
				.put("filename", filename)
				.put("bytes", bytes.length)
				.put("sha256", sha256(bytes));
	}

}
