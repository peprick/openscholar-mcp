package com.openscholar.search.internal.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import com.openscholar.TestcontainersConfiguration;
import com.openscholar.paper.DocumentType;
import com.openscholar.provider.ProviderId;
import com.openscholar.provider.ProviderSearchQuery;
import com.openscholar.provider.ProviderSearchResult;
import com.openscholar.provider.ResearchProvider;
import com.openscholar.search.SearchCommand;
import com.openscholar.search.SearchMode;
import com.openscholar.search.SearchResultView;
import com.openscholar.search.internal.LocalCatalogSearchEvaluationAdapter;
import com.openscholar.search.internal.LocalCatalogSearchEvaluationAdapter.EvaluationPage;
import com.openscholar.search.internal.persistence.OwnerScopedRelatedTopicComparator.FeedbackList;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.ObjectMapper;

/**
 * Opt-in, evaluation-only scale mechanics benchmark. Latencies are diagnostic
 * observations, not product gates or target-deployment evidence.
 */
@Import({
	TestcontainersConfiguration.class,
	LocalCatalogSearchEvaluationAdapter.Configuration.class,
	OwnerScopedRelatedTopicReuseScaleEvaluationTests.EvaluationConfiguration.class
})
@SpringBootTest(properties = {
	"openscholar.providers.europe-pmc.enabled=false",
	"openscholar.providers.doaj.enabled=false",
	"openscholar.providers.core.enabled=false",
	"openscholar.providers.datacite.enabled=false",
	"openscholar.related-topic-scale-context=true"
})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@EnabledIfEnvironmentVariable(
		named = RelatedTopicReuseScalePolicy.ENVIRONMENT_GATE,
		matches = "true")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OwnerScopedRelatedTopicReuseScaleEvaluationTests {

	private static final List<Stage> STAGES = List.of(
			Stage.CONTROL, Stage.FEEDBACK, Stage.FUSION);

	@Autowired
	private ObjectMapper objectMapper;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Autowired
	private JdbcClient jdbcClient;

	@Autowired
	private LocalCatalogSearchEvaluationAdapter localSearch;

	@Autowired
	private SearchSnapshotStore snapshotStore;

	@Autowired
	private PlatformTransactionManager transactionManager;

	@Autowired
	private PostgreSQLContainer postgresContainer;

	@Autowired
	private NoCallResearchProvider provider;

	@Test
	void reportsReferenceShapedScaleMechanicsWithoutMutatingProductState() throws Exception {
		RelatedTopicReuseScalePolicy.BoundPolicy boundPolicy =
				RelatedTopicReuseScalePolicy.loadFrozen(objectMapper);
		RelatedTopicReuseScalePolicy policy = boundPolicy.policy();
		RelatedTopicReuseEvaluationPolicy.BoundPolicy subjectPolicy =
				RelatedTopicReuseEvaluationPolicy.loadFrozen(objectMapper);
		subjectPolicy.validateReference(
				policy.subjectPolicyId(), policy.subjectPolicySha256());
		Set<String> seedEligibilityFeatures =
				Set.copyOf(subjectPolicy.policy().candidate().seedEligibilityFeatures());
		assertPinnedRuntime(policy);
		provider.reset();

		RelatedTopicReuseScaleFixture.SeedTiming seedTiming =
				RelatedTopicReuseScaleFixture.seed(
						jdbcTemplate, transactionManager, policy.corpus());
		assertFixtureCounts(policy.corpus());
		RelatedTopicReuseScaleFixture.ProductTableCounts countsBefore =
				RelatedTopicReuseScaleFixture.productTableCounts(jdbcTemplate);
		long snapshotsBefore = countsBefore.searchSnapshots();

		BenchmarkRun benchmark = runBenchmark(policy, seedEligibilityFeatures);

		RelatedTopicReuseScaleFixture.ProductTableCounts countsAfter =
				RelatedTopicReuseScaleFixture.productTableCounts(jdbcTemplate);
		assertThat(countsAfter)
				.as("product tables after the read-only measured phase")
				.isEqualTo(countsBefore);
		long experimentalSnapshotWrites = countsAfter.searchSnapshots() - snapshotsBefore;
		assertThat(provider.calls())
				.isLessThanOrEqualTo(policy.structuralGates().maximumProviderCallCount());
		assertThat(experimentalSnapshotWrites)
				.isLessThanOrEqualTo(
						policy.structuralGates().maximumExperimentalSnapshotWriteCount());

		StructuralReport structural = new StructuralReport(
				true,
				true,
				true,
				benchmark.exactNoSeedFallback(),
				benchmark.nonemptySeededFeedback(),
				benchmark.ownerScopeLeakCount(),
				benchmark.filterViolationCount(),
				provider.calls(),
				experimentalSnapshotWrites,
				countsBefore.equals(countsAfter));
		assertStructuralGates(policy.structuralGates(), structural);

		ScaleReport report = new ScaleReport(
				policy.measurement().outputSchemaVersion(),
				policy.policyId(),
				boundPolicy.sha256(),
				policy.interpretation().evidenceClassification(),
				policy.interpretation().latencyDecision(),
				Instant.now(),
				new PolicyReference(policy.subjectPolicyId(), policy.subjectPolicySha256()),
				runtime(policy, benchmark.transactionReadOnly()),
				policy.corpus(),
				new MeasurementConfiguration(
						policy.measurement().baselinePoolSize(),
						policy.measurement().cutoff(),
						policy.measurement().warmupRuns(),
						policy.measurement().measurementRuns(),
						policy.workloads().size(),
						policy.measurement().measurementRuns() * policy.workloads().size(),
						policy.measurement().concurrency(),
						policy.measurement().cacheState(),
						policy.measurement().percentileMethod()),
				new SeedReport(millis(seedTiming.seedNanos()), millis(seedTiming.analyzeNanos())),
				benchmark.workloads(),
				benchmark.aggregate(),
				structural,
				policy.interpretation().qualifiedTargetEnvironmentRequired(),
				policy.interpretation().activationEvidence(),
				false);
		System.out.println(objectMapper.writeValueAsString(report));
	}

	private BenchmarkRun runBenchmark(
			RelatedTopicReuseScalePolicy policy, Set<String> seedEligibilityFeatures) {
		String transactionReadOnly = inFreshReadOnlyTransaction(() ->
				jdbcClient.sql("SHOW transaction_read_only").query(String.class).single());
		assertThat(transactionReadOnly).isEqualTo("on");
		OwnerScopedRelatedTopicComparator comparator =
				new OwnerScopedRelatedTopicComparator(jdbcClient);
		List<PreparedWorkload> prepared = policy.workloads().stream()
				.map(workload -> prepare(
						workload, policy, comparator, seedEligibilityFeatures))
				.toList();
		StructuralAssessment structural = assess(prepared, policy);
		warmUp(prepared, policy, comparator);
		MeasuredSamples measured = measure(prepared, policy, comparator);
		return new BenchmarkRun(
				transactionReadOnly,
				measured.workloads(),
				measured.aggregate(),
				structural.exactNoSeedFallback(),
				structural.nonemptySeededFeedback(),
				structural.ownerScopeLeakCount(),
				structural.filterViolationCount());
	}

	private PreparedWorkload prepare(
			RelatedTopicReuseScalePolicy.Workload workload,
			RelatedTopicReuseScalePolicy policy,
			OwnerScopedRelatedTopicComparator comparator,
			Set<String> seedEligibilityFeatures) {
		SearchCommand command = command(workload, policy.measurement().baselinePoolSize());
		EvaluationPage control = inFreshReadOnlyTransaction(() -> localSearch.search(
				RelatedTopicReuseScaleFixture.TARGET_OWNER, command));
		List<UUID> seeds = seedIds(
				control,
				OwnerScopedRelatedTopicComparator.MAXIMUM_SEEDS,
				seedEligibilityFeatures);
		assertThat(seeds)
				.as("seed count for %s", workload.key())
				.hasSize(workload.expectedSeedCount());
		List<FeedbackList> feedback = inFreshReadOnlyTransaction(() -> comparator.findFeedback(
				RelatedTopicReuseScaleFixture.TARGET_OWNER, command, seeds));
		List<RelatedTopicRankFusion.FusedPaper> fusion = fuse(
				control, feedback, policy.measurement().cutoff());

		EvaluationPage repeatedControl = inFreshReadOnlyTransaction(() -> localSearch.search(
				RelatedTopicReuseScaleFixture.TARGET_OWNER, command));
		List<FeedbackList> repeatedFeedback = inFreshReadOnlyTransaction(
				() -> comparator.findFeedback(
						RelatedTopicReuseScaleFixture.TARGET_OWNER, command, seeds));
		List<RelatedTopicRankFusion.FusedPaper> repeatedFusion = fuse(
				repeatedControl, repeatedFeedback, policy.measurement().cutoff());
		assertThat(repeatedControl)
				.as("stable control for %s", workload.key())
				.isEqualTo(control);
		assertThat(repeatedFeedback)
				.as("stable feedback for %s", workload.key())
				.containsExactlyElementsOf(feedback);
		assertThat(repeatedFusion)
				.as("stable fusion for %s", workload.key())
				.containsExactlyElementsOf(fusion);
		return new PreparedWorkload(
				workload,
				command,
				control,
				seeds,
				feedback,
				fusion,
				policy.measurement().cutoff());
	}

	private StructuralAssessment assess(
			List<PreparedWorkload> prepared, RelatedTopicReuseScalePolicy policy) {
		int ownerScopeLeaks = 0;
		int filterViolations = 0;
		boolean exactNoSeedFallback = true;
		boolean nonemptySeededFeedback = true;
		for (PreparedWorkload workload : prepared) {
			List<UUID> controlIds = resultIds(workload.control());
			List<UUID> feedbackIds = workload.feedback().stream()
					.flatMap(list -> list.candidates().stream())
					.map(OwnerScopedRelatedTopicComparator.RelatedCandidate::paperId)
					.toList();
			assertThat(controlIds).doesNotHaveDuplicates();
			assertThat(workload.feedback())
					.allSatisfy(list -> {
						assertThat(list.candidates())
								.hasSizeLessThanOrEqualTo(
										OwnerScopedRelatedTopicComparator
												.MAXIMUM_RELATED_CANDIDATES_PER_SEED);
						assertThat(list.candidates())
								.extracting(
										OwnerScopedRelatedTopicComparator.RelatedCandidate::paperId)
								.doesNotContain(list.seedPaperId())
								.doesNotHaveDuplicates();
					});
			ownerScopeLeaks += (int) java.util.stream.Stream
					.concat(controlIds.stream(), feedbackIds.stream())
					.filter(id -> !RelatedTopicReuseScaleFixture.targetVisible(id, policy.corpus()))
					.count();

			Set<UUID> idsToCheck = new LinkedHashSet<>(controlIds);
			idsToCheck.addAll(feedbackIds);
			Map<UUID, PaperMetadata> metadata = inFreshReadOnlyTransaction(
					() -> metadata(idsToCheck));
			assertThat(metadata.keySet()).containsExactlyInAnyOrderElementsOf(idsToCheck);
			filterViolations += (int) idsToCheck.stream()
					.map(metadata::get)
					.filter(candidate -> !matches(candidate, workload.command()))
					.count();

			if (workload.workload().expectedSeedCount() == 0) {
				List<String> expectedBaselineOrder = stringIds(workload.control()).stream()
								.limit(workload.cutoff())
								.toList();
				List<String> actualFallbackOrder = workload.fusion().stream()
						.map(RelatedTopicRankFusion.FusedPaper::paperKey)
						.toList();
				exactNoSeedFallback &= workload.seeds().isEmpty()
						&& workload.feedback().isEmpty()
						&& actualFallbackOrder.equals(expectedBaselineOrder);
			}
			else {
				nonemptySeededFeedback &= !feedbackIds.isEmpty();
			}
		}
		return new StructuralAssessment(
				exactNoSeedFallback,
				nonemptySeededFeedback,
				ownerScopeLeaks,
				filterViolations);
	}

	private void warmUp(
			List<PreparedWorkload> prepared,
			RelatedTopicReuseScalePolicy policy,
			OwnerScopedRelatedTopicComparator comparator) {
		for (int run = 0; run < policy.measurement().warmupRuns(); run++) {
			for (int offset = 0; offset < prepared.size(); offset++) {
				PreparedWorkload workload = prepared.get((run + offset) % prepared.size());
				assertControl(workload, inFreshReadOnlyTransaction(() -> localSearch.search(
						RelatedTopicReuseScaleFixture.TARGET_OWNER, workload.command())));
				assertFeedback(workload, inFreshReadOnlyTransaction(
						() -> comparator.findFeedback(
								RelatedTopicReuseScaleFixture.TARGET_OWNER,
								workload.command(),
								workload.seeds())));
				assertFusion(workload, fuse(
						workload.control(), workload.feedback(), workload.cutoff()));
			}
		}
	}

	private MeasuredSamples measure(
			List<PreparedWorkload> prepared,
			RelatedTopicReuseScalePolicy policy,
			OwnerScopedRelatedTopicComparator comparator) {
		Map<String, SampleBucket> buckets = new LinkedHashMap<>();
		prepared.forEach(workload -> buckets.put(workload.workload().key(), new SampleBucket()));
		for (int run = 0; run < policy.measurement().measurementRuns(); run++) {
			for (int offset = 0; offset < prepared.size(); offset++) {
				int workloadIndex = (run + offset) % prepared.size();
				PreparedWorkload workload = prepared.get(workloadIndex);
				SampleBucket bucket = buckets.get(workload.workload().key());
				long controlNanos = 0L;
				long feedbackNanos = 0L;
				long fusionNanos = 0L;
				for (Stage stage : rotatedStages(run + workloadIndex)) {
					switch (stage) {
						case CONTROL -> {
							Timed<EvaluationPage> timed = timed(
									() -> inFreshReadOnlyTransaction(() -> localSearch.search(
											RelatedTopicReuseScaleFixture.TARGET_OWNER,
											workload.command())));
							controlNanos = timed.nanos();
							assertControl(workload, timed.value());
						}
						case FEEDBACK -> {
							Timed<List<FeedbackList>> timed = timed(
									() -> inFreshReadOnlyTransaction(() -> comparator.findFeedback(
											RelatedTopicReuseScaleFixture.TARGET_OWNER,
											workload.command(),
											workload.seeds())));
							feedbackNanos = timed.nanos();
							assertFeedback(workload, timed.value());
						}
						case FUSION -> {
							Timed<List<RelatedTopicRankFusion.FusedPaper>> timed = timed(
									() -> fuse(
											workload.control(),
											workload.feedback(),
											workload.cutoff()));
							fusionNanos = timed.nanos();
							assertFusion(workload, timed.value());
						}
					}
				}
				bucket.add(controlNanos, feedbackNanos, fusionNanos);
			}
		}

		List<WorkloadMeasurements> workloadReports = new ArrayList<>();
		SampleBucket aggregate = new SampleBucket();
		for (PreparedWorkload workload : prepared) {
			SampleBucket bucket = buckets.get(workload.workload().key());
			assertThat(bucket.control).hasSize(policy.measurement().measurementRuns());
			aggregate.addAll(bucket);
			workloadReports.add(new WorkloadMeasurements(
					workload.workload().key(),
					workload.workload().kind(),
					workload.workload().expectedSeedCount(),
					bucket.summaries()));
		}
		return new MeasuredSamples(
				List.copyOf(workloadReports), aggregate.summaries());
	}

	private void assertPinnedRuntime(RelatedTopicReuseScalePolicy policy) {
		int serverVersionNumber = jdbcClient.sql("SHOW server_version_num")
				.query(Integer.class)
				.single();
		assertThat(serverVersionNumber / 10_000)
				.as("PostgreSQL major")
				.isEqualTo(policy.postgresMajorVersion());
		assertThat(postgresContainer.getDockerImageName())
				.isEqualTo(policy.databaseImage());
	}

	private void assertFixtureCounts(RelatedTopicReuseScalePolicy.Corpus corpus) {
		RelatedTopicReuseScaleFixture.FixtureCounts counts =
				RelatedTopicReuseScaleFixture.counts(jdbcTemplate);
		assertThat(counts.papers()).isEqualTo(corpus.totalPaperCount());
		assertThat(counts.targetSearchVisible()).isEqualTo(corpus.targetSearchVisibleCount());
		assertThat(counts.targetCollectionVisible())
				.isEqualTo(corpus.targetCollectionVisibleCount());
		assertThat(counts.otherOwnerVisible()).isEqualTo(corpus.otherOwnerVisibleCount());
		assertThat(counts.catalogOnly()).isEqualTo(corpus.catalogOnlyCount());

		var roundTripped = snapshotStore.findById(
				RelatedTopicReuseScaleFixture.TARGET_OWNER,
				UUID.fromString("43000000-0000-0000-0000-000000000001"))
				.orElseThrow();
		assertThat(roundTripped.providerCoverage())
				.singleElement()
				.satisfies(coverage -> {
					assertThat(coverage.provider()).isEqualTo(ProviderId.OPENALEX);
					assertThat(coverage.status()).isEqualTo("SUCCESS");
					assertThat(coverage.returnedCount())
							.isEqualTo(RelatedTopicReuseScaleFixture.SEARCH_RESULTS_PER_SNAPSHOT);
				});
		assertThat(roundTripped.results())
				.hasSize(RelatedTopicReuseScaleFixture.SEARCH_RESULTS_PER_SNAPSHOT)
				.first()
				.satisfies(result -> {
					assertThat(result.paper().id())
							.isEqualTo(RelatedTopicReuseScaleFixture.paperId(0));
					assertThat(result.paper().title()).isEqualTo("Coastal Erosion Drone Mapping");
					assertThat(result.pdfUrl()).isNull();
				});
		var storedSearch = snapshotStore.findStoredSearch(
				UUID.fromString("43000000-0000-0000-0000-000000000001"))
				.orElseThrow();
		assertThat(storedSearch.ownerId()).isEqualTo(RelatedTopicReuseScaleFixture.TARGET_OWNER);
		assertThat(storedSearch.command().pageSize())
				.isEqualTo(RelatedTopicReuseScaleFixture.SEARCH_RESULTS_PER_SNAPSHOT);
		assertThat(storedSearch.command().cursor()).isEqualTo("*");
		assertThat(storedSearch.command().mode()).isEqualTo(SearchMode.ONLINE);
	}

	private RuntimeReport runtime(
			RelatedTopicReuseScalePolicy policy, String transactionReadOnly) {
		return new RuntimeReport(
				postgresContainer.getDockerImageName(),
				jdbcClient.sql("SHOW server_version").query(String.class).single(),
				Runtime.getRuntime().availableProcessors(),
				Runtime.getRuntime().maxMemory() / (1024L * 1024L),
				jdbcClient.sql("SHOW work_mem").query(String.class).single(),
				policy.measurement().cacheState(),
				policy.measurement().concurrency(),
				transactionReadOnly);
	}

	private static SearchCommand command(
			RelatedTopicReuseScalePolicy.Workload workload, int pageSize) {
		return new SearchCommand(
				workload.query(),
				workload.yearFrom(),
				workload.yearTo(),
				Set.copyOf(workload.documentTypes()),
				workload.openAccessOnly(),
				workload.minimumCitations(),
				Set.copyOf(workload.languages()),
				pageSize,
				"*",
				false,
				SearchMode.LOCAL);
	}

	private static List<UUID> seedIds(
			EvaluationPage control,
			int maximumSeeds,
			Set<String> seedEligibilityFeatures) {
		return control.results().stream()
				.filter(result -> result.rankingReasons().stream()
						.anyMatch(reason -> seedEligibilityFeatures.contains(reason.feature())))
				.limit(maximumSeeds)
				.map(result -> result.paper().id())
				.toList();
	}

	private static List<RelatedTopicRankFusion.FusedPaper> fuse(
			EvaluationPage control, List<FeedbackList> feedback, int cutoff) {
		return RelatedTopicRankFusion.fuse(
				stringIds(control),
				feedback.stream()
						.map(list -> list.candidates().stream()
								.map(candidate -> candidate.paperId().toString())
								.toList())
						.toList())
				.stream()
				.limit(cutoff)
				.toList();
	}

	private static List<String> stringIds(EvaluationPage control) {
		return control.results().stream()
				.map(result -> result.paper().id().toString())
				.toList();
	}

	private static List<UUID> resultIds(EvaluationPage control) {
		return control.results().stream()
				.map(SearchResultView::paper)
				.map(paper -> paper.id())
				.toList();
	}

	private Map<UUID, PaperMetadata> metadata(Set<UUID> paperIds) {
		if (paperIds.isEmpty()) {
			return Map.of();
		}
		List<PaperMetadata> rows = jdbcClient.sql("""
				SELECT paper.id,
				       paper.publication_year,
				       paper.document_type,
				       paper.language,
				       paper.citation_count,
				       EXISTS (
				           SELECT 1
				           FROM provider_record record
				           WHERE record.paper_id = paper.id
				             AND record.reported_open_access
				       ) AS reported_open_access
				FROM paper
				WHERE paper.id IN (:paperIds)
				""")
				.param("paperIds", paperIds)
				.query((resultSet, rowNumber) -> new PaperMetadata(
						resultSet.getObject("id", UUID.class),
						resultSet.getInt("publication_year"),
						DocumentType.valueOf(resultSet.getString("document_type")),
						resultSet.getString("language"),
						resultSet.getInt("citation_count"),
						resultSet.getBoolean("reported_open_access")))
				.list();
		Map<UUID, PaperMetadata> byId = new LinkedHashMap<>();
		rows.forEach(row -> byId.put(row.paperId(), row));
		return Map.copyOf(byId);
	}

	private static boolean matches(PaperMetadata paper, SearchCommand command) {
		return (command.yearFrom() == null || paper.publicationYear() >= command.yearFrom())
				&& (command.yearTo() == null || paper.publicationYear() <= command.yearTo())
				&& (command.documentTypes().isEmpty()
						|| command.documentTypes().contains(paper.documentType()))
				&& (!command.openAccessOnly() || paper.reportedOpenAccess())
				&& paper.citationCount() >= command.minimumCitations()
				&& (command.languages().isEmpty()
						|| command.languages().contains(paper.language().toLowerCase(java.util.Locale.ROOT)));
	}

	private static void assertControl(
			PreparedWorkload expected, EvaluationPage actual) {
		assertThat(actual)
				.as("measured control for %s", expected.workload().key())
				.isEqualTo(expected.control());
	}

	private static void assertFeedback(
			PreparedWorkload expected, List<FeedbackList> actual) {
		assertThat(actual)
				.as("measured feedback for %s", expected.workload().key())
				.containsExactlyElementsOf(expected.feedback());
	}

	private static void assertFusion(
			PreparedWorkload expected,
			List<RelatedTopicRankFusion.FusedPaper> actual) {
		assertThat(actual)
				.as("measured fusion for %s", expected.workload().key())
				.containsExactlyElementsOf(expected.fusion());
	}

	private static List<Stage> rotatedStages(int offset) {
		List<Stage> order = new ArrayList<>(STAGES.size());
		for (int index = 0; index < STAGES.size(); index++) {
			order.add(STAGES.get((offset + index) % STAGES.size()));
		}
		return List.copyOf(order);
	}

	private static <T> Timed<T> timed(Supplier<T> action) {
		long started = System.nanoTime();
		T value = action.get();
		return new Timed<>(value, System.nanoTime() - started);
	}

	private <T> T inFreshReadOnlyTransaction(Supplier<T> action) {
		TransactionTemplate transaction = new TransactionTemplate(transactionManager);
		transaction.setReadOnly(true);
		transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
		T value = transaction.execute(status -> action.get());
		return java.util.Objects.requireNonNull(value, "read-only benchmark action result");
	}

	private static void assertStructuralGates(
			RelatedTopicReuseScalePolicy.StructuralGates gates, StructuralReport actual) {
		if (gates.requireStableControl()) {
			assertThat(actual.stableControl()).isTrue();
		}
		if (gates.requireStableFeedback()) {
			assertThat(actual.stableFeedback()).isTrue();
		}
		if (gates.requireStableFusion()) {
			assertThat(actual.stableFusion()).isTrue();
		}
		if (gates.requireExactNoSeedFallback()) {
			assertThat(actual.exactNoSeedFallback()).isTrue();
		}
		if (gates.requireNonemptySeededFeedback()) {
			assertThat(actual.nonemptySeededFeedback()).isTrue();
		}
		assertThat(actual.ownerScopeLeakCount())
				.isLessThanOrEqualTo(gates.maximumOwnerScopeLeakCount());
		assertThat(actual.filterViolationCount())
				.isLessThanOrEqualTo(gates.maximumFilterViolationCount());
		assertThat(actual.providerCallCount())
				.isLessThanOrEqualTo(gates.maximumProviderCallCount());
		assertThat(actual.experimentalSnapshotWriteCount())
				.isLessThanOrEqualTo(gates.maximumExperimentalSnapshotWriteCount());
		assertThat(actual.productTableCountsStable()).isTrue();
	}

	private static double millis(long nanos) {
		return nanos / 1_000_000.0d;
	}

	private enum Stage {
		CONTROL,
		FEEDBACK,
		FUSION
	}

	private static final class SampleBucket {

		private final List<Long> control = new ArrayList<>();
		private final List<Long> feedback = new ArrayList<>();
		private final List<Long> fusion = new ArrayList<>();
		private final List<Long> projectedTotal = new ArrayList<>();

		private void add(long controlNanos, long feedbackNanos, long fusionNanos) {
			control.add(controlNanos);
			feedback.add(feedbackNanos);
			fusion.add(fusionNanos);
			projectedTotal.add(RelatedTopicReuseScaleMetrics.projectedTotal(
					controlNanos, feedbackNanos, fusionNanos));
		}

		private void addAll(SampleBucket other) {
			control.addAll(other.control);
			feedback.addAll(other.feedback);
			fusion.addAll(other.fusion);
			projectedTotal.addAll(other.projectedTotal);
		}

		private StageMeasurements summaries() {
			return new StageMeasurements(
					RelatedTopicReuseScaleMetrics.summarize(control),
					RelatedTopicReuseScaleMetrics.summarize(feedback),
					RelatedTopicReuseScaleMetrics.summarize(fusion),
					RelatedTopicReuseScaleMetrics.summarize(projectedTotal));
		}
	}

	private record Timed<T>(T value, long nanos) {
	}

	private record PreparedWorkload(
			RelatedTopicReuseScalePolicy.Workload workload,
			SearchCommand command,
			EvaluationPage control,
			List<UUID> seeds,
			List<FeedbackList> feedback,
			List<RelatedTopicRankFusion.FusedPaper> fusion,
			int cutoff) {
	}

	private record PaperMetadata(
			UUID paperId,
			int publicationYear,
			DocumentType documentType,
			String language,
			int citationCount,
			boolean reportedOpenAccess) {
	}

	private record StructuralAssessment(
			boolean exactNoSeedFallback,
			boolean nonemptySeededFeedback,
			int ownerScopeLeakCount,
			int filterViolationCount) {
	}

	private record MeasuredSamples(
			List<WorkloadMeasurements> workloads,
			StageMeasurements aggregate) {
	}

	private record BenchmarkRun(
			String transactionReadOnly,
			List<WorkloadMeasurements> workloads,
			StageMeasurements aggregate,
			boolean exactNoSeedFallback,
			boolean nonemptySeededFeedback,
			int ownerScopeLeakCount,
			int filterViolationCount) {
	}

	private record PolicyReference(String policyId, String sha256) {
	}

	private record RuntimeReport(
			String postgresImage,
			String postgresVersion,
			int processors,
			long maximumJvmMemoryMiB,
			String workMem,
			String cacheState,
			int concurrency,
			String transactionReadOnly) {
	}

	private record MeasurementConfiguration(
			int baselinePoolSize,
			int cutoff,
			int warmupRuns,
			int measurementRunsPerWorkload,
			int workloadCount,
			int aggregateSamplesPerStage,
			int concurrency,
			String cacheState,
			String percentileMethod) {
	}

	private record SeedReport(double seedMillis, double analyzeMillis) {
	}

	private record WorkloadMeasurements(
			String key,
			RelatedTopicReuseScalePolicy.WorkloadKind kind,
			int expectedSeedCount,
			StageMeasurements measurements) {
	}

	private record StageMeasurements(
			RelatedTopicReuseScaleMetrics.Summary localControl,
			RelatedTopicReuseScaleMetrics.Summary feedbackStage,
			RelatedTopicReuseScaleMetrics.Summary inMemoryFusion,
			RelatedTopicReuseScaleMetrics.Summary projectedTotal) {
	}

	private record StructuralReport(
			boolean stableControl,
			boolean stableFeedback,
			boolean stableFusion,
			boolean exactNoSeedFallback,
			boolean nonemptySeededFeedback,
			int ownerScopeLeakCount,
			int filterViolationCount,
			int providerCallCount,
			long experimentalSnapshotWriteCount,
			boolean productTableCountsStable) {
	}

	private record ScaleReport(
			int schemaVersion,
			String benchmarkId,
			String benchmarkPolicySha256,
			String evidenceClassification,
			String latencyDecision,
			Instant measuredAt,
			PolicyReference subjectPolicy,
			RuntimeReport runtime,
			RelatedTopicReuseScalePolicy.Corpus corpus,
			MeasurementConfiguration measurement,
			SeedReport setup,
			List<WorkloadMeasurements> workloads,
			StageMeasurements aggregate,
			StructuralReport structural,
			boolean qualifiedTargetEnvironmentRequired,
			boolean activationEvidence,
			boolean retainedArtifact) {
	}

	@TestConfiguration(proxyBeanMethods = false)
	static class EvaluationConfiguration {

		@Bean
		@Primary
		com.openscholar.security.CurrentUserIdProvider relatedTopicScaleCurrentUser() {
			return () -> RelatedTopicReuseScaleFixture.TARGET_OWNER;
		}

		@Bean
		@Primary
		NoCallResearchProvider relatedTopicScaleResearchProvider() {
			return new NoCallResearchProvider();
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
					"Related-topic scale evaluation must not call a provider: " + query.query());
		}

		int calls() {
			return calls.get();
		}

		void reset() {
			calls.set(0);
		}
	}
}
