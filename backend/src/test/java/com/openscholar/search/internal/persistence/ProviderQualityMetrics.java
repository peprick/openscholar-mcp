package com.openscholar.search.internal.persistence;

import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * Pure evaluation helpers for provider-backed result quality. Relevance labels
 * are consulted only after a ranking has been produced.
 */
final class ProviderQualityMetrics {

	private ProviderQualityMetrics() {
	}

	static RankingMeasurement measureRanking(
			List<String> rankedPaperKeys,
			Map<String, Integer> judgments,
			RankingCutoffs cutoffs) {
		List<String> ranked = List.copyOf(Objects.requireNonNull(rankedPaperKeys, "rankedPaperKeys"));
		Map<String, Integer> labels = Map.copyOf(Objects.requireNonNull(judgments, "judgments"));
		Objects.requireNonNull(cutoffs, "cutoffs");
		if (ranked.stream().anyMatch(key -> key == null || key.isBlank())
				|| ranked.stream().distinct().count() != ranked.size()) {
			throw new IllegalArgumentException("ranked paper keys must be unique and non-blank");
		}
		validateJudgments(labels);

		Set<String> relevant = labels.entrySet().stream()
				.filter(entry -> entry.getValue() > 0)
				.map(Map.Entry::getKey)
				.collect(Collectors.toUnmodifiableSet());
		double recall = (double) ranked.stream()
				.limit(cutoffs.recallAt())
				.filter(relevant::contains)
				.count() / relevant.size();
		double ndcg = ndcgAt(ranked, labels, cutoffs.ndcgAt());
		double precision = (double) ranked.stream()
				.limit(cutoffs.precisionAt())
				.filter(relevant::contains)
				.count() / cutoffs.precisionAt();
		double reciprocalRank = IntStream.range(0, Math.min(cutoffs.reciprocalRankAt(), ranked.size()))
				.filter(index -> relevant.contains(ranked.get(index)))
				.mapToDouble(index -> 1.0d / (index + 1.0d))
				.findFirst()
				.orElse(0.0d);
		return new RankingMeasurement(recall, ndcg, precision, reciprocalRank);
	}

	static RankingSummary summarizeRankings(List<RankingMeasurement> measurements) {
		List<RankingMeasurement> values = List.copyOf(Objects.requireNonNull(measurements, "measurements"));
		if (values.isEmpty()) {
			throw new IllegalArgumentException("ranking measurements must not be empty");
		}
		return new RankingSummary(
				values.stream().mapToDouble(RankingMeasurement::recall).average().orElseThrow(),
				values.stream().mapToDouble(RankingMeasurement::ndcg).average().orElseThrow(),
				values.stream().mapToDouble(RankingMeasurement::precision).average().orElseThrow(),
				values.stream().mapToDouble(RankingMeasurement::reciprocalRank).average().orElseThrow());
	}

	static List<FusedScore> reciprocalRankFusion(List<RankedContribution> contributions, int rrfK) {
		List<RankedContribution> values = List.copyOf(
				Objects.requireNonNull(contributions, "contributions"));
		if (rrfK < 1) {
			throw new IllegalArgumentException("rrfK must be positive");
		}
		if (values.isEmpty()) {
			throw new IllegalArgumentException("contributions must not be empty");
		}
		Set<String> providerRecordKeys = new HashSet<>();
		Map<UUID, MutableFusedScore> scores = new LinkedHashMap<>();
		for (RankedContribution contribution : values) {
			Objects.requireNonNull(contribution, "contributions must not contain null");
			String contributionKey = contribution.provider() + '\n' + contribution.providerRecordId();
			if (!providerRecordKeys.add(contributionKey)) {
				throw new IllegalArgumentException(
						"provider record contributions must be unique: " + contributionKey);
			}
			scores.computeIfAbsent(contribution.canonicalPaperId(), MutableFusedScore::new)
					.add(contribution);
		}
		return scores.values().stream()
				.map(score -> score.finish(rrfK))
				.sorted(Comparator.comparingDouble(FusedScore::score).reversed()
						.thenComparingInt(FusedScore::primaryProviderRank)
						.thenComparing(FusedScore::primaryProvider)
						.thenComparing(FusedScore::primaryProviderRecordId)
						.thenComparing(FusedScore::canonicalPaperId))
				.toList();
	}

	static PairwiseDeduplication measureDeduplication(List<DedupObservation> observations) {
		List<DedupObservation> values = List.copyOf(
				Objects.requireNonNull(observations, "observations"));
		if (values.size() < 2) {
			throw new IllegalArgumentException("at least two deduplication observations are required");
		}
		if (values.stream().map(DedupObservation::recordKey).distinct().count() != values.size()) {
			throw new IllegalArgumentException("deduplication record keys must be unique");
		}
		long truePositives = 0;
		long falsePositives = 0;
		long falseNegatives = 0;
		long trueNegatives = 0;
		for (int left = 0; left < values.size(); left++) {
			for (int right = left + 1; right < values.size(); right++) {
				boolean goldMatch = values.get(left).goldPaperKey()
						.equals(values.get(right).goldPaperKey());
				boolean predictedMatch = values.get(left).canonicalPaperKey()
						.equals(values.get(right).canonicalPaperKey());
				if (goldMatch && predictedMatch) {
					truePositives++;
				}
				else if (!goldMatch && predictedMatch) {
					falsePositives++;
				}
				else if (goldMatch) {
					falseNegatives++;
				}
				else {
					trueNegatives++;
				}
			}
		}
		double precision = ratioOrOne(truePositives, truePositives + falsePositives);
		double recall = ratioOrOne(truePositives, truePositives + falseNegatives);
		double f1 = precision + recall == 0.0d
				? 0.0d
				: 2.0d * precision * recall / (precision + recall);
		return new PairwiseDeduplication(
				truePositives, falsePositives, falseNegatives, trueNegatives, precision, recall, f1);
	}

	static FieldCoverage measureFieldCoverage(List<MetadataObservation> observations) {
		List<MetadataObservation> values = List.copyOf(
				Objects.requireNonNull(observations, "observations"));
		if (values.isEmpty()) {
			throw new IllegalArgumentException("metadata observations must not be empty");
		}
		if (values.stream().map(MetadataObservation::recordKey).distinct().count() != values.size()) {
			throw new IllegalArgumentException("metadata observation keys must be unique");
		}
		Map<MetadataField, FieldMeasurement> fields = new EnumMap<>(MetadataField.class);
		for (MetadataField field : MetadataField.values()) {
			long present = values.stream().filter(observation -> observation.presentFields().contains(field)).count();
			fields.put(field, new FieldMeasurement(present, values.size(), (double) present / values.size()));
		}
		return new FieldCoverage(values.size(), fields);
	}

	static double meanFieldCoverage(FieldCoverage coverage, Set<MetadataField> scoredFields) {
		Objects.requireNonNull(coverage, "coverage");
		Set<MetadataField> fields = Set.copyOf(Objects.requireNonNull(scoredFields, "scoredFields"));
		if (fields.isEmpty()) {
			throw new IllegalArgumentException("scored metadata fields must not be empty");
		}
		return fields.stream()
				.mapToDouble(field -> {
					FieldMeasurement measurement = coverage.fields().get(field);
					if (measurement == null) {
						throw new IllegalArgumentException("coverage is missing field " + field);
					}
					return measurement.rate();
				})
				.average()
				.orElseThrow();
	}

	static double completenessDelta(
			FieldCoverage fused,
			FieldCoverage baseline,
			Set<MetadataField> scoredFields) {
		return meanFieldCoverage(fused, scoredFields) - meanFieldCoverage(baseline, scoredFields);
	}

	private static void validateJudgments(Map<String, Integer> judgments) {
		if (judgments.isEmpty()
				|| judgments.entrySet().stream().anyMatch(entry -> entry.getKey() == null
						|| entry.getKey().isBlank()
						|| entry.getValue() == null
						|| entry.getValue() < 0
						|| entry.getValue() > 3)) {
			throw new IllegalArgumentException("judgments must use non-blank keys and grades from 0 to 3");
		}
		if (judgments.values().stream().noneMatch(grade -> grade > 0)) {
			throw new IllegalArgumentException("judgments must contain a relevant paper");
		}
	}

	private static double ndcgAt(List<String> ranked, Map<String, Integer> judgments, int cutoff) {
		double actual = IntStream.range(0, Math.min(cutoff, ranked.size()))
				.mapToDouble(index -> discountedGain(judgments.getOrDefault(ranked.get(index), 0), index))
				.sum();
		List<Integer> idealGrades = judgments.values().stream()
				.filter(grade -> grade > 0)
				.sorted(Comparator.reverseOrder())
				.limit(cutoff)
				.toList();
		double ideal = IntStream.range(0, idealGrades.size())
				.mapToDouble(index -> discountedGain(idealGrades.get(index), index))
				.sum();
		return actual / ideal;
	}

	private static double discountedGain(int grade, int zeroBasedRank) {
		if (grade <= 0) {
			return 0.0d;
		}
		return (Math.pow(2.0d, grade) - 1.0d)
				/ (Math.log(zeroBasedRank + 2.0d) / Math.log(2.0d));
	}

	private static double ratioOrOne(long numerator, long denominator) {
		return denominator == 0 ? 1.0d : (double) numerator / denominator;
	}

	record RankingCutoffs(int recallAt, int ndcgAt, int precisionAt, int reciprocalRankAt) {

		RankingCutoffs {
			if (recallAt < 1 || ndcgAt < 1 || precisionAt < 1 || reciprocalRankAt < 1) {
				throw new IllegalArgumentException("ranking cutoffs must be positive");
			}
		}
	}

	record RankingMeasurement(double recall, double ndcg, double precision, double reciprocalRank) {
	}

	record RankingSummary(
			double macroRecall,
			double macroNdcg,
			double macroPrecision,
			double meanReciprocalRank) {
	}

	record RankedContribution(
			String provider,
			String providerRecordId,
			String paperKey,
			UUID canonicalPaperId,
			int providerRank) {

		RankedContribution {
			provider = requireText(provider, "provider");
			providerRecordId = requireText(providerRecordId, "providerRecordId");
			paperKey = requireText(paperKey, "paperKey");
			canonicalPaperId = Objects.requireNonNull(canonicalPaperId, "canonicalPaperId");
			if (providerRank < 1) {
				throw new IllegalArgumentException("providerRank must be positive");
			}
		}
	}

	record FusedScore(
			String paperKey,
			UUID canonicalPaperId,
			double score,
			int primaryProviderRank,
			String primaryProvider,
			String primaryProviderRecordId,
			List<RankedContribution> contributions) {

		FusedScore {
			contributions = List.copyOf(contributions);
		}
	}

	record DedupObservation(String recordKey, String goldPaperKey, String canonicalPaperKey) {

		DedupObservation {
			recordKey = requireText(recordKey, "recordKey");
			goldPaperKey = requireText(goldPaperKey, "goldPaperKey");
			canonicalPaperKey = requireText(canonicalPaperKey, "canonicalPaperKey");
		}
	}

	record PairwiseDeduplication(
			long truePositives,
			long falsePositives,
			long falseNegatives,
			long trueNegatives,
			double precision,
			double recall,
			double f1) {
	}

	enum MetadataField {
		TITLE,
		DOCUMENT_TYPE,
		SOURCE_URL,
		DOI,
		PMID,
		PMCID,
		ABSTRACT,
		AUTHORS,
		ORCID,
		PUBLICATION_YEAR,
		VENUE,
		LANGUAGE,
		ISSN,
		CITATION_COUNT
	}

	record MetadataObservation(String recordKey, Set<MetadataField> presentFields) {

		MetadataObservation {
			recordKey = requireText(recordKey, "recordKey");
			presentFields = Set.copyOf(Objects.requireNonNull(presentFields, "presentFields"));
		}
	}

	record FieldMeasurement(long presentCount, long recordCount, double rate) {
	}

	record FieldCoverage(int recordCount, Map<MetadataField, FieldMeasurement> fields) {

		FieldCoverage {
			fields = Map.copyOf(fields);
		}
	}

	private static String requireText(String value, String field) {
		if (value == null || value.isBlank()) {
			throw new IllegalArgumentException(field + " must not be blank");
		}
		return value.strip();
	}

	private static final class MutableFusedScore {

		private static final Comparator<RankedContribution> PRIMARY_ORDER = Comparator
				.comparingInt(RankedContribution::providerRank)
				.thenComparing(RankedContribution::provider)
				.thenComparing(RankedContribution::providerRecordId);

		private final UUID canonicalPaperId;
		private final Map<String, RankedContribution> contributions = new LinkedHashMap<>();

		private MutableFusedScore(UUID canonicalPaperId) {
			this.canonicalPaperId = canonicalPaperId;
		}

		private void add(RankedContribution contribution) {
			contributions.merge(
					contribution.provider(), contribution,
					(left, right) -> PRIMARY_ORDER.compare(left, right) <= 0 ? left : right);
		}

		private FusedScore finish(int rrfK) {
			List<RankedContribution> ordered = contributions.values().stream()
					.sorted(PRIMARY_ORDER)
					.toList();
			RankedContribution primary = ordered.getFirst();
			double score = ordered.stream()
					.mapToDouble(value -> 1.0d / (rrfK + value.providerRank()))
					.sum();
			return new FusedScore(
					primary.paperKey(), canonicalPaperId, score, primary.providerRank(), primary.provider(),
					primary.providerRecordId(), ordered);
		}
	}
}
