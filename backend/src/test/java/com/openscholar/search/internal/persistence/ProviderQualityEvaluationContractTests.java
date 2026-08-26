package com.openscholar.search.internal.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.openscholar.paper.PaperIdentifierType;
import com.openscholar.provider.ProviderId;
import com.openscholar.search.internal.persistence.ProviderQualityEvaluationFixture.CriticalRelation;
import com.openscholar.search.internal.persistence.ProviderQualityEvaluationFixture.EvaluationQuery;
import com.openscholar.search.internal.persistence.ProviderQualityEvaluationFixture.FixtureRecord;
import com.openscholar.search.internal.persistence.ProviderQualityEvaluationFixture.ProviderResult;
import com.openscholar.search.internal.persistence.ProviderQualityMetrics.MetadataField;
import com.openscholar.search.internal.persistence.ProviderQualityMetrics.RankedContribution;
import com.openscholar.search.internal.persistence.ProviderQualityMetrics.RankingMeasurement;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

class ProviderQualityEvaluationContractTests {

	private static final String FIXTURE_PATH =
			"search/provider-quality/provider-fusion-development-v1.json";
	private static final String POLICY_PATH =
			"search/provider-quality/provider-fusion-policy-v1.json";
	private static final JsonMapper OBJECT_MAPPER = JsonMapper.builder().build();

	@Test
	void frozenResourcesAreStrictDigestBoundAndInternallyConsistent() throws Exception {
		ProviderQualityEvaluationFixture fixture = ProviderQualityEvaluationFixture.load(
				OBJECT_MAPPER, FIXTURE_PATH);
		ProviderQualityEvaluationPolicy policy = ProviderQualityEvaluationPolicy.loadBound(
				OBJECT_MAPPER, POLICY_PATH, fixture, FIXTURE_PATH);

		assertThat(fixture.fixtureId()).isEqualTo("provider-fusion-development-v1");
		assertThat(policy.policyId()).isEqualTo("provider-fusion-policy-v1");
		assertThat(fixture.split()).isEqualTo("DEVELOPMENT");
		assertThat(fixture.sourcePolicy()).isEqualTo("SYNTHETIC_METADATA_ONLY");
		assertThat(fixture.queries()).hasSize(8);
		assertThat(fixture.queries().stream().map(ProviderQualityEvaluationContractTests::depthProfile))
				.containsExactly("6:4", "4:7", "5:3", "3:5", "7:4", "4:6", "6:2", "2:5")
				.doesNotHaveDuplicates();
		assertThat(fixture.queries()).allSatisfy(query -> {
			assertThat(query.providerResults())
					.extracting(ProviderQualityEvaluationFixture.ProviderResult::provider)
					.containsExactlyInAnyOrder(ProviderId.OPENALEX, ProviderId.EUROPE_PMC);
			assertThat(query.providerResults()).allSatisfy(
					result -> assertThat(result.records()).hasSizeBetween(1, 10));
			assertThat(query.criticalPairs())
					.extracting(ProviderQualityEvaluationFixture.CriticalPair::relation)
					.contains(CriticalRelation.MUST_LINK, CriticalRelation.MUST_SEPARATE);
			Set<String> returned = returnedGoldKeys(query);
			assertThat(query.judgments().keySet()).containsAll(returned);
			assertThat(query.judgments().entrySet().stream()
					.filter(entry -> entry.getValue() > 0)
					.map(java.util.Map.Entry::getKey)
					.filter(key -> !returned.contains(key)))
					.as("judged relevant works absent from both providers for %s", query.key())
					.isNotEmpty();
		});
		assertThat(fixture.queries().stream()
				.flatMap(query -> query.providerResults().stream())
				.flatMap(result -> result.records().stream()))
				.hasSize(73);
		assertThat(fixture.queries().stream()
				.flatMap(query -> query.providerResults().stream())
				.mapToInt(result -> result.records().size()).max().orElseThrow())
				.isEqualTo(7);
		assertThat(fixture.queries().stream()
				.filter(ProviderQualityEvaluationContractTests::hasEarlyHardNegative))
				.as("query groups with a grade-0 result in either provider's first two ranks")
				.hasSize(7);
		assertThat(fixture.queries().stream()
				.filter(ProviderQualityEvaluationContractTests::hasEuropePmcUniqueGradeTwoOrBetter))
				.hasSize(5);
		assertThat(fixture.queries().stream()
				.filter(query -> !hasEuropePmcUniqueGradeTwoOrBetter(query)))
				.hasSize(3);
		assertThat(fixture.queries().stream()
				.filter(query -> !hasEuropePmcUniqueRelevantWork(query)))
				.as("query groups with no relevant Europe PMC-only result at any positive grade")
				.hasSize(2);
		assertThat(fixture.exactOverlapSignals())
				.containsExactlyInAnyOrder(
						PaperIdentifierType.DOI, PaperIdentifierType.PMID, PaperIdentifierType.PMCID);
		assertThat(policy.ranking().rrfK()).isEqualTo(60);
		assertThat(policy.ranking().tieBreak()).containsExactly(
				"FUSED_SCORE_DESC",
				"PRIMARY_PROVIDER_RANK_ASC",
				"PRIMARY_PROVIDER_ASC",
				"PRIMARY_PROVIDER_RECORD_ID_ASC",
				"CANONICAL_PAPER_UUID_ASC");
		assertThat(policy.rankingCutoffs())
				.isEqualTo(new ProviderQualityMetrics.RankingCutoffs(20, 10, 5, 20));
		assertThat(policy.gates().minimumEuropePmcUniqueRelevantQueryCount()).isEqualTo(4);
		assertThat(policy.gates().minimumFusedCompletenessDelta()).isEqualTo(0.06d);
		assertThat(policy.metadataFusionGainFields()).containsExactly(
				MetadataField.ABSTRACT,
				MetadataField.ORCID,
				MetadataField.PUBLICATION_YEAR,
				MetadataField.VENUE,
				MetadataField.LANGUAGE,
				MetadataField.ISSN,
				MetadataField.CITATION_COUNT);
		assertThat(policy.gates().minimumPerFieldFusionDelta()).isEqualTo(0.01d);
		assertThat(policy.gates().requireVariedFixtureMetadata()).isTrue();
		assertThat(policy.gates().forbidDocumentFields()).isTrue();
		assertThat(policy.developmentFixtureSha256())
				.isEqualTo(ProviderQualityEvaluationPolicy.sha256(FIXTURE_PATH));
	}

	@Test
	void frozenCorpusExercisesVariedMetadataPresenceForBothProviders() throws Exception {
		ProviderQualityEvaluationFixture fixture = ProviderQualityEvaluationFixture.load(
				OBJECT_MAPPER, FIXTURE_PATH);
		ProviderQualityEvaluationPolicy policy = ProviderQualityEvaluationPolicy.loadBound(
				OBJECT_MAPPER, POLICY_PATH, fixture, FIXTURE_PATH);
		Set<MetadataField> requiredOnEveryRecord = Set.of(
				MetadataField.TITLE,
				MetadataField.DOCUMENT_TYPE,
				MetadataField.SOURCE_URL,
				MetadataField.AUTHORS);
		Set<MetadataField> variedOnBothProviders = Set.of(
				MetadataField.DOI,
				MetadataField.ABSTRACT,
				MetadataField.ORCID,
				MetadataField.PUBLICATION_YEAR,
				MetadataField.VENUE,
				MetadataField.LANGUAGE,
				MetadataField.ISSN,
				MetadataField.CITATION_COUNT);

		assertThat(policy.gates().requireVariedFixtureMetadata()).isTrue();
		for (ProviderId provider : List.of(ProviderId.OPENALEX, ProviderId.EUROPE_PMC)) {
			List<FixtureRecord> records = fixture.queries().stream()
					.map(query -> providerResult(query, provider))
					.flatMap(result -> result.records().stream())
					.toList();
			assertThat(records).isNotEmpty().allSatisfy(record ->
					assertThat(presentMetadataFields(record)).containsAll(requiredOnEveryRecord));
			for (MetadataField field : variedOnBothProviders) {
				long presentCount = records.stream()
						.filter(record -> presentMetadataFields(record).contains(field))
						.count();
				assertThat(presentCount)
						.as("%s fixture presence for %s", field, provider)
						.isBetween(1L, records.size() - 1L);
			}
		}

		List<FixtureRecord> openAlexRecords = fixture.queries().stream()
				.map(query -> providerResult(query, ProviderId.OPENALEX))
				.flatMap(result -> result.records().stream())
				.toList();
		assertThat(openAlexRecords.stream().filter(record -> record.hasIdentifier(PaperIdentifierType.PMID)))
				.isNotEmpty();
		assertThat(openAlexRecords.stream().filter(record -> record.hasIdentifier(PaperIdentifierType.PMCID)))
				.isNotEmpty();
		assertThat(openAlexRecords.stream().filter(record -> !record.hasIdentifier(PaperIdentifierType.PMID)))
				.isNotEmpty();
		assertThat(openAlexRecords.stream().filter(record -> !record.hasIdentifier(PaperIdentifierType.PMCID)))
				.isNotEmpty();
		assertThat(fixture.queries().stream()
				.map(query -> providerResult(query, ProviderId.EUROPE_PMC))
				.flatMap(result -> result.records().stream()))
				.allSatisfy(record -> assertThat(presentMetadataFields(record))
						.contains(MetadataField.PMID, MetadataField.PMCID));
	}

	@Test
	void frozenCorpusHasVariedMeasuredQualityAndOneBoundedRegression() throws Exception {
		ProviderQualityEvaluationFixture fixture = ProviderQualityEvaluationFixture.load(
				OBJECT_MAPPER, FIXTURE_PATH);
		ProviderQualityEvaluationPolicy policy = ProviderQualityEvaluationPolicy.loadBound(
				OBJECT_MAPPER, POLICY_PATH, fixture, FIXTURE_PATH);
		List<RankingMeasurement> baseline = new ArrayList<>();
		List<RankingMeasurement> candidate = new ArrayList<>();
		Set<String> regressions = new LinkedHashSet<>();

		for (EvaluationQuery query : fixture.queries()) {
			List<String> openAlex = providerResult(query, ProviderId.OPENALEX).records().stream()
					.map(FixtureRecord::goldPaperKey)
					.toList();
			RankingMeasurement baselineMeasurement = ProviderQualityMetrics.measureRanking(
					openAlex, query.judgments(), policy.rankingCutoffs());
			RankingMeasurement candidateMeasurement = ProviderQualityMetrics.measureRanking(
					fusedRanking(query, policy.ranking().rrfK()),
					query.judgments(), policy.rankingCutoffs());
			baseline.add(baselineMeasurement);
			candidate.add(candidateMeasurement);
			double delta = candidateMeasurement.ndcg() - baselineMeasurement.ndcg();
			if (delta < 0.0d) {
				regressions.add(query.key());
				assertThat(delta).isGreaterThanOrEqualTo(
						-policy.gates().maximumPerQueryNdcgRegression());
			}
		}

		var baselineSummary = ProviderQualityMetrics.summarizeRankings(baseline);
		var candidateSummary = ProviderQualityMetrics.summarizeRankings(candidate);
		assertThat(regressions).containsExactly("maternal-sepsis-prediction");
		assertThat(baselineSummary.macroRecall()).isCloseTo(0.5327380952380952d, within(1.0e-12d));
		assertThat(baselineSummary.macroNdcg()).isCloseTo(0.550724489644315d, within(1.0e-12d));
		assertThat(baselineSummary.macroPrecision()).isCloseTo(0.575d, within(1.0e-12d));
		assertThat(baselineSummary.meanReciprocalRank()).isEqualTo(0.75d);
		assertThat(candidateSummary.macroRecall()).isCloseTo(0.8339285714285714d, within(1.0e-12d));
		assertThat(candidateSummary.macroNdcg()).isCloseTo(0.7775791383883233d, within(1.0e-12d));
		assertThat(candidateSummary.macroPrecision()).isCloseTo(0.6d, within(1.0e-12d));
		assertThat(candidateSummary.meanReciprocalRank()).isEqualTo(1.0d);
		assertThat(candidateSummary.macroRecall() - baselineSummary.macroRecall())
				.isGreaterThan(policy.gates().minimumMacroRecallGain());
		assertThat(candidateSummary.macroNdcg() - baselineSummary.macroNdcg())
				.isGreaterThan(policy.gates().minimumMacroNdcgDelta());
		assertThat(candidateSummary.macroPrecision() - baselineSummary.macroPrecision())
				.isGreaterThan(policy.gates().minimumMacroPrecisionDelta());
		assertThat(candidateSummary.meanReciprocalRank() - baselineSummary.meanReciprocalRank())
				.isGreaterThan(policy.gates().minimumMrrDelta());
	}

	@Test
	void strictLoadersRejectUnknownFixtureAndPolicyKeys() throws Exception {
		JsonNode fixtureWithPdf = resourceTree(FIXTURE_PATH);
		ObjectNode firstRecord = (ObjectNode) fixtureWithPdf.required("queries").get(0)
				.required("providerResults").get(0).required("records").get(0);
		firstRecord.put("pdfUrl", "https://fixtures.openscholar.test/forbidden.pdf");

		assertThatThrownBy(() -> ProviderQualityEvaluationFixture.parse(
				OBJECT_MAPPER, fixtureWithPdf))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("Unknown keys")
				.hasMessageContaining("pdfUrl");

		JsonNode fixtureWithFullText = resourceTree(FIXTURE_PATH);
		ObjectNode fullTextRecord = (ObjectNode) fixtureWithFullText.required("queries").get(0)
				.required("providerResults").get(0).required("records").get(0);
		fullTextRecord.put("fullTextXml", "<article>forbidden</article>");
		assertThatThrownBy(() -> ProviderQualityEvaluationFixture.parse(
				OBJECT_MAPPER, fixtureWithFullText))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("Unknown keys")
				.hasMessageContaining("fullTextXml");

		JsonNode policyWithUnknownKey = resourceTree(POLICY_PATH);
		((ObjectNode) policyWithUnknownKey.required("ranking")).put("providerWeight", 2.0d);

		assertThatThrownBy(() -> ProviderQualityEvaluationPolicy.parse(
				OBJECT_MAPPER, policyWithUnknownKey))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("Unknown keys at $.ranking")
				.hasMessageContaining("providerWeight");
	}

	@Test
	void fixtureAllowsUnretrievedJudgmentsButRequiresLabelsForReturnedWorks() throws Exception {
		JsonNode withAnotherUnretrievedRelevantWork = resourceTree(FIXTURE_PATH);
		ObjectNode judgments = (ObjectNode) withAnotherUnretrievedRelevantWork.required("queries").get(0)
				.required("judgments");
		judgments.put("q1-additional-unretrieved-work", 2);

		ProviderQualityEvaluationFixture parsed = ProviderQualityEvaluationFixture.parse(
				OBJECT_MAPPER, withAnotherUnretrievedRelevantWork);
		assertThat(parsed.queries().getFirst().judgments())
				.containsEntry("q1-additional-unretrieved-work", 2);

		JsonNode missingReturnedJudgment = resourceTree(FIXTURE_PATH);
		((ObjectNode) missingReturnedJudgment.required("queries").get(0).required("judgments"))
				.remove("q1-shared");
		assertThatThrownBy(() -> ProviderQualityEvaluationFixture.parse(
				OBJECT_MAPPER, missingReturnedJudgment))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("judgments must cover every returned query gold paper")
				.hasMessageContaining("q1-shared");
	}

	@Test
	void fixtureLoaderRejectsBrokenCriticalPairReferences() throws Exception {
		JsonNode fixtureWithBrokenReference = resourceTree(FIXTURE_PATH);
		ObjectNode firstPair = (ObjectNode) fixtureWithBrokenReference.required("queries").get(0)
				.required("criticalPairs").get(0);
		firstPair.put("rightRecordKey", "missing-record");

		assertThatThrownBy(() -> ProviderQualityEvaluationFixture.parse(
				OBJECT_MAPPER, fixtureWithBrokenReference))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("critical pair references an unknown record")
				.hasMessageContaining("missing-record");
	}

	@Test
	void fixtureLoaderRejectsLiveRegistryIdentifierValues() throws Exception {
		JsonNode fixtureWithLivePmid = resourceTree(FIXTURE_PATH);
		ObjectNode europePmcRecord = (ObjectNode) fixtureWithLivePmid.required("queries").get(0)
				.required("providerResults").get(1).required("records").get(0);
		((ObjectNode) europePmcRecord.required("identifiers").get(1))
				.put("value", "2101001");
		assertThatThrownBy(() -> ProviderQualityEvaluationFixture.parse(
				OBJECT_MAPPER, fixtureWithLivePmid))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("invalid synthetic PMID");

		JsonNode fixtureWithLiveOrcid = resourceTree(FIXTURE_PATH);
		ObjectNode richOpenAlexRecord = (ObjectNode) fixtureWithLiveOrcid.required("queries").get(0)
				.required("providerResults").get(0).required("records").get(2);
		((ObjectNode) richOpenAlexRecord.required("authors").get(0))
				.put("orcid", "https://orcid.org/0000-0001-5109-3700");
		assertThatThrownBy(() -> ProviderQualityEvaluationFixture.parse(
				OBJECT_MAPPER, fixtureWithLiveOrcid))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("invalid synthetic orcid placeholder");

		JsonNode fixtureWithNonTestDoi = resourceTree(FIXTURE_PATH);
		ObjectNode firstOpenAlexRecord = (ObjectNode) fixtureWithNonTestDoi.required("queries").get(0)
				.required("providerResults").get(0).required("records").get(0);
		((ObjectNode) firstOpenAlexRecord.required("identifiers").get(0))
				.put("value", "10.1234/live-registry-record");
		assertThatThrownBy(() -> ProviderQualityEvaluationFixture.parse(
				OBJECT_MAPPER, fixtureWithNonTestDoi))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("invalid synthetic DOI");
	}

	@Test
	void policyRejectsAChangedFixtureDigest() throws Exception {
		ProviderQualityEvaluationFixture fixture = ProviderQualityEvaluationFixture.load(
				OBJECT_MAPPER, FIXTURE_PATH);
		ProviderQualityEvaluationPolicy policy = ProviderQualityEvaluationPolicy.load(
				OBJECT_MAPPER, POLICY_PATH);

		assertThatThrownBy(() -> policy.validateBinding(fixture, "0".repeat(64)))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("fixture SHA-256 does not match");
	}

	private static JsonNode resourceTree(String path) throws Exception {
		ClassPathResource resource = new ClassPathResource(path);
		try (InputStream input = resource.getInputStream()) {
			return OBJECT_MAPPER.readTree(input);
		}
	}

	private static String depthProfile(EvaluationQuery query) {
		return providerResult(query, ProviderId.OPENALEX).records().size()
				+ ":" + providerResult(query, ProviderId.EUROPE_PMC).records().size();
	}

	private static ProviderResult providerResult(EvaluationQuery query, ProviderId provider) {
		return query.providerResults().stream()
				.filter(result -> result.provider() == provider)
				.findFirst()
				.orElseThrow();
	}

	private static Set<String> returnedGoldKeys(EvaluationQuery query) {
		return query.providerResults().stream()
				.flatMap(result -> result.records().stream())
				.map(FixtureRecord::goldPaperKey)
				.collect(java.util.stream.Collectors.toUnmodifiableSet());
	}

	private static Set<MetadataField> presentMetadataFields(FixtureRecord record) {
		Set<MetadataField> fields = java.util.EnumSet.of(
				MetadataField.TITLE, MetadataField.DOCUMENT_TYPE, MetadataField.SOURCE_URL);
		for (ProviderQualityEvaluationFixture.FixtureIdentifier identifier : record.identifiers()) {
			fields.add(MetadataField.valueOf(identifier.type().name()));
		}
		if (record.abstractText() != null) {
			fields.add(MetadataField.ABSTRACT);
		}
		if (!record.authors().isEmpty()) {
			fields.add(MetadataField.AUTHORS);
		}
		if (record.authors().stream().anyMatch(author -> author.orcid() != null)) {
			fields.add(MetadataField.ORCID);
		}
		if (record.publicationYear() != null) {
			fields.add(MetadataField.PUBLICATION_YEAR);
		}
		if (record.venueName() != null) {
			fields.add(MetadataField.VENUE);
		}
		if (record.language() != null) {
			fields.add(MetadataField.LANGUAGE);
		}
		if (!record.issn().isEmpty()) {
			fields.add(MetadataField.ISSN);
		}
		if (record.citationCount() != null) {
			fields.add(MetadataField.CITATION_COUNT);
		}
		return Set.copyOf(fields);
	}

	private static boolean hasEarlyHardNegative(EvaluationQuery query) {
		return query.providerResults().stream()
				.flatMap(result -> result.records().stream().limit(2))
				.anyMatch(record -> query.judgments().get(record.goldPaperKey()) == 0);
	}

	private static boolean hasEuropePmcUniqueGradeTwoOrBetter(EvaluationQuery query) {
		Set<String> openAlex = providerResult(query, ProviderId.OPENALEX).records().stream()
				.map(FixtureRecord::goldPaperKey)
				.collect(java.util.stream.Collectors.toUnmodifiableSet());
		return providerResult(query, ProviderId.EUROPE_PMC).records().stream()
				.map(FixtureRecord::goldPaperKey)
				.filter(key -> !openAlex.contains(key))
				.anyMatch(key -> query.judgments().get(key) >= 2);
	}

	private static boolean hasEuropePmcUniqueRelevantWork(EvaluationQuery query) {
		Set<String> openAlex = providerResult(query, ProviderId.OPENALEX).records().stream()
				.map(FixtureRecord::goldPaperKey)
				.collect(java.util.stream.Collectors.toUnmodifiableSet());
		return providerResult(query, ProviderId.EUROPE_PMC).records().stream()
				.map(FixtureRecord::goldPaperKey)
				.filter(key -> !openAlex.contains(key))
				.anyMatch(key -> query.judgments().get(key) > 0);
	}

	private static List<String> fusedRanking(EvaluationQuery query, int rrfK) {
		List<RankedContribution> contributions = new ArrayList<>();
		for (ProviderResult providerResult : query.providerResults()) {
			int rank = 1;
			for (FixtureRecord record : providerResult.records()) {
				UUID canonicalId = UUID.nameUUIDFromBytes(
						("provider-quality-gold:" + record.goldPaperKey()).getBytes(StandardCharsets.UTF_8));
				contributions.add(new RankedContribution(
						providerResult.provider().name(), record.providerRecordId(),
						record.goldPaperKey(), canonicalId, rank++));
			}
		}
		return ProviderQualityMetrics.reciprocalRankFusion(contributions, rrfK).stream()
				.map(ProviderQualityMetrics.FusedScore::paperKey)
				.toList();
	}
}
