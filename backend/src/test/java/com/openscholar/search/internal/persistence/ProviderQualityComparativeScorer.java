package com.openscholar.search.internal.persistence;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

import com.openscholar.paper.PaperIdentifierType;
import com.openscholar.provider.ProviderId;
import com.openscholar.search.internal.persistence.ProviderQualityLiveQuerySet.BoundQuerySet;
import com.openscholar.search.internal.persistence.ProviderQualityComparativeJudgments.GoldPaper;
import com.openscholar.search.internal.persistence.ProviderQualityComparativeJudgments.MustSeparatePair;
import com.openscholar.search.internal.persistence.ProviderQualityComparativeJudgments.QueryJudgments;
import com.openscholar.search.internal.persistence.ProviderQualityComparativeScoringPolicy.BoundPolicy;
import com.openscholar.search.internal.persistence.ProviderQualityComparativeScoringPolicy.Scenario;
import com.openscholar.search.internal.persistence.ProviderQualityMetrics.DedupObservation;
import com.openscholar.search.internal.persistence.ProviderQualityMetrics.MetadataField;
import com.openscholar.search.internal.persistence.ProviderQualityMetrics.PairwiseDeduplication;
import com.openscholar.search.internal.persistence.ProviderQualityMetrics.RankingCutoffs;
import com.openscholar.search.internal.persistence.ProviderQualityMetrics.RankingMeasurement;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Pure offline scorer for a verified comparative capture and independently
 * authored judgments. It consumes only bounded JSON trees already checked by
 * {@link ProviderQualityComparativeEvidenceBundle}; it has no Spring, network,
 * database, document, runtime endpoint, or reader-UI dependency.
 */
final class ProviderQualityComparativeScorer {

	private static final Pattern SHA256 = Pattern.compile("^[0-9a-f]{64}$");
	private static final Pattern GIT_REVISION =
			Pattern.compile("^[0-9a-f]{40}(?:[0-9a-f]{24})?$");
	private static final Pattern SAFE_TEXT = Pattern.compile("^[^\\p{Cntrl}]{1,1024}$");
	private static final String EVIDENCE_TYPE = "LIVE_COMPARATIVE_METADATA_CAPTURE";
	private static final String SOURCE_POLICY =
			"AUTHOR_WRITTEN_TOPICS_WITHOUT_RELEVANCE_LABELS";
	private static final String OPENALEX_BASE_URL = "https://api.openalex.org";
	private static final String EUROPE_PMC_BASE_URL =
			"https://www.ebi.ac.uk/europepmc/webservices/rest";
	private static final Set<String> SUMMARY_FIELDS = Set.of(
			"schemaVersion", "evidenceType", "evidenceId", "measuredAt",
			"repositoryRevision", "querySet", "providerConfiguration", "boundaries",
			"qualityReviewEligible", "providerRequests", "providerFailures", "queries");
	private static final Set<String> QUERY_SET_FIELDS = Set.of(
			"id", "sha256", "sourcePolicy", "pageSize");
	private static final Set<String> BOUNDARY_FIELDS = Set.of(
			"metadataOnly", "firstPageOnly", "providerFetchesPerProviderQuery",
			"fetchesPdf", "fetchesFullText", "fetchesSupplementaryFiles",
			"serializesPdfUrl", "serializesCanonicalMetadataValues",
			"serializesCanonicalMetadataPresence", "mutatesUserCatalog",
			"readerFacing", "defaultEnablementDecision");
	private static final Set<String> SUMMARY_QUERY_FIELDS = Set.of(
			"queryKey", "complete", "rawCandidateCount", "providerCalls",
			"scenarioResultCounts");
	private static final Set<String> PROVIDER_CALL_FIELDS = Set.of(
			"provider", "status", "durationMilliseconds", "returnedRecords",
			"totalMatches", "retrievedAt", "errorCode", "retryable");
	private static final Set<String> BLINDED_FIELDS = Set.of(
			"schemaVersion", "evidenceId", "qualityReviewEligible", "instructions",
			"candidates");
	private static final Set<String> BLINDED_CANDIDATE_FIELDS = Set.of(
			"reviewKey", "queryKey", "title", "abstractText", "publicationDate",
			"publicationYear", "documentType", "language", "venueName", "authors");
	private static final Set<String> BLINDED_AUTHOR_FIELDS = Set.of(
			"displayName", "position", "corresponding");
	private static final Set<String> PROVENANCE_FIELDS = Set.of(
			"schemaVersion", "evidenceId", "warning", "candidates");
	private static final Set<String> PROVENANCE_CANDIDATE_FIELDS = Set.of(
			"schemaVersion", "reviewKey", "queryKey", "providerRank", "provider",
			"providerRecordId", "title", "abstractText", "publicationDate",
			"publicationYear", "documentType", "language", "venueName",
			"citationCount", "authors", "reportedOpenAccess", "providerUpdatedAt",
			"identifiers", "sourceUrl", "publisher", "institution", "volume",
			"issue", "pages", "articleNumber", "edition", "isbn", "issn", "degree");
	private static final Set<String> PROVENANCE_AUTHOR_FIELDS = Set.of(
			"displayName", "orcid", "position", "corresponding");
	private static final Set<String> IDENTIFIER_FIELDS = Set.of(
			"type", "namespace", "value");
	private static final Set<String> RECONCILIATION_FIELDS = Set.of(
			"schemaVersion", "evidenceId", "warning", "queries");
	private static final Set<String> RECONCILIATION_QUERY_FIELDS = Set.of(
			"queryKey", "complete", "scenarios");
	private static final Set<String> SCENARIO_FIELDS = Set.of(
			"scenario", "rankedResults", "reconciliation");
	private static final Set<String> RANKED_RESULT_FIELDS = Set.of(
			"rank", "score", "primaryReviewKey", "clusterKey", "primaryProvider",
			"primaryProviderRecordId", "presentFields");
	private static final Set<String> TRACE_FIELDS = Set.of(
			"reviewKey", "provider", "providerRecordId", "providerRank", "clusterKey",
			"includedInFirstPage");

	private ProviderQualityComparativeScorer() {
	}

	/**
	 * Runs the scorer's complete evidence parser before anything is exposed for review.
	 * The supplied query set and policy must be the exact frozen resources, and the
	 * evidence must retain their ID, digest, and ordered query-key bindings.
	 */
	static void preflightForReview(
			ObjectMapper objectMapper,
			ProviderQualityComparativeEvidenceBundle bundle,
			BoundQuerySet boundQuerySet,
			BoundPolicy boundPolicy) throws IOException {
		Objects.requireNonNull(objectMapper, "objectMapper");
		Objects.requireNonNull(bundle, "bundle");
		Objects.requireNonNull(boundQuerySet, "boundQuerySet");
		Objects.requireNonNull(boundPolicy, "boundPolicy");

		BoundQuerySet frozenQuerySet = ProviderQualityLiveQuerySet.loadFrozen(objectMapper);
		if (!boundQuerySet.querySet().equals(frozenQuerySet.querySet())
				|| !sameDigest(boundQuerySet.sha256(), frozenQuerySet.sha256())) {
			throw invalid("review preflight requires the exact frozen query set");
		}
		BoundPolicy frozenPolicy = ProviderQualityComparativeScoringPolicy.loadBound(
				objectMapper, ProviderQualityComparativeScoringPolicy.RESOURCE_PATH);
		frozenPolicy.validateReference(
				ProviderQualityComparativeScoringPolicy.POLICY_ID,
				ProviderQualityComparativeScoringPolicy.POLICY_SHA256);
		if (!boundPolicy.policy().equals(frozenPolicy.policy())
				|| !sameDigest(boundPolicy.sha256(), frozenPolicy.sha256())) {
			throw invalid("review preflight requires the exact frozen scoring policy");
		}
		if (!bundle.reviewReady()) {
			throw invalid("comparative evidence is not review-ready");
		}

		Evidence evidence = parseEvidence(bundle, boundPolicy.policy());
		List<String> evidenceKeys = evidence.queries().stream()
				.map(EvidenceQuery::queryKey)
				.toList();
		List<String> frozenKeys = boundQuerySet.querySet().queries().stream()
				.map(ProviderQualityLiveQuerySet.Query::key)
				.toList();
		if (!evidence.querySetId().equals(boundQuerySet.querySet().querySetId())
				|| !sameDigest(evidence.querySetSha256(), boundQuerySet.sha256())
				|| !evidenceKeys.equals(frozenKeys)) {
			throw invalid(
					"review evidence does not match the exact frozen ordered query set");
		}
	}

	static ScoringResult score(
			ProviderQualityComparativeEvidenceBundle bundle,
			ProviderQualityComparativeJudgments.BoundJudgments boundJudgments,
			BoundPolicy boundPolicy,
			String verifiedReviewPacketSha256) {
		Objects.requireNonNull(bundle, "bundle");
		Objects.requireNonNull(boundJudgments, "boundJudgments");
		Objects.requireNonNull(boundPolicy, "boundPolicy");
		Objects.requireNonNull(verifiedReviewPacketSha256, "verifiedReviewPacketSha256");
		if (!bundle.reviewReady()) {
			throw invalid("comparative evidence is not review-ready");
		}

		ProviderQualityComparativeJudgments judgments = boundJudgments.judgments();
		if (!sameDigest(
				judgments.reviewPacketSha256(), verifiedReviewPacketSha256)) {
			throw invalid(
					"judgment review-packet SHA-256 does not match the verified packet");
		}
		if (!bundle.evidenceId().equals(judgments.evidenceId())) {
			throw invalid("judgment evidence ID does not match the verified bundle");
		}
		if (!sameDigest(bundle.manifestSha256(), judgments.evidenceManifestSha256())) {
			throw invalid("judgment evidence manifest SHA-256 does not match");
		}
		boundPolicy.validateReference(
				judgments.scoringPolicyId(), judgments.scoringPolicySha256());

		Evidence evidence = parseEvidence(bundle, boundPolicy.policy());
		if (!evidence.querySetId().equals(judgments.querySetId())
				|| !sameDigest(evidence.querySetSha256(), judgments.querySetSha256())) {
			throw invalid("judgment query-set reference does not match the evidence");
		}
		validateJudgmentPartition(evidence, judgments, boundPolicy.policy());

		RankingCutoffs cutoffs = new RankingCutoffs(
				boundPolicy.policy().ranking().recallAt(),
				boundPolicy.policy().ranking().ndcgAt(),
				boundPolicy.policy().ranking().precisionAt(),
				boundPolicy.policy().ranking().reciprocalRankAt());
		Map<Scenario, List<RankingScore>> rankingMeasurements = new EnumMap<>(Scenario.class);
		Map<Scenario, MutablePairCounts> deduplicationTotals = new EnumMap<>(Scenario.class);
		Map<Scenario, MutableMetadataRecovery> metadataTotals = new EnumMap<>(Scenario.class);
		Map<Scenario, MutableMustSeparate> mustSeparateTotals = new EnumMap<>(Scenario.class);
		for (Scenario scenario : boundPolicy.policy().scenarios()) {
			rankingMeasurements.put(scenario, new ArrayList<>());
			deduplicationTotals.put(scenario, new MutablePairCounts());
			metadataTotals.put(scenario, new MutableMetadataRecovery());
			mustSeparateTotals.put(scenario, new MutableMustSeparate());
		}

		List<QueryScore> queryScores = new ArrayList<>();
		Map<String, QueryJudgments> judgmentsByQuery = judgments.queriesByKey();
		for (EvidenceQuery query : evidence.queries()) {
			QueryJudgments queryJudgments = judgmentsByQuery.get(query.queryKey());
			Map<Scenario, QueryScenarioScore> scenarioScores = new LinkedHashMap<>();
			for (Scenario scenario : boundPolicy.policy().scenarios()) {
				QueryScenarioScore scenarioScore = scoreScenario(
						query.scenarios().get(scenario), queryJudgments, cutoffs);
				scenarioScores.put(scenario, scenarioScore);
				rankingMeasurements.get(scenario).add(scenarioScore.ranking());
				deduplicationTotals.get(scenario).add(scenarioScore.deduplication());
				metadataTotals.get(scenario).add(scenarioScore.metadataRecovery());
				mustSeparateTotals.get(scenario).add(scenarioScore.mustSeparate());
			}
			queryScores.add(new QueryScore(query.queryKey(), scenarioScores));
		}

		Map<Scenario, ScenarioSummary> summaries = new LinkedHashMap<>();
		for (Scenario scenario : boundPolicy.policy().scenarios()) {
			summaries.put(scenario, new ScenarioSummary(
					summarizeRankings(rankingMeasurements.get(scenario)),
					deduplicationTotals.get(scenario).finish(),
					metadataTotals.get(scenario).finish(),
					mustSeparateTotals.get(scenario).finish()));
		}

		UniqueRelevantQueryCoverage uniqueCoverage = uniqueRelevantCoverage(evidence, judgments);
		String reportId = reportId(
				bundle.manifestSha256(), boundJudgments.sha256(), boundPolicy.sha256());
		return new ScoringResult(
				1,
				reportId,
				bundle.evidenceId(),
				bundle.manifestSha256(),
				evidence.captureRepositoryRevision(),
				boundJudgments.sha256(),
				judgments.reviewPacketSha256(),
				evidence.querySetId(),
				evidence.querySetSha256(),
				boundPolicy.policy().policyId(),
				boundPolicy.sha256(),
				queryScores.size(),
				uniqueCoverage,
				queryScores,
				summaries,
				false,
				false);
	}

	static Map<String, Object> artifacts(ScoringResult result) {
		Objects.requireNonNull(result, "result");
		Map<String, Object> summary = new LinkedHashMap<>();
		summary.put("schemaVersion", result.schemaVersion());
		summary.put("reportType", "OFFLINE_COMPARATIVE_PROVIDER_QUALITY_SCORE");
		summary.put("reportId", result.reportId());
		summary.put("evidence", Map.of(
				"evidenceId", result.evidenceId(),
				"manifestSha256", result.evidenceManifestSha256(),
				"captureRepositoryRevision", result.captureRepositoryRevision()));
		summary.put("judgments", Map.of(
				"sha256", result.judgmentPacketSha256(),
				"reviewPacketSha256", result.reviewPacketSha256()));
		summary.put("querySet", Map.of(
				"id", result.querySetId(), "sha256", result.querySetSha256()));
		summary.put("scoringPolicy", Map.of(
				"id", result.scoringPolicyId(), "sha256", result.scoringPolicySha256()));
		summary.put("boundaries", Map.of(
				"offline", true,
				"externalRequests", false,
				"fetchesPdf", false,
				"readerFacing", result.readerFacing(),
				"defaultEnablementDecision", result.defaultEnablementDecision()));
		summary.put("queryCount", result.queryCount());
		summary.put("europePmcUniqueRelevantQueryCoverage", result.uniqueRelevantQueryCoverage());
		summary.put("scenarios", result.scenarios());

		Map<String, Object> queries = new LinkedHashMap<>();
		queries.put("schemaVersion", result.schemaVersion());
		queries.put("reportId", result.reportId());
		queries.put("queries", result.queries());
		return Map.of(
				"query-scores.json", queries,
				"score-summary.json", summary);
	}

	private static QueryScenarioScore scoreScenario(
			ScenarioEvidence scenario,
			QueryJudgments judgments,
			RankingCutoffs cutoffs) {
		Map<String, List<String>> reviewKeysByCluster = new LinkedHashMap<>();
		for (Trace trace : scenario.traces()) {
			reviewKeysByCluster.computeIfAbsent(trace.clusterKey(), ignored -> new ArrayList<>())
					.add(trace.reviewKey());
		}
		Map<String, String> goldByReviewKey = judgments.goldPaperKeyByReviewKey();
		Map<String, GoldPaper> goldByKey = judgments.goldPapersByKey();
		Set<String> credited = new HashSet<>();
		List<String> rankedKeys = new ArrayList<>();
		MutableMetadataRecovery metadata = new MutableMetadataRecovery();
		int creditedResults = 0;
		for (RankedResult result : scenario.rankedResults()) {
			String creditedKey = reviewKeysByCluster.get(result.clusterKey()).stream()
					.map(goldByReviewKey::get)
					.distinct()
					.filter(key -> !credited.contains(key))
					.sorted(Comparator
							.<String>comparingInt(key -> goldByKey.get(key).relevanceGrade())
							.reversed()
							.thenComparing(Comparator.naturalOrder()))
					.findFirst()
					.orElse(null);
			if (creditedKey == null) {
				rankedKeys.add("uncredited:" + scenario.scenario().name() + ':'
						+ result.rank() + ':' + result.clusterKey());
			}
			else {
				credited.add(creditedKey);
				rankedKeys.add(creditedKey);
				creditedResults++;
				metadata.add(goldByKey.get(creditedKey), result.presentFields());
			}
		}
		RankingScore ranking = ranking(
				rankedKeys, judgments.relevanceByGoldPaperKey(), cutoffs);

		List<DedupObservation> observations = scenario.traces().stream()
				.map(trace -> new DedupObservation(
						trace.reviewKey(),
						goldByReviewKey.get(trace.reviewKey()),
						trace.clusterKey()))
				.toList();
		DeduplicationScore deduplication = pairwise(observations);
		MustSeparateMeasurement mustSeparate = mustSeparate(
				scenario, judgments.mustSeparatePairs());
		return new QueryScenarioScore(
				scenario.rankedResults().size(),
				creditedResults,
				ranking,
				deduplication,
				metadata.finish(),
				mustSeparate);
	}

	private static RankingScore ranking(
			List<String> rankedKeys,
			Map<String, Integer> relevance,
			RankingCutoffs cutoffs) {
		long relevantGoldWorks = relevance.values().stream().filter(grade -> grade > 0).count();
		if (relevantGoldWorks == 0) {
			return new RankingScore(0, null, null, 0.0d, null);
		}
		RankingMeasurement measurement = ProviderQualityMetrics.measureRanking(
				rankedKeys, relevance, cutoffs);
		return new RankingScore(
				relevantGoldWorks,
				measurement.recall(),
				measurement.ndcg(),
				measurement.precision(),
				measurement.reciprocalRank());
	}

	private static RankingSummaryScore summarizeRankings(List<RankingScore> measurements) {
		List<RankingScore> values = List.copyOf(measurements);
		if (values.isEmpty()) {
			throw invalid("ranking measurements must not be empty");
		}
		List<RankingScore> applicable = values.stream()
				.filter(value -> value.relevantGoldWorkCount() > 0)
				.toList();
		return new RankingSummaryScore(
				values.size(),
				applicable.size(),
				values.size() - applicable.size(),
				applicable.isEmpty()
						? null
						: applicable.stream().mapToDouble(RankingScore::recall).average().orElseThrow(),
				applicable.isEmpty()
						? null
						: applicable.stream().mapToDouble(RankingScore::ndcg).average().orElseThrow(),
				values.stream().mapToDouble(RankingScore::precision).average().orElseThrow(),
				applicable.isEmpty()
						? null
						: applicable.stream()
								.mapToDouble(RankingScore::reciprocalRank).average().orElseThrow());
	}

	private static DeduplicationScore pairwise(List<DedupObservation> observations) {
		long pairCount = (long) observations.size() * (observations.size() - 1) / 2;
		if (pairCount == 0) {
			return deduplicationScore(observations.size(), 0, 0, 0, 0, 0);
		}
		PairwiseDeduplication measured = ProviderQualityMetrics.measureDeduplication(observations);
		return deduplicationScore(
				observations.size(),
				pairCount,
				measured.truePositives(),
				measured.falsePositives(),
				measured.falseNegatives(),
				measured.trueNegatives());
	}

	private static DeduplicationScore deduplicationScore(
			long candidateCount,
			long evaluatedPairCount,
			long truePositives,
			long falsePositives,
			long falseNegatives,
			long trueNegatives) {
		Double precision = nullableRatio(truePositives, truePositives + falsePositives);
		Double recall = nullableRatio(truePositives, truePositives + falseNegatives);
		Double f1 = precision == null || recall == null
				? null
				: precision + recall == 0.0d
						? 0.0d
						: 2.0d * precision * recall / (precision + recall);
		return new DeduplicationScore(
				candidateCount,
				evaluatedPairCount,
				truePositives,
				falsePositives,
				falseNegatives,
				trueNegatives,
				precision,
				recall,
				f1);
	}

	private static MustSeparateMeasurement mustSeparate(
			ScenarioEvidence scenario, List<MustSeparatePair> pairs) {
		Map<String, String> clusters = new LinkedHashMap<>();
		scenario.traces().forEach(trace -> clusters.put(trace.reviewKey(), trace.clusterKey()));
		long applicable = 0;
		long violations = 0;
		for (MustSeparatePair pair : pairs) {
			String left = clusters.get(pair.leftReviewKey());
			String right = clusters.get(pair.rightReviewKey());
			if (left != null && right != null) {
				applicable++;
				if (left.equals(right)) {
					violations++;
				}
			}
		}
		return new MustSeparateMeasurement(
				applicable, violations, applicable == 0 ? null : (double) (applicable - violations) / applicable);
	}

	private static UniqueRelevantQueryCoverage uniqueRelevantCoverage(
			Evidence evidence, ProviderQualityComparativeJudgments judgments) {
		Map<String, QueryJudgments> judgmentsByQuery = judgments.queriesByKey();
		List<String> covered = new ArrayList<>();
		for (EvidenceQuery query : evidence.queries()) {
			QueryJudgments queryJudgments = judgmentsByQuery.get(query.queryKey());
			Map<String, Set<Provider>> providersByGold = new LinkedHashMap<>();
			for (Candidate candidate : query.candidates().values()) {
				String gold = queryJudgments.goldPaperKeyByReviewKey().get(candidate.reviewKey());
				providersByGold.computeIfAbsent(gold, ignored -> new HashSet<>())
						.add(candidate.provider());
			}
			boolean hasUniqueRelevant = queryJudgments.goldPapers().stream()
					.filter(gold -> gold.relevanceGrade() > 0)
					.anyMatch(gold -> providersByGold.getOrDefault(
							gold.goldPaperKey(), Set.of()).contains(Provider.EUROPE_PMC)
							&& !providersByGold.getOrDefault(
									gold.goldPaperKey(), Set.of()).contains(Provider.OPENALEX));
			if (hasUniqueRelevant) {
				covered.add(query.queryKey());
			}
		}
		return new UniqueRelevantQueryCoverage(
				covered.size(),
				evidence.queries().size(),
				(double) covered.size() / evidence.queries().size(),
				covered);
	}

	private static void validateJudgmentPartition(
			Evidence evidence,
			ProviderQualityComparativeJudgments judgments,
			ProviderQualityComparativeScoringPolicy policy) {
		Map<String, QueryJudgments> byKey = judgments.queriesByKey();
		List<String> evidenceKeys = evidence.queries().stream().map(EvidenceQuery::queryKey).toList();
		if (!new LinkedHashSet<>(evidenceKeys).equals(new LinkedHashSet<>(byKey.keySet()))
				|| evidenceKeys.size() != byKey.size()) {
			throw invalid("judgment query keys must exactly partition the evidence queries");
		}
		if (evidenceKeys.size() > policy.limits().maximumQueries()) {
			throw invalid("evidence query count exceeds the scoring-policy limit");
		}
		for (EvidenceQuery query : evidence.queries()) {
			Set<String> evidenceReviewKeys = query.candidates().keySet();
			Set<String> judgmentReviewKeys = byKey.get(query.queryKey())
					.goldPaperKeyByReviewKey().keySet();
			if (!evidenceReviewKeys.equals(judgmentReviewKeys)) {
				throw invalid("judgment review keys must exactly partition query " + query.queryKey());
			}
			if (evidenceReviewKeys.size() > policy.limits().maximumCandidatesPerQuery()) {
				throw invalid("query exceeds the scoring-policy candidate limit");
			}
		}
	}

	private static Evidence parseEvidence(
			ProviderQualityComparativeEvidenceBundle bundle,
			ProviderQualityComparativeScoringPolicy policy) {
		JsonNode summary = bundle.summary();
		requireExact(summary, "$summary", SUMMARY_FIELDS);
		requireInt(summary.required("schemaVersion"), "$summary.schemaVersion", 2, 2);
		requireText(summary.required("evidenceType"), "$summary.evidenceType", EVIDENCE_TYPE);
		requireText(summary.required("evidenceId"), "$summary.evidenceId", bundle.evidenceId());
		requireBoolean(summary.required("qualityReviewEligible"), "$summary.qualityReviewEligible", true);
		String measuredAt = requireText(summary.required("measuredAt"), "$summary.measuredAt");
		try {
			Instant.parse(measuredAt);
		}
		catch (RuntimeException exception) {
			throw invalid("$summary.measuredAt must be an ISO-8601 instant");
		}
		JsonNode querySet = summary.required("querySet");
		requireExact(querySet, "$summary.querySet", QUERY_SET_FIELDS);
		String querySetId = requireBoundedText(
				querySet.required("id"), "$summary.querySet.id", 3, 100);
		String querySetSha256 = requireSha256(
				querySet.required("sha256"), "$summary.querySet.sha256");
		requireText(querySet.required("sourcePolicy"), "$summary.querySet.sourcePolicy", SOURCE_POLICY);
		String captureRepositoryRevision = requireText(
				summary.required("repositoryRevision"), "$summary.repositoryRevision");
		if (!GIT_REVISION.matcher(captureRepositoryRevision).matches()) {
			throw invalid("$summary.repositoryRevision must be a lowercase Git commit ID");
		}
		int pageSize = requireInt(querySet.required("pageSize"), "$summary.querySet.pageSize", 20, 20);
		validateProviderConfiguration(summary.required("providerConfiguration"));
		JsonNode boundaries = summary.required("boundaries");
		requireExact(boundaries, "$summary.boundaries", BOUNDARY_FIELDS);
		requireBoolean(boundaries.required("metadataOnly"), "$summary.boundaries.metadataOnly", true);
		requireBoolean(boundaries.required("firstPageOnly"), "$summary.boundaries.firstPageOnly", true);
		requireInt(
				boundaries.required("providerFetchesPerProviderQuery"),
				"$summary.boundaries.providerFetchesPerProviderQuery", 1, 1);
		for (String field : List.of(
				"fetchesPdf", "fetchesFullText", "fetchesSupplementaryFiles",
				"serializesPdfUrl", "serializesCanonicalMetadataValues", "mutatesUserCatalog",
				"readerFacing", "defaultEnablementDecision")) {
			requireBoolean(boundaries.required(field), "$summary.boundaries." + field, false);
		}
		requireBoolean(
				boundaries.required("serializesCanonicalMetadataPresence"),
				"$summary.boundaries.serializesCanonicalMetadataPresence", true);

		List<SummaryQuery> summaryQueries = parseSummaryQueries(
				summary.required("queries"), policy);
		validateProviderCounters(summary, summaryQueries.size());
		Map<String, Candidate> provenance = parseCandidates(
				bundle.provenanceMap(), bundle.evidenceId(), querySetId, policy.limits());
		Map<String, BlindedCandidate> blinded = parseBlinded(
				bundle.blindedCandidates(), bundle.evidenceId(), policy.limits());
		validateBlindedOrder(blinded, summaryQueries, bundle.evidenceId());
		if (!provenance.keySet().equals(blinded.keySet())) {
			throw invalid("blinded and provenance candidate sets do not match");
		}
		for (Candidate candidate : provenance.values()) {
			BlindedCandidate blindedCandidate = blinded.get(candidate.reviewKey());
			if (!candidate.queryKey().equals(blindedCandidate.queryKey())
					|| !candidate.reviewerProjection().equals(
							blindedCandidate.reviewerProjection())) {
				throw invalid("blinded and provenance reviewer projections do not match");
			}
		}

		Map<String, Map<Scenario, ScenarioEvidence>> scenariosByQuery = parseReconciliation(
				bundle.reconciliationTrace(), bundle.evidenceId(), policy, provenance, pageSize);
		List<EvidenceQuery> queries = new ArrayList<>();
		Set<String> seenQueries = new HashSet<>();
		for (SummaryQuery summaryQuery : summaryQueries) {
			if (!seenQueries.add(summaryQuery.queryKey())) {
				throw invalid("summary query keys must be unique");
			}
			Map<String, Candidate> queryCandidates = new LinkedHashMap<>();
			provenance.values().stream()
					.filter(candidate -> candidate.queryKey().equals(summaryQuery.queryKey()))
					.forEach(candidate -> queryCandidates.put(candidate.reviewKey(), candidate));
			if (queryCandidates.size() != summaryQuery.rawCandidateCount()) {
				throw invalid("summary candidate count does not match provenance");
			}
			for (Provider provider : Provider.values()) {
				long observed = queryCandidates.values().stream()
						.filter(candidate -> candidate.provider() == provider)
						.count();
				if (observed != summaryQuery.providerReturnedRecords().get(provider)) {
					throw invalid("provider returned-record count does not match provenance");
				}
			}
			Map<Scenario, ScenarioEvidence> scenarios = scenariosByQuery.get(summaryQuery.queryKey());
			if (scenarios == null) {
				throw invalid("reconciliation trace is missing a summary query");
			}
			for (Scenario scenario : policy.scenarios()) {
				if (scenarios.get(scenario).rankedResults().size()
						!= summaryQuery.scenarioResultCounts().get(scenario)) {
					throw invalid("summary scenario result count does not match reconciliation");
				}
			}
			queries.add(new EvidenceQuery(summaryQuery.queryKey(), queryCandidates, scenarios));
		}
		if (!seenQueries.equals(scenariosByQuery.keySet())
				|| provenance.values().stream().anyMatch(candidate -> !seenQueries.contains(candidate.queryKey()))) {
			throw invalid("evidence documents do not contain the same query set");
		}
		return new Evidence(querySetId, querySetSha256, captureRepositoryRevision, queries);
	}

	private static List<SummaryQuery> parseSummaryQueries(
			JsonNode node, ProviderQualityComparativeScoringPolicy policy) {
		requireArray(node, "$summary.queries", 1, policy.limits().maximumQueries());
		List<SummaryQuery> result = new ArrayList<>();
		for (int index = 0; index < node.size(); index++) {
			JsonNode value = node.get(index);
			String path = "$summary.queries[" + index + ']';
			requireExact(value, path, SUMMARY_QUERY_FIELDS);
			requireBoolean(value.required("complete"), path + ".complete", true);
			JsonNode providerCalls = value.required("providerCalls");
			requireArray(providerCalls, path + ".providerCalls", 2, 2);
			Map<Provider, Integer> returnedRecords = new EnumMap<>(Provider.class);
			for (int callIndex = 0; callIndex < providerCalls.size(); callIndex++) {
				JsonNode call = providerCalls.get(callIndex);
				String callPath = path + ".providerCalls[" + callIndex + ']';
				requireExact(call, callPath, PROVIDER_CALL_FIELDS);
				Provider provider = requireEnum(
						call.required("provider"), callPath + ".provider", Provider.class);
				requireText(call.required("status"), callPath + ".status", "SUCCESS");
				requireLong(
						call.required("durationMilliseconds"),
						callPath + ".durationMilliseconds", 0, Long.MAX_VALUE);
				int returned = requireInt(
						call.required("returnedRecords"), callPath + ".returnedRecords", 0, 20);
				requireLong(
						call.required("totalMatches"), callPath + ".totalMatches", 0, Long.MAX_VALUE);
				String retrievedAt = requireText(
						call.required("retrievedAt"), callPath + ".retrievedAt");
				try {
					Instant.parse(retrievedAt);
				}
				catch (RuntimeException exception) {
					throw invalid(callPath + ".retrievedAt must be an ISO-8601 instant");
				}
				if (!call.required("errorCode").isNull()) {
					throw invalid(callPath + ".errorCode must be null for a successful call");
				}
				requireBoolean(call.required("retryable"), callPath + ".retryable", false);
				if (returnedRecords.putIfAbsent(provider, returned) != null) {
					throw invalid(path + ".providerCalls must contain each provider once");
				}
			}
			if (!returnedRecords.keySet().equals(Set.of(Provider.OPENALEX, Provider.EUROPE_PMC))) {
				throw invalid(path + ".providerCalls must contain each provider once");
			}
			JsonNode scenarioCounts = value.required("scenarioResultCounts");
			Set<String> expectedScenarioFields = policy.scenarios().stream()
					.map(Enum::name).collect(java.util.stream.Collectors.toUnmodifiableSet());
			requireExact(scenarioCounts, path + ".scenarioResultCounts", expectedScenarioFields);
			Map<Scenario, Integer> resultCounts = new LinkedHashMap<>();
			for (Scenario scenario : policy.scenarios()) {
				resultCounts.put(scenario, requireInt(
						scenarioCounts.required(scenario.name()),
						path + ".scenarioResultCounts." + scenario.name(), 0, 20));
			}
			result.add(new SummaryQuery(
					requireText(value.required("queryKey"), path + ".queryKey"),
					requireInt(value.required("rawCandidateCount"), path + ".rawCandidateCount", 0, 40),
					returnedRecords,
					resultCounts));
		}
		return List.copyOf(result);
	}

	private static void validateProviderConfiguration(JsonNode node) {
		requireExact(node, "$summary.providerConfiguration", Set.of("OPENALEX", "EUROPE_PMC"));
		JsonNode openAlex = node.required("OPENALEX");
		requireExact(openAlex, "$summary.providerConfiguration.OPENALEX", Set.of(
				"baseUrl", "apiKeyConfigured", "maxResponseBytes"));
		requireText(
				openAlex.required("baseUrl"),
				"$summary.providerConfiguration.OPENALEX.baseUrl",
				OPENALEX_BASE_URL);
		requireBoolean(
				openAlex.required("apiKeyConfigured"),
				"$summary.providerConfiguration.OPENALEX.apiKeyConfigured",
				false);
		requireInt(
				openAlex.required("maxResponseBytes"),
				"$summary.providerConfiguration.OPENALEX.maxResponseBytes",
				8_388_608,
				8_388_608);

		JsonNode europePmc = node.required("EUROPE_PMC");
		requireExact(europePmc, "$summary.providerConfiguration.EUROPE_PMC", Set.of(
				"baseUrl", "maxResponseBytes"));
		requireText(
				europePmc.required("baseUrl"),
				"$summary.providerConfiguration.EUROPE_PMC.baseUrl",
				EUROPE_PMC_BASE_URL);
		requireInt(
				europePmc.required("maxResponseBytes"),
				"$summary.providerConfiguration.EUROPE_PMC.maxResponseBytes",
				8_388_608,
				8_388_608);
	}

	private static void validateProviderCounters(JsonNode summary, int queryCount) {
		for (String field : List.of("providerRequests", "providerFailures")) {
			JsonNode counters = summary.required(field);
			requireExact(counters, "$summary." + field, Set.of("OPENALEX", "EUROPE_PMC"));
			long expected = field.equals("providerRequests") ? queryCount : 0;
			for (Provider provider : Provider.values()) {
				requireLong(
						counters.required(provider.name()),
						"$summary." + field + '.' + provider.name(), expected, expected);
			}
		}
	}

	private static Map<String, Candidate> parseCandidates(
			JsonNode root,
			String evidenceId,
			String querySetId,
			ProviderQualityComparativeScoringPolicy.Limits limits) {
		requireExact(root, "$provenance", PROVENANCE_FIELDS);
		requireInt(root.required("schemaVersion"), "$provenance.schemaVersion", 2, 2);
		requireText(root.required("evidenceId"), "$provenance.evidenceId", evidenceId);
		JsonNode nodes = root.required("candidates");
		requireArray(nodes, "$provenance.candidates", 0,
				limits.maximumQueries() * limits.maximumCandidatesPerQuery());
		Map<String, Candidate> result = new LinkedHashMap<>();
		for (int index = 0; index < nodes.size(); index++) {
			JsonNode node = nodes.get(index);
			String path = "$provenance.candidates[" + index + ']';
			requireExact(node, path, PROVENANCE_CANDIDATE_FIELDS);
			requireInt(node.required("schemaVersion"), path + ".schemaVersion", 1, 1);
			String reviewKey = requireSha256(node.required("reviewKey"), path + ".reviewKey");
			String queryKey = requireText(node.required("queryKey"), path + ".queryKey");
			Provider provider = requireEnum(node.required("provider"), path + ".provider", Provider.class);
			String providerRecordId = requireText(
					node.required("providerRecordId"), path + ".providerRecordId");
			int providerRank = requireInt(node.required("providerRank"), path + ".providerRank", 1, 20);
			String expectedReviewKey = ProviderQualityRawReviewKey.create(
					querySetId,
					queryKey,
					ProviderId.valueOf(provider.name()),
					providerRecordId);
			if (!sameDigest(reviewKey, expectedReviewKey)) {
				throw invalid("provenance review key does not match its deterministic identity");
			}
			validateProvenanceDetails(node, path);
			Candidate previous = result.putIfAbsent(reviewKey, new Candidate(
					reviewKey,
					queryKey,
					provider,
					providerRecordId,
					providerRank,
					parseReviewerProjection(node, path, true)));
			if (previous != null) {
				throw invalid("provenance review keys must be unique");
			}
		}
		return Collections.unmodifiableMap(result);
	}

	private static void validateBlindedOrder(
			Map<String, BlindedCandidate> blinded,
			List<SummaryQuery> summaryQueries,
			String evidenceId) {
		Map<String, Integer> queryOrder = new LinkedHashMap<>();
		for (int index = 0; index < summaryQueries.size(); index++) {
			queryOrder.put(summaryQueries.get(index).queryKey(), index);
		}
		int previousQueryIndex = -1;
		String previousOrderingKey = null;
		for (Map.Entry<String, BlindedCandidate> entry : blinded.entrySet()) {
			Integer queryIndex = queryOrder.get(entry.getValue().queryKey());
			if (queryIndex == null || queryIndex < previousQueryIndex) {
				throw invalid("blinded candidates do not follow the frozen query order");
			}
			String orderingKey = ProviderQualityComparativeEvidenceBundle
					.blindedOrderingKey(evidenceId, entry.getKey());
			if (queryIndex == previousQueryIndex
					&& previousOrderingKey.compareTo(orderingKey) > 0) {
				throw invalid("blinded candidates do not follow the evidence-scoped order");
			}
			if (queryIndex != previousQueryIndex) {
				previousQueryIndex = queryIndex;
			}
			previousOrderingKey = orderingKey;
		}
	}

	private static void validateProvenanceDetails(JsonNode node, String path) {
		requireOptionalInt(
				node.required("citationCount"), path + ".citationCount", 0, Integer.MAX_VALUE);
		requireBoolean(node.required("reportedOpenAccess"), path + ".reportedOpenAccess");
		requireOptionalInstant(node.required("providerUpdatedAt"), path + ".providerUpdatedAt");
		requireIdentifiers(node.required("identifiers"), path + ".identifiers");
		requireOptionalPublicUrl(node.required("sourceUrl"), path + ".sourceUrl");
		requireOptionalProjectionText(node.required("publisher"), path + ".publisher", 10_000);
		requireOptionalProjectionText(node.required("institution"), path + ".institution", 10_000);
		requireOptionalProjectionText(node.required("volume"), path + ".volume", 1_000);
		requireOptionalProjectionText(node.required("issue"), path + ".issue", 1_000);
		requireOptionalProjectionText(node.required("pages"), path + ".pages", 2_000);
		requireOptionalProjectionText(
				node.required("articleNumber"), path + ".articleNumber", 1_000);
		requireOptionalProjectionText(node.required("edition"), path + ".edition", 1_000);
		requireSortedUniqueTextArray(node.required("isbn"), path + ".isbn", 100, 256);
		requireSortedUniqueTextArray(node.required("issn"), path + ".issn", 100, 256);
		requireOptionalProjectionText(node.required("degree"), path + ".degree", 2_000);
	}

	private static void requireIdentifiers(JsonNode node, String path) {
		requireArray(node, path, 0, 32);
		List<String> keys = new ArrayList<>();
		for (int index = 0; index < node.size(); index++) {
			JsonNode identifier = node.get(index);
			String identifierPath = path + '[' + index + ']';
			requireExact(identifier, identifierPath, IDENTIFIER_FIELDS);
			PaperIdentifierType type = requireEnum(
					identifier.required("type"), identifierPath + ".type", PaperIdentifierType.class);
			String namespace = requireOptionalProjectionText(
					identifier.required("namespace"), identifierPath + ".namespace", 200);
			String value = requireProjectionText(
					identifier.required("value"), identifierPath + ".value", 2_048);
			keys.add(type.name() + '\n' + Objects.toString(namespace, "") + '\n' + value);
		}
		if (!keys.equals(keys.stream().distinct().sorted().toList())) {
			throw invalid(path + " must contain sorted unique identifiers");
		}
	}

	private static void requireSortedUniqueTextArray(
			JsonNode node, String path, int maximumItems, int maximumCharacters) {
		requireArray(node, path, 0, maximumItems);
		List<String> values = new ArrayList<>();
		for (int index = 0; index < node.size(); index++) {
			values.add(requireProjectionText(
					node.get(index), path + '[' + index + ']', maximumCharacters));
		}
		if (!values.equals(values.stream().distinct().sorted().toList())) {
			throw invalid(path + " must contain sorted unique values");
		}
	}

	private static void requireOptionalInstant(JsonNode node, String path) {
		String value = requireOptionalProjectionText(node, path, 64);
		if (value == null) {
			return;
		}
		try {
			Instant.parse(value);
		}
		catch (RuntimeException exception) {
			throw invalid(path + " must be an ISO-8601 instant");
		}
	}

	private static void requireOptionalPublicUrl(JsonNode node, String path) {
		String value = requireOptionalProjectionText(node, path, 4_096);
		if (value == null) {
			return;
		}
		try {
			URI uri = URI.create(value);
			String scheme = uri.getScheme();
			if (!uri.isAbsolute()
					|| uri.getHost() == null
					|| uri.getHost().isBlank()
					|| uri.getUserInfo() != null
					|| uri.getRawQuery() != null
					|| uri.getRawFragment() != null
					|| (!("http".equalsIgnoreCase(scheme))
							&& !("https".equalsIgnoreCase(scheme)))
					|| !value.equals(uri.toASCIIString())) {
				throw invalid(path + " must be a bounded public HTTP(S) URL");
			}
		}
		catch (IllegalArgumentException exception) {
			throw invalid(path + " must be a bounded public HTTP(S) URL");
		}
	}

	private static Map<String, BlindedCandidate> parseBlinded(
			JsonNode root,
			String evidenceId,
			ProviderQualityComparativeScoringPolicy.Limits limits) {
		requireExact(root, "$blinded", BLINDED_FIELDS);
		requireInt(root.required("schemaVersion"), "$blinded.schemaVersion", 2, 2);
		requireText(root.required("evidenceId"), "$blinded.evidenceId", evidenceId);
		requireBoolean(root.required("qualityReviewEligible"), "$blinded.qualityReviewEligible", true);
		JsonNode nodes = root.required("candidates");
		requireArray(nodes, "$blinded.candidates", 0,
				limits.maximumQueries() * limits.maximumCandidatesPerQuery());
		Map<String, BlindedCandidate> result = new LinkedHashMap<>();
		for (int index = 0; index < nodes.size(); index++) {
			JsonNode node = nodes.get(index);
			String path = "$blinded.candidates[" + index + ']';
			requireExact(node, path, BLINDED_CANDIDATE_FIELDS);
			String reviewKey = requireSha256(node.required("reviewKey"), path + ".reviewKey");
			String queryKey = requireText(node.required("queryKey"), path + ".queryKey");
			if (result.putIfAbsent(
					reviewKey,
					new BlindedCandidate(
							queryKey, parseReviewerProjection(node, path, false))) != null) {
				throw invalid("blinded review keys must be unique");
			}
		}
		return Collections.unmodifiableMap(result);
	}

	private static ReviewerProjection parseReviewerProjection(
			JsonNode node, String path, boolean provenance) {
		JsonNode authors = node.required("authors");
		requireArray(authors, path + ".authors", 0, 1_000);
		List<ReviewerAuthor> reviewerAuthors = new ArrayList<>();
		for (int index = 0; index < authors.size(); index++) {
			JsonNode author = authors.get(index);
			String authorPath = path + ".authors[" + index + ']';
			requireExact(
					author,
					authorPath,
					provenance ? PROVENANCE_AUTHOR_FIELDS : BLINDED_AUTHOR_FIELDS);
			if (provenance) {
				requireOptionalProjectionText(
						author.required("orcid"), authorPath + ".orcid", 200);
			}
			reviewerAuthors.add(new ReviewerAuthor(
					requireProjectionText(
							author.required("displayName"), authorPath + ".displayName", 1_000),
					requireInt(author.required("position"), authorPath + ".position", 0, 1_000),
					requireBoolean(author.required("corresponding"), authorPath + ".corresponding")));
		}
		return new ReviewerProjection(
				requireProjectionText(node.required("title"), path + ".title", 10_000),
				requireOptionalProjectionText(
						node.required("abstractText"), path + ".abstractText", 200_000),
				requirePublicationDate(
						node.required("publicationDate"), path + ".publicationDate"),
				requireOptionalInteger(
						node.required("publicationYear"), path + ".publicationYear"),
				requireArticleDocumentType(
						node.required("documentType"), path + ".documentType"),
				requireOptionalProjectionText(
						node.required("language"), path + ".language", 100),
				requireOptionalProjectionText(
						node.required("venueName"), path + ".venueName", 10_000),
				reviewerAuthors);
	}

	private static Map<String, Map<Scenario, ScenarioEvidence>> parseReconciliation(
			JsonNode root,
			String evidenceId,
			ProviderQualityComparativeScoringPolicy policy,
			Map<String, Candidate> candidates,
			int pageSize) {
		requireExact(root, "$reconciliation", RECONCILIATION_FIELDS);
		requireInt(root.required("schemaVersion"), "$reconciliation.schemaVersion", 2, 2);
		requireText(root.required("evidenceId"), "$reconciliation.evidenceId", evidenceId);
		JsonNode queries = root.required("queries");
		requireArray(queries, "$reconciliation.queries", 1, policy.limits().maximumQueries());
		Map<String, Map<Scenario, ScenarioEvidence>> result = new LinkedHashMap<>();
		for (int queryIndex = 0; queryIndex < queries.size(); queryIndex++) {
			JsonNode query = queries.get(queryIndex);
			String queryPath = "$reconciliation.queries[" + queryIndex + ']';
			requireExact(query, queryPath, RECONCILIATION_QUERY_FIELDS);
			String queryKey = requireText(query.required("queryKey"), queryPath + ".queryKey");
			requireBoolean(query.required("complete"), queryPath + ".complete", true);
			JsonNode scenarioNodes = query.required("scenarios");
			Set<String> expectedScenarioFields = policy.scenarios().stream()
					.map(Enum::name).collect(java.util.stream.Collectors.toUnmodifiableSet());
			requireExact(scenarioNodes, queryPath + ".scenarios", expectedScenarioFields);
			Map<Scenario, ScenarioEvidence> scenarioMap = new LinkedHashMap<>();
			for (Scenario scenario : policy.scenarios()) {
				scenarioMap.put(scenario, parseScenario(
						scenarioNodes.required(scenario.name()),
						queryPath + ".scenarios." + scenario.name(),
						queryKey,
						scenario,
						candidates,
						pageSize));
			}
			if (result.putIfAbsent(queryKey, Collections.unmodifiableMap(scenarioMap)) != null) {
				throw invalid("reconciliation query keys must be unique");
			}
		}
		return Collections.unmodifiableMap(result);
	}

	private static ScenarioEvidence parseScenario(
			JsonNode node,
			String path,
			String queryKey,
			Scenario scenario,
			Map<String, Candidate> allCandidates,
			int pageSize) {
		requireExact(node, path, SCENARIO_FIELDS);
		requireText(node.required("scenario"), path + ".scenario", scenario.name());
		Map<String, Candidate> queryCandidates = new LinkedHashMap<>();
		allCandidates.values().stream()
				.filter(candidate -> candidate.queryKey().equals(queryKey))
				.filter(candidate -> scenario == Scenario.FUSED
						|| candidate.provider().name().equals(scenario.name().replace("_ONLY", "")))
				.forEach(candidate -> queryCandidates.put(candidate.reviewKey(), candidate));

		JsonNode traceNodes = node.required("reconciliation");
		requireArray(traceNodes, path + ".reconciliation", 0, 40);
		List<Trace> traces = new ArrayList<>();
		Set<String> traceKeys = new LinkedHashSet<>();
		for (int index = 0; index < traceNodes.size(); index++) {
			JsonNode traceNode = traceNodes.get(index);
			String tracePath = path + ".reconciliation[" + index + ']';
			requireExact(traceNode, tracePath, TRACE_FIELDS);
			String reviewKey = requireSha256(traceNode.required("reviewKey"), tracePath + ".reviewKey");
			Candidate candidate = queryCandidates.get(reviewKey);
			if (candidate == null || !traceKeys.add(reviewKey)) {
				throw invalid("scenario reconciliation does not exactly match its provider candidates");
			}
			Provider provider = requireEnum(traceNode.required("provider"), tracePath + ".provider", Provider.class);
			String providerRecordId = requireText(
					traceNode.required("providerRecordId"), tracePath + ".providerRecordId");
			int providerRank = requireInt(
					traceNode.required("providerRank"), tracePath + ".providerRank", 1, 20);
			if (provider != candidate.provider()
					|| !providerRecordId.equals(candidate.providerRecordId())
					|| providerRank != candidate.providerRank()) {
				throw invalid("scenario reconciliation provenance does not match the candidate map");
			}
			traces.add(new Trace(
					reviewKey,
					provider,
					providerRecordId,
					providerRank,
					requireSha256(traceNode.required("clusterKey"), tracePath + ".clusterKey"),
					requireBoolean(traceNode.required("includedInFirstPage"),
							tracePath + ".includedInFirstPage")));
		}
		if (!traceKeys.equals(queryCandidates.keySet())) {
			throw invalid("scenario reconciliation does not exactly match its provider candidates");
		}

		JsonNode resultNodes = node.required("rankedResults");
		requireArray(resultNodes, path + ".rankedResults", 0, pageSize);
		List<RankedResult> rankedResults = new ArrayList<>();
		Set<String> rankedClusters = new LinkedHashSet<>();
		Map<String, Trace> traceByKey = new LinkedHashMap<>();
		traces.forEach(trace -> traceByKey.put(trace.reviewKey(), trace));
		for (int index = 0; index < resultNodes.size(); index++) {
			JsonNode resultNode = resultNodes.get(index);
			String resultPath = path + ".rankedResults[" + index + ']';
			requireExact(resultNode, resultPath, RANKED_RESULT_FIELDS);
			int rank = requireInt(resultNode.required("rank"), resultPath + ".rank", index + 1, index + 1);
			String primaryReviewKey = requireSha256(
					resultNode.required("primaryReviewKey"), resultPath + ".primaryReviewKey");
			Trace primary = traceByKey.get(primaryReviewKey);
			String clusterKey = requireSha256(
					resultNode.required("clusterKey"), resultPath + ".clusterKey");
			if (primary == null || !primary.clusterKey().equals(clusterKey)
					|| !rankedClusters.add(clusterKey)) {
				throw invalid("ranked result does not identify one unique reconciled cluster");
			}
			Provider primaryProvider = requireEnum(
					resultNode.required("primaryProvider"), resultPath + ".primaryProvider", Provider.class);
			String primaryRecordId = requireText(
					resultNode.required("primaryProviderRecordId"),
					resultPath + ".primaryProviderRecordId");
			if (primaryProvider != primary.provider()
					|| !primaryRecordId.equals(primary.providerRecordId())) {
				throw invalid("ranked result primary provenance does not match its trace");
			}
			JsonNode score = resultNode.required("score");
			if (!score.isNull() && !score.isNumber()) {
				throw invalid(resultPath + ".score must be numeric or null");
			}
			rankedResults.add(new RankedResult(
					rank, clusterKey, parsePresentFields(
							resultNode.required("presentFields"), resultPath + ".presentFields")));
		}
		Set<String> includedClusters = traces.stream()
				.filter(Trace::includedInFirstPage)
				.map(Trace::clusterKey)
				.collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
		if (!includedClusters.equals(rankedClusters)) {
			throw invalid("ranked clusters do not match first-page reconciliation flags");
		}
		Map<String, Set<Boolean>> flagsByCluster = new LinkedHashMap<>();
		for (Trace trace : traces) {
			flagsByCluster.computeIfAbsent(trace.clusterKey(), ignored -> new HashSet<>())
					.add(trace.includedInFirstPage());
		}
		if (flagsByCluster.values().stream().anyMatch(flags -> flags.size() != 1)) {
			throw invalid("first-page flags must be consistent within a reconciled cluster");
		}
		return new ScenarioEvidence(scenario, rankedResults, traces);
	}

	private static Set<MetadataField> parsePresentFields(JsonNode node, String path) {
		requireArray(node, path, 0, MetadataField.values().length);
		List<MetadataField> fields = new ArrayList<>();
		for (int index = 0; index < node.size(); index++) {
			fields.add(requireEnum(node.get(index), path + '[' + index + ']', MetadataField.class));
		}
		List<String> names = fields.stream().map(Enum::name).toList();
		if (!names.equals(names.stream().distinct().sorted().toList())) {
			throw invalid(path + " must contain sorted unique metadata-field names");
		}
		return Collections.unmodifiableSet(new LinkedHashSet<>(fields));
	}

	private static void requireExact(JsonNode node, String path, Set<String> expected) {
		if (node == null || !node.isObject()) {
			throw invalid(path + " must be an object");
		}
		Set<String> actual = new LinkedHashSet<>(node.propertyNames());
		if (!actual.equals(expected)) {
			throw invalid(path + " must contain exactly the frozen fields");
		}
	}

	private static void requireArray(JsonNode node, String path, int minimum, int maximum) {
		if (node == null || !node.isArray() || node.size() < minimum || node.size() > maximum) {
			throw invalid(path + " has an invalid array length");
		}
	}

	private static String requireText(JsonNode node, String path) {
		if (node == null || !node.isString()) {
			throw invalid(path + " must be text");
		}
		String value = node.asString();
		if (!value.equals(value.strip()) || !SAFE_TEXT.matcher(value).matches()) {
			throw invalid(path + " must be bounded text without surrounding whitespace");
		}
		return value;
	}

	private static String requireBoundedText(
			JsonNode node, String path, int minimum, int maximum) {
		String value = requireText(node, path);
		if (value.length() < minimum || value.length() > maximum) {
			throw invalid(path + " is outside the frozen text range");
		}
		return value;
	}

	private static String requireProjectionText(JsonNode node, String path, int maximum) {
		if (node == null || !node.isString()) {
			throw invalid(path + " must be text");
		}
		String value = node.asString();
		if (!value.equals(value.strip()) || value.isEmpty() || value.length() > maximum) {
			throw invalid(path + " must be bounded text without surrounding whitespace");
		}
		return value;
	}

	private static String requireOptionalProjectionText(
			JsonNode node, String path, int maximum) {
		if (node != null && node.isNull()) {
			return null;
		}
		return requireProjectionText(node, path, maximum);
	}

	private static String requirePublicationDate(JsonNode node, String path) {
		String value = requireOptionalProjectionText(node, path, 32);
		if (value == null) {
			return null;
		}
		try {
			LocalDate.parse(value);
			return value;
		}
		catch (RuntimeException exception) {
			throw invalid(path + " must be an ISO-8601 local date");
		}
	}

	private static String requireArticleDocumentType(JsonNode node, String path) {
		String value = requireProjectionText(node, path, 100);
		if (!"ARTICLE".equals(value)) {
			throw invalid(path + " must be ARTICLE");
		}
		return value;
	}

	private static Integer requireOptionalInteger(JsonNode node, String path) {
		return requireOptionalInt(node, path, 1_000, 9_999);
	}

	private static Integer requireOptionalInt(
			JsonNode node, String path, int minimum, int maximum) {
		if (node != null && node.isNull()) {
			return null;
		}
		return requireInt(node, path, minimum, maximum);
	}

	private static void requireText(JsonNode node, String path, String expected) {
		if (!expected.equals(requireText(node, path))) {
			throw invalid(path + " does not match the bound value");
		}
	}

	private static String requireSha256(JsonNode node, String path) {
		String value = requireText(node, path);
		if (!SHA256.matcher(value).matches()) {
			throw invalid(path + " must be a lowercase SHA-256 value");
		}
		return value;
	}

	private static int requireInt(JsonNode node, String path, int minimum, int maximum) {
		if (node == null || !node.isInt()) {
			throw invalid(path + " must be an integer");
		}
		int value = node.asInt();
		if (value < minimum || value > maximum) {
			throw invalid(path + " is outside the frozen range");
		}
		return value;
	}

	private static long requireLong(JsonNode node, String path, long minimum, long maximum) {
		if (node == null || !node.isIntegralNumber() || !node.canConvertToLong()) {
			throw invalid(path + " must be an integer");
		}
		long value = node.longValue();
		if (value < minimum || value > maximum) {
			throw invalid(path + " is outside the frozen range");
		}
		return value;
	}

	private static boolean requireBoolean(JsonNode node, String path) {
		if (node == null || !node.isBoolean()) {
			throw invalid(path + " must be boolean");
		}
		return node.asBoolean();
	}

	private static void requireBoolean(JsonNode node, String path, boolean expected) {
		if (requireBoolean(node, path) != expected) {
			throw invalid(path + " does not match the frozen boundary");
		}
	}

	private static <E extends Enum<E>> E requireEnum(
			JsonNode node, String path, Class<E> enumType) {
		String value = requireText(node, path);
		try {
			return Enum.valueOf(enumType, value);
		}
		catch (IllegalArgumentException exception) {
			throw invalid(path + " has an unsupported value");
		}
	}

	private static boolean sameDigest(String left, String right) {
		return left != null && right != null
				&& MessageDigest.isEqual(
						left.getBytes(StandardCharsets.US_ASCII),
						right.getBytes(StandardCharsets.US_ASCII));
	}

	private static String reportId(String evidence, String judgments, String policy) {
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			digest.update("openscholar-comparative-score-v1".getBytes(StandardCharsets.US_ASCII));
			for (String value : List.of(evidence, judgments, policy)) {
				digest.update((byte) 0);
				digest.update(value.getBytes(StandardCharsets.US_ASCII));
			}
			return "provider-comparative-score-" + HexFormat.of().formatHex(digest.digest());
		}
		catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException("SHA-256 is unavailable", exception);
		}
	}

	private static IllegalArgumentException invalid(String message) {
		return new IllegalArgumentException(message);
	}

	private static Double nullableRatio(long numerator, long denominator) {
		return denominator == 0 ? null : (double) numerator / denominator;
	}

	private enum Provider {
		OPENALEX,
		EUROPE_PMC
	}

	record ScoringResult(
			int schemaVersion,
			String reportId,
			String evidenceId,
			String evidenceManifestSha256,
			String captureRepositoryRevision,
			String judgmentPacketSha256,
			String reviewPacketSha256,
			String querySetId,
			String querySetSha256,
			String scoringPolicyId,
			String scoringPolicySha256,
			int queryCount,
			UniqueRelevantQueryCoverage uniqueRelevantQueryCoverage,
			List<QueryScore> queries,
			Map<Scenario, ScenarioSummary> scenarios,
			boolean readerFacing,
			boolean defaultEnablementDecision) {

		ScoringResult {
			queries = List.copyOf(queries);
			scenarios = Collections.unmodifiableMap(new LinkedHashMap<>(scenarios));
		}
	}

	record QueryScore(String queryKey, Map<Scenario, QueryScenarioScore> scenarios) {

		QueryScore {
			scenarios = Collections.unmodifiableMap(new LinkedHashMap<>(scenarios));
		}
	}

	record QueryScenarioScore(
			int rankedResultCount,
			int creditedGoldWorkCount,
			RankingScore ranking,
			DeduplicationScore deduplication,
			ExpectedFieldRecovery metadataRecovery,
			MustSeparateMeasurement mustSeparate) {
	}

	record ScenarioSummary(
			RankingSummaryScore ranking,
			DeduplicationScore deduplication,
			ExpectedFieldRecovery metadataRecovery,
			MustSeparateMeasurement mustSeparate) {
	}

	record RankingScore(
			long relevantGoldWorkCount,
			Double recall,
			Double ndcg,
			double precision,
			Double reciprocalRank) {
	}

	record RankingSummaryScore(
			int queryCount,
			int relevanceApplicableQueryCount,
			int noRelevantGoldQueryCount,
			Double macroRecall,
			Double macroNdcg,
			double macroPrecision,
			Double meanReciprocalRank) {
	}

	record DeduplicationScore(
			long candidateCount,
			long evaluatedPairCount,
			long truePositives,
			long falsePositives,
			long falseNegatives,
			long trueNegatives,
			Double precision,
			Double recall,
			Double f1) {
	}

	record ExpectedFieldRecovery(
			long creditedGoldWorks,
			long goldWorksWithExpectations,
			long expectedFieldCount,
			long recoveredFieldCount,
			Double recoveryRate,
			Map<MetadataField, FieldRecovery> fields) {

		ExpectedFieldRecovery {
			fields = Collections.unmodifiableMap(new EnumMap<>(fields));
		}
	}

	record FieldRecovery(long expectedCount, long recoveredCount, Double recoveryRate) {
	}

	record MustSeparateMeasurement(long applicablePairs, long violations, Double passRate) {
	}

	record UniqueRelevantQueryCoverage(
			int coveredQueries, int totalQueries, double rate, List<String> queryKeys) {

		UniqueRelevantQueryCoverage {
			queryKeys = List.copyOf(queryKeys);
		}
	}

	private record Evidence(
			String querySetId,
			String querySetSha256,
			String captureRepositoryRevision,
			List<EvidenceQuery> queries) {

		private Evidence {
			queries = List.copyOf(queries);
		}
	}

	private record SummaryQuery(
			String queryKey,
			int rawCandidateCount,
			Map<Provider, Integer> providerReturnedRecords,
			Map<Scenario, Integer> scenarioResultCounts) {

		private SummaryQuery {
			providerReturnedRecords = Collections.unmodifiableMap(
					new EnumMap<>(providerReturnedRecords));
			scenarioResultCounts = Collections.unmodifiableMap(
					new EnumMap<>(scenarioResultCounts));
		}
	}

	private record EvidenceQuery(
			String queryKey,
			Map<String, Candidate> candidates,
			Map<Scenario, ScenarioEvidence> scenarios) {

		private EvidenceQuery {
			candidates = Collections.unmodifiableMap(new LinkedHashMap<>(candidates));
			scenarios = Collections.unmodifiableMap(new LinkedHashMap<>(scenarios));
		}
	}

	private record Candidate(
			String reviewKey,
			String queryKey,
			Provider provider,
			String providerRecordId,
			int providerRank,
			ReviewerProjection reviewerProjection) {
	}

	private record BlindedCandidate(
			String queryKey, ReviewerProjection reviewerProjection) {
	}

	private record ReviewerProjection(
			String title,
			String abstractText,
			String publicationDate,
			Integer publicationYear,
			String documentType,
			String language,
			String venueName,
			List<ReviewerAuthor> authors) {

		private ReviewerProjection {
			authors = List.copyOf(authors);
		}
	}

	private record ReviewerAuthor(
			String displayName, int position, boolean corresponding) {
	}

	private record ScenarioEvidence(
			Scenario scenario, List<RankedResult> rankedResults, List<Trace> traces) {

		private ScenarioEvidence {
			rankedResults = List.copyOf(rankedResults);
			traces = List.copyOf(traces);
		}
	}

	private record RankedResult(int rank, String clusterKey, Set<MetadataField> presentFields) {

		private RankedResult {
			presentFields = Set.copyOf(presentFields);
		}
	}

	private record Trace(
			String reviewKey,
			Provider provider,
			String providerRecordId,
			int providerRank,
			String clusterKey,
			boolean includedInFirstPage) {
	}

	private static final class MutablePairCounts {

		private long candidateCount;
		private long evaluatedPairCount;
		private long truePositives;
		private long falsePositives;
		private long falseNegatives;
		private long trueNegatives;

		private void add(DeduplicationScore value) {
			candidateCount += value.candidateCount();
			evaluatedPairCount += value.evaluatedPairCount();
			truePositives += value.truePositives();
			falsePositives += value.falsePositives();
			falseNegatives += value.falseNegatives();
			trueNegatives += value.trueNegatives();
		}

		private DeduplicationScore finish() {
			return deduplicationScore(
					candidateCount,
					evaluatedPairCount,
					truePositives,
					falsePositives,
					falseNegatives,
					trueNegatives);
		}
	}

	private static final class MutableMetadataRecovery {

		private long creditedGoldWorks;
		private long goldWorksWithExpectations;
		private long expectedFieldCount;
		private long recoveredFieldCount;
		private final Map<MetadataField, long[]> fields = new EnumMap<>(MetadataField.class);

		private MutableMetadataRecovery() {
			for (MetadataField field : MetadataField.values()) {
				fields.put(field, new long[2]);
			}
		}

		private void add(GoldPaper goldPaper, Set<MetadataField> presentFields) {
			creditedGoldWorks++;
			if (!goldPaper.expectedFields().isEmpty()) {
				goldWorksWithExpectations++;
			}
			for (MetadataField field : goldPaper.expectedFields()) {
				expectedFieldCount++;
				fields.get(field)[0]++;
				if (presentFields.contains(field)) {
					recoveredFieldCount++;
					fields.get(field)[1]++;
				}
			}
		}

		private void add(ExpectedFieldRecovery value) {
			creditedGoldWorks += value.creditedGoldWorks();
			goldWorksWithExpectations += value.goldWorksWithExpectations();
			expectedFieldCount += value.expectedFieldCount();
			recoveredFieldCount += value.recoveredFieldCount();
			value.fields().forEach((field, measurement) -> {
				fields.get(field)[0] += measurement.expectedCount();
				fields.get(field)[1] += measurement.recoveredCount();
			});
		}

		private ExpectedFieldRecovery finish() {
			Map<MetadataField, FieldRecovery> result = new EnumMap<>(MetadataField.class);
			fields.forEach((field, counts) -> result.put(field, new FieldRecovery(
					counts[0], counts[1], counts[0] == 0 ? null : (double) counts[1] / counts[0])));
			return new ExpectedFieldRecovery(
					creditedGoldWorks,
					goldWorksWithExpectations,
					expectedFieldCount,
					recoveredFieldCount,
					expectedFieldCount == 0
							? null
							: (double) recoveredFieldCount / expectedFieldCount,
					result);
		}
	}

	private static final class MutableMustSeparate {

		private long applicablePairs;
		private long violations;

		private void add(MustSeparateMeasurement value) {
			applicablePairs += value.applicablePairs();
			violations += value.violations();
		}

		private MustSeparateMeasurement finish() {
			return new MustSeparateMeasurement(
					applicablePairs,
					violations,
					applicablePairs == 0
							? null
							: (double) (applicablePairs - violations) / applicablePairs);
		}
	}
}
