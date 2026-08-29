package com.openscholar.search.internal.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import com.openscholar.TestcontainersConfiguration;
import com.openscholar.library.LibraryUseCase;
import com.openscholar.library.ReadingStatus;
import com.openscholar.paper.CanonicalPaperCandidate;
import com.openscholar.paper.PaperAuthorCandidate;
import com.openscholar.paper.PaperCatalog;
import com.openscholar.paper.PaperIdentifier;
import com.openscholar.paper.PaperIdentifierType;
import com.openscholar.paper.PaperView;
import com.openscholar.paper.ProviderRecordCandidate;
import com.openscholar.provider.ProviderAuthor;
import com.openscholar.provider.ProviderId;
import com.openscholar.provider.ProviderPaperRecord;
import com.openscholar.provider.ProviderSearchBatchResult;
import com.openscholar.provider.ProviderSearchQuery;
import com.openscholar.provider.ProviderSearchResult;
import com.openscholar.provider.ResearchProvider;
import com.openscholar.search.CacheDisposition;
import com.openscholar.search.SearchCommand;
import com.openscholar.search.SearchExecutionSource;
import com.openscholar.search.SearchMode;
import com.openscholar.search.SearchResearchUseCase;
import com.openscholar.search.SearchResultView;
import com.openscholar.search.SearchView;
import com.openscholar.search.internal.persistence.OwnerScopedRelatedTopicComparator.FeedbackList;
import com.openscholar.search.internal.persistence.RelatedTopicReuseEvaluationFixture.AdversaryKind;
import com.openscholar.search.internal.persistence.RelatedTopicReuseEvaluationFixture.BoundFixture;
import com.openscholar.search.internal.persistence.RelatedTopicReuseEvaluationFixture.Candidate;
import com.openscholar.search.internal.persistence.RelatedTopicReuseEvaluationFixture.Lineage;
import com.openscholar.search.internal.persistence.RelatedTopicReuseEvaluationFixture.LineageKind;
import com.openscholar.search.internal.persistence.RelatedTopicReuseEvaluationFixture.Query;
import com.openscholar.search.internal.persistence.RelatedTopicReuseEvaluationFixture.QueryKind;
import com.openscholar.search.internal.persistence.RelatedTopicReuseEvaluationPolicy.BoundPolicy;
import com.openscholar.search.internal.persistence.ProviderQualityMetrics.RankingCutoffs;
import com.openscholar.search.internal.persistence.ProviderQualityMetrics.RankingMeasurement;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.annotation.DirtiesContext;
import tools.jackson.databind.ObjectMapper;

@Import({
	TestcontainersConfiguration.class,
	OwnerScopedRelatedTopicReuseEvaluationTests.EvaluationConfiguration.class
})
@SpringBootTest(properties = {
	"openscholar.providers.europe-pmc.enabled=false",
	"openscholar.providers.doaj.enabled=false",
	"openscholar.providers.core.enabled=false",
	"openscholar.providers.datacite.enabled=false"
})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class OwnerScopedRelatedTopicReuseEvaluationTests {

	private static final Instant NOW = Instant.parse("2026-08-29T00:00:00Z");
	private static final UUID TARGET_OWNER =
			UUID.fromString("20000000-0000-0000-0000-000000000001");
	private static final UUID OTHER_OWNER =
			UUID.fromString("20000000-0000-0000-0000-000000000002");
	private static final String RECORD_PREFIX = "OSREUSEV1-";
	private static final String AUTHOR_PREFIX = "AOSREUSEV1-";
	private static final String HIDDEN_KEY = "hidden-coastal-feedback-decoy";
	static final Set<String> SEED_ELIGIBILITY_FEATURES = Set.of(
			"TITLE_EXACT", "TITLE_PREFIX", "TITLE_CONTAINS", "POSTGRES_FULL_TEXT");

	@Autowired
	private ObjectMapper objectMapper;

	@Autowired
	private PaperCatalog paperCatalog;

	@Autowired
	private SearchSnapshotStore snapshotStore;

	@Autowired
	private LibraryUseCase library;

	@Autowired
	private SearchResearchUseCase search;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Autowired
	private JdbcClient jdbcClient;

	@Autowired
	private MutableCurrentUser currentUser;

	@Autowired
	private NoCallResearchProvider provider;

	private BoundFixture boundFixture;
	private BoundPolicy boundPolicy;
	private OwnerScopedRelatedTopicComparator comparator;
	private Map<String, UUID> paperIdsByKey;
	private Map<UUID, String> keysByPaperId;

	@BeforeEach
	void seedSyntheticRelatedTopicCatalog() throws Exception {
		cleanupFixtureRows();
		provider.reset();
		boundFixture = RelatedTopicReuseEvaluationFixture.loadFrozen(objectMapper);
		boundPolicy = RelatedTopicReuseEvaluationPolicy.loadFrozen(objectMapper);
		var fixture = boundFixture.fixture();
		boundPolicy.policy().validateFixture(
				fixture.fixtureId(),
				boundFixture.sha256(),
				fixture.queries().size(),
				fixture.candidates().size(),
				fixture.targetVisibleKeys().size(),
				(int) fixture.queries().stream().filter(query -> query.kind().opportunity()).count(),
				(int) fixture.queries().stream().filter(query -> !query.kind().opportunity()).count());
		insertOwner(TARGET_OWNER, "Related topic target owner");
		insertOwner(OTHER_OWNER, "Related topic other owner");
		seedFixture(fixture);
		currentUser.use(TARGET_OWNER);
		comparator = new OwnerScopedRelatedTopicComparator(jdbcClient);
	}

	@AfterEach
	void removeSyntheticRelatedTopicCatalog() {
		cleanupFixtureRows();
		provider.reset();
	}

	@Test
	void frozenOwnerScopedFeedbackImprovesBridgeCasesWithoutChangingProduction() {
		var fixture = boundFixture.fixture();
		var policy = boundPolicy.policy();
		var gates = policy.gates();
		List<RankingMeasurement> baselineMeasurements = new ArrayList<>();
		List<RankingMeasurement> candidateMeasurements = new ArrayList<>();
		int strictOpportunityRecallImprovements = 0;
		int novelRelevantAt10 = 0;
		int ownerScopeLeaks = 0;
		int filterViolations = 0;
		int baselineAdversariesAt10 = 0;
		int candidateAdversariesAt10 = 0;
		int rankOneAdversaries = 0;
		int experimentalSnapshotWrites = 0;

		for (Query query : fixture.queries()) {
			SearchCommand command = command(query, policy.baseline().poolSize());
			SearchView baseline = search.search(command);
			assertLocalControl(baseline, query);
			List<String> baselineIds = resultIds(baseline);
			List<String> baselineKeys = keys(baselineIds);
			List<UUID> seedIds = seedIds(baseline, policy.candidate().maximumSeeds());

			long snapshotsBeforeCandidate = snapshotCount(TARGET_OWNER);
			List<FeedbackList> feedback = comparator.findFeedback(TARGET_OWNER, command, seedIds);
			List<RelatedTopicRankFusion.FusedPaper> fused = fuse(baselineIds, feedback);
			List<FeedbackList> repeatedFeedback = comparator.findFeedback(
					TARGET_OWNER, command, seedIds);
			List<RelatedTopicRankFusion.FusedPaper> repeated = fuse(
					baselineIds, repeatedFeedback);
			experimentalSnapshotWrites += Math.toIntExact(
					snapshotCount(TARGET_OWNER) - snapshotsBeforeCandidate);
			if (gates.requireRepeatedOrderAndScores()) {
				assertThat(repeated)
						.as("repeated related-topic fusion for %s", query.key())
						.containsExactlyElementsOf(fused);
				assertThat(repeatedFeedback)
						.as("repeated scoped feedback for %s", query.key())
						.containsExactlyElementsOf(feedback);
			}

			List<String> candidateIds = fused.stream()
					.limit(query.cutoff())
					.map(RelatedTopicRankFusion.FusedPaper::paperKey)
					.toList();
			List<String> candidateKeys = keys(candidateIds);
			assertThat(candidateIds).doesNotHaveDuplicates();
			List<String> feedbackKeys = feedback.stream()
					.flatMap(list -> list.candidates().stream())
					.map(candidate -> keysByPaperId.get(candidate.paperId()))
					.toList();
			ownerScopeLeaks += (int) feedbackKeys.stream()
					.filter(key -> !fixture.targetVisibleKeys().contains(key))
					.count();
			filterViolations += (int) feedbackKeys.stream()
					.map(fixture.candidatesByKey()::get)
					.filter(candidate -> !matches(candidate, query.filters()))
					.count();
			Set<String> adversaries = query.adversaries().stream()
					.map(RelatedTopicReuseEvaluationFixture.Adversary::candidateKey)
					.collect(java.util.stream.Collectors.toUnmodifiableSet());
			baselineAdversariesAt10 += (int) baselineKeys.stream()
					.limit(query.cutoff())
					.filter(adversaries::contains)
					.count();
			candidateAdversariesAt10 += (int) candidateKeys.stream()
					.filter(adversaries::contains)
					.count();
			if (!candidateKeys.isEmpty() && adversaries.contains(candidateKeys.getFirst())) {
				rankOneAdversaries++;
			}

			boolean hasRelevant = query.judgments().values().stream().anyMatch(grade -> grade > 0);
			if (hasRelevant) {
				RankingCutoffs cutoffs = new RankingCutoffs(
						query.cutoff(), query.cutoff(), 1, query.cutoff());
				RankingMeasurement baselineMeasurement = ProviderQualityMetrics.measureRanking(
						baselineKeys.stream().limit(query.cutoff()).toList(),
						query.judgments(),
						cutoffs);
				RankingMeasurement candidateMeasurement = ProviderQualityMetrics.measureRanking(
						candidateKeys,
						query.judgments(),
						cutoffs);
				baselineMeasurements.add(baselineMeasurement);
				candidateMeasurements.add(candidateMeasurement);
				if (gates.requireNoPerQueryRecallRegression()) {
					assertThat(candidateMeasurement.recall())
							.as("candidate Recall@10 for %s", query.key())
							.isGreaterThanOrEqualTo(baselineMeasurement.recall());
				}
				if (gates.requireNoPerQueryNdcgRegression()) {
					assertThat(candidateMeasurement.ndcg())
							.as("candidate nDCG@10 for %s", query.key())
							.isGreaterThanOrEqualTo(baselineMeasurement.ndcg());
				}
				if (query.kind().opportunity()
						&& candidateMeasurement.recall() > baselineMeasurement.recall()) {
					strictOpportunityRecallImprovements++;
				}
				if (query.kind() == QueryKind.FILTERED_LEXICAL_BRIDGE_OPPORTUNITY
						&& gates.requireFilteredOpportunityStrictRecallImprovement()) {
					assertThat(feedback)
							.as("filtered opportunity feedback for %s", query.key())
							.flatExtracting(FeedbackList::candidates)
							.isNotEmpty();
					assertThat(candidateMeasurement.recall())
							.as("filtered opportunity Recall@10 for %s", query.key())
							.isGreaterThan(baselineMeasurement.recall());
				}
				if (query.kind() == QueryKind.AUTHOR_NO_RELATED_SIGNAL_CONTROL
						&& gates.requireAuthorControlRelevantBaselineHit()) {
					List<String> relevantKeys = query.judgments().entrySet().stream()
							.filter(entry -> entry.getValue() > 0)
							.map(Map.Entry::getKey)
							.toList();
					assertThat(relevantKeys).as("author control relevant targets").hasSize(1);
					assertThat(baselineKeys)
							.as("author control baseline for %s", query.key())
							.isNotEmpty()
							.startsWith(relevantKeys.getFirst());
					assertThat(baselineMeasurement.recall()).isEqualTo(1.0d);
					assertThat(baselineMeasurement.precision()).isEqualTo(1.0d);
					assertThat(seedIds).as("author-only control seeds").isEmpty();
					assertThat(feedback).as("author-only control feedback").isEmpty();
				}
				if (!query.kind().opportunity() && gates.requireNoControlRegression()) {
					assertThat(candidateMeasurement.ndcg())
							.as("control nDCG@10 for %s", query.key())
							.isGreaterThanOrEqualTo(baselineMeasurement.ndcg());
					assertThat(candidateMeasurement.precision())
							.as("control Precision@1 for %s", query.key())
							.isGreaterThanOrEqualTo(baselineMeasurement.precision());
				}
				novelRelevantAt10 += novelRelevant(
						baselineKeys.stream().limit(query.cutoff()).toList(),
						candidateKeys,
						query.judgments());
				System.out.printf(
						Locale.ROOT,
						"related-topic-reuse-development-v1 query=%s kind=%s "
								+ "baseline-recall@10=%.3f candidate-recall@10=%.3f "
								+ "baseline-ndcg@10=%.3f candidate-ndcg@10=%.3f "
								+ "baseline-precision@1=%.3f candidate-precision@1=%.3f "
								+ "baseline-mrr@10=%.3f candidate-mrr@10=%.3f "
								+ "baseline=%s candidate=%s%n",
						query.key(), query.kind(), baselineMeasurement.recall(),
						candidateMeasurement.recall(), baselineMeasurement.ndcg(),
						candidateMeasurement.ndcg(), baselineMeasurement.precision(),
						candidateMeasurement.precision(), baselineMeasurement.reciprocalRank(),
						candidateMeasurement.reciprocalRank(), baselineKeys, candidateKeys);
			}
			else {
				assertThat(baselineKeys).as("no-seed control baseline").isEmpty();
				assertThat(seedIds).as("no-seed control seeds").isEmpty();
				assertThat(feedback).as("no-seed control feedback").isEmpty();
				if (gates.requireExactFallbackWithoutFeedback()) {
					assertThat(candidateKeys).isEqualTo(baselineKeys);
				}
			}
			if ((query.kind() == QueryKind.AUTHOR_NO_RELATED_SIGNAL_CONTROL
					|| query.kind() == QueryKind.NO_SEED_FALLBACK_CONTROL)
					&& gates.requireExactFallbackWithoutFeedback()) {
				assertThat(feedback).allSatisfy(list -> assertThat(list.candidates()).isEmpty());
				assertThat(candidateKeys).containsExactlyElementsOf(
						baselineKeys.stream().limit(query.cutoff()).toList());
			}
		}

		var baselineSummary = ProviderQualityMetrics.summarizeRankings(baselineMeasurements);
		var candidateSummary = ProviderQualityMetrics.summarizeRankings(candidateMeasurements);
		assertThat(strictOpportunityRecallImprovements)
				.as("strict opportunity-query Recall@10 improvements")
				.isGreaterThanOrEqualTo(gates.minimumStrictOpportunityRecallImprovements());
		assertThat(novelRelevantAt10)
				.as("novel relevant candidates at 10")
				.isGreaterThanOrEqualTo(gates.minimumNovelRelevantAt10());
		if (gates.requireNoMacroNdcgRegression()) {
			assertThat(candidateSummary.macroNdcg())
					.as("candidate macro nDCG@10")
					.isGreaterThanOrEqualTo(baselineSummary.macroNdcg());
		}
		assertThat(ownerScopeLeaks)
				.as("owner-scope leak count")
				.isLessThanOrEqualTo(gates.maximumOwnerScopeLeakCount());
		assertThat(filterViolations)
				.as("filter violation count")
				.isLessThanOrEqualTo(gates.maximumFilterViolationCount());
		assertThat(baselineAdversariesAt10)
				.as("baseline explicit adversary count at 10")
				.isLessThanOrEqualTo(gates.maximumBaselineAdversaryAt10Count());
		assertThat(candidateAdversariesAt10)
				.as("candidate explicit adversary count at 10")
				.isLessThanOrEqualTo(gates.maximumCandidateAdversaryAt10Count());
		assertThat(rankOneAdversaries)
				.as("rank-one adversary count")
				.isLessThanOrEqualTo(gates.maximumRankOneAdversaryCount());
		assertThat(provider.calls())
				.as("discovery provider calls")
				.isLessThanOrEqualTo(gates.maximumProviderCallCount());
		assertThat(experimentalSnapshotWrites)
				.as("candidate snapshot writes")
				.isLessThanOrEqualTo(gates.maximumExperimentalSnapshotWriteCount());

		System.out.printf(
				Locale.ROOT,
				"related-topic-reuse-development-v1 baseline-macro-recall@10=%.3f "
						+ "candidate-macro-recall@10=%.3f baseline-macro-ndcg@10=%.3f "
						+ "candidate-macro-ndcg@10=%.3f baseline-macro-precision@1=%.3f "
						+ "candidate-macro-precision@1=%.3f baseline-mrr@10=%.3f "
						+ "candidate-mrr@10=%.3f novel-relevant@10=%d "
						+ "strict-opportunity-improvements=%d owner-leaks=%d filter-violations=%d "
						+ "baseline-adversaries@10=%d candidate-adversaries@10=%d "
						+ "rank-one-adversaries=%d provider-calls=%d candidate-snapshot-writes=%d%n",
				baselineSummary.macroRecall(), candidateSummary.macroRecall(),
				baselineSummary.macroNdcg(), candidateSummary.macroNdcg(),
				baselineSummary.macroPrecision(), candidateSummary.macroPrecision(),
				baselineSummary.meanReciprocalRank(), candidateSummary.meanReciprocalRank(),
				novelRelevantAt10,
				strictOpportunityRecallImprovements, ownerScopeLeaks, filterViolations,
				baselineAdversariesAt10, candidateAdversariesAt10, rankOneAdversaries,
				provider.calls(), experimentalSnapshotWrites);
	}

	@Test
	void higherScoringHiddenCatalogCandidateCannotDisplaceOrRerankVisibleFeedback() {
		Query query = boundFixture.fixture().queries().stream()
				.filter(candidate -> "coastal-erosion-drone-mapping".equals(candidate.key()))
				.findFirst()
				.orElseThrow();
		SearchCommand command = command(query, boundPolicy.policy().baseline().poolSize());
		SearchView baseline = search.search(command);
		List<String> baselineIds = resultIds(baseline);
		List<UUID> seedIds = seedIds(
				baseline, boundPolicy.policy().candidate().maximumSeeds());
		List<FeedbackList> beforeFeedback = comparator.findFeedback(
				TARGET_OWNER, command, seedIds);
		var beforeFusion = fuse(baselineIds, beforeFeedback);
		long snapshotsBefore = snapshotCount(TARGET_OWNER);

		PaperView hidden = paperCatalog.upsert(
				hiddenCandidate(), hiddenProviderRecord(), NOW);
		assertThat(boundFixture.fixture().targetVisibleKeys()).doesNotContain(HIDDEN_KEY);
		assertThat(hidden.id()).isNotIn(seedIds);
		List<FeedbackList> afterFeedback = comparator.findFeedback(
				TARGET_OWNER, command, seedIds);
		var afterFusion = fuse(baselineIds, afterFeedback);

		assertThat(afterFeedback).containsExactlyElementsOf(beforeFeedback);
		assertThat(afterFusion).containsExactlyElementsOf(beforeFusion);
		assertThat(afterFeedback).flatExtracting(FeedbackList::candidates)
				.extracting(OwnerScopedRelatedTopicComparator.RelatedCandidate::paperId)
				.doesNotContain(hidden.id());
		assertThat(snapshotCount(TARGET_OWNER)).isEqualTo(snapshotsBefore);
		assertThat(provider.calls()).isZero();
	}

	private void assertLocalControl(SearchView view, Query query) {
		assertThat(view.query()).isEqualTo(query.text());
		assertThat(view.cacheDisposition()).isEqualTo(CacheDisposition.LOCAL_RESULT);
		assertThat(view.requestedMode()).isEqualTo(SearchMode.LOCAL);
		assertThat(view.executionSource()).isEqualTo(SearchExecutionSource.LOCAL_CATALOG);
		assertThat(view.providerCoverage()).isEmpty();
		assertThat(view.results()).extracting(result -> result.paper().id()).doesNotHaveDuplicates();
		assertThat(resultIds(view)).allSatisfy(id ->
				assertThat(boundFixture.fixture().targetVisibleKeys()).contains(keysByPaperId.get(UUID.fromString(id))));
	}

	private List<RelatedTopicRankFusion.FusedPaper> fuse(
			List<String> baselineIds, List<FeedbackList> feedback) {
		return RelatedTopicRankFusion.fuse(
				baselineIds,
				feedback.stream()
						.map(list -> list.candidates().stream()
								.map(candidate -> candidate.paperId().toString())
								.toList())
						.toList());
	}

	private List<String> resultIds(SearchView view) {
		return view.results().stream()
				.map(result -> result.paper().id().toString())
				.toList();
	}

	private static List<UUID> seedIds(SearchView baseline, int maximumSeeds) {
		return baseline.results().stream()
				.filter(result -> result.rankingReasons().stream()
						.anyMatch(reason -> SEED_ELIGIBILITY_FEATURES.contains(reason.feature())))
				.limit(maximumSeeds)
				.map(result -> result.paper().id())
				.toList();
	}

	private List<String> keys(List<String> paperIds) {
		return paperIds.stream()
				.map(UUID::fromString)
				.map(keysByPaperId::get)
				.toList();
	}

	private static int novelRelevant(
			List<String> baselineKeys,
			List<String> candidateKeys,
			Map<String, Integer> judgments) {
		Set<String> baseline = Set.copyOf(baselineKeys);
		return (int) candidateKeys.stream()
				.filter(key -> judgments.getOrDefault(key, 0) > 0)
				.filter(key -> !baseline.contains(key))
				.count();
	}

	private static boolean matches(
			Candidate candidate, RelatedTopicReuseEvaluationFixture.Filter filter) {
		return (filter.yearFrom() == null
					|| candidate.publicationYear() != null
					&& candidate.publicationYear() >= filter.yearFrom())
				&& (filter.yearTo() == null
						|| candidate.publicationYear() != null
						&& candidate.publicationYear() <= filter.yearTo())
				&& (filter.documentTypes().isEmpty()
						|| filter.documentTypes().contains(candidate.documentType()))
				&& (!filter.openAccessOnly() || candidate.reportedOpenAccess())
				&& (candidate.citationCount() == null ? 0 : candidate.citationCount())
						>= filter.minimumCitations()
				&& (filter.languages().isEmpty()
						|| filter.languages().contains(candidate.language()));
	}

	private SearchCommand command(Query query, int pageSize) {
		var filter = query.filters();
		return new SearchCommand(
				query.text(),
				filter.yearFrom(),
				filter.yearTo(),
				Set.copyOf(filter.documentTypes()),
				filter.openAccessOnly(),
				filter.minimumCitations(),
				Set.copyOf(filter.languages()),
				pageSize,
				"*",
				false,
				SearchMode.LOCAL);
	}

	private void seedFixture(RelatedTopicReuseEvaluationFixture fixture) {
		Map<String, UUID> ids = new LinkedHashMap<>();
		for (Candidate candidate : fixture.candidates()) {
			PaperView paper = paperCatalog.upsert(
					canonicalCandidate(candidate), providerRecord(candidate), fixture.retrievedAt());
			ids.put(candidate.key(), paper.id());
		}
		paperIdsByKey = Map.copyOf(ids);
		Map<UUID, String> reverse = new LinkedHashMap<>();
		ids.forEach((key, paperId) -> reverse.put(paperId, key));
		keysByPaperId = Map.copyOf(reverse);

		int searchLineageIndex = 1;
		for (Lineage lineage : fixture.lineages()) {
			if (lineage.kind() == LineageKind.TARGET_OWNER_SEARCH
					|| lineage.kind() == LineageKind.OTHER_OWNER_SEARCH) {
				UUID ownerId = lineage.kind() == LineageKind.TARGET_OWNER_SEARCH
						? TARGET_OWNER : OTHER_OWNER;
				seedPriorSearch(
						ownerId,
						fixture,
						lineage,
						String.format(Locale.ROOT, "%064x", searchLineageIndex++));
			}
			else if (lineage.kind() == LineageKind.TARGET_OWNER_COLLECTION
					|| lineage.kind() == LineageKind.OTHER_OWNER_COLLECTION) {
				UUID ownerId = lineage.kind() == LineageKind.TARGET_OWNER_COLLECTION
						? TARGET_OWNER : OTHER_OWNER;
				seedCollection(ownerId, fixture, lineage);
			}
		}
	}

	private void seedPriorSearch(
			UUID ownerId,
			RelatedTopicReuseEvaluationFixture fixture,
			Lineage lineage,
			String fingerprint) {
		List<ProviderPaperRecord> records = fixture.candidates().stream()
				.filter(candidate -> candidate.lineageKey().equals(lineage.key()))
				.map(this::providerPaper)
				.toList();
		assertThat(records).isNotEmpty();
		String priorQuery = "prior history " + lineage.key().replace('-', ' ');
		SearchCommand seedCommand = new SearchCommand(
				priorQuery, null, null, Set.of(), false, 0, Set.of(), 50, "*", false,
				SearchMode.ONLINE);
		snapshotStore.store(
				ownerId,
				seedCommand,
				priorQuery,
				fingerprint,
				1,
				"openalex-v1",
				new ProviderSearchBatchResult(
						List.of(new ProviderSearchResult(
								ProviderId.OPENALEX, records, records.size(), null, fixture.retrievedAt())),
						List.of(),
						null,
						fixture.retrievedAt()),
				fixture.retrievedAt().plus(Duration.ofDays(1)),
				CacheDisposition.MISS_FETCHED);
	}

	private void seedCollection(
			UUID ownerId, RelatedTopicReuseEvaluationFixture fixture, Lineage lineage) {
		currentUser.use(ownerId);
		UUID collectionId = library.createCollection(
				"Synthetic " + lineage.key(), "Related-topic visibility lineage").collectionId();
		fixture.candidates().stream()
				.filter(candidate -> candidate.lineageKey().equals(lineage.key()))
				.forEach(candidate -> library.addPaper(
						collectionId,
						paperIdsByKey.get(candidate.key()),
						ReadingStatus.UNREAD,
						List.of("synthetic-related-topic")));
	}

	private CanonicalPaperCandidate canonicalCandidate(Candidate candidate) {
		return new CanonicalPaperCandidate(
				candidate.title(),
				candidate.abstractText(),
				null,
				candidate.publicationYear(),
				candidate.documentType(),
				candidate.language(),
				candidate.venueName(),
				candidate.citationCount(),
				NOW,
				List.of(identifier(candidate.key())),
				authorCandidates(candidate));
	}

	private ProviderRecordCandidate providerRecord(Candidate candidate) {
		return providerRecord(candidate.key(), candidate.reportedOpenAccess());
	}

	private ProviderRecordCandidate providerRecord(String key, boolean reportedOpenAccess) {
		return new ProviderRecordCandidate(
				ProviderId.OPENALEX.name(),
				recordId(key),
				NOW.minusSeconds(60),
				NOW,
				sourceUri(key),
				reportedOpenAccess,
				landingUri(key),
				null,
				Map.of("synthetic", true));
	}

	private ProviderPaperRecord providerPaper(Candidate candidate) {
		return new ProviderPaperRecord(
				ProviderId.OPENALEX,
				recordId(candidate.key()),
				null,
				null,
				candidate.title(),
				candidate.abstractText(),
				null,
				candidate.publicationYear(),
				candidate.documentType(),
				candidate.language(),
				candidate.venueName(),
				candidate.citationCount(),
				providerAuthors(candidate),
				candidate.reportedOpenAccess(),
				landingUri(candidate.key()),
				null,
				null,
				NOW.minusSeconds(60),
				Map.of("synthetic", true),
				List.of(identifier(candidate.key())),
				sourceUri(candidate.key()));
	}

	private List<PaperAuthorCandidate> authorCandidates(Candidate candidate) {
		List<PaperAuthorCandidate> values = new ArrayList<>();
		for (int index = 0; index < candidate.authors().size(); index++) {
			values.add(new PaperAuthorCandidate(
					authorId(candidate.key(), index),
					candidate.authors().get(index),
					null,
					index,
					index == 0));
		}
		return List.copyOf(values);
	}

	private List<ProviderAuthor> providerAuthors(Candidate candidate) {
		List<ProviderAuthor> values = new ArrayList<>();
		for (int index = 0; index < candidate.authors().size(); index++) {
			values.add(new ProviderAuthor(
					authorId(candidate.key(), index),
					candidate.authors().get(index),
					null,
					index,
					index == 0));
		}
		return List.copyOf(values);
	}

	private CanonicalPaperCandidate hiddenCandidate() {
		return new CanonicalPaperCandidate(
				"Coastal Erosion Drone Mapping",
				"A synthetic hidden record with a stronger related-title and metadata tie-break.",
				null,
				2026,
				com.openscholar.paper.DocumentType.ARTICLE,
				"en",
				"Hidden Catalog Archive",
				1_000_000,
				NOW,
				List.of(identifier(HIDDEN_KEY)),
				List.of(new PaperAuthorCandidate(
						authorId(HIDDEN_KEY, 0), "Hidden Catalog Author", null, 0, true)));
	}

	private ProviderRecordCandidate hiddenProviderRecord() {
		return providerRecord(HIDDEN_KEY, true);
	}

	private static PaperIdentifier identifier(String key) {
		return new PaperIdentifier(PaperIdentifierType.OPENALEX, "", recordId(key));
	}

	private static String recordId(String key) {
		return RECORD_PREFIX + key;
	}

	private static String authorId(String key, int index) {
		return AUTHOR_PREFIX + key + "-" + index;
	}

	private static URI landingUri(String key) {
		return URI.create("https://fixtures.openscholar.test/papers/" + key);
	}

	private static URI sourceUri(String key) {
		return URI.create("https://fixtures.openscholar.test/source/" + key);
	}

	private long snapshotCount(UUID ownerId) {
		Long count = jdbcTemplate.queryForObject(
				"SELECT count(*) FROM search_snapshot WHERE owner_id = ?", Long.class, ownerId);
		return count == null ? 0L : count;
	}

	private void insertOwner(UUID ownerId, String displayName) {
		jdbcTemplate.update(
				"INSERT INTO app_user (id, display_name, created_at) VALUES (?, ?, ?)",
				ownerId,
				displayName,
				Timestamp.from(NOW));
	}

	private void cleanupFixtureRows() {
		jdbcTemplate.update("DELETE FROM app_user WHERE id IN (?, ?)", TARGET_OWNER, OTHER_OWNER);
		jdbcTemplate.update(
				"DELETE FROM paper WHERE id IN ("
						+ "SELECT paper_id FROM provider_record "
						+ "WHERE provider = ? AND provider_record_id LIKE ?)",
				ProviderId.OPENALEX.name(),
				RECORD_PREFIX + "%");
		jdbcTemplate.update("DELETE FROM author WHERE openalex_id LIKE ?", AUTHOR_PREFIX + "%");
	}

	@TestConfiguration(proxyBeanMethods = false)
	static class EvaluationConfiguration {

		@Bean
		@Primary
		Clock relatedTopicEvaluationClock() {
			return Clock.fixed(NOW, ZoneOffset.UTC);
		}

		@Bean
		@Primary
		MutableCurrentUser relatedTopicEvaluationCurrentUser() {
			return new MutableCurrentUser();
		}

		@Bean
		@Primary
		NoCallResearchProvider relatedTopicEvaluationResearchProvider() {
			return new NoCallResearchProvider();
		}
	}

	static final class MutableCurrentUser implements com.openscholar.security.CurrentUserIdProvider {

		private final AtomicReference<UUID> ownerId = new AtomicReference<>(TARGET_OWNER);

		@Override
		public UUID currentUserId() {
			return ownerId.get();
		}

		void use(UUID value) {
			ownerId.set(java.util.Objects.requireNonNull(value, "ownerId"));
		}
	}

	static final class NoCallResearchProvider implements ResearchProvider {

		private final AtomicInteger calls = new AtomicInteger();

		@Override
		public ProviderId id() {
			return ProviderId.OPENALEX;
		}

		@Override
		public ProviderSearchResult search(ProviderSearchQuery query) {
			calls.incrementAndGet();
			throw new AssertionError(
					"Related-topic LOCAL evaluation must not call a provider: " + query.query());
		}

		int calls() {
			return calls.get();
		}

		void reset() {
			calls.set(0);
		}
	}
}
