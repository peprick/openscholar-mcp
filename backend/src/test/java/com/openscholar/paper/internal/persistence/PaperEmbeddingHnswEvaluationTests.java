package com.openscholar.paper.internal.persistence;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.StringJoiner;
import java.util.UUID;

import com.openscholar.TestcontainersConfiguration;
import com.openscholar.paper.EmbeddingDistanceMetric;
import com.openscholar.paper.EmbeddingProfile;
import com.openscholar.paper.PaperEmbeddingMatch;
import com.openscholar.paper.PaperEmbeddingStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@Transactional
@EnabledIfEnvironmentVariable(named = "RUN_HNSW_EVALUATION", matches = "true")
class PaperEmbeddingHnswEvaluationTests {

	private static final Instant SEEDED_AT = Instant.parse("2026-08-21T12:00:00Z");
	private static final int BATCH_SIZE = 250;

	@Autowired
	private PaperEmbeddingStore embeddingStore;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Test
	void pinnedHnswPolicyMeetsItsRecallAndReferenceLatencyGates() throws Exception {
		PaperEmbeddingAnnEvaluationPolicy policy = PaperEmbeddingAnnEvaluationPolicy.load(
				JsonMapper.builder().build());
		assertPolicyMatchesRuntime(policy);
		assertThat(policy.recallGate().queryCount()).isEqualTo(20);

		embeddingStore.registerProfile(pinnedProfile());
		seedCorpus(policy.recallGate().corpusSize());
		jdbcTemplate.execute("analyze paper_embedding");
		assertThat(jdbcTemplate.queryForObject(
				"select count(*) from paper_embedding where profile_key = ?",
				Integer.class,
				PaperEmbeddingAnnPolicy.PROFILE_KEY))
				.isEqualTo(policy.recallGate().corpusSize());

		List<UUID> queryIds = queryIds(policy);
		List<Double> recalls = measureRecall(policy, queryIds);
		double macroRecall = recalls.stream().mapToDouble(Double::doubleValue).average().orElseThrow();
		assertThat(recalls)
				.allSatisfy(recall -> assertThat(recall)
						.isGreaterThanOrEqualTo(policy.recallGate().minimumPerQueryRecall()));
		assertThat(macroRecall)
				.isGreaterThanOrEqualTo(policy.recallGate().minimumMacroRecall());

		warmUp(policy, queryIds);
		LatencySamples latency = measureLatency(policy, queryIds);
		double exactP95Millis = p95Millis(latency.exactNanos());
		double approximateP95Millis = p95Millis(latency.approximateNanos());
		double speedup = exactP95Millis / approximateP95Millis;

		System.out.printf(
				Locale.ROOT,
				"%s corpus=%d queries=%d cutoff=%d macro-recall=%.4f "
						+ "exact-p95-ms=%.3f approximate-p95-ms=%.3f speedup=%.3fx "
						+ "reference-cpus=%d reference-memory-gib=%d cache=%s concurrent-load=%d%n",
				policy.policyId(),
				policy.recallGate().corpusSize(),
				queryIds.size(),
				policy.recallGate().cutoff(),
				macroRecall,
				exactP95Millis,
				approximateP95Millis,
				speedup,
				policy.latencyGate().referenceCpuCount(),
				policy.latencyGate().referenceMemoryGiB(),
				policy.latencyGate().cacheState(),
				policy.latencyGate().concurrentLoad());

		assertThat(approximateP95Millis)
				.isLessThanOrEqualTo(policy.latencyGate().maximumApproximateP95Millis());
		assertThat(speedup)
				.isGreaterThanOrEqualTo(
						policy.latencyGate().minimumExactToApproximateP95Speedup());
	}

	private void assertPolicyMatchesRuntime(PaperEmbeddingAnnEvaluationPolicy policy) {
		assertThat(policy.version()).isEqualTo(PaperEmbeddingAnnPolicy.VERSION);
		assertThat(policy.policyId()).isEqualTo(PaperEmbeddingAnnPolicy.POLICY_ID);
		assertThat(policy.activation()).isEqualTo("EVALUATION_ONLY");
		assertThat(policy.profileKey()).isEqualTo(PaperEmbeddingAnnPolicy.PROFILE_KEY);
		assertThat(policy.dimensions()).isEqualTo(PaperEmbeddingAnnPolicy.DIMENSIONS);
		assertThat(policy.index().name()).isEqualTo(PaperEmbeddingAnnPolicy.INDEX_NAME);
		assertThat(policy.query().efSearch()).isEqualTo(PaperEmbeddingAnnPolicy.QUERY_EF_SEARCH);
		assertThat(policy.query().maxScanTuples())
				.isEqualTo(PaperEmbeddingAnnPolicy.QUERY_MAX_SCAN_TUPLES);
	}

	private void seedCorpus(int corpusSize) {
		jdbcTemplate.execute((ConnectionCallback<Void>) connection -> {
			try (PreparedStatement papers = connection.prepareStatement("""
					insert into paper
					    (id, title, normalized_title, abstract_text, document_type,
					     metadata_quality, metadata_updated_at, version, created_at, updated_at)
					values (?, ?, ?, ?, 'ARTICLE', 0, ?, 0, ?, ?)
					""");
					PreparedStatement embeddings = connection.prepareStatement("""
					insert into paper_embedding
					    (paper_id, profile_key, dimensions, content_checksum,
					     embedding, embedded_at)
					values (?, ?, ?, repeat('a', 64), cast(? as public.vector), ?)
					""")) {
				for (int ordinal = 0; ordinal < corpusSize; ordinal++) {
					UUID paperId = paperId(ordinal);
					String title = "HNSW mechanics paper " + ordinal;
					papers.setObject(1, paperId);
					papers.setString(2, title);
					papers.setString(3, title.toLowerCase(Locale.ROOT));
					papers.setString(4, "Deterministic synthetic ANN evaluation vector " + ordinal);
					papers.setTimestamp(5, Timestamp.from(SEEDED_AT));
					papers.setTimestamp(6, Timestamp.from(SEEDED_AT));
					papers.setTimestamp(7, Timestamp.from(SEEDED_AT));
					papers.addBatch();

					embeddings.setObject(1, paperId);
					embeddings.setString(2, PaperEmbeddingAnnPolicy.PROFILE_KEY);
					embeddings.setInt(3, PaperEmbeddingAnnPolicy.DIMENSIONS);
					embeddings.setString(4, vectorLiteral(ordinal));
					embeddings.setTimestamp(5, Timestamp.from(SEEDED_AT));
					embeddings.addBatch();

					if ((ordinal + 1) % BATCH_SIZE == 0 || ordinal + 1 == corpusSize) {
						papers.executeBatch();
						embeddings.executeBatch();
					}
				}
			}
			return null;
		});
	}

	private List<UUID> queryIds(PaperEmbeddingAnnEvaluationPolicy policy) {
		int queryCount = policy.recallGate().queryCount();
		List<UUID> queryIds = new ArrayList<>(queryCount);
		for (int queryIndex = 0; queryIndex < queryCount; queryIndex++) {
			int ordinal = (queryIndex + 1) * policy.recallGate().corpusSize()
					/ (queryCount + 1);
			queryIds.add(paperId(ordinal));
		}
		return List.copyOf(queryIds);
	}

	private List<Double> measureRecall(
			PaperEmbeddingAnnEvaluationPolicy policy, List<UUID> queryIds) {
		List<Double> recalls = new ArrayList<>(queryIds.size());
		for (UUID queryId : queryIds) {
			List<PaperEmbeddingMatch> exact = embeddingStore.findNearestExact(
					queryId, policy.profileKey(), policy.recallGate().cutoff());
			List<PaperEmbeddingMatch> approximate = embeddingStore.findNearestApproximate(
					queryId, policy.profileKey(), policy.recallGate().cutoff());
			List<PaperEmbeddingMatch> repeated = embeddingStore.findNearestApproximate(
					queryId, policy.profileKey(), policy.recallGate().cutoff());

			assertResultContract(exact, queryId, policy.recallGate().cutoff());
			assertResultContract(approximate, queryId, policy.recallGate().cutoff());
			assertThat(repeated).containsExactlyElementsOf(approximate);
			Set<UUID> exactIds = paperIds(exact);
			Set<UUID> approximateIds = paperIds(approximate);
			long overlap = exactIds.stream().filter(approximateIds::contains).count();
			recalls.add((double) overlap / exactIds.size());
		}
		return List.copyOf(recalls);
	}

	private void assertResultContract(
			List<PaperEmbeddingMatch> matches, UUID sourceId, int expectedSize) {
		assertThat(matches).hasSize(expectedSize);
		assertThat(matches).extracting(PaperEmbeddingMatch::paperId)
				.doesNotContain(sourceId)
				.doesNotHaveDuplicates();
		assertThat(matches).extracting(PaperEmbeddingMatch::rank)
				.containsExactlyElementsOf(expectedRanks(expectedSize));
	}

	private void warmUp(PaperEmbeddingAnnEvaluationPolicy policy, List<UUID> queryIds) {
		for (int run = 0; run < policy.latencyGate().warmupRuns(); run++) {
			for (UUID queryId : queryIds) {
				embeddingStore.findNearestExact(
						queryId, policy.profileKey(), policy.recallGate().cutoff());
				embeddingStore.findNearestApproximate(
						queryId, policy.profileKey(), policy.recallGate().cutoff());
			}
		}
	}

	private LatencySamples measureLatency(
			PaperEmbeddingAnnEvaluationPolicy policy, List<UUID> queryIds) {
		List<Long> exact = new ArrayList<>();
		List<Long> approximate = new ArrayList<>();
		for (int run = 0; run < policy.latencyGate().measurementRuns(); run++) {
			for (int queryIndex = 0; queryIndex < queryIds.size(); queryIndex++) {
				UUID queryId = queryIds.get(queryIndex);
				if ((run + queryIndex) % 2 == 0) {
					exact.add(measureNanos(() -> embeddingStore.findNearestExact(
							queryId, policy.profileKey(), policy.recallGate().cutoff())));
					approximate.add(measureNanos(() -> embeddingStore.findNearestApproximate(
							queryId, policy.profileKey(), policy.recallGate().cutoff())));
				}
				else {
					approximate.add(measureNanos(() -> embeddingStore.findNearestApproximate(
							queryId, policy.profileKey(), policy.recallGate().cutoff())));
					exact.add(measureNanos(() -> embeddingStore.findNearestExact(
							queryId, policy.profileKey(), policy.recallGate().cutoff())));
				}
			}
		}
		return new LatencySamples(List.copyOf(exact), List.copyOf(approximate));
	}

	private long measureNanos(Runnable lookup) {
		long started = System.nanoTime();
		lookup.run();
		return System.nanoTime() - started;
	}

	private double p95Millis(List<Long> samples) {
		List<Long> sorted = samples.stream().sorted().toList();
		int index = Math.max(0, (int) Math.ceil(sorted.size() * 0.95d) - 1);
		return sorted.get(index) / 1_000_000.0d;
	}

	private Set<UUID> paperIds(List<PaperEmbeddingMatch> matches) {
		Set<UUID> paperIds = new HashSet<>();
		matches.forEach(match -> paperIds.add(match.paperId()));
		return Set.copyOf(paperIds);
	}

	private List<Integer> expectedRanks(int count) {
		List<Integer> ranks = new ArrayList<>(count);
		for (int rank = 1; rank <= count; rank++) {
			ranks.add(rank);
		}
		return List.copyOf(ranks);
	}

	private EmbeddingProfile pinnedProfile() {
		return new EmbeddingProfile(
				PaperEmbeddingAnnPolicy.PROFILE_KEY,
				PaperEmbeddingAnnPolicy.PROVIDER,
				PaperEmbeddingAnnPolicy.MODEL,
				PaperEmbeddingAnnPolicy.MODEL_REVISION,
				PaperEmbeddingAnnPolicy.CONTENT_KIND,
				PaperEmbeddingAnnPolicy.INPUT_POLICY_VERSION,
				PaperEmbeddingAnnPolicy.DIMENSIONS,
				EmbeddingDistanceMetric.COSINE);
	}

	private UUID paperId(int ordinal) {
		return UUID.nameUUIDFromBytes(("hnsw-policy-v1-paper-" + ordinal).getBytes(UTF_8));
	}

	private String vectorLiteral(int ordinal) {
		StringJoiner vector = new StringJoiner(",", "[", "]");
		for (int dimension = 0; dimension < PaperEmbeddingAnnPolicy.DIMENSIONS; dimension++) {
			long coordinate = ((long) (ordinal + 1) << 32) | dimension;
			vector.add(Float.toString((float) signedUnit(coordinate)));
		}
		return vector.toString();
	}

	private double signedUnit(long value) {
		long mixed = value + 0x9e3779b97f4a7c15L;
		mixed = (mixed ^ (mixed >>> 30)) * 0xbf58476d1ce4e5b9L;
		mixed = (mixed ^ (mixed >>> 27)) * 0x94d049bb133111ebL;
		mixed ^= mixed >>> 31;
		double unit = (mixed >>> 40) / (double) (1L << 24);
		return unit * 2.0d - 1.0d;
	}

	private record LatencySamples(List<Long> exactNanos, List<Long> approximateNanos) {
	}
}
