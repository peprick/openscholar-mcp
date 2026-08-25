package com.openscholar.paper.internal.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import java.io.InputStream;
import java.net.URI;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import com.openscholar.TestcontainersConfiguration;
import com.openscholar.paper.CanonicalPaperCandidate;
import com.openscholar.paper.PaperAuthorCandidate;
import com.openscholar.paper.PaperCatalog;
import com.openscholar.paper.PaperIdentifier;
import com.openscholar.paper.ProviderRecordCandidate;
import com.openscholar.paper.internal.persistence.PaperDeduplicationV2EvaluationFixture.CriticalRelation;
import com.openscholar.paper.internal.persistence.PaperDeduplicationV2EvaluationFixture.FixtureRecord;
import com.openscholar.paper.internal.persistence.PaperDeduplicationV2EvaluationPolicy.CaseFamily;
import com.openscholar.paper.internal.persistence.PaperDeduplicationV2EvaluationPolicy.ExpectedExactBaseline;
import com.openscholar.paper.internal.persistence.PaperDeduplicationV2EvaluationPolicy.Gates;
import com.openscholar.paper.internal.persistence.PaperDeduplicationV2EvaluationPolicy.SignalClass;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ClassPathResource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
class PaperDeduplicationV2EvaluationTests {

	private static final String DEVELOPMENT_FIXTURE_PATH =
			"search/relevance/paper-deduplication-development-v2.json";
	private static final String POLICY_PATH =
			"search/relevance/paper-deduplication-policy-v2.json";
	private static final double EPSILON = 1.0e-12d;

	@Autowired
	private PaperCatalog paperCatalog;

	@Autowired
	private ObjectMapper objectMapper;

	@Autowired
	private PlatformTransactionManager transactionManager;

	@Test
	void frozenDevelopmentResourcesAreStrictBoundAndInternallyConsistent() throws Exception {
		PaperDeduplicationV2EvaluationFixture fixture = loadFixture();
		PaperDeduplicationV2EvaluationPolicy policy = loadPolicy();
		ExpectedExactBaseline expected = policy.activation().expectedExactBaseline();

		assertThat(fixture.fixtureId()).isEqualTo("paper-deduplication-development-v2");
		assertThat(policy.policyId()).isEqualTo("paper-deduplication-policy-v2");
		assertThat(fixture.split()).isEqualTo("DEVELOPMENT");
		assertThat(fixture.labelUnit()).isEqualTo("BIBLIOGRAPHIC_MANIFESTATION");
		assertThat(fixture.sourcePolicy()).isEqualTo("SYNTHETIC_METADATA_ONLY");
		assertThat(policy.status()).isEqualTo("EVALUATION_ONLY");
		assertThat(fixture.fixtureId()).isEqualTo(policy.developmentFixtureId());
		assertThat(fixture.policyId()).isEqualTo(policy.policyId());
		assertThat(fixture.labelUnit()).isEqualTo(policy.labelUnit());
		assertThat(fixture.sourcePolicy()).isEqualTo(policy.sourcePolicy());
		assertThat(sha256(DEVELOPMENT_FIXTURE_PATH))
				.isEqualTo(policy.developmentFixtureSha256());

		assertThat(fixture.records()).hasSize(expected.recordCount());
		assertThat(fixture.records().stream()
				.map(FixtureRecord::goldCluster)
				.collect(Collectors.toSet())).hasSize(expected.goldClusterCount());
		assertThat(goldPositivePairs(fixture.records()))
				.isEqualTo(expected.goldPositivePairCount());
		assertThat(fixture.ingestOrders()).extracting(order -> order.key())
				.containsExactlyElementsOf(policy.ingestOrderKeys());
		assertThat(policy.metrics()).containsExactly(
				"PAIRWISE_PRECISION_RECALL_F1",
				"B_CUBED_PRECISION_RECALL_F1",
				"EXACT_CLUSTER_MATCH_RATE",
				"CASE_FAMILY_PAIRWISE",
				"INGEST_ORDER_PARTITION_STABILITY");

		Map<String, CaseFamily> familyPolicies = familyPolicies(policy);
		assertThat(fixture.records().stream()
				.map(FixtureRecord::caseFamily)
				.collect(Collectors.toSet()))
				.containsExactlyInAnyOrderElementsOf(familyPolicies.keySet());
		for (CaseFamily family : policy.caseFamilies()) {
			List<FixtureRecord> records = fixture.records().stream()
					.filter(record -> record.caseFamily().equals(family.key()))
					.toList();
			long positivePairs = goldPositivePairs(records);
			long mustSeparatePairs = fixture.criticalPairs().stream()
					.filter(pair -> pair.relation() == CriticalRelation.MUST_SEPARATE)
					.filter(pair -> pair.caseFamily().equals(family.key()))
					.count();
			assertThat(positivePairs)
					.as("gold-positive pairs for %s", family.key())
					.isGreaterThanOrEqualTo(family.minimumGoldPositivePairs());
			assertThat(mustSeparatePairs)
					.as("critical must-separate pairs for %s", family.key())
					.isGreaterThanOrEqualTo(family.minimumCriticalMustSeparatePairs());
		}
	}

	@Test
	void strictLoadersRejectUnknownKeysAndBrokenReferences() throws Exception {
		JsonNode corpusWithUnknownKey = resourceTree(DEVELOPMENT_FIXTURE_PATH);
		((ObjectNode) corpusWithUnknownKey.required("records").get(0))
				.put("unrecognizedMatchingHint", true);
		assertThatThrownBy(() -> PaperDeduplicationV2EvaluationFixture.parse(
				objectMapper, corpusWithUnknownKey))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("Unknown keys at $.records[0]")
				.hasMessageContaining("unrecognizedMatchingHint");

		JsonNode policyWithUnknownKey = resourceTree(POLICY_PATH);
		((ObjectNode) policyWithUnknownKey.required("activation"))
				.put("implicitActivation", true);
		assertThatThrownBy(() -> PaperDeduplicationV2EvaluationPolicy.parse(
				objectMapper, policyWithUnknownKey))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("Unknown keys at $.activation")
				.hasMessageContaining("implicitActivation");

		JsonNode corpusWithBrokenReference = resourceTree(DEVELOPMENT_FIXTURE_PATH);
		((ObjectNode) corpusWithBrokenReference.required("criticalPairs").get(0))
				.put("left", "missing-record");
		assertThatThrownBy(() -> PaperDeduplicationV2EvaluationFixture.parse(
				objectMapper, corpusWithBrokenReference))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("Critical pair references an unknown record")
				.hasMessageContaining("missing-record");
	}

	@Test
	void currentExactBaselinePreservesSafetyButCannotActivateMetadataMatching() throws Exception {
		PaperDeduplicationV2EvaluationFixture fixture = loadFixture();
		PaperDeduplicationV2EvaluationPolicy policy = loadPolicy();
		Map<String, CaseFamily> familyPolicies = familyPolicies(policy);
		List<OrderEvaluation> results = new ArrayList<>();

		for (PaperDeduplicationV2EvaluationFixture.IngestOrder order : fixture.ingestOrders()) {
			OrderEvaluation result = inRollbackTransaction(
					() -> evaluateOrder(fixture, order, familyPolicies));
			results.add(result);
			assertExpectedBaseline(result, policy.activation().expectedExactBaseline());
			assertSafetyAndActivationBoundary(
					result, policy.activation().gates(), policy.activation().expectedExactBaseline());
			assertCaseFamilyBoundary(result, policy);
			printMetrics(result, familyPolicies);
		}

		List<List<String>> expectedPartition = results.getFirst().partition();
		for (OrderEvaluation result : results) {
			assertThat(result.partition())
					.as("canonical partition for ingest order %s", result.orderKey())
					.isEqualTo(expectedPartition);
		}
		assertThat(policy.activation().gates().requireOrderInvariantPartitions()).isTrue();
	}

	private OrderEvaluation evaluateOrder(
			PaperDeduplicationV2EvaluationFixture fixture,
			PaperDeduplicationV2EvaluationFixture.IngestOrder order,
			Map<String, CaseFamily> familyPolicies) {
		Map<String, FixtureRecord> recordsByKey = fixture.recordsByKey();
		Map<String, Integer> sourceOrdinals = new HashMap<>();
		for (int index = 0; index < fixture.records().size(); index++) {
			sourceOrdinals.put(fixture.records().get(index).key(), index + 1);
		}
		Map<String, Observation> observations = new LinkedHashMap<>();
		for (String recordKey : order.recordKeys()) {
			FixtureRecord record = recordsByKey.get(recordKey);
			UUID paperId = paperCatalog.upsert(
					candidate(record),
					providerRecord(record, fixture, sourceOrdinals.get(recordKey)),
					fixture.retrievedInstant())
					.id();
			observations.put(record.key(), new Observation(
					record.key(), record.goldCluster(), record.caseFamily(),
					familyPolicies.get(record.caseFamily()).signalClass(), paperId));
		}

		List<Observation> values = List.copyOf(observations.values());
		PairwiseMetrics pairwise = pairwise(values);
		BCubedMetrics bCubed = bCubed(values);
		ExactClusterMetrics exactCluster = exactCluster(values);
		double exactSignalRecall = signalRecall(values, SignalClass.EXACT_SIGNAL);
		double metadataOnlyRecall = signalRecall(values, SignalClass.METADATA_ONLY);
		CriticalMetrics critical = criticalMetrics(fixture, observations);
		Map<String, PairwiseMetrics> caseMetrics = new LinkedHashMap<>();
		for (String family : familyPolicies.keySet()) {
			caseMetrics.put(family, pairwise(values.stream()
					.filter(observation -> observation.caseFamily().equals(family))
					.toList()));
		}
		return new OrderEvaluation(
				order.key(), pairwise, bCubed, exactCluster, exactSignalRecall,
				metadataOnlyRecall, critical.falseMerges(), critical.missedLinks(),
				Map.copyOf(caseMetrics), partition(values));
	}

	private static CanonicalPaperCandidate candidate(FixtureRecord record) {
		PaperDeduplicationV2EvaluationFixture.FixturePaper paper = record.paper();
		List<PaperIdentifier> identifiers = paper.identifiers().stream()
				.map(identifier -> new PaperIdentifier(
						identifier.type(), Objects.requireNonNullElse(identifier.namespace(), ""),
						identifier.value()))
				.toList();
		List<PaperAuthorCandidate> authors = paper.authors().stream()
				.map(author -> new PaperAuthorCandidate(
						author.openAlexId(), author.displayName(), author.orcid(), author.position(),
						author.corresponding()))
				.toList();
		return new CanonicalPaperCandidate(
				paper.title(), paper.abstractText(),
				paper.publicationDate() == null ? null : LocalDate.parse(paper.publicationDate()),
				paper.publicationYear(), paper.documentType(), paper.language(), paper.venueName(),
				null, null, identifiers, authors);
	}

	private static ProviderRecordCandidate providerRecord(
			FixtureRecord record,
			PaperDeduplicationV2EvaluationFixture fixture,
			int sourceOrdinal) {
		return new ProviderRecordCandidate(
				record.provider().name(), record.provider().recordId(), fixture.retrievedInstant(),
				fixture.retrievedInstant(),
				URI.create("https://fixtures.openscholar.test/v2/record/" + sourceOrdinal),
				false, null, null, Map.of());
	}

	private static PairwiseMetrics pairwise(List<Observation> observations) {
		int truePositives = 0;
		int falsePositives = 0;
		int falseNegatives = 0;
		int trueNegatives = 0;
		for (int left = 0; left < observations.size(); left++) {
			for (int right = left + 1; right < observations.size(); right++) {
				Observation a = observations.get(left);
				Observation b = observations.get(right);
				boolean expectedSame = a.goldCluster().equals(b.goldCluster());
				boolean actualSame = a.paperId().equals(b.paperId());
				if (expectedSame && actualSame) {
					truePositives++;
				}
				else if (!expectedSame && actualSame) {
					falsePositives++;
				}
				else if (expectedSame) {
					falseNegatives++;
				}
				else {
					trueNegatives++;
				}
			}
		}
		double precision = ratioOrOne(
				truePositives, truePositives + falsePositives);
		double recall = ratioOrOne(truePositives, truePositives + falseNegatives);
		double f1 = precision + recall == 0.0d
				? 0.0d
				: 2.0d * precision * recall / (precision + recall);
		return new PairwiseMetrics(
				truePositives, falsePositives, falseNegatives, trueNegatives,
				precision, recall, f1);
	}

	private static BCubedMetrics bCubed(List<Observation> observations) {
		Map<String, Set<String>> goldClusters = observations.stream()
				.collect(Collectors.groupingBy(
						Observation::goldCluster,
						Collectors.mapping(
								Observation::key, Collectors.toCollection(LinkedHashSet::new))));
		Map<UUID, Set<String>> actualClusters = observations.stream()
				.collect(Collectors.groupingBy(
						Observation::paperId,
						Collectors.mapping(
								Observation::key, Collectors.toCollection(LinkedHashSet::new))));
		double precisionSum = 0.0d;
		double recallSum = 0.0d;
		for (Observation observation : observations) {
			Set<String> gold = goldClusters.get(observation.goldCluster());
			Set<String> actual = actualClusters.get(observation.paperId());
			long intersection = actual.stream().filter(gold::contains).count();
			precisionSum += (double) intersection / actual.size();
			recallSum += (double) intersection / gold.size();
		}
		double precision = precisionSum / observations.size();
		double recall = recallSum / observations.size();
		double f1 = precision + recall == 0.0d
				? 0.0d
				: 2.0d * precision * recall / (precision + recall);
		return new BCubedMetrics(precision, recall, f1);
	}

	private static ExactClusterMetrics exactCluster(List<Observation> observations) {
		Set<Set<String>> actualClusters = observations.stream()
				.collect(Collectors.groupingBy(
						Observation::paperId,
						Collectors.mapping(
								Observation::key, Collectors.toCollection(LinkedHashSet::new))))
				.values().stream()
				.map(Set::copyOf)
				.collect(Collectors.toSet());
		List<Set<String>> goldClusters = observations.stream()
				.collect(Collectors.groupingBy(
						Observation::goldCluster,
						Collectors.mapping(
								Observation::key, Collectors.toCollection(LinkedHashSet::new))))
				.values().stream()
				.map(Set::copyOf)
				.toList();
		int matches = (int) goldClusters.stream().filter(actualClusters::contains).count();
		return new ExactClusterMetrics(matches, goldClusters.size(), ratioOrOne(matches, goldClusters.size()));
	}

	private static double signalRecall(
			List<Observation> observations, SignalClass signalClass) {
		int truePositives = 0;
		int expectedPositives = 0;
		for (int left = 0; left < observations.size(); left++) {
			for (int right = left + 1; right < observations.size(); right++) {
				Observation a = observations.get(left);
				Observation b = observations.get(right);
				if (a.signalClass() == signalClass
						&& b.signalClass() == signalClass
						&& a.goldCluster().equals(b.goldCluster())) {
					expectedPositives++;
					if (a.paperId().equals(b.paperId())) {
						truePositives++;
					}
				}
			}
		}
		return ratioOrOne(truePositives, expectedPositives);
	}

	private static CriticalMetrics criticalMetrics(
			PaperDeduplicationV2EvaluationFixture fixture,
			Map<String, Observation> observations) {
		int falseMerges = 0;
		int missedLinks = 0;
		for (PaperDeduplicationV2EvaluationFixture.CriticalPair pair : fixture.criticalPairs()) {
			boolean actualSame = observations.get(pair.left()).paperId()
					.equals(observations.get(pair.right()).paperId());
			if (pair.relation() == CriticalRelation.MUST_SEPARATE && actualSame) {
				falseMerges++;
			}
			if (pair.relation() == CriticalRelation.MUST_LINK && !actualSame) {
				missedLinks++;
			}
		}
		return new CriticalMetrics(falseMerges, missedLinks);
	}

	private static List<List<String>> partition(List<Observation> observations) {
		return observations.stream()
				.collect(Collectors.groupingBy(Observation::paperId))
				.values().stream()
				.map(cluster -> cluster.stream().map(Observation::key).sorted().toList())
				.sorted(Comparator.comparing(cluster -> String.join("\u0000", cluster)))
				.toList();
	}

	private static void assertExpectedBaseline(
			OrderEvaluation result, ExpectedExactBaseline expected) {
		PairwiseMetrics pairwise = result.pairwise();
		assertThat(pairwise.truePositives()).isEqualTo(expected.truePositives());
		assertThat(pairwise.falsePositives()).isEqualTo(expected.falsePositives());
		assertThat(pairwise.falseNegatives()).isEqualTo(expected.falseNegatives());
		assertThat(pairwise.trueNegatives()).isEqualTo(expected.trueNegatives());
		assertThat(pairwise.precision()).isCloseTo(expected.pairwisePrecision(), within(EPSILON));
		assertThat(pairwise.recall()).isCloseTo(expected.pairwiseRecall(), within(EPSILON));
		assertThat(pairwise.f1()).isCloseTo(expected.pairwiseF1(), within(EPSILON));
		assertThat(result.bCubed().precision())
				.isCloseTo(expected.bCubedPrecision(), within(EPSILON));
		assertThat(result.bCubed().recall()).isCloseTo(expected.bCubedRecall(), within(EPSILON));
		assertThat(result.bCubed().f1()).isCloseTo(expected.bCubedF1(), within(EPSILON));
		assertThat(result.exactCluster().matches()).isEqualTo(expected.exactClusterMatches());
		assertThat(result.exactCluster().rate())
				.isCloseTo(expected.exactClusterMatchRate(), within(EPSILON));
		assertThat(result.exactSignalRecall())
				.isCloseTo(expected.exactSignalRecall(), within(EPSILON));
		assertThat(result.metadataOnlyRecall())
				.isCloseTo(expected.metadataOnlyRecall(), within(EPSILON));
		assertThat(result.criticalFalseMerges()).isEqualTo(expected.criticalFalseMerges());
		assertThat(result.criticalMissedLinks()).isEqualTo(expected.criticalMissedLinks());
	}

	private static void assertSafetyAndActivationBoundary(
			OrderEvaluation result, Gates gates, ExpectedExactBaseline expected) {
		assertThat(result.pairwise().precision())
				.as("pairwise safety for %s", result.orderKey())
				.isEqualTo(gates.minimumPairwisePrecision());
		assertThat(result.bCubed().precision())
				.as("B-cubed safety for %s", result.orderKey())
				.isEqualTo(gates.minimumBCubedPrecision());
		assertThat(result.criticalFalseMerges())
				.as("must-separate safety for %s", result.orderKey())
				.isLessThanOrEqualTo(gates.maximumCriticalFalseMerges());
		assertThat(result.criticalMissedLinks())
				.as("critical must-link safety for %s", result.orderKey())
				.isLessThanOrEqualTo(gates.maximumCriticalMissedLinks());
		assertThat(result.exactSignalRecall())
				.as("exact-signal recall for %s", result.orderKey())
				.isGreaterThanOrEqualTo(gates.minimumExactSignalRecall());
		assertThat(result.metadataOnlyRecall())
				.as("metadata-only activation remains disabled for %s", result.orderKey())
				.isLessThan(gates.minimumMetadataOnlyRecall());
		assertThat(result.pairwise().recall())
				.as("overall activation remains disabled for %s", result.orderKey())
				.isLessThan(gates.minimumOverallPairwiseRecall());
		assertThat(result.pairwise().recall() - expected.pairwiseRecall())
				.as("pairwise recall gain over exact baseline for %s", result.orderKey())
				.isLessThan(gates.minimumPairwiseRecallGainOverExactBaseline());
		assertThat(result.bCubed().f1() - expected.bCubedF1())
				.as("B-cubed F1 gain over exact baseline for %s", result.orderKey())
				.isLessThan(gates.minimumBCubedF1GainOverExactBaseline());
	}

	private static void assertCaseFamilyBoundary(
			OrderEvaluation result, PaperDeduplicationV2EvaluationPolicy policy) {
		for (CaseFamily family : policy.caseFamilies()) {
			PairwiseMetrics metrics = result.caseMetrics().get(family.key());
			int positivePairs = metrics.truePositives() + metrics.falseNegatives();
			if (family.signalClass() == SignalClass.EXACT_SIGNAL) {
				assertThat(positivePairs)
						.as("exact positive pairs for %s/%s", result.orderKey(), family.key())
						.isGreaterThanOrEqualTo(policy.activation().gates()
								.minimumPositivePairsForFamilyGate());
				assertThat(metrics.recall())
						.as("exact case-family recall for %s/%s", result.orderKey(), family.key())
						.isEqualTo(1.0d);
			}
			else if (family.signalClass() == SignalClass.METADATA_ONLY) {
				assertThat(positivePairs)
						.as("metadata positive pairs for %s/%s", result.orderKey(), family.key())
						.isGreaterThanOrEqualTo(policy.activation().gates()
								.minimumPositivePairsForFamilyGate());
				assertThat(metrics.recall())
						.as("metadata case-family activation for %s/%s", result.orderKey(), family.key())
						.isLessThan(policy.activation().gates().minimumPerCaseFamilyRecall());
			}
			else {
				assertThat(metrics.falsePositives())
						.as("must-separate case-family safety for %s/%s", result.orderKey(), family.key())
						.isZero();
			}
		}
	}

	private static void printMetrics(
			OrderEvaluation result, Map<String, CaseFamily> familyPolicies) {
		System.out.printf(
				"dedup-v2 order=%s tp=%d fp=%d fn=%d tn=%d pair-p=%.3f pair-r=%.3f pair-f1=%.3f "
						+ "b3-p=%.3f b3-r=%.3f b3-f1=%.3f exact-clusters=%d/%d metadata-r=%.3f "
						+ "must-separate-fp=%d must-link-fn=%d%n",
				result.orderKey(), result.pairwise().truePositives(), result.pairwise().falsePositives(),
				result.pairwise().falseNegatives(), result.pairwise().trueNegatives(),
				result.pairwise().precision(), result.pairwise().recall(), result.pairwise().f1(),
				result.bCubed().precision(), result.bCubed().recall(), result.bCubed().f1(),
				result.exactCluster().matches(), result.exactCluster().total(),
				result.metadataOnlyRecall(), result.criticalFalseMerges(), result.criticalMissedLinks());
		for (CaseFamily family : familyPolicies.values()) {
			PairwiseMetrics metrics = result.caseMetrics().get(family.key());
			System.out.printf(
					"dedup-v2-case order=%s family=%s signal=%s positives=%d tp=%d fp=%d fn=%d recall=%.3f%n",
					result.orderKey(), family.key(), family.signalClass(),
					metrics.truePositives() + metrics.falseNegatives(), metrics.truePositives(),
					metrics.falsePositives(), metrics.falseNegatives(), metrics.recall());
		}
	}

	private <T> T inRollbackTransaction(Supplier<T> operation) {
		TransactionTemplate transaction = new TransactionTemplate(transactionManager);
		transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
		return Objects.requireNonNull(transaction.execute(status -> {
			T result = operation.get();
			status.setRollbackOnly();
			return result;
		}));
	}

	private PaperDeduplicationV2EvaluationFixture loadFixture() throws Exception {
		return PaperDeduplicationV2EvaluationFixture.load(objectMapper, DEVELOPMENT_FIXTURE_PATH);
	}

	private PaperDeduplicationV2EvaluationPolicy loadPolicy() throws Exception {
		return PaperDeduplicationV2EvaluationPolicy.load(objectMapper, POLICY_PATH);
	}

	private JsonNode resourceTree(String path) throws Exception {
		try (InputStream input = new ClassPathResource(path).getInputStream()) {
			return objectMapper.readTree(input);
		}
	}

	private static String sha256(String path) throws Exception {
		try (InputStream input = new ClassPathResource(path).getInputStream()) {
			return HexFormat.of().formatHex(
					MessageDigest.getInstance("SHA-256").digest(input.readAllBytes()));
		}
	}

	private static Map<String, CaseFamily> familyPolicies(
			PaperDeduplicationV2EvaluationPolicy policy) {
		return policy.caseFamilies().stream().collect(Collectors.toMap(
				CaseFamily::key,
				family -> family,
				(left, right) -> {
					throw new IllegalArgumentException("Duplicate case-family policy: " + left.key());
				},
				LinkedHashMap::new));
	}

	private static long goldPositivePairs(List<FixtureRecord> records) {
		long pairs = 0;
		for (int left = 0; left < records.size(); left++) {
			for (int right = left + 1; right < records.size(); right++) {
				if (records.get(left).goldCluster().equals(records.get(right).goldCluster())) {
					pairs++;
				}
			}
		}
		return pairs;
	}

	private static double ratioOrOne(int numerator, int denominator) {
		return denominator == 0 ? 1.0d : (double) numerator / denominator;
	}

	private record Observation(
			String key,
			String goldCluster,
			String caseFamily,
			SignalClass signalClass,
			UUID paperId) {
	}

	private record PairwiseMetrics(
			int truePositives,
			int falsePositives,
			int falseNegatives,
			int trueNegatives,
			double precision,
			double recall,
			double f1) {
	}

	private record BCubedMetrics(double precision, double recall, double f1) {
	}

	private record ExactClusterMetrics(int matches, int total, double rate) {
	}

	private record CriticalMetrics(int falseMerges, int missedLinks) {
	}

	private record OrderEvaluation(
			String orderKey,
			PairwiseMetrics pairwise,
			BCubedMetrics bCubed,
			ExactClusterMetrics exactCluster,
			double exactSignalRecall,
			double metadataOnlyRecall,
			int criticalFalseMerges,
			int criticalMissedLinks,
			Map<String, PairwiseMetrics> caseMetrics,
			List<List<String>> partition) {
	}
}
