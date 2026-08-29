package com.openscholar.search.internal.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
import com.openscholar.search.SearchNotFoundException;
import com.openscholar.search.SearchResearchUseCase;
import com.openscholar.search.SearchResultView;
import com.openscholar.search.SearchView;
import com.openscholar.search.internal.persistence.LocalCatalogTopicEvaluationFixture.BoundFixture;
import com.openscholar.search.internal.persistence.LocalCatalogTopicEvaluationFixture.Candidate;
import com.openscholar.search.internal.persistence.LocalCatalogTopicEvaluationFixture.Query;
import com.openscholar.search.internal.persistence.LocalCatalogTopicEvaluationFixture.Visibility;
import com.openscholar.search.internal.persistence.LocalCatalogTopicEvaluationPolicy.BoundPolicy;
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
import org.springframework.test.annotation.DirtiesContext;
import tools.jackson.databind.ObjectMapper;

@Import({
	TestcontainersConfiguration.class,
	LocalCatalogTopicSearchEvaluationTests.EvaluationConfiguration.class
})
@SpringBootTest(properties = {
	"openscholar.providers.europe-pmc.enabled=false",
	"openscholar.providers.doaj.enabled=false",
	"openscholar.providers.core.enabled=false",
	"openscholar.providers.datacite.enabled=false"
})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class LocalCatalogTopicSearchEvaluationTests {

	private static final Instant NOW = Instant.parse("2026-08-29T00:00:00Z");
	private static final UUID TARGET_OWNER =
			UUID.fromString("10000000-0000-0000-0000-000000000001");
	private static final UUID OTHER_OWNER =
			UUID.fromString("10000000-0000-0000-0000-000000000002");
	private static final String RECORD_PREFIX = "OSLOCALV1-";
	private static final String AUTHOR_PREFIX = "AOSLOCALV1-";
	private static final Set<String> LOCAL_REASON_FEATURES = Set.of(
			"TITLE_EXACT", "TITLE_PREFIX", "TITLE_CONTAINS", "POSTGRES_FULL_TEXT", "AUTHOR_MATCH");

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
	private MutableCurrentUser currentUser;

	@Autowired
	private NoCallResearchProvider provider;

	private BoundFixture boundFixture;
	private BoundPolicy boundPolicy;
	private Map<String, UUID> paperIdsByKey;
	private Map<UUID, String> keysByPaperId;

	@BeforeEach
	void seedSyntheticOwnerScopedCatalog() throws Exception {
		cleanupFixtureRows();
		provider.reset();
		boundFixture = LocalCatalogTopicEvaluationFixture.loadFrozen(objectMapper);
		boundPolicy = LocalCatalogTopicEvaluationPolicy.loadFrozen(objectMapper);
		boundPolicy.policy().validateFixture(boundFixture);
		insertOwner(TARGET_OWNER, "Local topic target owner");
		insertOwner(OTHER_OWNER, "Local topic other owner");
		seedFixture(boundFixture.fixture());
		currentUser.use(TARGET_OWNER);
	}

	@AfterEach
	void removeSyntheticOwnerScopedCatalog() {
		cleanupFixtureRows();
		provider.reset();
	}

	@Test
	void productionLocalSearchMeetsTheFrozenOwnerScopedTopicBaseline() {
		var fixture = boundFixture.fixture();
		var policy = boundPolicy.policy();
		var gates = policy.gates();
		List<RankingMeasurement> measurements = new ArrayList<>();
		int ownerScopeLeaks = 0;
		int topRankedAdversaries = 0;

		for (Query query : fixture.queries()) {
			SearchView first = search.search(command(query, policy.constraints().pageSize()));
			SearchView repeated = search.search(command(query, policy.constraints().pageSize()));
			assertLocalResultContract(first, query);
			assertLocalResultContract(repeated, query);

			List<String> rankedKeys = resultKeys(first);
			List<String> repeatedKeys = resultKeys(repeated);
			if (gates.requireRepeatedOrder()) {
				assertThat(repeatedKeys)
						.as("repeated LOCAL order for %s", query.key())
						.containsExactlyElementsOf(rankedKeys);
				assertThat(repeated.results().stream().map(SearchResultView::score).toList())
						.as("repeated LOCAL scores for %s", query.key())
						.containsExactlyElementsOf(
								first.results().stream().map(SearchResultView::score).toList());
			}

			ownerScopeLeaks += (int) rankedKeys.stream()
					.filter(key -> !fixture.targetVisibleKeys().contains(key))
					.count();
			Set<String> targetVisibleAdversaries = query.adversaries().stream()
					.map(adversary -> fixture.candidatesByKey().get(adversary.candidateKey()))
					.filter(candidate -> candidate.visibility().targetVisible())
					.map(Candidate::key)
					.collect(java.util.stream.Collectors.toUnmodifiableSet());
			if (!rankedKeys.isEmpty() && targetVisibleAdversaries.contains(rankedKeys.getFirst())) {
				topRankedAdversaries++;
			}

			RankingMeasurement measurement = ProviderQualityMetrics.measureRanking(
					rankedKeys,
					query.judgments(),
					new RankingCutoffs(query.cutoff(), query.cutoff(), 1, query.cutoff()));
			measurements.add(measurement);
			assertThat(measurement.recall())
					.as("Recall@%d for %s with %s", query.cutoff(), query.key(), rankedKeys)
					.isGreaterThanOrEqualTo(gates.minimumPerQueryRecallAt10());
			assertThat(measurement.ndcg())
					.as("nDCG@%d for %s with %s", query.cutoff(), query.key(), rankedKeys)
					.isGreaterThanOrEqualTo(gates.minimumPerQueryNdcgAt10());

			System.out.printf(
					Locale.ROOT,
					"local-catalog-topic-development-v1 query=%s recall@10=%.3f ndcg@10=%.3f "
							+ "precision@1=%.3f reciprocal-rank@10=%.3f ranked=%s%n",
					query.key(), measurement.recall(), measurement.ndcg(), measurement.precision(),
					measurement.reciprocalRank(), rankedKeys);
		}

		var summary = ProviderQualityMetrics.summarizeRankings(measurements);
		assertThat(summary.macroRecall())
				.as("macro Recall@10")
				.isGreaterThanOrEqualTo(gates.minimumMacroRecallAt10());
		assertThat(summary.macroNdcg())
				.as("macro nDCG@10")
				.isGreaterThanOrEqualTo(gates.minimumMacroNdcgAt10());
		assertThat(summary.macroPrecision())
				.as("macro Precision@1")
				.isGreaterThanOrEqualTo(gates.minimumMacroPrecisionAt1());
		assertThat(summary.meanReciprocalRank())
				.as("mean reciprocal rank at 10")
				.isGreaterThanOrEqualTo(gates.minimumMeanReciprocalRankAt10());
		assertThat(ownerScopeLeaks)
				.as("owner-scope leak count")
				.isLessThanOrEqualTo(gates.maximumOwnerScopeLeakCount());
		assertThat(topRankedAdversaries)
				.as("top-ranked adversary count")
				.isLessThanOrEqualTo(gates.maximumTopRankedAdversaryCount());
		if (gates.requireNoProviderCalls()) {
			assertThat(provider.calls()).as("discovery provider calls").isZero();
		}

		System.out.printf(
				Locale.ROOT,
				"local-catalog-topic-development-v1 macro-recall@10=%.3f macro-ndcg@10=%.3f "
						+ "macro-precision@1=%.3f mrr@10=%.3f owner-leaks=%d top-adversaries=%d "
						+ "provider-calls=%d%n",
				summary.macroRecall(), summary.macroNdcg(), summary.macroPrecision(),
				summary.meanReciprocalRank(), ownerScopeLeaks, topRankedAdversaries,
				provider.calls());
	}

	@Test
	void localContinuationRemainsFrozenPersistedAndOwnerPrivate() {
		Query query = boundFixture.fixture().queries().stream()
				.filter(candidate -> "rare-disease-graph-diagnosis".equals(candidate.key()))
				.findFirst()
				.orElseThrow();
		SearchView complete = search.search(command(query, query.cutoff()));
		SearchView first = search.search(command(query, 2));
		assertThat(first.results()).hasSize(2);
		assertThat(first.nextCursor()).startsWith("oslocal1.");

		currentUser.use(OTHER_OWNER);
		assertThatThrownBy(() -> search.get(first.searchId()))
				.isInstanceOf(SearchNotFoundException.class);
		assertThatThrownBy(() -> search.next(first.searchId()))
				.isInstanceOf(SearchNotFoundException.class);
		SearchView otherOwner = search.search(command(query, query.cutoff()));
		assertThat(resultKeys(otherOwner)).containsExactly("other-rare-disease-exact");
		Query otherCollectionQuery = boundFixture.fixture().queries().stream()
				.filter(candidate -> "maternal-sepsis-prediction".equals(candidate.key()))
				.findFirst()
				.orElseThrow();
		assertThat(resultKeys(search.search(command(otherCollectionQuery, otherCollectionQuery.cutoff()))))
				.containsExactly("other-maternal-sepsis-exact");

		currentUser.use(TARGET_OWNER);
		List<SearchResultView> pagedResults = new ArrayList<>(first.results());
		SearchView currentPage = first;
		while (currentPage.nextCursor() != null) {
			currentPage = search.next(currentPage.searchId());
			pagedResults.addAll(currentPage.results());
		}
		assertThat(pagedResults.stream().map(result -> keysByPaperId.get(result.paper().id())).toList())
				.containsExactlyElementsOf(resultKeys(complete));
		assertThat(pagedResults).extracting(result -> result.paper().id()).doesNotHaveDuplicates();

		SearchView reopened = search.get(first.searchId());
		assertThat(reopened.cacheDisposition()).isEqualTo(CacheDisposition.EXACT_HIT);
		assertThat(reopened.executionSource()).isEqualTo(SearchExecutionSource.LOCAL_CATALOG);
		assertThat(reopened.results()).isEqualTo(first.results());
		assertThat(provider.calls()).isZero();
	}

	private void assertLocalResultContract(SearchView view, Query query) {
		assertThat(view.query()).isEqualTo(query.text());
		assertThat(view.cacheDisposition()).isEqualTo(CacheDisposition.LOCAL_RESULT);
		assertThat(view.requestedMode()).isEqualTo(SearchMode.LOCAL);
		assertThat(view.executionSource()).isEqualTo(SearchExecutionSource.LOCAL_CATALOG);
		assertThat(view.searchedAt()).isEqualTo(NOW);
		assertThat(view.freshUntil()).isEqualTo(NOW);
		assertThat(view.providerCoverage()).isEmpty();
		assertThat(view.warnings()).isEmpty();
		assertThat(view.results()).extracting(result -> result.paper().id()).doesNotHaveDuplicates();

		double previousScore = Double.POSITIVE_INFINITY;
		for (int index = 0; index < view.results().size(); index++) {
			SearchResultView result = view.results().get(index);
			assertThat(result.rank()).isEqualTo(index + 1);
			assertThat(result.score()).isNotNull().isFinite().isPositive();
			assertThat(result.score()).isLessThanOrEqualTo(previousScore);
			previousScore = result.score();
			assertThat(result.rankingReasons()).isNotEmpty().allSatisfy(reason -> {
				assertThat(reason.feature()).isIn(LOCAL_REASON_FEATURES);
				assertThat(reason.value()).isNotNull().isFinite().isPositive();
			});
			assertThat(result.provider()).isEqualTo(ProviderId.OPENALEX);
			assertThat(result.providerRecordId()).startsWith(RECORD_PREFIX);
			assertThat(result.retrievedAt()).isEqualTo(NOW);
			assertThat(result.providerContributions()).singleElement().satisfies(contribution -> {
				assertThat(contribution.provider()).isEqualTo(ProviderId.OPENALEX);
				assertThat(contribution.providerRecordId()).isEqualTo(result.providerRecordId());
				assertThat(contribution.retrievedAt()).isEqualTo(NOW);
			});
			assertThat(result.landingPageUrl()).isNotNull().satisfies(uri ->
					assertThat(uri.getHost()).isEqualTo("fixtures.openscholar.test"));
			assertThat(result.pdfUrl()).isNull();
			String key = keysByPaperId.get(result.paper().id());
			assertThat(key).as("fixture key for returned canonical paper").isNotNull();
			assertThat(boundFixture.fixture().targetVisibleKeys()).contains(key);
		}
	}

	private List<String> resultKeys(SearchView view) {
		return view.results().stream()
				.map(result -> keysByPaperId.get(result.paper().id()))
				.toList();
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

	private void seedFixture(LocalCatalogTopicEvaluationFixture fixture) {
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

		seedPriorSearch(TARGET_OWNER, fixture, Visibility.TARGET_OWNER_SEARCH, "a".repeat(64));
		seedPriorSearch(OTHER_OWNER, fixture, Visibility.OTHER_OWNER_SEARCH, "b".repeat(64));
		seedCollection(TARGET_OWNER, fixture, Visibility.TARGET_OWNER_COLLECTION, "Target collection");
		seedCollection(OTHER_OWNER, fixture, Visibility.OTHER_OWNER_COLLECTION, "Other collection");
	}

	private void seedPriorSearch(
			UUID ownerId,
			LocalCatalogTopicEvaluationFixture fixture,
			Visibility visibility,
			String fingerprint) {
		List<ProviderPaperRecord> records = fixture.candidates().stream()
				.filter(candidate -> candidate.visibility() == visibility)
				.map(this::providerPaper)
				.toList();
		assertThat(records).isNotEmpty();
		SearchCommand seedCommand = new SearchCommand(
				"synthetic local topic visibility seed",
				null,
				null,
				Set.of(),
				false,
				0,
				Set.of(),
				50,
				"*",
				false,
				SearchMode.ONLINE);
		snapshotStore.store(
				ownerId,
				seedCommand,
				"synthetic local topic visibility seed",
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
			UUID ownerId,
			LocalCatalogTopicEvaluationFixture fixture,
			Visibility visibility,
			String name) {
		currentUser.use(ownerId);
		UUID collectionId = library.createCollection(name, "Synthetic local-topic visibility edge")
				.collectionId();
		fixture.candidates().stream()
				.filter(candidate -> candidate.visibility() == visibility)
				.forEach(candidate -> library.addPaper(
						collectionId, paperIdsByKey.get(candidate.key()), ReadingStatus.UNREAD,
						List.of("synthetic-baseline")));
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
				List.of(identifier(candidate)),
				authorCandidates(candidate));
	}

	private ProviderRecordCandidate providerRecord(Candidate candidate) {
		return new ProviderRecordCandidate(
				ProviderId.OPENALEX.name(),
				recordId(candidate),
				NOW.minusSeconds(60),
				NOW,
				sourceUri(candidate),
				candidate.reportedOpenAccess(),
				landingUri(candidate),
				null,
				Map.of("synthetic", true));
	}

	private ProviderPaperRecord providerPaper(Candidate candidate) {
		return new ProviderPaperRecord(
				ProviderId.OPENALEX,
				recordId(candidate),
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
				landingUri(candidate),
				null,
				null,
				NOW.minusSeconds(60),
				Map.of("synthetic", true),
				List.of(identifier(candidate)),
				sourceUri(candidate));
	}

	private List<PaperAuthorCandidate> authorCandidates(Candidate candidate) {
		List<PaperAuthorCandidate> values = new ArrayList<>();
		for (int index = 0; index < candidate.authors().size(); index++) {
			values.add(new PaperAuthorCandidate(
					authorId(candidate, index), candidate.authors().get(index), null, index, index == 0));
		}
		return List.copyOf(values);
	}

	private List<ProviderAuthor> providerAuthors(Candidate candidate) {
		List<ProviderAuthor> values = new ArrayList<>();
		for (int index = 0; index < candidate.authors().size(); index++) {
			values.add(new ProviderAuthor(
					authorId(candidate, index), candidate.authors().get(index), null, index, index == 0));
		}
		return List.copyOf(values);
	}

	private static PaperIdentifier identifier(Candidate candidate) {
		return new PaperIdentifier(PaperIdentifierType.OPENALEX, "", recordId(candidate));
	}

	private static String recordId(Candidate candidate) {
		return RECORD_PREFIX + candidate.key();
	}

	private static String authorId(Candidate candidate, int index) {
		return AUTHOR_PREFIX + candidate.key() + "-" + index;
	}

	private static URI landingUri(Candidate candidate) {
		return URI.create("https://fixtures.openscholar.test/papers/" + candidate.key());
	}

	private static URI sourceUri(Candidate candidate) {
		return URI.create("https://fixtures.openscholar.test/source/" + candidate.key());
	}

	private void insertOwner(UUID ownerId, String displayName) {
		jdbcTemplate.update(
				"INSERT INTO app_user (id, display_name, created_at) VALUES (?, ?, ?)",
				ownerId,
				displayName,
				Timestamp.from(NOW));
	}

	private void cleanupFixtureRows() {
		jdbcTemplate.update(
				"DELETE FROM app_user WHERE id IN (?, ?)", TARGET_OWNER, OTHER_OWNER);
		jdbcTemplate.update(
				"DELETE FROM paper WHERE id IN ("
						+ "SELECT paper_id FROM provider_record "
						+ "WHERE provider = ? AND provider_record_id LIKE ?)",
				ProviderId.OPENALEX.name(),
				RECORD_PREFIX + "%");
		jdbcTemplate.update(
				"DELETE FROM author WHERE openalex_id LIKE ?", AUTHOR_PREFIX + "%");
	}

	@TestConfiguration(proxyBeanMethods = false)
	static class EvaluationConfiguration {

		@Bean
		@Primary
		Clock localTopicEvaluationClock() {
			return Clock.fixed(NOW, ZoneOffset.UTC);
		}

		@Bean
		@Primary
		MutableCurrentUser localTopicEvaluationCurrentUser() {
			return new MutableCurrentUser();
		}

		@Bean
		@Primary
		NoCallResearchProvider localTopicEvaluationResearchProvider() {
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
					"LOCAL evaluation must not call a discovery provider: " + query.query());
		}

		int calls() {
			return calls.get();
		}

		void reset() {
			calls.set(0);
		}
	}
}
