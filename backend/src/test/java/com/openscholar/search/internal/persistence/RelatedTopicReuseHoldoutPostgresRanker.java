package com.openscholar.search.internal.persistence;

import java.io.IOException;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

import com.openscholar.search.SearchCommand;
import com.openscholar.search.SearchMode;
import com.openscholar.search.SearchResultView;
import com.openscholar.search.internal.LocalCatalogSearchEvaluationAdapter;
import com.openscholar.search.internal.LocalCatalogSearchEvaluationAdapter.EvaluationPage;
import com.openscholar.search.internal.persistence.OwnerScopedRelatedTopicComparator.FeedbackList;
import com.openscholar.search.internal.persistence.RelatedTopicReuseHoldoutPostgresFixture.StagedCorpus;
import com.openscholar.search.internal.persistence.RelatedTopicReuseHoldoutPostgresFixture.StagedCorpus.HiddenLease;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/**
 * Label-blind PostgreSQL ranking phase for the preregistered related-topic
 * holdout. This class is test-only and has no runtime, REST, MCP, or UI entry
 * point.
 */
final class RelatedTopicReuseHoldoutPostgresRanker {

	private final ObjectMapper objectMapper;
	private final LocalCatalogSearchEvaluationAdapter localSearch;
	private final OwnerScopedRelatedTopicComparator comparator;
	private final PlatformTransactionManager transactionManager;
	private final RelatedTopicReuseHoldoutPostgresFixture fixture;
	private final LongSupplier providerCallCount;

	RelatedTopicReuseHoldoutPostgresRanker(
			ObjectMapper objectMapper,
			LocalCatalogSearchEvaluationAdapter localSearch,
			JdbcClient jdbcClient,
			PlatformTransactionManager transactionManager,
			RelatedTopicReuseHoldoutPostgresFixture fixture,
			LongSupplier providerCallCount) {
		this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
		this.localSearch = Objects.requireNonNull(localSearch, "localSearch");
		this.comparator = new OwnerScopedRelatedTopicComparator(
				Objects.requireNonNull(jdbcClient, "jdbcClient"));
		this.transactionManager = Objects.requireNonNull(
				transactionManager, "transactionManager");
		this.fixture = Objects.requireNonNull(fixture, "fixture");
		this.providerCallCount = Objects.requireNonNull(
				providerCallCount, "providerCallCount");
	}

	RelatedTopicReuseHoldoutRankingSnapshot.Observation rank(
			RelatedTopicReuseHoldoutBundle.RankingCorpus corpus) throws IOException {
		Objects.requireNonNull(corpus, "corpus");
		Policies policies = loadAndValidatePolicies(corpus);
		long providerCallsBefore = providerCallCount.getAsLong();
		try (StagedCorpus staged = fixture.stage(corpus)) {
			long snapshotsBefore = staged.targetSnapshotCount();
			List<RelatedTopicReuseHoldoutRankingSnapshot.QueryRanking> queries =
					corpus.corpus().queries().stream()
							.map(query -> rankQuery(staged, query, policies))
							.toList();
			long snapshotsAfter = staged.targetSnapshotCount();
			long providerCallsAfter = providerCallCount.getAsLong();
			long providerCalls = nonnegativeDelta(
					providerCallsBefore, providerCallsAfter, "provider-call counter");
			long experimentalSnapshotWrites = nonnegativeDelta(
					snapshotsBefore, snapshotsAfter, "search-snapshot counter");
			return new RelatedTopicReuseHoldoutRankingSnapshot.Observation(
					policies.holdout().candidateFreezeRevision(),
					policies.holdout().evaluation().cutoff(),
					queries.stream()
							.map(RelatedTopicReuseHoldoutRankingSnapshot.QueryRanking::queryKey)
							.toList(),
					queries,
					new RelatedTopicReuseHoldoutRankingSnapshot.StructuralCounters(
							providerCalls, experimentalSnapshotWrites));
		}
	}

	private RelatedTopicReuseHoldoutRankingSnapshot.QueryRanking rankQuery(
			StagedCorpus staged,
			RelatedTopicReuseHoldoutBundle.Query query,
			Policies policies) {
		RelatedTopicReuseHoldoutRankingSnapshot.RankingRun initial =
				rankOnce(staged, query, policies.candidate());
		RelatedTopicReuseHoldoutRankingSnapshot.RankingRun repeated =
				rankOnce(staged, query, policies.candidate());
		try (HiddenLease hidden = staged.injectMaximumMatch(query)) {
			RelatedTopicReuseHoldoutRankingSnapshot.RankingRun perturbed =
					rankOnce(staged, query, policies.candidate());
			if (!perturbed.controlPool().equals(initial.controlPool())
					|| !perturbed.controlTop10().equals(initial.controlTop10())
					|| !perturbed.eligibleSeedKeys().equals(initial.eligibleSeedKeys())) {
				throw new IllegalStateException(
						"target-invisible hidden candidates altered the production control");
			}
			return new RelatedTopicReuseHoldoutRankingSnapshot.QueryRanking(
					query.key(),
					initial,
					repeated,
					new RelatedTopicReuseHoldoutRankingSnapshot.HiddenPerturbation(
							hidden.otherOwnerCandidateKey(),
							hidden.catalogOnlyCandidateKey(),
							perturbed.feedbackPools(),
							perturbed.candidateTop10()));
		}
	}

	private RelatedTopicReuseHoldoutRankingSnapshot.RankingRun rankOnce(
			StagedCorpus staged,
			RelatedTopicReuseHoldoutBundle.Query query,
			RelatedTopicReuseEvaluationPolicy candidatePolicy) {
		return inFreshReadOnlyTransaction(() -> {
			SearchCommand command = command(query, candidatePolicy.baseline().poolSize());
			EvaluationPage control = localSearch.search(staged.targetOwnerId(), command);
			List<UUID> seedIds = control.results().stream()
					.filter(result -> result.rankingReasons().stream()
							.anyMatch(reason -> candidatePolicy.candidate()
									.seedEligibilityFeatures().contains(reason.feature())))
					.limit(candidatePolicy.candidate().maximumSeeds())
					.map(result -> result.paper().id())
					.toList();
			List<FeedbackList> feedback = comparator.findFeedback(
					staged.targetOwnerId(), command, seedIds);
			List<RelatedTopicRankFusion.FusedPaper> fused = RelatedTopicRankFusion.fuse(
					control.results().stream()
							.map(result -> result.paper().id().toString())
							.toList(),
					feedback.stream()
							.map(pool -> pool.candidates().stream()
									.map(candidate -> candidate.paperId().toString())
									.toList())
							.toList());
			List<RelatedTopicReuseHoldoutRankingSnapshot.RankedPaper> controlPool =
					control.results().stream()
							.map(result -> rankedControl(staged, result))
							.toList();
			List<RelatedTopicReuseHoldoutRankingSnapshot.FeedbackPool> feedbackPools =
					feedback.stream()
							.map(pool -> new RelatedTopicReuseHoldoutRankingSnapshot.FeedbackPool(
									staged.externalKey(pool.seedPaperId()),
									pool.candidates().stream()
											.map(candidate ->
													new RelatedTopicReuseHoldoutRankingSnapshot.RankedPaper(
															staged.externalKey(candidate.paperId()),
															candidate.lexicalScore()))
											.toList()))
							.toList();
			int cutoff = candidatePolicy.baseline().cutoff();
			return new RelatedTopicReuseHoldoutRankingSnapshot.RankingRun(
					controlPool,
					controlPool.stream().limit(cutoff).toList(),
					seedIds.stream().map(staged::externalKey).toList(),
					feedbackPools,
					fused.stream()
							.limit(cutoff)
							.map(candidate ->
									new RelatedTopicReuseHoldoutRankingSnapshot.RankedPaper(
											staged.externalKey(UUID.fromString(candidate.paperKey())),
											candidate.score()))
							.toList());
		});
	}

	private static RelatedTopicReuseHoldoutRankingSnapshot.RankedPaper rankedControl(
			StagedCorpus staged, SearchResultView result) {
		if (result.score() == null) {
			throw new IllegalStateException("production LOCAL control returned a null score");
		}
		return new RelatedTopicReuseHoldoutRankingSnapshot.RankedPaper(
				staged.externalKey(result.paper().id()), result.score());
	}

	private Policies loadAndValidatePolicies(
			RelatedTopicReuseHoldoutBundle.RankingCorpus corpus) throws IOException {
		RelatedTopicReuseHoldoutPolicy.BoundPolicy holdoutBound =
				RelatedTopicReuseHoldoutPolicy.loadFrozen(objectMapper);
		RelatedTopicReuseEvaluationPolicy.BoundPolicy candidateBound =
				RelatedTopicReuseEvaluationPolicy.loadFrozen(objectMapper);
		RelatedTopicReuseHoldoutPolicy holdout = holdoutBound.policy();
		candidateBound.validateReference(
				holdout.candidatePolicyId(), holdout.candidatePolicySha256());
		if (!corpus.protocolId().equals(holdout.bundle().protocolId())
				|| !corpus.policyId().equals(holdout.policyId())
				|| !corpus.policySha256().equals(holdoutBound.sha256())
				|| !corpus.corpusId().equals(corpus.corpus().corpusId())
				|| candidateBound.policy().baseline().poolSize()
						!= RelatedTopicReuseHoldoutRankingSnapshot.MAXIMUM_CONTROL_POOL_SIZE
				|| candidateBound.policy().baseline().cutoff()
						!= holdout.evaluation().cutoff()) {
			throw new IllegalArgumentException(
					"ranker input does not match the frozen related-topic holdout policies");
		}
		return new Policies(holdout, candidateBound.policy());
	}

	private <T> T inFreshReadOnlyTransaction(Supplier<T> action) {
		TransactionTemplate transaction = new TransactionTemplate(transactionManager);
		transaction.setReadOnly(true);
		transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
		T value = transaction.execute(status -> action.get());
		return Objects.requireNonNull(value, "holdout ranking transaction result");
	}

	private static SearchCommand command(
			RelatedTopicReuseHoldoutBundle.Query query, int pageSize) {
		RelatedTopicReuseHoldoutBundle.Filter filter = query.filters();
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

	private static long nonnegativeDelta(long before, long after, String counter) {
		if (before < 0 || after < before) {
			throw new IllegalStateException(counter + " must be monotonic and nonnegative");
		}
		return Math.subtractExact(after, before);
	}

	private record Policies(
			RelatedTopicReuseHoldoutPolicy holdout,
			RelatedTopicReuseEvaluationPolicy candidate) {
	}
}
