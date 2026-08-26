package com.openscholar.search.internal.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.IntStream;

import com.openscholar.TestcontainersConfiguration;
import com.openscholar.paper.DocumentType;
import com.openscholar.paper.PaperIdentifier;
import com.openscholar.paper.PaperIdentifierType;
import com.openscholar.paper.PaperView;
import com.openscholar.provider.ProviderAuthor;
import com.openscholar.provider.ProviderException;
import com.openscholar.provider.ProviderId;
import com.openscholar.provider.ProviderPaperRecord;
import com.openscholar.provider.ProviderSearchBatchResult;
import com.openscholar.provider.ProviderSearchResult;
import com.openscholar.search.CacheDisposition;
import com.openscholar.search.SearchCommand;
import com.openscholar.search.SearchFingerprintVersion;
import com.openscholar.search.SearchMode;
import com.openscholar.search.SearchResultView;
import com.openscholar.search.SearchView;
import com.openscholar.search.internal.persistence.ProviderQualityEvaluationFixture.CriticalRelation;
import com.openscholar.search.internal.persistence.ProviderQualityEvaluationFixture.EvaluationQuery;
import com.openscholar.search.internal.persistence.ProviderQualityEvaluationFixture.FixtureRecord;
import com.openscholar.search.internal.persistence.ProviderQualityEvaluationFixture.ProviderResult;
import com.openscholar.search.internal.persistence.ProviderQualityMetrics.DedupObservation;
import com.openscholar.search.internal.persistence.ProviderQualityMetrics.FieldCoverage;
import com.openscholar.search.internal.persistence.ProviderQualityMetrics.MetadataField;
import com.openscholar.search.internal.persistence.ProviderQualityMetrics.MetadataObservation;
import com.openscholar.search.internal.persistence.ProviderQualityMetrics.RankedContribution;
import com.openscholar.search.internal.persistence.ProviderQualityMetrics.RankingMeasurement;
import com.openscholar.search.internal.persistence.ProviderQualityMetrics.RankingSummary;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
class EuropePmcProviderQualityEvaluationTests {

	private static final String FIXTURE_PATH =
			"search/provider-quality/provider-fusion-development-v1.json";
	private static final String POLICY_PATH =
			"search/provider-quality/provider-fusion-policy-v1.json";
	private static final String PIPELINE_VERSION = "provider-fanout-v1";
	private static final Instant RETRIEVED_AT = Instant.parse("2026-08-26T09:00:00Z");
	private static final UUID OWNER_ID =
			UUID.fromString("00000000-0000-0000-0000-000000000001");
	private static final double EPSILON = 1.0e-12d;
	@Autowired
	private SearchSnapshotStore snapshotStore;

	@Autowired
	private ObjectMapper objectMapper;

	@Autowired
	private PlatformTransactionManager transactionManager;

	@Test
	void europePmcCandidateMeetsFrozenIncrementalQualityAndSafetyGates() throws Exception {
		ProviderQualityEvaluationFixture fixture = ProviderQualityEvaluationFixture.load(
				objectMapper, FIXTURE_PATH);
		ProviderQualityEvaluationPolicy policy = ProviderQualityEvaluationPolicy.loadBound(
				objectMapper, POLICY_PATH, fixture, FIXTURE_PATH);

		List<RankingMeasurement> openAlexMeasurements = new ArrayList<>();
		List<RankingMeasurement> europePmcMeasurements = new ArrayList<>();
		List<RankingMeasurement> fusedMeasurements = new ArrayList<>();
		List<DedupObservation> deduplicationObservations = new ArrayList<>();
		List<MetadataObservation> openAlexMetadata = new ArrayList<>();
		List<MetadataObservation> fusedMetadata = new ArrayList<>();
		List<MetadataObservation> europePmcSourceMetadata = new ArrayList<>();
		List<CriticalFalseMerge> criticalFalseMerges = new ArrayList<>();
		int europePmcUniqueRelevantQueries = 0;
		int regressingQueries = 0;

		for (EvaluationQuery query : fixture.queries()) {
			QueryIndex index = new QueryIndex(query);
			ProviderSearchResult openAlex = providerSearchResult(
					index.providerResult(ProviderId.OPENALEX));
			ProviderSearchResult europePmc = providerSearchResult(
					index.providerResult(ProviderId.EUROPE_PMC));

			Scenario openAlexOnly = storeScenario(
					query, index, "openalex-only", List.of(openAlex), List.of());
			Scenario europePmcOnly = storeScenario(
					query, index, "europe-pmc-only", List.of(europePmc), List.of());
			Scenario fused = storeScenario(
					query, index, "fused", List.of(openAlex, europePmc), List.of());
			Scenario reversed = storeScenario(
					query, index, "fused-reversed", List.of(europePmc, openAlex), List.of());
			Scenario replayed = storeScenario(
					query, index, "fused-replayed", List.of(openAlex, europePmc), List.of());

			assertStableReplay(fused, reversed);
			assertStableReplay(fused, replayed);
			assertProductionRrf(fused, query, index, policy.ranking().rrfK());

			RankingMeasurement openAlexQuality = ProviderQualityMetrics.measureRanking(
					openAlexOnly.rankedGoldKeys(), query.judgments(), policy.rankingCutoffs());
			RankingMeasurement europePmcQuality = ProviderQualityMetrics.measureRanking(
					europePmcOnly.rankedGoldKeys(), query.judgments(), policy.rankingCutoffs());
			RankingMeasurement fusedQuality = ProviderQualityMetrics.measureRanking(
					fused.rankedGoldKeys(), query.judgments(), policy.rankingCutoffs());
			openAlexMeasurements.add(openAlexQuality);
			europePmcMeasurements.add(europePmcQuality);
			fusedMeasurements.add(fusedQuality);

			double ndcgDelta = fusedQuality.ndcg() - openAlexQuality.ndcg();
			if (ndcgDelta < -EPSILON) {
				regressingQueries++;
				assertThat(ndcgDelta)
						.as("nDCG delta for %s", query.key())
						.isGreaterThanOrEqualTo(-policy.gates().maximumPerQueryNdcgRegression());
			}
			if (hasEuropePmcUniqueRelevantResult(query, openAlexOnly, europePmcOnly)) {
				europePmcUniqueRelevantQueries++;
			}

			deduplicationObservations.addAll(deduplicationObservations(query, fused));
			criticalFalseMerges.addAll(assertCriticalPairs(query, fused));
			openAlexMetadata.addAll(metadataObservations(
					query.key() + ":openalex", openAlexOnly));
			europePmcSourceMetadata.addAll(metadataObservations(
					query.key() + ":europe-pmc", europePmcOnly));
			fusedMetadata.addAll(metadataObservations(query.key() + ":fused", fused));
			assertPopulatedProviderFieldsSurviveFusion(
					ProviderId.OPENALEX, openAlexOnly, fused);
			assertPopulatedProviderFieldsSurviveFusion(
					ProviderId.EUROPE_PMC, europePmcOnly, fused);
			printQueryMeasurement(
					query.key(), openAlexQuality, europePmcQuality, fusedQuality, ndcgDelta);
		}

		RankingSummary openAlexSummary = ProviderQualityMetrics.summarizeRankings(openAlexMeasurements);
		RankingSummary europePmcSummary = ProviderQualityMetrics.summarizeRankings(europePmcMeasurements);
		RankingSummary fusedSummary = ProviderQualityMetrics.summarizeRankings(fusedMeasurements);
		ProviderQualityMetrics.PairwiseDeduplication deduplication =
				ProviderQualityMetrics.measureDeduplication(deduplicationObservations);
		FieldCoverage sourceCoverage = ProviderQualityMetrics.measureFieldCoverage(europePmcSourceMetadata);
		FieldCoverage baselineCoverage = ProviderQualityMetrics.measureFieldCoverage(openAlexMetadata);
		FieldCoverage candidateCoverage = ProviderQualityMetrics.measureFieldCoverage(fusedMetadata);
		double completenessDelta = ProviderQualityMetrics.completenessDelta(
				candidateCoverage,
				baselineCoverage,
				Set.copyOf(policy.metadataCoverageFields()));
		assertProviderMetadataVariation(ProviderId.OPENALEX, baselineCoverage);
		assertProviderMetadataVariation(ProviderId.EUROPE_PMC, sourceCoverage);
		assertPerFieldFusionGain(policy, baselineCoverage, candidateCoverage);

		assertThat(europePmcUniqueRelevantQueries)
				.isGreaterThanOrEqualTo(policy.gates().minimumEuropePmcUniqueRelevantQueryCount());
		assertThat(fusedSummary.macroRecall() - openAlexSummary.macroRecall())
				.isGreaterThanOrEqualTo(policy.gates().minimumMacroRecallGain() - EPSILON);
		assertThat(fusedSummary.macroNdcg() - openAlexSummary.macroNdcg())
				.isGreaterThanOrEqualTo(policy.gates().minimumMacroNdcgDelta() - EPSILON);
		assertThat(fusedSummary.macroPrecision() - openAlexSummary.macroPrecision())
				.isGreaterThanOrEqualTo(policy.gates().minimumMacroPrecisionDelta() - EPSILON);
		assertThat(fusedSummary.meanReciprocalRank() - openAlexSummary.meanReciprocalRank())
				.isGreaterThanOrEqualTo(policy.gates().minimumMrrDelta() - EPSILON);
		assertThat(regressingQueries).isLessThanOrEqualTo(policy.gates().maximumRegressingQueryCount());
		assertThat(criticalFalseMerges.size())
				.as("critical MUST_SEPARATE false merges: %s", criticalFalseMerges)
				.isLessThanOrEqualTo(policy.gates().maximumCriticalFalseMerges());
		assertThat(deduplication.precision())
				.isGreaterThanOrEqualTo(policy.gates().minimumExactDedupPrecision());
		assertThat(deduplication.recall())
				.isGreaterThanOrEqualTo(policy.gates().minimumExactDedupRecall());
		assertThat(deduplication.f1()).isEqualTo(1.0d);
		assertThat(completenessDelta)
				.isGreaterThanOrEqualTo(policy.gates().minimumFusedCompletenessDelta() - EPSILON);

		printSummary(
				fixture, openAlexSummary, europePmcSummary, fusedSummary,
				europePmcUniqueRelevantQueries, deduplication, criticalFalseMerges.size(),
				sourceCoverage, completenessDelta);
	}

	@Test
	void emptyOrFailedEuropePmcContributionPreservesOpenAlexOrder() throws Exception {
		ProviderQualityEvaluationFixture fixture = ProviderQualityEvaluationFixture.load(
				objectMapper, FIXTURE_PATH);
		EvaluationQuery query = fixture.queries().getFirst();
		QueryIndex index = new QueryIndex(query);
		ProviderSearchResult openAlex = providerSearchResult(index.providerResult(ProviderId.OPENALEX));
		ProviderSearchResult emptyEuropePmc = new ProviderSearchResult(
				ProviderId.EUROPE_PMC, List.of(), 0, null, retrievedAt(ProviderId.EUROPE_PMC));
		ProviderException europePmcFailure = new ProviderException(
				ProviderId.EUROPE_PMC,
				"EUROPE_PMC_EVALUATION_FAILURE",
				"Synthetic evaluation failure",
				true,
				null,
				null);

		Scenario baseline = storeScenario(
				query, index, "empty-control", List.of(openAlex), List.of());
		Scenario empty = storeScenario(
				query, index, "empty-europe-pmc", List.of(openAlex, emptyEuropePmc), List.of());
		Scenario failed = storeScenario(
				query, index, "failed-europe-pmc", List.of(openAlex), List.of(europePmcFailure));

		assertThat(empty.rankedGoldKeys()).containsExactlyElementsOf(baseline.rankedGoldKeys());
		assertThat(failed.rankedGoldKeys()).containsExactlyElementsOf(baseline.rankedGoldKeys());
		assertThat(empty.view().results()).allSatisfy(result -> assertRrfScoreForSingleContribution(result));
		assertThat(failed.view().results()).allSatisfy(result -> assertRrfScoreForSingleContribution(result));
		assertThat(empty.view().providerCoverage())
				.extracting(coverage -> coverage.provider(), coverage -> coverage.status())
				.containsExactly(
						org.assertj.core.groups.Tuple.tuple(ProviderId.EUROPE_PMC, "SUCCESS"),
						org.assertj.core.groups.Tuple.tuple(ProviderId.OPENALEX, "SUCCESS"));
		assertThat(failed.view().warnings()).containsExactly("EUROPE_PMC_EVALUATION_FAILURE");
	}

	private Scenario storeScenario(
			EvaluationQuery query,
			QueryIndex index,
			String scenario,
			List<ProviderSearchResult> results,
			List<ProviderException> failures) {
		TransactionTemplate transaction = new TransactionTemplate(transactionManager);
		transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
		return Objects.requireNonNull(transaction.execute(status -> {
			Scenario stored = storeScenarioInCurrentTransaction(
					query, index, scenario, results, failures);
			assertThat(snapshotStore.findById(OWNER_ID, stored.view().searchId()))
					.hasValueSatisfying(reloaded -> assertThat(reloaded.results())
							.isEqualTo(stored.view().results()));
			status.setRollbackOnly();
			return stored;
		}));
	}

	private Scenario storeScenarioInCurrentTransaction(
			EvaluationQuery query,
			QueryIndex index,
			String scenario,
			List<ProviderSearchResult> results,
			List<ProviderException> failures) {
		Instant searchedAt = results.stream()
				.map(ProviderSearchResult::retrievedAt)
				.max(Instant::compareTo)
				.orElse(RETRIEVED_AT);
		ProviderSearchBatchResult batch = new ProviderSearchBatchResult(
				results, failures, null, searchedAt);
		SearchView view = snapshotStore.store(
				OWNER_ID,
				command(query),
				query.text().toLowerCase(Locale.ROOT),
				sha256(query.key() + '\n' + scenario),
				SearchFingerprintVersion.CURRENT,
				PIPELINE_VERSION,
				batch,
				searchedAt.plus(Duration.ofHours(1)),
				CacheDisposition.MISS_FETCHED);
		return Scenario.from(view, index);
	}

	private static ProviderSearchResult providerSearchResult(ProviderResult fixtureResult) {
		Instant retrievedAt = retrievedAt(fixtureResult.provider());
		return new ProviderSearchResult(
				fixtureResult.provider(),
				fixtureResult.records().stream()
						.map(record -> providerPaperRecord(fixtureResult.provider(), record, retrievedAt))
						.toList(),
				fixtureResult.records().size(),
				null,
				retrievedAt);
	}

	private static ProviderPaperRecord providerPaperRecord(
			ProviderId provider, FixtureRecord record, Instant retrievedAt) {
		List<PaperIdentifier> identifiers = record.identifiers().stream()
				.map(identifier -> new PaperIdentifier(identifier.type(), "", identifier.value()))
				.toList();
		List<ProviderAuthor> authors = IntStream.range(0, record.authors().size())
				.mapToObj(index -> new ProviderAuthor(
						null,
						record.authors().get(index).displayName(),
						record.authors().get(index).orcid(),
						index + 1,
						false))
				.toList();
		URI sourceUrl = URI.create(record.sourceUrl());
		return new ProviderPaperRecord(
				provider,
				record.providerRecordId(),
				identifierValue(record, PaperIdentifierType.DOI),
				null,
				record.title(),
				record.abstractText(),
				null,
				record.publicationYear(),
				record.documentType(),
				record.language(),
				record.venueName(),
				record.citationCount(),
				authors,
				record.reportedOpenAccess(),
				sourceUrl,
				null,
				null,
				retrievedAt,
				Map.of("fixtureKey", record.key(), "goldPaperKey", record.goldPaperKey()),
				identifiers,
				sourceUrl,
				null,
				null,
				null,
				null,
				null,
				null,
				null,
				List.of(),
				record.issn(),
				null);
	}

	private static void assertProductionRrf(
			Scenario fused, EvaluationQuery query, QueryIndex index, int rrfK) {
		List<RankedContribution> contributions = new ArrayList<>();
		for (ProviderResult providerResult : query.providerResults()) {
			int providerRank = 1;
			for (FixtureRecord record : providerResult.records()) {
				contributions.add(new RankedContribution(
						providerResult.provider().name(),
						record.providerRecordId(),
						record.goldPaperKey(),
						Objects.requireNonNull(fused.canonicalByRecordKey().get(record.key())),
						providerRank++));
			}
		}
		var expected = ProviderQualityMetrics.reciprocalRankFusion(contributions, rrfK);
		assertThat(fused.view().results())
				.extracting(result -> result.paper().id())
				.containsExactlyElementsOf(expected.stream()
						.map(ProviderQualityMetrics.FusedScore::canonicalPaperId)
						.toList());
		for (int resultIndex = 0; resultIndex < expected.size(); resultIndex++) {
			SearchResultView actual = fused.view().results().get(resultIndex);
			ProviderQualityMetrics.FusedScore expectedScore = expected.get(resultIndex);
			assertThat(actual.rank()).isEqualTo(resultIndex + 1);
			assertThat(actual.score()).isFinite().isPositive()
					.isCloseTo(expectedScore.score(), within(EPSILON));
			assertThat(actual.provider().name()).isEqualTo(expectedScore.primaryProvider());
			assertThat(actual.providerRecordId()).isEqualTo(expectedScore.primaryProviderRecordId());
			assertThat(actual.rankingReasons()).singleElement().satisfies(reason -> {
				assertThat(reason.feature()).isEqualTo("PROVIDER_RECIPROCAL_RANK_FUSION");
				assertThat(reason.value()).isCloseTo(expectedScore.score(), within(EPSILON));
			});
			assertThat(index.primaryGoldKey(actual)).isEqualTo(expectedScore.paperKey());
			assertThat(actual.providerContributions())
					.extracting(contribution -> contribution.provider())
					.doesNotHaveDuplicates();
		}
	}

	private static void assertStableReplay(Scenario expected, Scenario replayed) {
		assertThat(replayed.rankedGoldKeys()).containsExactlyElementsOf(expected.rankedGoldKeys());
		assertThat(replayed.resultSignatures()).isEqualTo(expected.resultSignatures());
		assertThat(replayed.canonicalPartition()).isEqualTo(expected.canonicalPartition());
	}

	private static List<CriticalFalseMerge> assertCriticalPairs(
			EvaluationQuery query, Scenario fused) {
		List<CriticalFalseMerge> falseMerges = new ArrayList<>();
		for (ProviderQualityEvaluationFixture.CriticalPair pair : query.criticalPairs()) {
			UUID left = Objects.requireNonNull(fused.canonicalByRecordKey().get(pair.leftRecordKey()));
			UUID right = Objects.requireNonNull(fused.canonicalByRecordKey().get(pair.rightRecordKey()));
			if (pair.relation() == CriticalRelation.MUST_LINK) {
				assertThat(left).as(pair.reason()).isEqualTo(right);
				SearchResultView result = fused.view().results().stream()
						.filter(candidate -> candidate.paper().id().equals(left))
						.findFirst()
						.orElseThrow();
				assertThat(result.providerContributions())
						.extracting(contribution -> contribution.provider())
						.containsExactlyInAnyOrder(ProviderId.OPENALEX, ProviderId.EUROPE_PMC);
				assertThat(result.paper().identifiers())
						.extracting(PaperIdentifier::type)
						.contains(PaperIdentifierType.valueOf(pair.signal().name()));
			}
			else if (left.equals(right)) {
				falseMerges.add(new CriticalFalseMerge(
						query.key(), pair.leftRecordKey(), pair.rightRecordKey(), pair.reason()));
			}
			else {
				assertThat(left).as(pair.reason()).isNotEqualTo(right);
			}
		}
		return List.copyOf(falseMerges);
	}

	private static List<DedupObservation> deduplicationObservations(
			EvaluationQuery query, Scenario fused) {
		return query.recordsByKey().values().stream()
				.map(record -> new DedupObservation(
						record.key(),
						record.goldPaperKey(),
						Objects.requireNonNull(fused.canonicalByRecordKey().get(record.key())).toString()))
				.toList();
	}

	private static List<MetadataObservation> metadataObservations(String prefix, Scenario scenario) {
		List<MetadataObservation> observations = new ArrayList<>();
		for (SearchResultView result : scenario.view().results()) {
			observations.add(new MetadataObservation(
					prefix + ':' + result.rank(), presentFields(result)));
		}
		return List.copyOf(observations);
	}

	private static Set<MetadataField> presentFields(SearchResultView result) {
		PaperView paper = result.paper();
		Set<MetadataField> fields = EnumSet.of(MetadataField.TITLE, MetadataField.DOCUMENT_TYPE);
		if (result.landingPageUrl() != null) {
			fields.add(MetadataField.SOURCE_URL);
		}
		for (PaperIdentifier identifier : paper.identifiers()) {
			try {
				fields.add(MetadataField.valueOf(identifier.type().name()));
			}
			catch (IllegalArgumentException ignored) {
				// Only the frozen bibliographic fields are scored.
			}
		}
		if (hasText(paper.abstractText())) {
			fields.add(MetadataField.ABSTRACT);
		}
		if (!paper.authors().isEmpty()) {
			fields.add(MetadataField.AUTHORS);
		}
		if (paper.authors().stream().anyMatch(author -> hasText(author.orcid()))) {
			fields.add(MetadataField.ORCID);
		}
		if (paper.publicationYear() != null) {
			fields.add(MetadataField.PUBLICATION_YEAR);
		}
		if (hasText(paper.venueName())) {
			fields.add(MetadataField.VENUE);
		}
		if (hasText(paper.language())) {
			fields.add(MetadataField.LANGUAGE);
		}
		if (!paper.issn().isEmpty()) {
			fields.add(MetadataField.ISSN);
		}
		if (paper.citationCount() != null) {
			fields.add(MetadataField.CITATION_COUNT);
		}
		return Set.copyOf(fields);
	}

	private static void assertPopulatedProviderFieldsSurviveFusion(
			ProviderId provider, Scenario baseline, Scenario fused) {
		Map<String, SearchResultView> fusedByProviderRecord = new LinkedHashMap<>();
		for (SearchResultView result : fused.view().results()) {
			result.providerContributions().stream()
					.filter(contribution -> contribution.provider() == provider)
					.forEach(contribution -> fusedByProviderRecord.put(
							contribution.providerRecordId(), result));
		}
		for (SearchResultView baselineResult : baseline.view().results()) {
			SearchResultView candidate = Objects.requireNonNull(
					fusedByProviderRecord.get(baselineResult.providerRecordId()));
			PaperView before = baselineResult.paper();
			PaperView after = candidate.paper();
			assertThat(after.title()).isNotBlank();
			assertThat(after.documentType()).isEqualTo(before.documentType());
			assertTextPreserved(before.abstractText(), after.abstractText());
			assertTextPreserved(before.language(), after.language());
			assertTextPreserved(before.venueName(), after.venueName());
			if (before.publicationYear() != null) {
				assertThat(after.publicationYear()).isNotNull();
			}
			if (before.citationCount() != null) {
				assertThat(after.citationCount()).isNotNull();
			}
			if (!before.authors().isEmpty()) {
				assertThat(after.authors()).isNotEmpty();
			}
			if (candidate.providerContributions().size() == 1
					&& before.authors().stream().anyMatch(author -> hasText(author.orcid()))) {
				assertThat(after.authors()).anyMatch(author -> hasText(author.orcid()));
			}
			if (!before.issn().isEmpty()) {
				assertThat(after.issn()).isNotEmpty();
			}
			assertThat(after.identifiers()).containsAll(before.identifiers());
		}
	}

	private static void assertPerFieldFusionGain(
			ProviderQualityEvaluationPolicy policy,
			FieldCoverage baseline,
			FieldCoverage candidate) {
		for (MetadataField field : policy.metadataFusionGainFields()) {
			double delta = candidate.fields().get(field).rate()
					- baseline.fields().get(field).rate();
			assertThat(delta)
					.as("fused metadata coverage delta for %s", field)
					.isGreaterThanOrEqualTo(
							policy.gates().minimumPerFieldFusionDelta() - EPSILON);
		}
	}

	private static void assertProviderMetadataVariation(
			ProviderId provider, FieldCoverage coverage) {
		for (MetadataField field : Set.of(
				MetadataField.TITLE,
				MetadataField.DOCUMENT_TYPE,
				MetadataField.SOURCE_URL,
				MetadataField.AUTHORS)) {
			assertThat(coverage.fields().get(field).rate())
					.as("%s %s coverage", provider, field)
					.isEqualTo(1.0d);
		}
		for (MetadataField field : Set.of(
				MetadataField.DOI,
				MetadataField.ABSTRACT,
				MetadataField.ORCID,
				MetadataField.PUBLICATION_YEAR,
				MetadataField.VENUE,
				MetadataField.LANGUAGE,
				MetadataField.ISSN,
				MetadataField.CITATION_COUNT)) {
			assertThat(coverage.fields().get(field).rate())
					.as("%s %s coverage", provider, field)
					.isStrictlyBetween(0.0d, 1.0d);
		}
		if (provider == ProviderId.EUROPE_PMC) {
			assertThat(coverage.fields().get(MetadataField.PMID).rate()).isEqualTo(1.0d);
			assertThat(coverage.fields().get(MetadataField.PMCID).rate()).isEqualTo(1.0d);
		}
		else {
			assertThat(coverage.fields().get(MetadataField.PMID).rate())
					.isStrictlyBetween(0.0d, 1.0d);
			assertThat(coverage.fields().get(MetadataField.PMCID).rate())
					.isStrictlyBetween(0.0d, 1.0d);
		}
	}

	private static boolean hasEuropePmcUniqueRelevantResult(
			EvaluationQuery query, Scenario openAlexOnly, Scenario europePmcOnly) {
		Set<String> openAlexGold = Set.copyOf(openAlexOnly.rankedGoldKeys());
		return europePmcOnly.rankedGoldKeys().stream()
				.filter(goldKey -> !goldKey.startsWith("__duplicate__:"))
				.filter(goldKey -> !openAlexGold.contains(goldKey))
				.anyMatch(goldKey -> query.judgments().getOrDefault(goldKey, 0) >= 2);
	}

	private static void assertRrfScoreForSingleContribution(SearchResultView result) {
		assertThat(result.providerContributions()).hasSize(1);
		assertThat(result.score()).isCloseTo(1.0d / (60.0d + result.rank()), within(EPSILON));
		assertThat(result.rankingReasons()).singleElement()
				.satisfies(reason -> assertThat(reason.feature())
						.isEqualTo("PROVIDER_RECIPROCAL_RANK_FUSION"));
	}

	private static SearchCommand command(EvaluationQuery query) {
		return new SearchCommand(
				query.text(),
				null,
				null,
				Set.of(DocumentType.ARTICLE),
				false,
				0,
				Set.of(),
				20,
				"*",
				false,
				SearchMode.ONLINE);
	}

	private static Instant retrievedAt(ProviderId provider) {
		return provider == ProviderId.OPENALEX ? RETRIEVED_AT.plusSeconds(1) : RETRIEVED_AT;
	}

	private static String identifierValue(FixtureRecord record, PaperIdentifierType type) {
		return record.identifiers().stream()
				.filter(identifier -> identifier.type() == type)
				.map(ProviderQualityEvaluationFixture.FixtureIdentifier::value)
				.findFirst()
				.orElse(null);
	}

	private static void assertTextPreserved(String before, String after) {
		if (hasText(before)) {
			assertThat(after).isNotBlank();
		}
	}

	private static boolean hasText(String value) {
		return value != null && !value.isBlank();
	}

	private static String sha256(String value) {
		try {
			return HexFormat.of().formatHex(
					MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
		}
		catch (java.security.NoSuchAlgorithmException exception) {
			throw new IllegalStateException("SHA-256 is unavailable", exception);
		}
	}

	private static void printQueryMeasurement(
			String queryKey,
			RankingMeasurement openAlex,
			RankingMeasurement europePmc,
			RankingMeasurement fused,
			double ndcgDelta) {
		System.out.printf(
				Locale.ROOT,
				"provider-quality-v1 query=%s openalex=[%.3f %.3f %.3f %.3f] "
						+ "europe-pmc=[%.3f %.3f %.3f %.3f] fused=[%.3f %.3f %.3f %.3f] "
						+ "fused-ndcg-delta=%+.3f%n",
				queryKey,
				openAlex.recall(), openAlex.ndcg(), openAlex.precision(), openAlex.reciprocalRank(),
				europePmc.recall(), europePmc.ndcg(), europePmc.precision(), europePmc.reciprocalRank(),
				fused.recall(), fused.ndcg(), fused.precision(), fused.reciprocalRank(),
				ndcgDelta);
	}

	private static void printSummary(
			ProviderQualityEvaluationFixture fixture,
			RankingSummary openAlex,
			RankingSummary europePmc,
			RankingSummary fused,
			int incrementalQueries,
			ProviderQualityMetrics.PairwiseDeduplication deduplication,
			int criticalFalseMergeCount,
			FieldCoverage europePmcCoverage,
			double completenessDelta) {
		System.out.printf(
				Locale.ROOT,
				"provider-quality-v1 fixture=%s queries=%d macro-openalex=[%.3f %.3f %.3f %.3f] "
						+ "macro-europe-pmc=[%.3f %.3f %.3f %.3f] macro-fused=[%.3f %.3f %.3f %.3f] "
						+ "incremental-queries=%d dedup=[p=%.3f r=%.3f f1=%.3f] "
						+ "critical-false-merges=%d fused-completeness-delta=%+.3f%n",
				fixture.fixtureId(), fixture.queries().size(),
				openAlex.macroRecall(), openAlex.macroNdcg(), openAlex.macroPrecision(),
				openAlex.meanReciprocalRank(),
				europePmc.macroRecall(), europePmc.macroNdcg(), europePmc.macroPrecision(),
				europePmc.meanReciprocalRank(),
				fused.macroRecall(), fused.macroNdcg(), fused.macroPrecision(),
				fused.meanReciprocalRank(), incrementalQueries,
				deduplication.precision(), deduplication.recall(), deduplication.f1(),
				criticalFalseMergeCount, completenessDelta);
		for (MetadataField field : MetadataField.values()) {
			System.out.printf(
					Locale.ROOT,
					"provider-quality-v1 europe-pmc-field=%s coverage=%.3f%n",
					field,
					europePmcCoverage.fields().get(field).rate());
		}
	}

	private record CriticalFalseMerge(
			String queryKey,
			String leftRecordKey,
			String rightRecordKey,
			String reason) {
	}

	private record Scenario(
			SearchView view,
			QueryIndex index,
			List<String> rankedGoldKeys,
			Map<String, UUID> canonicalByRecordKey) {

		private static Scenario from(SearchView view, QueryIndex index) {
			List<String> ranked = new ArrayList<>();
			Map<String, Integer> occurrences = new LinkedHashMap<>();
			for (SearchResultView result : view.results()) {
				String goldKey = index.primaryGoldKey(result);
				int occurrence = occurrences.merge(goldKey, 1, Integer::sum);
				ranked.add(occurrence == 1
						? goldKey
						: "__duplicate__:" + goldKey + ':' + occurrence);
			}
			assertThat(ranked).doesNotHaveDuplicates();
			Map<String, UUID> canonicalByRecord = new LinkedHashMap<>();
			for (SearchResultView result : view.results()) {
				for (com.openscholar.search.ProviderContributionView contribution
						: result.providerContributions()) {
					FixtureRecord record = index.fixtureRecord(
							contribution.provider(), contribution.providerRecordId());
					canonicalByRecord.put(record.key(), result.paper().id());
				}
			}
			return new Scenario(view, index, ranked, Map.copyOf(canonicalByRecord));
		}

		private List<ResultSignature> resultSignatures() {
			return view.results().stream()
					.map(result -> ResultSignature.from(result, index.primaryGoldKey(result)))
					.toList();
		}

		private Map<String, String> canonicalPartition() {
			Map<UUID, List<String>> recordsByCanonical = new LinkedHashMap<>();
			canonicalByRecordKey.forEach((recordKey, canonicalId) -> recordsByCanonical
					.computeIfAbsent(canonicalId, ignored -> new ArrayList<>())
					.add(recordKey));
			Map<String, String> partition = new LinkedHashMap<>();
			for (List<String> recordKeys : recordsByCanonical.values()) {
				List<String> sorted = recordKeys.stream().sorted().toList();
				String cluster = String.join("|", sorted);
				sorted.forEach(recordKey -> partition.put(recordKey, cluster));
			}
			return Map.copyOf(partition);
		}
	}

	private record ResultSignature(
			int rank,
			String goldPaperKey,
			String title,
			String abstractText,
			Integer publicationYear,
			DocumentType documentType,
			String language,
			String venueName,
			Integer citationCount,
			List<PaperIdentifier> identifiers,
			List<AuthorSignature> authors,
			List<String> issn,
			boolean reportedOpenAccess,
			URI landingPageUrl,
			URI pdfUrl,
			Double score,
			List<com.openscholar.search.RankingReason> rankingReasons,
			ProviderId provider,
			String providerRecordId,
			Instant retrievedAt,
			List<com.openscholar.search.ProviderContributionView> providerContributions) {

		private static ResultSignature from(SearchResultView result, String goldPaperKey) {
			PaperView paper = result.paper();
			return new ResultSignature(
					result.rank(),
					goldPaperKey,
					paper.title(),
					paper.abstractText(),
					paper.publicationYear(),
					paper.documentType(),
					paper.language(),
					paper.venueName(),
					paper.citationCount(),
					paper.identifiers(),
					paper.authors().stream()
							.map(author -> new AuthorSignature(
									author.displayName(), author.orcid(), author.openAlexId(),
									author.position(), author.corresponding()))
							.toList(),
					paper.issn(),
					result.reportedOpenAccess(),
					result.landingPageUrl(),
					result.pdfUrl(),
					result.score(),
					result.rankingReasons(),
					result.provider(),
					result.providerRecordId(),
					result.retrievedAt(),
					result.providerContributions());
		}
	}

	private record AuthorSignature(
			String displayName,
			String orcid,
			String openAlexId,
			int position,
			boolean corresponding) {
	}

	private static final class QueryIndex {

		private final EvaluationQuery query;
		private final Map<ProviderId, ProviderResult> providerResults = new LinkedHashMap<>();
		private final Map<String, FixtureRecord> recordsByContribution = new LinkedHashMap<>();

		private QueryIndex(EvaluationQuery query) {
			this.query = query;
			for (ProviderResult providerResult : query.providerResults()) {
				providerResults.put(providerResult.provider(), providerResult);
				for (FixtureRecord record : providerResult.records()) {
					recordsByContribution.put(
							contributionKey(providerResult.provider(), record.providerRecordId()), record);
				}
			}
		}

		private ProviderResult providerResult(ProviderId provider) {
			return Objects.requireNonNull(providerResults.get(provider));
		}

		private FixtureRecord fixtureRecord(ProviderId provider, String providerRecordId) {
			FixtureRecord record = recordsByContribution.get(contributionKey(provider, providerRecordId));
			if (record == null) {
				throw new IllegalStateException(
						"Unknown provider contribution for " + query.key() + ": "
								+ provider + '/' + providerRecordId);
			}
			return record;
		}

		private String primaryGoldKey(SearchResultView result) {
			return fixtureRecord(result.provider(), result.providerRecordId()).goldPaperKey();
		}

		private static String contributionKey(ProviderId provider, String providerRecordId) {
			return provider.name() + '\n' + providerRecordId;
		}
	}
}
