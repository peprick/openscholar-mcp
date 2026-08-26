package com.openscholar.search.internal.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.within;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.openscholar.search.internal.persistence.ProviderQualityMetrics.DedupObservation;
import com.openscholar.search.internal.persistence.ProviderQualityMetrics.MetadataField;
import com.openscholar.search.internal.persistence.ProviderQualityMetrics.MetadataObservation;
import com.openscholar.search.internal.persistence.ProviderQualityMetrics.RankedContribution;
import com.openscholar.search.internal.persistence.ProviderQualityMetrics.RankingCutoffs;
import org.junit.jupiter.api.Test;

class ProviderQualityMetricsTests {

	private static final RankingCutoffs FROZEN_CUTOFFS = new RankingCutoffs(20, 10, 5, 20);

	@Test
	void measuresFrozenRelevanceMetricsAndMacroAverages() {
		Map<String, Integer> judgments = Map.of(
				"best", 3,
				"second", 2,
				"third", 1,
				"negative", 0);

		var displaced = ProviderQualityMetrics.measureRanking(
				List.of("negative", "best", "second", "third"), judgments, FROZEN_CUTOFFS);
		var perfect = ProviderQualityMetrics.measureRanking(
				List.of("best", "second", "third", "negative"), judgments, FROZEN_CUTOFFS);

		assertThat(displaced.recall()).isEqualTo(1.0d);
		assertThat(displaced.ndcg()).isCloseTo(0.6757d, within(0.0001d));
		assertThat(displaced.precision()).isEqualTo(0.6d);
		assertThat(displaced.reciprocalRank()).isEqualTo(0.5d);
		assertThat(perfect.recall()).isEqualTo(1.0d);
		assertThat(perfect.ndcg()).isEqualTo(1.0d);
		assertThat(perfect.precision()).isEqualTo(0.6d);
		assertThat(perfect.reciprocalRank()).isEqualTo(1.0d);

		var summary = ProviderQualityMetrics.summarizeRankings(List.of(displaced, perfect));
		assertThat(summary.macroRecall()).isEqualTo(1.0d);
		assertThat(summary.macroNdcg()).isCloseTo(0.83785d, within(0.0001d));
		assertThat(summary.macroPrecision()).isEqualTo(0.6d);
		assertThat(summary.meanReciprocalRank()).isEqualTo(0.75d);
	}

	@Test
	void computesRrfK60WithProductionPrimaryAndCandidateTieBreaks() {
		UUID shared = UUID.fromString("00000000-0000-0000-0000-000000000001");
		UUID openAlexOnly = UUID.fromString("00000000-0000-0000-0000-000000000002");
		UUID europePmcOnly = UUID.fromString("00000000-0000-0000-0000-000000000003");
		List<RankedContribution> contributions = List.of(
				contribution("OPENALEX", "W-SHARED-LATER", "shared", shared, 2),
				contribution("EUROPE_PMC", "MED:SHARED", "shared", shared, 1),
				contribution("OPENALEX", "W-ONLY", "openalex-only", openAlexOnly, 1),
				contribution("EUROPE_PMC", "MED:ONLY", "europe-pmc-only", europePmcOnly, 2),
				// The production accumulator retains only the best same-provider contribution.
				contribution("OPENALEX", "W-SHARED-EARLIER", "shared", shared, 3));

		var fused = ProviderQualityMetrics.reciprocalRankFusion(contributions, 60);

		assertThat(fused).extracting(result -> result.paperKey())
				.containsExactly("shared", "openalex-only", "europe-pmc-only");
		assertThat(fused.getFirst().score()).isEqualTo(1.0d / 61.0d + 1.0d / 62.0d);
		assertThat(fused.getFirst().primaryProviderRank()).isEqualTo(1);
		assertThat(fused.getFirst().primaryProvider()).isEqualTo("EUROPE_PMC");
		assertThat(fused.getFirst().primaryProviderRecordId()).isEqualTo("MED:SHARED");
		assertThat(fused.getFirst().canonicalPaperId()).isEqualTo(shared);
		assertThat(fused.getFirst().contributions())
				.extracting(RankedContribution::providerRecordId)
				.containsExactly("MED:SHARED", "W-SHARED-LATER");
	}

	@Test
	void measuresPairwiseDeduplicationConfusionAndRates() {
		List<DedupObservation> observations = List.of(
				new DedupObservation("a", "gold-1", "canonical-1"),
				new DedupObservation("b", "gold-1", "canonical-1"),
				new DedupObservation("c", "gold-2", "canonical-2"),
				new DedupObservation("d", "gold-2", "canonical-3"),
				new DedupObservation("e", "gold-3", "canonical-3"));

		var measurement = ProviderQualityMetrics.measureDeduplication(observations);

		assertThat(measurement.truePositives()).isEqualTo(1);
		assertThat(measurement.falsePositives()).isEqualTo(1);
		assertThat(measurement.falseNegatives()).isEqualTo(1);
		assertThat(measurement.trueNegatives()).isEqualTo(7);
		assertThat(measurement.precision()).isEqualTo(0.5d);
		assertThat(measurement.recall()).isEqualTo(0.5d);
		assertThat(measurement.f1()).isEqualTo(0.5d);
	}

	@Test
	void reportsPerFieldMetadataCoverage() {
		List<MetadataObservation> observations = List.of(
				new MetadataObservation("a", Set.of(
						MetadataField.TITLE, MetadataField.DOCUMENT_TYPE, MetadataField.SOURCE_URL,
						MetadataField.DOI, MetadataField.ABSTRACT)),
				new MetadataObservation("b", Set.of(
						MetadataField.TITLE, MetadataField.DOCUMENT_TYPE, MetadataField.SOURCE_URL,
						MetadataField.PMID, MetadataField.AUTHORS)));

		var coverage = ProviderQualityMetrics.measureFieldCoverage(observations);

		assertThat(coverage.recordCount()).isEqualTo(2);
		assertThat(coverage.fields().get(MetadataField.TITLE).rate()).isEqualTo(1.0d);
		assertThat(coverage.fields().get(MetadataField.DOI).presentCount()).isEqualTo(1);
		assertThat(coverage.fields().get(MetadataField.DOI).rate()).isEqualTo(0.5d);
		assertThat(coverage.fields().get(MetadataField.PMID).rate()).isEqualTo(0.5d);
		assertThat(coverage.fields().get(MetadataField.PMCID).rate()).isZero();
		assertThat(coverage.fields()).containsOnlyKeys(MetadataField.values());
		Set<MetadataField> scored = Set.of(
				MetadataField.TITLE, MetadataField.DOI, MetadataField.PMID);
		assertThat(ProviderQualityMetrics.meanFieldCoverage(coverage, scored))
				.isCloseTo(2.0d / 3.0d, within(1.0e-12d));

		var baseline = ProviderQualityMetrics.measureFieldCoverage(List.of(
				new MetadataObservation("baseline", Set.of(
						MetadataField.TITLE, MetadataField.DOI))));
		assertThat(ProviderQualityMetrics.completenessDelta(coverage, baseline, scored))
				.isZero();
	}

	@Test
	void rejectsInvalidMetricInputs() {
		assertThatIllegalArgumentException().isThrownBy(() -> ProviderQualityMetrics.measureRanking(
				List.of("duplicate", "duplicate"), Map.of("duplicate", 1), FROZEN_CUTOFFS));
		assertThatIllegalArgumentException().isThrownBy(() -> ProviderQualityMetrics.measureRanking(
				List.of("negative"), Map.of("negative", 0), FROZEN_CUTOFFS));
		assertThatIllegalArgumentException().isThrownBy(() -> ProviderQualityMetrics.reciprocalRankFusion(
				List.of(
						contribution("OPENALEX", "W-DUP", "one", UUID.randomUUID(), 1),
						contribution("OPENALEX", "W-DUP", "two", UUID.randomUUID(), 2)),
				60));
		assertThatIllegalArgumentException().isThrownBy(
				() -> ProviderQualityMetrics.measureDeduplication(List.of()));
		assertThatIllegalArgumentException().isThrownBy(
				() -> ProviderQualityMetrics.measureFieldCoverage(List.of()));
	}

	private static RankedContribution contribution(
			String provider,
			String providerRecordId,
			String paperKey,
			UUID canonicalPaperId,
			int rank) {
		return new RankedContribution(provider, providerRecordId, paperKey, canonicalPaperId, rank);
	}
}
