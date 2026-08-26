package com.openscholar.search.internal.persistence;

import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

import com.openscholar.provider.ProviderId;
import com.openscholar.search.internal.persistence.ProviderQualityEvaluationFixture.ExactSignal;
import com.openscholar.search.internal.persistence.ProviderQualityMetrics.MetadataField;
import com.openscholar.search.internal.persistence.ProviderQualityMetrics.RankingCutoffs;
import org.springframework.core.io.ClassPathResource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

record ProviderQualityEvaluationPolicy(
		int schemaVersion,
		String policyId,
		String developmentFixtureId,
		String developmentFixtureSha256,
		String status,
		List<ProviderId> requiredProviders,
		List<ExactSignal> requiredExactOverlapSignals,
		RankingPolicy ranking,
		Cutoffs cutoffs,
		Constraints constraints,
		List<String> metrics,
		List<MetadataField> metadataCoverageFields,
		List<MetadataField> metadataFusionGainFields,
		Gates gates) {

	private static final Pattern SHA_256 = Pattern.compile("^[0-9a-f]{64}$");
	private static final Set<String> ROOT_REQUIRED = Set.of(
			"schemaVersion", "policyId", "developmentFixtureId", "developmentFixtureSha256",
			"status", "requiredProviders", "requiredExactOverlapSignals", "ranking", "cutoffs",
			"constraints", "metrics", "metadataCoverageFields", "metadataFusionGainFields", "gates");
	private static final Set<String> RANKING_REQUIRED = Set.of("method", "rrfK", "tieBreak");
	private static final Set<String> CUTOFFS_REQUIRED = Set.of(
			"recallAt", "ndcgAt", "precisionAt", "reciprocalRankAt");
	private static final Set<String> CONSTRAINTS_REQUIRED = Set.of(
			"queryGroupCount", "maxRecordsPerProviderPerQuery", "metadataOnly");
	private static final Set<String> GATES_REQUIRED = Set.of(
			"minimumEuropePmcUniqueRelevantQueryCount", "minimumMacroRecallGain",
			"minimumMacroNdcgDelta", "minimumMacroPrecisionDelta", "minimumMrrDelta",
			"minimumFusedCompletenessDelta", "minimumPerFieldFusionDelta",
			"maximumPerQueryNdcgRegression", "maximumRegressingQueryCount",
			"minimumExactDedupPrecision", "minimumExactDedupRecall",
			"maximumCriticalFalseMerges", "requireVariedFixtureMetadata",
			"forbidDocumentFields");
	private static final List<String> EXPECTED_METRICS = List.of(
			"RECALL_AT_20",
			"NDCG_AT_10",
			"PRECISION_AT_5",
			"MEAN_RECIPROCAL_RANK_AT_20",
			"PAIRWISE_DEDUPLICATION_PRECISION_RECALL_F1",
			"PER_FIELD_METADATA_COVERAGE");
	private static final List<String> EXPECTED_TIE_BREAK = List.of(
			"FUSED_SCORE_DESC",
			"PRIMARY_PROVIDER_RANK_ASC",
			"PRIMARY_PROVIDER_ASC",
			"PRIMARY_PROVIDER_RECORD_ID_ASC",
			"CANONICAL_PAPER_UUID_ASC");

	ProviderQualityEvaluationPolicy {
		requiredProviders = List.copyOf(Objects.requireNonNull(requiredProviders, "requiredProviders"));
		requiredExactOverlapSignals = List.copyOf(
				Objects.requireNonNull(requiredExactOverlapSignals, "requiredExactOverlapSignals"));
		metrics = List.copyOf(Objects.requireNonNull(metrics, "metrics"));
		metadataCoverageFields = List.copyOf(
				Objects.requireNonNull(metadataCoverageFields, "metadataCoverageFields"));
		metadataFusionGainFields = List.copyOf(
				Objects.requireNonNull(metadataFusionGainFields, "metadataFusionGainFields"));
	}

	static ProviderQualityEvaluationPolicy load(ObjectMapper objectMapper, String resourcePath)
			throws Exception {
		ClassPathResource resource = new ClassPathResource(resourcePath);
		try (InputStream input = resource.getInputStream()) {
			return parse(objectMapper, objectMapper.readTree(input));
		}
	}

	static ProviderQualityEvaluationPolicy loadBound(
			ObjectMapper objectMapper,
			String policyResourcePath,
			ProviderQualityEvaluationFixture fixture,
			String fixtureResourcePath) throws Exception {
		ProviderQualityEvaluationPolicy policy = load(objectMapper, policyResourcePath);
		policy.validateBinding(fixture, sha256(fixtureResourcePath));
		return policy;
	}

	static ProviderQualityEvaluationPolicy parse(ObjectMapper objectMapper, JsonNode root)
			throws Exception {
		validateSchema(root);
		ProviderQualityEvaluationPolicy policy = objectMapper.treeToValue(
				root, ProviderQualityEvaluationPolicy.class);
		policy.validateValues();
		return policy;
	}

	RankingCutoffs rankingCutoffs() {
		return new RankingCutoffs(
				cutoffs.recallAt(), cutoffs.ndcgAt(), cutoffs.precisionAt(), cutoffs.reciprocalRankAt());
	}

	void validateBinding(ProviderQualityEvaluationFixture fixture, String fixtureSha256) {
		Objects.requireNonNull(fixture, "fixture");
		if (!developmentFixtureId.equals(fixture.fixtureId())) {
			throw new IllegalArgumentException("policy developmentFixtureId does not match the fixture");
		}
		if (!policyId.equals(fixture.policyId())) {
			throw new IllegalArgumentException("fixture policyId does not match the policy");
		}
		if (!developmentFixtureSha256.equals(fixtureSha256)) {
			throw new IllegalArgumentException("fixture SHA-256 does not match the frozen policy");
		}
		if (fixture.queries().size() != constraints.queryGroupCount()) {
			throw new IllegalArgumentException("fixture query count does not match the policy");
		}
		for (ProviderQualityEvaluationFixture.EvaluationQuery query : fixture.queries()) {
			Set<ProviderId> providers = query.providerResults().stream()
					.map(ProviderQualityEvaluationFixture.ProviderResult::provider)
					.collect(java.util.stream.Collectors.toSet());
			if (!providers.equals(Set.copyOf(requiredProviders))) {
				throw new IllegalArgumentException("fixture provider set does not match the policy: " + query.key());
			}
			boolean exceedsLimit = query.providerResults().stream()
					.anyMatch(result -> result.records().size() > constraints.maxRecordsPerProviderPerQuery());
			if (exceedsLimit) {
				throw new IllegalArgumentException("fixture exceeds the records-per-provider policy: " + query.key());
			}
		}
		Set<ExactSignal> observed = fixture.exactOverlapSignals().stream()
				.map(type -> ExactSignal.valueOf(type.name()))
				.collect(java.util.stream.Collectors.toSet());
		if (!observed.containsAll(requiredExactOverlapSignals)) {
			throw new IllegalArgumentException("fixture is missing a required exact-overlap signal");
		}
	}

	static String sha256(String resourcePath) throws Exception {
		ClassPathResource resource = new ClassPathResource(resourcePath);
		try (InputStream input = resource.getInputStream()) {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			byte[] buffer = new byte[8192];
			int read;
			while ((read = input.read(buffer)) >= 0) {
				if (read > 0) {
					digest.update(buffer, 0, read);
				}
			}
			return HexFormat.of().formatHex(digest.digest());
		}
		catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException("SHA-256 is unavailable", exception);
		}
	}

	private static void validateSchema(JsonNode root) {
		requireObjectKeys(root, "$", ROOT_REQUIRED);
		requireInteger(root.required("schemaVersion"), "$.schemaVersion");
		requireTextFields(root, "$", List.of(
				"policyId", "developmentFixtureId", "developmentFixtureSha256", "status"));
		requireTextArray(root.required("requiredProviders"), "$.requiredProviders");
		requireTextArray(root.required("requiredExactOverlapSignals"), "$.requiredExactOverlapSignals");
		requireTextArray(root.required("metrics"), "$.metrics");
		requireTextArray(root.required("metadataCoverageFields"), "$.metadataCoverageFields");
		requireTextArray(root.required("metadataFusionGainFields"), "$.metadataFusionGainFields");

		JsonNode ranking = root.required("ranking");
		requireObjectKeys(ranking, "$.ranking", RANKING_REQUIRED);
		requireText(ranking.required("method"), "$.ranking.method");
		requireInteger(ranking.required("rrfK"), "$.ranking.rrfK");
		requireTextArray(ranking.required("tieBreak"), "$.ranking.tieBreak");

		JsonNode cutoffs = root.required("cutoffs");
		requireObjectKeys(cutoffs, "$.cutoffs", CUTOFFS_REQUIRED);
		CUTOFFS_REQUIRED.forEach(field -> requireInteger(
				cutoffs.required(field), "$.cutoffs." + field));

		JsonNode constraints = root.required("constraints");
		requireObjectKeys(constraints, "$.constraints", CONSTRAINTS_REQUIRED);
		requireInteger(constraints.required("queryGroupCount"), "$.constraints.queryGroupCount");
		requireInteger(constraints.required("maxRecordsPerProviderPerQuery"),
				"$.constraints.maxRecordsPerProviderPerQuery");
		requireBoolean(constraints.required("metadataOnly"), "$.constraints.metadataOnly");

		JsonNode gates = root.required("gates");
		requireObjectKeys(gates, "$.gates", GATES_REQUIRED);
		for (String integerField : List.of(
				"minimumEuropePmcUniqueRelevantQueryCount", "maximumRegressingQueryCount",
				"maximumCriticalFalseMerges")) {
			requireInteger(gates.required(integerField), "$.gates." + integerField);
		}
		for (String booleanField : List.of(
				"requireVariedFixtureMetadata", "forbidDocumentFields")) {
			requireBoolean(gates.required(booleanField), "$.gates." + booleanField);
		}
		for (String decimalField : List.of(
				"minimumMacroRecallGain", "minimumMacroNdcgDelta", "minimumMacroPrecisionDelta",
				"minimumMrrDelta", "minimumFusedCompletenessDelta", "minimumPerFieldFusionDelta",
				"maximumPerQueryNdcgRegression",
				"minimumExactDedupPrecision", "minimumExactDedupRecall")) {
			requireNumber(gates.required(decimalField), "$.gates." + decimalField);
		}
	}

	private void validateValues() {
		if (schemaVersion != 1) {
			throw new IllegalArgumentException("schemaVersion must be 1");
		}
		requireNonBlank(policyId, "policyId");
		requireNonBlank(developmentFixtureId, "developmentFixtureId");
		if (!SHA_256.matcher(developmentFixtureSha256).matches()) {
			throw new IllegalArgumentException("developmentFixtureSha256 must be lowercase SHA-256");
		}
		if (!"EVALUATION_ONLY".equals(status)) {
			throw new IllegalArgumentException("status must be EVALUATION_ONLY");
		}
		requireUniqueNonEmpty(requiredProviders, "requiredProviders");
		if (!Set.copyOf(requiredProviders).equals(Set.of(ProviderId.OPENALEX, ProviderId.EUROPE_PMC))) {
			throw new IllegalArgumentException("requiredProviders must be OPENALEX and EUROPE_PMC");
		}
		requireUniqueNonEmpty(requiredExactOverlapSignals, "requiredExactOverlapSignals");
		if (!Set.copyOf(requiredExactOverlapSignals)
				.equals(Set.of(ExactSignal.DOI, ExactSignal.PMID, ExactSignal.PMCID))) {
			throw new IllegalArgumentException("required exact signals must be DOI, PMID, and PMCID");
		}
		Objects.requireNonNull(ranking, "ranking").validate();
		Objects.requireNonNull(cutoffs, "cutoffs").validate();
		Objects.requireNonNull(constraints, "constraints").validate();
		if (!metrics.equals(EXPECTED_METRICS)) {
			throw new IllegalArgumentException("metrics do not match the frozen provider-quality set");
		}
		requireUniqueNonEmpty(metadataCoverageFields, "metadataCoverageFields");
		if (!Set.copyOf(metadataCoverageFields).equals(Set.of(MetadataField.values()))) {
			throw new IllegalArgumentException("metadataCoverageFields must contain every frozen field");
		}
		requireUniqueNonEmpty(metadataFusionGainFields, "metadataFusionGainFields");
		if (!Set.copyOf(metadataFusionGainFields).equals(Set.of(
				MetadataField.ABSTRACT,
				MetadataField.ORCID,
				MetadataField.PUBLICATION_YEAR,
				MetadataField.VENUE,
				MetadataField.LANGUAGE,
				MetadataField.ISSN,
				MetadataField.CITATION_COUNT))) {
			throw new IllegalArgumentException(
					"metadataFusionGainFields must contain every frozen optional enrichment field");
		}
		Objects.requireNonNull(gates, "gates").validate(constraints.queryGroupCount());
	}

	private static void requireObjectKeys(JsonNode node, String path, Set<String> required) {
		if (!node.isObject()) {
			throw new IllegalArgumentException(path + " must be an object");
		}
		Set<String> actual = new LinkedHashSet<>(node.propertyNames());
		Set<String> unknown = new LinkedHashSet<>(actual);
		unknown.removeAll(required);
		if (!unknown.isEmpty()) {
			throw new IllegalArgumentException("Unknown keys at " + path + ": " + unknown);
		}
		Set<String> missing = new LinkedHashSet<>(required);
		missing.removeAll(actual);
		if (!missing.isEmpty()) {
			throw new IllegalArgumentException("Missing keys at " + path + ": " + missing);
		}
	}

	private static void requireTextArray(JsonNode node, String path) {
		if (!node.isArray()) {
			throw new IllegalArgumentException(path + " must be an array");
		}
		for (int index = 0; index < node.size(); index++) {
			requireText(node.get(index), path + "[" + index + "]");
		}
	}

	private static void requireTextFields(JsonNode node, String path, List<String> fields) {
		for (String field : fields) {
			requireText(node.required(field), path + "." + field);
		}
	}

	private static void requireText(JsonNode node, String path) {
		if (!node.isString() || node.asString().isBlank()) {
			throw new IllegalArgumentException(path + " must be a non-blank string");
		}
	}

	private static void requireInteger(JsonNode node, String path) {
		if (!node.isInt()) {
			throw new IllegalArgumentException(path + " must be an integer");
		}
	}

	private static void requireNumber(JsonNode node, String path) {
		if (!node.isNumber()) {
			throw new IllegalArgumentException(path + " must be numeric");
		}
	}

	private static void requireBoolean(JsonNode node, String path) {
		if (!node.isBoolean()) {
			throw new IllegalArgumentException(path + " must be a boolean");
		}
	}

	private static void requireUniqueNonEmpty(List<?> values, String field) {
		if (values.isEmpty() || new HashSet<>(values).size() != values.size()
				|| values.stream().anyMatch(Objects::isNull)) {
			throw new IllegalArgumentException(field + " must contain unique non-null values");
		}
	}

	private static String requireNonBlank(String value, String field) {
		if (value == null || value.isBlank()) {
			throw new IllegalArgumentException(field + " must not be blank");
		}
		return value.strip();
	}

	record RankingPolicy(String method, int rrfK, List<String> tieBreak) {

		RankingPolicy {
			tieBreak = List.copyOf(Objects.requireNonNull(tieBreak, "tieBreak"));
		}

		private void validate() {
			if (!"RECIPROCAL_RANK_FUSION".equals(method)
					|| rrfK != 60
					|| !tieBreak.equals(EXPECTED_TIE_BREAK)) {
				throw new IllegalArgumentException("ranking policy must freeze RRF k=60 and its tie-break");
			}
		}
	}

	record Cutoffs(int recallAt, int ndcgAt, int precisionAt, int reciprocalRankAt) {

		private void validate() {
			if (recallAt != 20 || ndcgAt != 10 || precisionAt != 5 || reciprocalRankAt != 20) {
				throw new IllegalArgumentException("ranking cutoffs must be Recall@20/nDCG@10/P@5/MRR@20");
			}
		}
	}

	record Constraints(int queryGroupCount, int maxRecordsPerProviderPerQuery, boolean metadataOnly) {

		private void validate() {
			if (queryGroupCount != 8 || maxRecordsPerProviderPerQuery != 10 || !metadataOnly) {
				throw new IllegalArgumentException("constraints must freeze eight bounded metadata-only queries");
			}
		}
	}

	record Gates(
			int minimumEuropePmcUniqueRelevantQueryCount,
			double minimumMacroRecallGain,
			double minimumMacroNdcgDelta,
			double minimumMacroPrecisionDelta,
			double minimumMrrDelta,
			double minimumFusedCompletenessDelta,
			double minimumPerFieldFusionDelta,
			double maximumPerQueryNdcgRegression,
			int maximumRegressingQueryCount,
			double minimumExactDedupPrecision,
			double minimumExactDedupRecall,
			int maximumCriticalFalseMerges,
			boolean requireVariedFixtureMetadata,
			boolean forbidDocumentFields) {

		private void validate(int queryCount) {
			if (minimumEuropePmcUniqueRelevantQueryCount < 1
					|| minimumEuropePmcUniqueRelevantQueryCount > queryCount
					|| minimumMacroRecallGain < 0.0d
					|| minimumMacroNdcgDelta < 0.0d
					|| minimumMacroPrecisionDelta < 0.0d
					|| minimumMrrDelta < 0.0d
					|| minimumFusedCompletenessDelta < 0.0d
					|| minimumFusedCompletenessDelta > 1.0d
					|| minimumPerFieldFusionDelta <= 0.0d
					|| minimumPerFieldFusionDelta > 1.0d
					|| maximumPerQueryNdcgRegression < 0.0d
					|| maximumPerQueryNdcgRegression > 1.0d
					|| maximumRegressingQueryCount < 0
					|| maximumRegressingQueryCount > queryCount
					|| minimumExactDedupPrecision != 1.0d
					|| minimumExactDedupRecall != 1.0d
					|| maximumCriticalFalseMerges != 0
					|| !requireVariedFixtureMetadata
					|| !forbidDocumentFields) {
				throw new IllegalArgumentException("provider-quality gates are outside the frozen safe bounds");
			}
		}
	}
}
