package com.openscholar.search.internal.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import com.openscholar.provider.ProviderId;
import com.openscholar.search.internal.persistence.ProviderQualityLiveQuerySet.BoundQuerySet;
import com.openscholar.search.internal.persistence.ProviderQualityComparativeScorer.QueryScenarioScore;
import com.openscholar.search.internal.persistence.ProviderQualityComparativeScorer.QueryScore;
import com.openscholar.search.internal.persistence.ProviderQualityComparativeScorer.DeduplicationScore;
import com.openscholar.search.internal.persistence.ProviderQualityComparativeScorer.ScoringResult;
import com.openscholar.search.internal.persistence.ProviderQualityComparativeScoringPolicy.BoundPolicy;
import com.openscholar.search.internal.persistence.ProviderQualityComparativeScoringPolicy.Scenario;
import com.openscholar.search.internal.persistence.ProviderQualityMetrics.MetadataField;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectWriter;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * End-to-end scorer contract using synthetic evidence and synthetic judgments only.
 */
class ProviderQualityComparativeScorerTests {

	private static final JsonMapper OBJECT_MAPPER = JsonMapper.builder().build();
	private static final ObjectWriter CANONICAL_WRITER = OBJECT_MAPPER.writer()
			.with(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
	private static final long MAXIMUM_EVIDENCE_BYTES = 64L * 1024L * 1024L;
	private static final String QUERY_SET_ID = "synthetic-comparative-queries-v1";
	private static final String QUERY_SET_SHA256 = "9".repeat(64);
	private static final String REVIEW_PACKET_SHA256 = "8".repeat(64);

	private static final CandidateSpec ALPHA_OPENALEX_SHARED = new CandidateSpec(
			reviewKey("query-alpha", "OPENALEX", "OA-ALPHA-SHARED"),
			"query-alpha", "OPENALEX", "OA-ALPHA-SHARED", 1);
	private static final CandidateSpec ALPHA_EUROPE_SHARED = new CandidateSpec(
			reviewKey("query-alpha", "EUROPE_PMC", "EP-ALPHA-SHARED"),
			"query-alpha", "EUROPE_PMC", "EP-ALPHA-SHARED", 1);
	private static final CandidateSpec ALPHA_OPENALEX_FALSE_MERGE = new CandidateSpec(
			reviewKey("query-alpha", "OPENALEX", "OA-ALPHA-DISTINCT"),
			"query-alpha", "OPENALEX", "OA-ALPHA-DISTINCT", 2);
	private static final CandidateSpec ALPHA_EUROPE_ONLY = new CandidateSpec(
			reviewKey("query-alpha", "EUROPE_PMC", "EP-ALPHA-UNIQUE"),
			"query-alpha", "EUROPE_PMC", "EP-ALPHA-UNIQUE", 2);
	private static final CandidateSpec BETA_OPENALEX_RELEVANT = new CandidateSpec(
			reviewKey("query-beta", "OPENALEX", "OA-BETA-RELEVANT"),
			"query-beta", "OPENALEX", "OA-BETA-RELEVANT", 1);
	private static final CandidateSpec BETA_EUROPE_NEGATIVE = new CandidateSpec(
			reviewKey("query-beta", "EUROPE_PMC", "EP-BETA-NEGATIVE"),
			"query-beta", "EUROPE_PMC", "EP-BETA-NEGATIVE", 1);
	private static final List<CandidateSpec> ALL_CANDIDATES = List.of(
			ALPHA_OPENALEX_SHARED,
			ALPHA_EUROPE_SHARED,
			ALPHA_OPENALEX_FALSE_MERGE,
			ALPHA_EUROPE_ONLY,
			BETA_OPENALEX_RELEVANT,
			BETA_EUROPE_NEGATIVE);

	@TempDir
	private Path temporaryDirectory;

	@Test
	void scoresClusterCreditRankingMetadataCoverageAndMustSeparateSemantics()
			throws Exception {
		ScoringFixture fixture = readyFixture("synthetic-comparative-score-main");
		ScoringResult result = ProviderQualityComparativeScorer.score(
				fixture.bundle(), fixture.judgments(), fixture.policy(), REVIEW_PACKET_SHA256);
		Map<String, QueryScore> queries = queriesByKey(result);

		assertThat(result.queryCount()).isEqualTo(2);
		assertThat(queries).containsOnlyKeys("query-alpha", "query-beta");

		QueryScenarioScore fusedAlpha = queries.get("query-alpha")
				.scenarios().get(Scenario.FUSED);
		assertThat(fusedAlpha.rankedResultCount()).isEqualTo(3);
		assertThat(fusedAlpha.creditedGoldWorkCount()).isEqualTo(2);
		assertThat(fusedAlpha.ranking().recall()).isEqualTo(2.0d / 3.0d);
		assertThat(fusedAlpha.ranking().precision()).isEqualTo(2.0d / 5.0d);
		assertThat(fusedAlpha.ranking().reciprocalRank()).isEqualTo(1.0d);
		assertThat(fusedAlpha.ranking().ndcg()).isCloseTo(
				(discountedGain(3, 0) + discountedGain(1, 2))
						/ (discountedGain(3, 0)
								+ discountedGain(2, 1)
								+ discountedGain(1, 2)),
				within(1.0e-12d));

		// Rank one falsely merges two gold works and rank two is a split copy of
		// the already-credited work. Together they can still earn only one credit.
		assertThat(fusedAlpha.deduplication()).satisfies(value -> {
			assertThat(value.truePositives()).isZero();
			assertThat(value.falsePositives()).isEqualTo(1);
			assertThat(value.falseNegatives()).isEqualTo(1);
			assertThat(value.trueNegatives()).isEqualTo(4);
		});
		assertThat(fusedAlpha.mustSeparate().applicablePairs()).isEqualTo(1);
		assertThat(fusedAlpha.mustSeparate().violations()).isEqualTo(1);
		assertThat(fusedAlpha.mustSeparate().passRate()).isZero();

		assertThat(fusedAlpha.metadataRecovery().creditedGoldWorks()).isEqualTo(2);
		assertThat(fusedAlpha.metadataRecovery().goldWorksWithExpectations()).isEqualTo(2);
		assertThat(fusedAlpha.metadataRecovery().expectedFieldCount()).isEqualTo(4);
		assertThat(fusedAlpha.metadataRecovery().recoveredFieldCount()).isEqualTo(3);
		assertThat(fusedAlpha.metadataRecovery().recoveryRate()).isEqualTo(0.75d);
		assertThat(fusedAlpha.metadataRecovery().fields().get(MetadataField.ABSTRACT))
				.satisfies(field -> {
					assertThat(field.expectedCount()).isEqualTo(1);
					assertThat(field.recoveredCount()).isEqualTo(1);
				});
		assertThat(fusedAlpha.metadataRecovery().fields().get(MetadataField.PMID))
				.satisfies(field -> {
					assertThat(field.expectedCount()).isEqualTo(1);
					assertThat(field.recoveredCount()).isZero();
				});

		QueryScenarioScore openAlexAlpha = queries.get("query-alpha")
				.scenarios().get(Scenario.OPENALEX_ONLY);
		assertThat(openAlexAlpha.rankedResultCount()).isEqualTo(2);
		assertThat(openAlexAlpha.creditedGoldWorkCount()).isEqualTo(2);
		assertThat(openAlexAlpha.ranking().recall()).isEqualTo(2.0d / 3.0d);
		assertThat(openAlexAlpha.ranking().precision()).isEqualTo(0.4d);
		assertThat(openAlexAlpha.ranking().reciprocalRank()).isEqualTo(1.0d);

		assertThat(result.uniqueRelevantQueryCoverage()).satisfies(coverage -> {
			assertThat(coverage.coveredQueries()).isEqualTo(1);
			assertThat(coverage.totalQueries()).isEqualTo(2);
			assertThat(coverage.rate()).isEqualTo(0.5d);
			assertThat(coverage.queryKeys()).containsExactly("query-alpha");
		});
	}

	@Test
	void aggregatesPairwiseCountsPerQueryAndProducesDeterministicReports()
			throws Exception {
		ScoringFixture fixture = readyFixture("synthetic-comparative-score-stable");
		ScoringResult first = ProviderQualityComparativeScorer.score(
				fixture.bundle(), fixture.judgments(), fixture.policy(), REVIEW_PACKET_SHA256);
		ScoringResult second = ProviderQualityComparativeScorer.score(
				fixture.bundle(), fixture.judgments(), fixture.policy(), REVIEW_PACKET_SHA256);

		DeduplicationScore fused = first.scenarios().get(Scenario.FUSED).deduplication();
		assertThat(fused.truePositives()).isZero();
		assertThat(fused.falsePositives()).isEqualTo(1);
		assertThat(fused.falseNegatives()).isEqualTo(1);
		assertThat(fused.trueNegatives()).isEqualTo(5);
		assertThat(totalPairs(fused)).isEqualTo(7);
		assertThat(totalPairs(first.scenarios().get(Scenario.OPENALEX_ONLY).deduplication()))
				.isEqualTo(1);
		assertThat(totalPairs(first.scenarios().get(Scenario.EUROPE_PMC_ONLY).deduplication()))
				.isEqualTo(1);

		assertThat(first.scenarios().get(Scenario.FUSED).mustSeparate()).satisfies(value -> {
			assertThat(value.applicablePairs()).isEqualTo(2);
			assertThat(value.violations()).isEqualTo(1);
			assertThat(value.passRate()).isEqualTo(0.5d);
		});
		assertThat(first.scenarios().get(Scenario.FUSED).metadataRecovery())
				.satisfies(value -> {
					assertThat(value.creditedGoldWorks()).isEqualTo(4);
					assertThat(value.goldWorksWithExpectations()).isEqualTo(3);
					assertThat(value.expectedFieldCount()).isEqualTo(6);
					assertThat(value.recoveredFieldCount()).isEqualTo(5);
					assertThat(value.recoveryRate()).isEqualTo(5.0d / 6.0d);
				});
		assertThat(first.scenarios().get(Scenario.FUSED).ranking()).satisfies(value -> {
			assertThat(value.macroRecall()).isCloseTo(5.0d / 6.0d, within(1.0e-12d));
			assertThat(value.macroPrecision()).isCloseTo(0.3d, within(1.0e-12d));
			assertThat(value.meanReciprocalRank()).isEqualTo(1.0d);
		});

		Map<String, Object> firstArtifacts = ProviderQualityComparativeScorer.artifacts(first);
		Map<String, Object> secondArtifacts = ProviderQualityComparativeScorer.artifacts(second);
		assertThat(first).isEqualTo(second);
		assertThat(first.reportId())
				.startsWith("provider-comparative-score-")
				.hasSize("provider-comparative-score-".length() + 64);
		assertThat(first.readerFacing()).isFalse();
		assertThat(first.defaultEnablementDecision()).isFalse();
		assertThat(first.reviewPacketSha256()).isEqualTo(REVIEW_PACKET_SHA256);
		assertThat(firstArtifacts).containsOnlyKeys("query-scores.json", "score-summary.json");
		assertThat(firstArtifacts).isEqualTo(secondArtifacts);
		assertThat(CANONICAL_WRITER.writeValueAsBytes(firstArtifacts))
				.isEqualTo(CANONICAL_WRITER.writeValueAsBytes(secondArtifacts));
		assertThat(firstArtifacts.toString())
				.doesNotContain("alpha-shared-work", "beta-openalex-relevant");
		JsonNode scoreSummary = OBJECT_MAPPER.valueToTree(
				firstArtifacts.get("score-summary.json"));
		assertThat(scoreSummary.required("judgments").required("reviewPacketSha256").asString())
				.isEqualTo(REVIEW_PACKET_SHA256);
		ProviderQualityEvidenceWriter.WriteResult written =
				ProviderQualityEvidenceWriter.forRepository(
						OBJECT_MAPPER, temporaryDirectory, 8L * 1024L * 1024L)
						.write(first.reportId(), firstArtifacts);
		assertThat(written.manifest().files()).hasSize(2);
	}

	@Test
	void reportsNoRelevantAndPairlessMeasurementsAsNotApplicable() throws Exception {
		BoundPolicy policy = frozenPolicy();
		ProviderQualityComparativeEvidenceBundle bundle = writeAndVerifyEvidence(
				"synthetic-comparative-score-not-applicable", true, ignored -> { });
		var judgments = boundJudgments(bundle, policy, root ->
				root.required("queries").forEach(query ->
						query.required("goldPapers").forEach(gold ->
								((ObjectNode) gold).put("relevanceGrade", 0))));

		ScoringResult result = ProviderQualityComparativeScorer.score(
				bundle, judgments, policy, REVIEW_PACKET_SHA256);
		QueryScenarioScore betaOpenAlex = queriesByKey(result).get("query-beta")
				.scenarios().get(Scenario.OPENALEX_ONLY);

		assertThat(betaOpenAlex.ranking().relevantGoldWorkCount()).isZero();
		assertThat(betaOpenAlex.ranking().recall()).isNull();
		assertThat(betaOpenAlex.ranking().ndcg()).isNull();
		assertThat(betaOpenAlex.ranking().precision()).isZero();
		assertThat(betaOpenAlex.ranking().reciprocalRank()).isNull();
		assertThat(betaOpenAlex.deduplication().candidateCount()).isEqualTo(1);
		assertThat(betaOpenAlex.deduplication().evaluatedPairCount()).isZero();
		assertThat(betaOpenAlex.deduplication().precision()).isNull();
		assertThat(betaOpenAlex.deduplication().recall()).isNull();
		assertThat(betaOpenAlex.deduplication().f1()).isNull();
		QueryScenarioScore betaFused = queriesByKey(result).get("query-beta")
				.scenarios().get(Scenario.FUSED);
		assertThat(betaFused.deduplication().evaluatedPairCount()).isEqualTo(1);
		assertThat(betaFused.deduplication().trueNegatives()).isEqualTo(1);
		assertThat(betaFused.deduplication().precision()).isNull();
		assertThat(betaFused.deduplication().recall()).isNull();
		assertThat(betaFused.deduplication().f1()).isNull();
		assertThat(result.scenarios().get(Scenario.OPENALEX_ONLY).deduplication())
				.satisfies(summary -> {
					assertThat(summary.evaluatedPairCount()).isEqualTo(1);
					assertThat(summary.trueNegatives()).isEqualTo(1);
					assertThat(summary.precision()).isNull();
					assertThat(summary.recall()).isNull();
					assertThat(summary.f1()).isNull();
				});

		assertThat(result.scenarios().get(Scenario.FUSED).ranking()).satisfies(summary -> {
			assertThat(summary.relevanceApplicableQueryCount()).isZero();
			assertThat(summary.noRelevantGoldQueryCount()).isEqualTo(2);
			assertThat(summary.macroRecall()).isNull();
			assertThat(summary.macroNdcg()).isNull();
			assertThat(summary.macroPrecision()).isZero();
			assertThat(summary.meanReciprocalRank()).isNull();
		});
	}

	@Test
	void retainsSuccessfulEmptyQueriesAsExplicitUndefinedOutcomes() throws Exception {
		BoundPolicy policy = frozenPolicy();
		ProviderQualityComparativeEvidenceBundle bundle = writeAndVerifyEvidence(
				"synthetic-comparative-score-empty", true,
				ProviderQualityComparativeScorerTests::clearAllCandidates);
		var judgments = boundJudgments(bundle, policy, root ->
				root.required("queries").forEach(query -> {
					((ArrayNode) query.required("goldPapers")).removeAll();
					((ArrayNode) query.required("mustSeparatePairs")).removeAll();
				}));

		ScoringResult result = ProviderQualityComparativeScorer.score(
				bundle, judgments, policy, REVIEW_PACKET_SHA256);
		assertThat(result.queries()).allSatisfy(query ->
				assertThat(query.scenarios().values()).allSatisfy(scenario -> {
					assertThat(scenario.rankedResultCount()).isZero();
					assertThat(scenario.ranking().relevantGoldWorkCount()).isZero();
					assertThat(scenario.ranking().recall()).isNull();
					assertThat(scenario.ranking().precision()).isZero();
					assertThat(scenario.deduplication().candidateCount()).isZero();
					assertThat(scenario.deduplication().evaluatedPairCount()).isZero();
					assertThat(scenario.deduplication().f1()).isNull();
				}));
		assertThat(result.uniqueRelevantQueryCoverage().coveredQueries()).isZero();
		assertThat(result.scenarios().get(Scenario.FUSED).ranking()
				.noRelevantGoldQueryCount()).isEqualTo(2);
	}

	@Test
	void rejectsMismatchedManifestQuerySetAndPolicyBindings() throws Exception {
		BoundPolicy policy = frozenPolicy();
		ProviderQualityComparativeEvidenceBundle bundle = writeAndVerifyEvidence(
				"synthetic-comparative-score-bindings", true, ignored -> { });

		var manifestMismatch = boundJudgments(bundle, policy,
				root -> root.put("evidenceManifestSha256", "0".repeat(64)));
		assertThatThrownBy(() -> ProviderQualityComparativeScorer.score(
				bundle, manifestMismatch, policy, REVIEW_PACKET_SHA256))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessage("judgment evidence manifest SHA-256 does not match");

		var querySetMismatch = boundJudgments(bundle, policy,
				root -> root.put("querySetSha256", "0".repeat(64)));
		assertThatThrownBy(() -> ProviderQualityComparativeScorer.score(
				bundle, querySetMismatch, policy, REVIEW_PACKET_SHA256))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessage("judgment query-set reference does not match the evidence");

		var policyMismatch = boundJudgments(bundle, policy,
				root -> root.put("scoringPolicySha256", "0".repeat(64)));
		assertThatThrownBy(() -> ProviderQualityComparativeScorer.score(
				bundle, policyMismatch, policy, REVIEW_PACKET_SHA256))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessage("referenced scoring policy SHA-256 does not match");

		var reviewPacketMismatch = boundJudgments(bundle, policy,
				root -> root.put("reviewPacketSha256", "0".repeat(64)));
		assertThatThrownBy(() -> ProviderQualityComparativeScorer.score(
				bundle, reviewPacketMismatch, policy, REVIEW_PACKET_SHA256))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessage(
						"judgment review-packet SHA-256 does not match the verified packet");
	}

	@Test
	void rejectsIncompleteAndStructurallyInconsistentVerifiedEvidence() throws Exception {
		BoundPolicy policy = frozenPolicy();
		ProviderQualityComparativeEvidenceBundle incomplete = writeAndVerifyEvidence(
				"synthetic-comparative-score-incomplete", false, ignored -> { });
		var incompleteJudgments = boundJudgments(incomplete, policy, ignored -> { });

		assertThat(incomplete.reviewReady()).isFalse();
		assertThatThrownBy(() -> ProviderQualityComparativeScorer.score(
				incomplete, incompleteJudgments, policy, REVIEW_PACKET_SHA256))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessage("comparative evidence is not review-ready");

		ProviderQualityComparativeEvidenceBundle inconsistent = writeAndVerifyEvidence(
				"synthetic-comparative-score-structure",
				true,
				documents -> ((ObjectNode) documents.summary()
						.required("queries").get(0)).put("rawCandidateCount", 3));
		var inconsistentJudgments = boundJudgments(inconsistent, policy, ignored -> { });

		assertThat(inconsistent.reviewReady()).isTrue();
		assertThatThrownBy(() -> ProviderQualityComparativeScorer.score(
				inconsistent, inconsistentJudgments, policy, REVIEW_PACKET_SHA256))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessage("summary candidate count does not match provenance");

		ProviderQualityComparativeEvidenceBundle rebound = writeAndVerifyEvidence(
				"synthetic-comparative-score-rebound",
				true,
				documents -> ((ObjectNode) documents.blinded()
						.required("candidates").get(0)).put("title", "A different synthetic work"));
		var reboundJudgments = boundJudgments(rebound, policy, ignored -> { });
		assertThatThrownBy(() -> ProviderQualityComparativeScorer.score(
				rebound, reboundJudgments, policy, REVIEW_PACKET_SHA256))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessage("blinded and provenance reviewer projections do not match");

		ProviderQualityComparativeEvidenceBundle countMismatch = writeAndVerifyEvidence(
				"synthetic-comparative-score-counts",
				true,
				documents -> ((ObjectNode) documents.summary()
						.required("queries").get(0)
						.required("scenarioResultCounts")).put("FUSED", 4));
		var countMismatchJudgments = boundJudgments(countMismatch, policy, ignored -> { });
		assertThatThrownBy(() -> ProviderQualityComparativeScorer.score(
				countMismatch, countMismatchJudgments, policy, REVIEW_PACKET_SHA256))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessage("summary scenario result count does not match reconciliation");

		ProviderQualityComparativeEvidenceBundle orderedLeak = writeAndVerifyEvidence(
				"synthetic-comparative-score-order",
				true,
				documents -> {
					ArrayNode candidates = (ArrayNode) documents.blinded().required("candidates");
					JsonNode first = candidates.get(0).deepCopy();
					JsonNode second = candidates.get(1).deepCopy();
					candidates.set(0, second);
					candidates.set(1, first);
				});
		var orderedLeakJudgments = boundJudgments(orderedLeak, policy, ignored -> { });
		assertThatThrownBy(() -> ProviderQualityComparativeScorer.score(
				orderedLeak, orderedLeakJudgments, policy, REVIEW_PACKET_SHA256))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("blinded candidates do not follow");

		ProviderQualityComparativeEvidenceBundle malformedProvenance = writeAndVerifyEvidence(
				"synthetic-comparative-score-provenance",
				true,
				documents -> ((ObjectNode) documents.provenance()
						.required("candidates").get(0)).put("citationCount", -1));
		var malformedProvenanceJudgments = boundJudgments(
				malformedProvenance, policy, ignored -> { });
		assertThatThrownBy(() -> ProviderQualityComparativeScorer.score(
				malformedProvenance, malformedProvenanceJudgments, policy,
				REVIEW_PACKET_SHA256))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("citationCount");

		ProviderQualityComparativeEvidenceBundle forgedReviewKey = writeAndVerifyEvidence(
				"synthetic-comparative-score-review-key",
				true,
				documents -> ((ObjectNode) documents.provenance()
						.required("candidates").get(0)).put("reviewKey", hex(999)));
		var forgedReviewKeyJudgments = boundJudgments(
				forgedReviewKey, policy, ignored -> { });
		assertThatThrownBy(() -> ProviderQualityComparativeScorer.score(
				forgedReviewKey, forgedReviewKeyJudgments, policy,
				REVIEW_PACKET_SHA256))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessage("provenance review key does not match its deterministic identity");
	}

	@Test
	void preflightsFullScorerEvidenceAgainstExactFrozenReviewInputs() throws Exception {
		BoundQuerySet querySet = ProviderQualityLiveQuerySet.loadFrozen(OBJECT_MAPPER);
		BoundPolicy policy = frozenPolicy();
		ProviderQualityComparativeEvidenceBundle ready = writeAndVerifyEvidence(
				"synthetic-comparative-review-preflight",
				true,
				documents -> bindEmptyEvidenceToFrozenQuerySet(documents, querySet));

		ProviderQualityComparativeScorer.preflightForReview(
				OBJECT_MAPPER, ready, querySet, policy);

		BoundQuerySet reboundQuerySet = new BoundQuerySet(
				querySet.querySet(), "0".repeat(64));
		assertThatThrownBy(() -> ProviderQualityComparativeScorer.preflightForReview(
				OBJECT_MAPPER, ready, reboundQuerySet, policy))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessage("review preflight requires the exact frozen query set");

		BoundPolicy reboundPolicy = new BoundPolicy(policy.policy(), "0".repeat(64));
		assertThatThrownBy(() -> ProviderQualityComparativeScorer.preflightForReview(
				OBJECT_MAPPER, ready, querySet, reboundPolicy))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessage("review preflight requires the exact frozen scoring policy");

		ProviderQualityComparativeEvidenceBundle scorerIncompatible = writeAndVerifyEvidence(
				"synthetic-comparative-review-preflight-invalid",
				true,
				documents -> {
					bindEmptyEvidenceToFrozenQuerySet(documents, querySet);
					((ObjectNode) documents.summary().required("queries").get(0))
							.put("rawCandidateCount", 1);
				});
		assertThatThrownBy(() -> ProviderQualityComparativeScorer.preflightForReview(
				OBJECT_MAPPER, scorerIncompatible, querySet, policy))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessage("summary candidate count does not match provenance");
	}

	private ScoringFixture readyFixture(String evidenceId) throws Exception {
		BoundPolicy policy = frozenPolicy();
		ProviderQualityComparativeEvidenceBundle bundle = writeAndVerifyEvidence(
				evidenceId, true, ignored -> { });
		return new ScoringFixture(bundle, boundJudgments(bundle, policy, ignored -> { }), policy);
	}

	private static BoundPolicy frozenPolicy() throws Exception {
		BoundPolicy policy = ProviderQualityComparativeScoringPolicy.loadBound(
				OBJECT_MAPPER, ProviderQualityComparativeScoringPolicy.RESOURCE_PATH);
		policy.validateReference(
				ProviderQualityComparativeScoringPolicy.POLICY_ID,
				ProviderQualityComparativeScoringPolicy.POLICY_SHA256);
		return policy;
	}

	private ProviderQualityComparativeEvidenceBundle writeAndVerifyEvidence(
			String evidenceId,
			boolean eligible,
			Consumer<EvidenceDocuments> mutation) throws Exception {
		EvidenceDocuments documents = evidenceDocuments(evidenceId, eligible);
		mutation.accept(documents);
		Path directory = ProviderQualityEvidenceWriter.forRepository(
				OBJECT_MAPPER, temporaryDirectory, MAXIMUM_EVIDENCE_BYTES)
				.write(evidenceId, documents.artifacts())
				.directory();
		return ProviderQualityComparativeEvidenceBundle.verify(OBJECT_MAPPER, directory);
	}

	private static ProviderQualityComparativeJudgments.BoundJudgments boundJudgments(
			ProviderQualityComparativeEvidenceBundle bundle,
			BoundPolicy policy,
			Consumer<ObjectNode> mutation) throws Exception {
		ObjectNode packet = judgmentPacket(bundle, policy);
		mutation.accept(packet);
		return ProviderQualityComparativeJudgments.parseBound(
				OBJECT_MAPPER, CANONICAL_WRITER.writeValueAsBytes(packet));
	}

	private static ObjectNode judgmentPacket(
			ProviderQualityComparativeEvidenceBundle bundle, BoundPolicy policy) {
		Map<String, Object> root = new LinkedHashMap<>();
		root.put("schemaVersion", 2);
		root.put("protocolId", ProviderQualityComparativeJudgments.PROTOCOL_ID);
		root.put("evidenceId", bundle.evidenceId());
		root.put("evidenceManifestSha256", bundle.manifestSha256());
		root.put("querySetId", QUERY_SET_ID);
		root.put("querySetSha256", QUERY_SET_SHA256);
		root.put("scoringPolicyId", policy.policy().policyId());
		root.put("scoringPolicySha256", policy.sha256());
		root.put("reviewPacketSha256", REVIEW_PACKET_SHA256);
		root.put(
				"independenceAttestation",
				ProviderQualityComparativeJudgments.INDEPENDENCE_ATTESTATION);
		root.put("queries", List.of(
				judgmentQuery(
						"query-alpha",
						List.of(
								gold("alpha-shared-work", List.of(
										ALPHA_OPENALEX_SHARED.reviewKey(),
										ALPHA_EUROPE_SHARED.reviewKey()), 3,
										List.of("ABSTRACT", "TITLE")),
								gold("alpha-false-merge-work", List.of(
										ALPHA_OPENALEX_FALSE_MERGE.reviewKey()), 2,
										List.of("DOI", "TITLE")),
								gold("alpha-europe-only-work", List.of(
										ALPHA_EUROPE_ONLY.reviewKey()), 1,
										List.of("PMID", "TITLE"))),
						List.of(pair(
								ALPHA_OPENALEX_SHARED.reviewKey(),
								ALPHA_OPENALEX_FALSE_MERGE.reviewKey()))),
				judgmentQuery(
						"query-beta",
						List.of(
								gold("beta-openalex-relevant", List.of(
										BETA_OPENALEX_RELEVANT.reviewKey()), 2,
										List.of("AUTHORS", "TITLE")),
								gold("beta-europe-negative", List.of(
										BETA_EUROPE_NEGATIVE.reviewKey()), 0, List.of())),
						List.of(pair(
								BETA_OPENALEX_RELEVANT.reviewKey(),
								BETA_EUROPE_NEGATIVE.reviewKey())))));
		return OBJECT_MAPPER.valueToTree(root);
	}

	private static Map<String, Object> judgmentQuery(
			String queryKey,
			List<Map<String, Object>> goldPapers,
			List<Map<String, Object>> mustSeparatePairs) {
		return Map.of(
				"queryKey", queryKey,
				"goldPapers", goldPapers,
				"mustSeparatePairs", mustSeparatePairs);
	}

	private static Map<String, Object> gold(
			String goldPaperKey,
			List<String> reviewKeys,
			int relevanceGrade,
			List<String> expectedFields) {
		return Map.of(
				"goldPaperKey", goldPaperKey,
				"reviewKeys", reviewKeys,
				"relevanceGrade", relevanceGrade,
				"expectedFields", expectedFields);
	}

	private static Map<String, Object> pair(String left, String right) {
		String first = left.compareTo(right) < 0 ? left : right;
		String second = left.compareTo(right) < 0 ? right : left;
		return Map.of(
				"leftReviewKey", first,
				"rightReviewKey", second,
				"reasonCode", "DISTINCT_SYNTHETIC_WORKS");
	}

	private static EvidenceDocuments evidenceDocuments(String evidenceId, boolean eligible) {
		ObjectNode summary = OBJECT_MAPPER.valueToTree(summary(evidenceId, eligible));
		ObjectNode blinded = OBJECT_MAPPER.valueToTree(Map.of(
				"schemaVersion", 2,
				"evidenceId", evidenceId,
				"qualityReviewEligible", eligible,
				"instructions", eligible
						? ProviderQualityComparativeEvidenceBundle.ELIGIBLE_REVIEW_INSTRUCTIONS
						: "Do not label this incomplete capture; repeat the isolated run.",
				"candidates", ALL_CANDIDATES.stream()
						.sorted(Comparator.comparing(CandidateSpec::queryKey)
								.thenComparing(candidate ->
										ProviderQualityComparativeEvidenceBundle.blindedOrderingKey(
												evidenceId, candidate.reviewKey())))
						.map(ProviderQualityComparativeScorerTests::blindedCandidate)
						.toList()));
		ObjectNode provenance = OBJECT_MAPPER.valueToTree(Map.of(
				"schemaVersion", 2,
				"evidenceId", evidenceId,
				"warning", "Synthetic provenance for scorer contract testing only.",
				"candidates", ALL_CANDIDATES.stream()
						.map(ProviderQualityComparativeScorerTests::provenanceCandidate)
						.toList()));
		ObjectNode reconciliation = OBJECT_MAPPER.valueToTree(Map.of(
				"schemaVersion", 2,
				"evidenceId", evidenceId,
				"warning", "Synthetic cluster decisions are not real duplicate labels.",
				"queries", List.of(alphaReconciliation(), betaReconciliation())));
		return new EvidenceDocuments(summary, blinded, provenance, reconciliation);
	}

	private static void clearAllCandidates(EvidenceDocuments documents) {
		((ArrayNode) documents.blinded().required("candidates")).removeAll();
		((ArrayNode) documents.provenance().required("candidates")).removeAll();
		documents.summary().required("queries").forEach(query -> {
			ObjectNode summaryQuery = (ObjectNode) query;
			summaryQuery.put("rawCandidateCount", 0);
			summaryQuery.required("providerCalls").forEach(call -> {
				((ObjectNode) call).put("returnedRecords", 0);
				((ObjectNode) call).put("totalMatches", 0L);
			});
			ObjectNode counts = (ObjectNode) summaryQuery.required("scenarioResultCounts");
			counts.put("OPENALEX_ONLY", 0);
			counts.put("EUROPE_PMC_ONLY", 0);
			counts.put("FUSED", 0);
		});
		documents.reconciliation().required("queries").forEach(query ->
				query.required("scenarios").propertyNames().forEach(name -> {
					JsonNode scenario = query.required("scenarios").required(name);
					((ArrayNode) scenario.required("rankedResults")).removeAll();
					((ArrayNode) scenario.required("reconciliation")).removeAll();
				}));
	}

	private static void bindEmptyEvidenceToFrozenQuerySet(
			EvidenceDocuments documents, BoundQuerySet querySet) {
		clearAllCandidates(documents);
		ObjectNode querySetNode = (ObjectNode) documents.summary().required("querySet");
		querySetNode.put("id", querySet.querySet().querySetId());
		querySetNode.put("sha256", querySet.sha256());
		querySetNode.put("sourcePolicy", querySet.querySet().sourcePolicy());
		querySetNode.put("pageSize", querySet.querySet().pageSize());
		ObjectNode requests = (ObjectNode) documents.summary().required("providerRequests");
		requests.put("OPENALEX", querySet.querySet().queries().size());
		requests.put("EUROPE_PMC", querySet.querySet().queries().size());

		ArrayNode summaryQueries = (ArrayNode) documents.summary().required("queries");
		summaryQueries.removeAll();
		ArrayNode reconciliationQueries =
				(ArrayNode) documents.reconciliation().required("queries");
		reconciliationQueries.removeAll();
		for (ProviderQualityLiveQuerySet.Query query : querySet.querySet().queries()) {
			summaryQueries.add(OBJECT_MAPPER.valueToTree(
					summaryQuery(query.key(), 0, 0, 0, 0)));
			Map<String, Object> scenarios = new LinkedHashMap<>();
			for (Scenario scenario : querySetScenarios()) {
				scenarios.put(scenario.name(), scenario(scenario.name(), List.of(), List.of()));
			}
			reconciliationQueries.add(OBJECT_MAPPER.valueToTree(Map.of(
					"queryKey", query.key(),
					"complete", true,
					"scenarios", scenarios)));
		}
	}

	private static List<Scenario> querySetScenarios() {
		return List.of(Scenario.OPENALEX_ONLY, Scenario.EUROPE_PMC_ONLY, Scenario.FUSED);
	}

	private static Map<String, Object> summary(String evidenceId, boolean eligible) {
		Map<String, Object> boundaries = new LinkedHashMap<>();
		boundaries.put("metadataOnly", true);
		boundaries.put("firstPageOnly", true);
		boundaries.put("providerFetchesPerProviderQuery", 1);
		boundaries.put("fetchesPdf", false);
		boundaries.put("fetchesFullText", false);
		boundaries.put("fetchesSupplementaryFiles", false);
		boundaries.put("serializesPdfUrl", false);
		boundaries.put("serializesCanonicalMetadataValues", false);
		boundaries.put("serializesCanonicalMetadataPresence", true);
		boundaries.put("mutatesUserCatalog", false);
		boundaries.put("readerFacing", false);
		boundaries.put("defaultEnablementDecision", false);

		Map<String, Object> summary = new LinkedHashMap<>();
		summary.put("schemaVersion", 2);
		summary.put("evidenceType", "LIVE_COMPARATIVE_METADATA_CAPTURE");
		summary.put("evidenceId", evidenceId);
		summary.put("measuredAt", "2026-08-26T00:00:00Z");
		summary.put("repositoryRevision", "a".repeat(40));
		summary.put("querySet", Map.of(
				"id", QUERY_SET_ID,
				"sha256", QUERY_SET_SHA256,
				"sourcePolicy", "AUTHOR_WRITTEN_TOPICS_WITHOUT_RELEVANCE_LABELS",
				"pageSize", 20));
		summary.put("providerConfiguration", Map.of(
				"OPENALEX", Map.of(
						"baseUrl", "https://api.openalex.org",
						"apiKeyConfigured", false,
						"maxResponseBytes", 8_388_608),
				"EUROPE_PMC", Map.of(
						"baseUrl", "https://www.ebi.ac.uk/europepmc/webservices/rest",
						"maxResponseBytes", 8_388_608)));
		summary.put("boundaries", boundaries);
		summary.put("qualityReviewEligible", eligible);
		summary.put("providerRequests", Map.of("OPENALEX", 2, "EUROPE_PMC", 2));
		summary.put("providerFailures", Map.of("OPENALEX", 0, "EUROPE_PMC", 0));
		summary.put("queries", List.of(
				summaryQuery("query-alpha", 4, 2, 2, 3),
				summaryQuery("query-beta", 2, 1, 1, 2)));
		return summary;
	}

	private static Map<String, Object> summaryQuery(
			String queryKey, int rawCount, int openAlexCount, int europePmcCount, int fusedCount) {
		return Map.of(
				"queryKey", queryKey,
				"complete", true,
				"rawCandidateCount", rawCount,
				"providerCalls", List.of(
						providerCall("EUROPE_PMC", europePmcCount),
						providerCall("OPENALEX", openAlexCount)),
				"scenarioResultCounts", Map.of(
						"OPENALEX_ONLY", openAlexCount,
						"EUROPE_PMC_ONLY", europePmcCount,
						"FUSED", fusedCount));
	}

	private static Map<String, Object> providerCall(String provider, int returnedRecords) {
		Map<String, Object> call = new LinkedHashMap<>();
		call.put("provider", provider);
		call.put("status", "SUCCESS");
		call.put("durationMilliseconds", 1L);
		call.put("returnedRecords", returnedRecords);
		call.put("totalMatches", (long) returnedRecords);
		call.put("retrievedAt", "2026-08-26T00:00:00Z");
		call.put("errorCode", null);
		call.put("retryable", false);
		return call;
	}

	private static Map<String, Object> blindedCandidate(CandidateSpec candidate) {
		Map<String, Object> value = new LinkedHashMap<>();
		value.put("reviewKey", candidate.reviewKey());
		value.put("queryKey", candidate.queryKey());
		value.put("title", "Synthetic " + candidate.providerRecordId());
		value.put("abstractText", "Synthetic abstract for an offline scorer test.");
		value.put("publicationDate", "2026-01-01");
		value.put("publicationYear", 2026);
		value.put("documentType", "ARTICLE");
		value.put("language", "en");
		value.put("venueName", "Synthetic Venue");
		value.put("authors", List.of());
		return value;
	}

	private static Map<String, Object> provenanceCandidate(CandidateSpec candidate) {
		Map<String, Object> value = new LinkedHashMap<>();
		value.put("schemaVersion", 1);
		value.put("reviewKey", candidate.reviewKey());
		value.put("queryKey", candidate.queryKey());
		value.put("providerRank", candidate.providerRank());
		value.put("provider", candidate.provider());
		value.put("providerRecordId", candidate.providerRecordId());
		value.put("title", "Synthetic " + candidate.providerRecordId());
		value.put("abstractText", "Synthetic abstract for an offline scorer test.");
		value.put("publicationDate", "2026-01-01");
		value.put("publicationYear", 2026);
		value.put("documentType", "ARTICLE");
		value.put("language", "en");
		value.put("venueName", "Synthetic Venue");
		value.put("citationCount", 0);
		value.put("authors", List.of());
		value.put("reportedOpenAccess", true);
		value.put("providerUpdatedAt", "2026-01-01T00:00:00Z");
		value.put("identifiers", List.of());
		value.put("sourceUrl", "https://example.test/synthetic-paper");
		value.put("publisher", null);
		value.put("institution", null);
		value.put("volume", null);
		value.put("issue", null);
		value.put("pages", null);
		value.put("articleNumber", null);
		value.put("edition", null);
		value.put("isbn", List.of());
		value.put("issn", List.of());
		value.put("degree", null);
		return value;
	}

	private static Map<String, Object> alphaReconciliation() {
		Map<String, Object> scenarios = new LinkedHashMap<>();
		scenarios.put("OPENALEX_ONLY", scenario(
				"OPENALEX_ONLY",
				List.of(
						trace(ALPHA_OPENALEX_SHARED, 101),
						trace(ALPHA_OPENALEX_FALSE_MERGE, 103)),
				List.of(
						ranked(ALPHA_OPENALEX_SHARED, 101, List.of("TITLE")),
						ranked(ALPHA_OPENALEX_FALSE_MERGE, 103, List.of("DOI", "TITLE")))));
		scenarios.put("EUROPE_PMC_ONLY", scenario(
				"EUROPE_PMC_ONLY",
				List.of(
						trace(ALPHA_EUROPE_SHARED, 102),
						trace(ALPHA_EUROPE_ONLY, 104)),
				List.of(
						ranked(ALPHA_EUROPE_SHARED, 102, List.of("ABSTRACT", "TITLE")),
						ranked(ALPHA_EUROPE_ONLY, 104, List.of("PMID")))));
		scenarios.put("FUSED", scenario(
				"FUSED",
				List.of(
						trace(ALPHA_OPENALEX_SHARED, 105),
						trace(ALPHA_OPENALEX_FALSE_MERGE, 105),
						trace(ALPHA_EUROPE_SHARED, 106),
						trace(ALPHA_EUROPE_ONLY, 107)),
				List.of(
						ranked(ALPHA_OPENALEX_SHARED, 105, List.of("ABSTRACT", "TITLE")),
						ranked(ALPHA_EUROPE_SHARED, 106, List.of("TITLE")),
						ranked(ALPHA_EUROPE_ONLY, 107, List.of("TITLE")))));
		return Map.of(
				"queryKey", "query-alpha",
				"complete", true,
				"scenarios", scenarios);
	}

	private static Map<String, Object> betaReconciliation() {
		Map<String, Object> scenarios = new LinkedHashMap<>();
		scenarios.put("OPENALEX_ONLY", scenario(
				"OPENALEX_ONLY",
				List.of(trace(BETA_OPENALEX_RELEVANT, 201)),
				List.of(ranked(BETA_OPENALEX_RELEVANT, 201, List.of("AUTHORS")))));
		scenarios.put("EUROPE_PMC_ONLY", scenario(
				"EUROPE_PMC_ONLY",
				List.of(trace(BETA_EUROPE_NEGATIVE, 202)),
				List.of(ranked(BETA_EUROPE_NEGATIVE, 202, List.of()))));
		scenarios.put("FUSED", scenario(
				"FUSED",
				List.of(
						trace(BETA_OPENALEX_RELEVANT, 203),
						trace(BETA_EUROPE_NEGATIVE, 204)),
				List.of(
						ranked(BETA_OPENALEX_RELEVANT, 203, List.of("AUTHORS", "TITLE")),
						ranked(BETA_EUROPE_NEGATIVE, 204, List.of()))));
		return Map.of(
				"queryKey", "query-beta",
				"complete", true,
				"scenarios", scenarios);
	}

	private static Map<String, Object> scenario(
			String name,
			List<TraceSpec> traces,
			List<RankedSpec> rankedResults) {
		List<Map<String, Object>> reconciliation = traces.stream()
				.map(trace -> Map.<String, Object>of(
						"reviewKey", trace.candidate().reviewKey(),
						"provider", trace.candidate().provider(),
						"providerRecordId", trace.candidate().providerRecordId(),
						"providerRank", trace.candidate().providerRank(),
						"clusterKey", hex(trace.clusterSeed()),
						"includedInFirstPage", true))
				.toList();
		List<Map<String, Object>> rankings = new ArrayList<>();
		for (int index = 0; index < rankedResults.size(); index++) {
			RankedSpec ranked = rankedResults.get(index);
			Map<String, Object> result = new LinkedHashMap<>();
			result.put("rank", index + 1);
			result.put("score", 1.0d / (index + 1));
			result.put("primaryReviewKey", ranked.primary().reviewKey());
			result.put("clusterKey", hex(ranked.clusterSeed()));
			result.put("primaryProvider", ranked.primary().provider());
			result.put("primaryProviderRecordId", ranked.primary().providerRecordId());
			result.put("presentFields", ranked.presentFields());
			rankings.add(result);
		}
		return Map.of(
				"scenario", name,
				"rankedResults", rankings,
				"reconciliation", reconciliation);
	}

	private static TraceSpec trace(CandidateSpec candidate, int clusterSeed) {
		return new TraceSpec(candidate, clusterSeed);
	}

	private static RankedSpec ranked(
			CandidateSpec primary, int clusterSeed, List<String> presentFields) {
		return new RankedSpec(primary, clusterSeed, presentFields);
	}

	private static Map<String, QueryScore> queriesByKey(ScoringResult result) {
		Map<String, QueryScore> queries = new LinkedHashMap<>();
		result.queries().forEach(query -> queries.put(query.queryKey(), query));
		return queries;
	}

	private static long totalPairs(DeduplicationScore value) {
		return value.truePositives()
				+ value.falsePositives()
				+ value.falseNegatives()
				+ value.trueNegatives();
	}

	private static double discountedGain(int grade, int zeroBasedRank) {
		return (Math.pow(2.0d, grade) - 1.0d)
				/ (Math.log(zeroBasedRank + 2.0d) / Math.log(2.0d));
	}

	private static String hex(int value) {
		return String.format(java.util.Locale.ROOT, "%064x", value);
	}

	private static String reviewKey(String queryKey, String provider, String providerRecordId) {
		return ProviderQualityRawReviewKey.create(
				QUERY_SET_ID, queryKey, ProviderId.valueOf(provider), providerRecordId);
	}

	private record CandidateSpec(
			String reviewKey,
			String queryKey,
			String provider,
			String providerRecordId,
			int providerRank) {
	}

	private record TraceSpec(CandidateSpec candidate, int clusterSeed) {
	}

	private record RankedSpec(
			CandidateSpec primary, int clusterSeed, List<String> presentFields) {
	}

	private record EvidenceDocuments(
			ObjectNode summary,
			ObjectNode blinded,
			ObjectNode provenance,
			ObjectNode reconciliation) {

		private Map<String, Object> artifacts() {
			Map<String, Object> artifacts = new LinkedHashMap<>();
			artifacts.put("summary.json", summary);
			artifacts.put("blinded-candidates.json", blinded);
			artifacts.put("provenance-map.json", provenance);
			artifacts.put("reconciliation-trace.json", reconciliation);
			return artifacts;
		}
	}

	private record ScoringFixture(
			ProviderQualityComparativeEvidenceBundle bundle,
			ProviderQualityComparativeJudgments.BoundJudgments judgments,
			BoundPolicy policy) {
	}
}
