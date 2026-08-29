package com.openscholar.paper.internal.persistence;

import static com.openscholar.paper.internal.persistence.MultilingualLexicalEvaluationFixture.readBounded;
import static com.openscholar.paper.internal.persistence.MultilingualLexicalEvaluationFixture.requireArray;
import static com.openscholar.paper.internal.persistence.MultilingualLexicalEvaluationFixture.requireBoolean;
import static com.openscholar.paper.internal.persistence.MultilingualLexicalEvaluationFixture.requireEnum;
import static com.openscholar.paper.internal.persistence.MultilingualLexicalEvaluationFixture.requireExactObject;
import static com.openscholar.paper.internal.persistence.MultilingualLexicalEvaluationFixture.requireInteger;
import static com.openscholar.paper.internal.persistence.MultilingualLexicalEvaluationFixture.requireLanguage;
import static com.openscholar.paper.internal.persistence.MultilingualLexicalEvaluationFixture.requireNumber;
import static com.openscholar.paper.internal.persistence.MultilingualLexicalEvaluationFixture.requireObject;
import static com.openscholar.paper.internal.persistence.MultilingualLexicalEvaluationFixture.requireText;
import static com.openscholar.paper.internal.persistence.MultilingualLexicalEvaluationFixture.requireTextArray;
import static com.openscholar.paper.internal.persistence.MultilingualLexicalEvaluationFixture.requireTextValue;
import static com.openscholar.paper.internal.persistence.MultilingualLexicalEvaluationFixture.sha256;
import static com.openscholar.paper.internal.persistence.MultilingualLexicalEvaluationFixture.unique;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import com.openscholar.TestcontainersConfiguration;
import com.openscholar.paper.internal.persistence.MultilingualLexicalEvaluationFixture.BoundFixture;
import org.springframework.core.io.ClassPathResource;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

record MultilingualLexicalEvaluationPolicy(
		int schemaVersion,
		String policyId,
		String developmentFixtureId,
		String developmentFixtureSha256,
		Status status,
		Baseline baseline,
		Runtime runtime,
		List<Profile> profiles,
		List<String> supportedLanguages,
		List<String> fallbackLanguages,
		List<String> metrics,
		Gates gates) {

	static final String RESOURCE_PATH =
			"search/relevance/multilingual-lexical-policy-v1.json";
	static final String POLICY_ID = "multilingual-lexical-policy-v1";
	static final String POLICY_SHA256 =
			"f0392ce677e11c421e5f83c881c75d1bb9ccb6b4e30797cd2d9e8c66931000d4";
	static final String POSTGRES_IMAGE = TestcontainersConfiguration.POSTGRES_IMAGE;
	private static final int MAXIMUM_INPUT_BYTES = 64 * 1024;
	private static final double EPSILON = 0.000_000_1d;
	private static final Set<String> ROOT_FIELDS = Set.of(
			"schemaVersion", "policyId", "developmentFixtureId",
			"developmentFixtureSha256", "status", "baseline", "runtime", "profiles",
			"supportedLanguages", "fallbackLanguages", "metrics", "gates");
	private static final Set<String> BASELINE_FIELDS = Set.of(
			"profile", "configuration", "queryFunction", "rankFunction",
			"rankNormalization", "weightedFields");
	private static final Set<String> RUNTIME_FIELDS = Set.of("postgresMajor", "containerImage");
	private static final Set<String> PROFILE_FIELDS = Set.of(
			"key", "defaultConfiguration", "languageConfigurations");
	private static final Set<String> GATE_FIELDS = Set.of(
			"minimumLanguageAwareSupportedMacroRecallAt3",
			"minimumLanguageAwareSupportedMacroNdcgAt3",
			"minimumStrictNonEnglishImprovementCount",
			"maximumSupportedQueryRegressionCount",
			"requireStableRepeatedRanking",
			"requireFallbackReportedSeparately",
			"requireProductionConfigurationUnchanged");
	private static final List<String> EXPECTED_METRICS = List.of(
			"RECALL_AT_3", "NDCG_AT_3", "PRECISION_AT_1",
			"MEAN_RECIPROCAL_RANK_AT_3", "MATCHED_RELEVANT_COUNT");
	private static final Map<String, TextSearchConfiguration> EXPECTED_LANGUAGE_CONFIGURATIONS =
			Map.of(
					"en", TextSearchConfiguration.ENGLISH,
					"de", TextSearchConfiguration.GERMAN,
					"fr", TextSearchConfiguration.FRENCH,
					"es", TextSearchConfiguration.SPANISH);

	MultilingualLexicalEvaluationPolicy {
		policyId = requireTextValue(policyId, "policyId", 3, 100);
		developmentFixtureId = requireTextValue(
				developmentFixtureId, "developmentFixtureId", 3, 100);
		developmentFixtureSha256 = requireDigest(
				developmentFixtureSha256, "developmentFixtureSha256");
		status = Objects.requireNonNull(status, "status");
		baseline = Objects.requireNonNull(baseline, "baseline");
		runtime = Objects.requireNonNull(runtime, "runtime");
		profiles = List.copyOf(Objects.requireNonNull(profiles, "profiles"));
		supportedLanguages = List.copyOf(
				Objects.requireNonNull(supportedLanguages, "supportedLanguages"));
		fallbackLanguages = List.copyOf(
				Objects.requireNonNull(fallbackLanguages, "fallbackLanguages"));
		metrics = List.copyOf(Objects.requireNonNull(metrics, "metrics"));
		gates = Objects.requireNonNull(gates, "gates");
	}

	static BoundPolicy loadFrozen(ObjectMapper objectMapper) throws IOException {
		BoundPolicy bound = loadBound(objectMapper, RESOURCE_PATH);
		bound.validateReference(POLICY_ID, POLICY_SHA256);
		return bound;
	}

	static BoundPolicy loadBound(ObjectMapper objectMapper, String resourcePath) throws IOException {
		Objects.requireNonNull(objectMapper, "objectMapper");
		String path = requireTextValue(resourcePath, "resourcePath", 1, 240);
		try (InputStream input = new ClassPathResource(path).getInputStream()) {
			return parseBound(objectMapper, readBounded(input, MAXIMUM_INPUT_BYTES, "policy"));
		}
	}

	static BoundPolicy parseBound(ObjectMapper objectMapper, byte[] bytes) throws IOException {
		Objects.requireNonNull(objectMapper, "objectMapper");
		Objects.requireNonNull(bytes, "bytes");
		if (bytes.length < 1 || bytes.length > MAXIMUM_INPUT_BYTES) {
			throw new IllegalArgumentException("policy must contain 1 through 65536 bytes");
		}
		JsonNode root = objectMapper.reader()
				.with(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
				.with(
						DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY,
						DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
				.readTree(bytes);
		return new BoundPolicy(parse(root), sha256(bytes));
	}

	static MultilingualLexicalEvaluationPolicy parse(JsonNode root) {
		requireExactObject(root, "$", ROOT_FIELDS);
		List<Profile> profiles = new ArrayList<>();
		JsonNode profileNodes = requireArray(root.required("profiles"), "$.profiles");
		for (int index = 0; index < profileNodes.size(); index++) {
			profiles.add(parseProfile(profileNodes.get(index), "$.profiles[" + index + "]"));
		}
		MultilingualLexicalEvaluationPolicy policy = new MultilingualLexicalEvaluationPolicy(
				requireInteger(root.required("schemaVersion"), "$.schemaVersion"),
				requireText(root.required("policyId"), "$.policyId", 3, 100),
				requireText(
						root.required("developmentFixtureId"), "$.developmentFixtureId", 3, 100),
				requireText(
						root.required("developmentFixtureSha256"),
						"$.developmentFixtureSha256", 64, 64),
				requireEnum(root.required("status"), "$.status", Status.class),
				parseBaseline(root.required("baseline"), "$.baseline"),
				parseRuntime(root.required("runtime"), "$.runtime"),
				profiles,
				requireTextArray(root.required("supportedLanguages"), "$.supportedLanguages", 1, 20),
				requireTextArray(root.required("fallbackLanguages"), "$.fallbackLanguages", 1, 20),
				requireTextArray(root.required("metrics"), "$.metrics", 1, 20),
				parseGates(root.required("gates"), "$.gates"));
		validateValues(policy);
		return policy;
	}

	void validateFixture(BoundFixture fixture) {
		Objects.requireNonNull(fixture, "fixture");
		Set<String> fixtureLanguages = fixture.fixture().queries().stream()
				.map(MultilingualLexicalEvaluationFixture.Query::language)
				.collect(java.util.stream.Collectors.toUnmodifiableSet());
		Set<String> declaredLanguages = new java.util.LinkedHashSet<>(supportedLanguages);
		declaredLanguages.addAll(fallbackLanguages);
		if (!developmentFixtureId.equals(fixture.fixture().fixtureId())
				|| !developmentFixtureSha256.equals(fixture.sha256())
				|| !policyId.equals(fixture.fixture().policyId())
				|| !declaredLanguages.equals(fixtureLanguages)) {
			throw new IllegalArgumentException(
					"Policy and multilingual fixture are not digest/language bound");
		}
	}

	Profile profile(ProfileKey key) {
		return profiles.stream()
				.filter(profile -> profile.key() == key)
				.findFirst()
				.orElseThrow(() -> new IllegalArgumentException("Unknown profile: " + key));
	}

	boolean isSupportedLanguage(String language) {
		return supportedLanguages.contains(language);
	}

	private static Baseline parseBaseline(JsonNode node, String path) {
		requireExactObject(node, path, BASELINE_FIELDS);
		return new Baseline(
				requireEnum(node.required("profile"), path + ".profile", ProfileKey.class),
				parseConfiguration(node.required("configuration"), path + ".configuration"),
				requireEnum(
						node.required("queryFunction"), path + ".queryFunction", QueryFunction.class),
				requireEnum(
						node.required("rankFunction"), path + ".rankFunction", RankFunction.class),
				requireInteger(node.required("rankNormalization"), path + ".rankNormalization"),
				requireTextArray(node.required("weightedFields"), path + ".weightedFields", 1, 10));
	}

	private static Runtime parseRuntime(JsonNode node, String path) {
		requireExactObject(node, path, RUNTIME_FIELDS);
		return new Runtime(
				requireInteger(node.required("postgresMajor"), path + ".postgresMajor"),
				requireText(node.required("containerImage"), path + ".containerImage", 10, 300));
	}

	private static Profile parseProfile(JsonNode node, String path) {
		requireExactObject(node, path, PROFILE_FIELDS);
		JsonNode mappingNode = requireObject(
				node.required("languageConfigurations"), path + ".languageConfigurations");
		Map<String, TextSearchConfiguration> mappings = new LinkedHashMap<>();
		for (String propertyName : mappingNode.propertyNames()) {
			String language = requireLanguageText(propertyName, path + ".languageConfigurations key");
			mappings.put(
					language,
					parseConfiguration(
							mappingNode.required(propertyName),
							path + ".languageConfigurations." + language));
		}
		return new Profile(
				requireEnum(node.required("key"), path + ".key", ProfileKey.class),
				parseConfiguration(
						node.required("defaultConfiguration"), path + ".defaultConfiguration"),
				mappings);
	}

	private static Gates parseGates(JsonNode node, String path) {
		requireExactObject(node, path, GATE_FIELDS);
		return new Gates(
				requireNumber(
						node.required("minimumLanguageAwareSupportedMacroRecallAt3"),
						path + ".minimumLanguageAwareSupportedMacroRecallAt3"),
				requireNumber(
						node.required("minimumLanguageAwareSupportedMacroNdcgAt3"),
						path + ".minimumLanguageAwareSupportedMacroNdcgAt3"),
				requireInteger(
						node.required("minimumStrictNonEnglishImprovementCount"),
						path + ".minimumStrictNonEnglishImprovementCount"),
				requireInteger(
						node.required("maximumSupportedQueryRegressionCount"),
						path + ".maximumSupportedQueryRegressionCount"),
				requireBoolean(
						node.required("requireStableRepeatedRanking"),
						path + ".requireStableRepeatedRanking"),
				requireBoolean(
						node.required("requireFallbackReportedSeparately"),
						path + ".requireFallbackReportedSeparately"),
				requireBoolean(
						node.required("requireProductionConfigurationUnchanged"),
						path + ".requireProductionConfigurationUnchanged"));
	}

	private static void validateValues(MultilingualLexicalEvaluationPolicy policy) {
		if (policy.schemaVersion() != 1
				|| !POLICY_ID.equals(policy.policyId())
				|| !MultilingualLexicalEvaluationFixture.FIXTURE_ID.equals(
						policy.developmentFixtureId())
				|| policy.status() != Status.EVALUATION_ONLY) {
			throw new IllegalArgumentException("Unexpected multilingual lexical policy identity");
		}
		Baseline baseline = policy.baseline();
		if (baseline.profile() != ProfileKey.PRODUCTION_ENGLISH
				|| baseline.configuration() != TextSearchConfiguration.ENGLISH
				|| baseline.queryFunction() != QueryFunction.WEBSEARCH_TO_TSQUERY
				|| baseline.rankFunction() != RankFunction.TS_RANK_CD
				|| baseline.rankNormalization() != 32
				|| !baseline.weightedFields().equals(List.of("TITLE_A", "ABSTRACT_B", "VENUE_C"))) {
			throw new IllegalArgumentException("Production lexical baseline semantics drifted");
		}
		if (policy.runtime().postgresMajor() != 17
				|| !POSTGRES_IMAGE.equals(policy.runtime().containerImage())) {
			throw new IllegalArgumentException("Pinned PostgreSQL runtime drifted");
		}
		if (!policy.profiles().stream().map(Profile::key).toList().equals(List.of(
				ProfileKey.PRODUCTION_ENGLISH, ProfileKey.SIMPLE, ProfileKey.LANGUAGE_AWARE))) {
			throw new IllegalArgumentException("Lexical profile order or membership drifted");
		}
		Profile production = policy.profile(ProfileKey.PRODUCTION_ENGLISH);
		Profile simple = policy.profile(ProfileKey.SIMPLE);
		Profile languageAware = policy.profile(ProfileKey.LANGUAGE_AWARE);
		if (production.defaultConfiguration() != TextSearchConfiguration.ENGLISH
				|| !production.languageConfigurations().isEmpty()
				|| simple.defaultConfiguration() != TextSearchConfiguration.SIMPLE
				|| !simple.languageConfigurations().isEmpty()
				|| languageAware.defaultConfiguration() != TextSearchConfiguration.SIMPLE
				|| !languageAware.languageConfigurations().equals(EXPECTED_LANGUAGE_CONFIGURATIONS)) {
			throw new IllegalArgumentException("Lexical configuration allowlist drifted");
		}
		unique(policy.supportedLanguages(), "supportedLanguages");
		unique(policy.fallbackLanguages(), "fallbackLanguages");
		if (!Set.copyOf(policy.supportedLanguages()).equals(Set.of("en", "de", "fr", "es"))
				|| !policy.fallbackLanguages().equals(List.of("ja"))
				|| !java.util.Collections.disjoint(
						policy.supportedLanguages(), policy.fallbackLanguages())
				|| !policy.metrics().equals(EXPECTED_METRICS)) {
			throw new IllegalArgumentException("Language or metric declarations drifted");
		}
		Gates gates = policy.gates();
		for (double rate : List.of(
				gates.minimumLanguageAwareSupportedMacroRecallAt3(),
				gates.minimumLanguageAwareSupportedMacroNdcgAt3())) {
			if (!Double.isFinite(rate) || rate < 0.0d || rate > 1.0d) {
				throw new IllegalArgumentException("Metric gates must be finite values within 0..1");
			}
		}
		if (!close(gates.minimumLanguageAwareSupportedMacroRecallAt3(), 0.90d)
				|| !close(gates.minimumLanguageAwareSupportedMacroNdcgAt3(), 0.90d)
				|| gates.minimumStrictNonEnglishImprovementCount() != 2
				|| gates.maximumSupportedQueryRegressionCount() != 0
				|| !gates.requireStableRepeatedRanking()
				|| !gates.requireFallbackReportedSeparately()
				|| !gates.requireProductionConfigurationUnchanged()) {
			throw new IllegalArgumentException("Multilingual evaluation gates drifted");
		}
	}

	private static boolean close(double left, double right) {
		return Math.abs(left - right) <= EPSILON;
	}

	private static String requireDigest(String value, String path) {
		String digest = requireTextValue(value, path, 64, 64);
		if (!digest.matches("[0-9a-f]{64}")) {
			throw new IllegalArgumentException(path + " must be a lowercase SHA-256 digest");
		}
		return digest;
	}

	private static String requireLanguageText(String value, String path) {
		String language = requireTextValue(value, path, 2, 8);
		if (!language.matches("[a-z]{2,8}")) {
			throw new IllegalArgumentException(path + " must be a lowercase language code");
		}
		return language;
	}

	private static TextSearchConfiguration parseConfiguration(JsonNode node, String path) {
		String value = requireText(node, path, 3, 32);
		for (TextSearchConfiguration configuration : TextSearchConfiguration.values()) {
			if (configuration.sqlName().equals(value)) {
				return configuration;
			}
		}
		throw new IllegalArgumentException(path + " contains a non-allowlisted configuration");
	}

	enum Status {
		EVALUATION_ONLY
	}

	enum ProfileKey {
		PRODUCTION_ENGLISH,
		SIMPLE,
		LANGUAGE_AWARE
	}

	enum TextSearchConfiguration {
		ENGLISH("english"),
		SIMPLE("simple"),
		GERMAN("german"),
		FRENCH("french"),
		SPANISH("spanish");

		private final String sqlName;

		TextSearchConfiguration(String sqlName) {
			this.sqlName = sqlName;
		}

		String sqlName() {
			return sqlName;
		}
	}

	enum QueryFunction {
		WEBSEARCH_TO_TSQUERY
	}

	enum RankFunction {
		TS_RANK_CD
	}

	record Baseline(
			ProfileKey profile,
			TextSearchConfiguration configuration,
			QueryFunction queryFunction,
			RankFunction rankFunction,
			int rankNormalization,
			List<String> weightedFields) {

		Baseline {
			weightedFields = List.copyOf(Objects.requireNonNull(weightedFields, "weightedFields"));
		}
	}

	record Runtime(int postgresMajor, String containerImage) {

		Runtime {
			containerImage = requireTextValue(containerImage, "containerImage", 10, 300);
		}
	}

	record Profile(
			ProfileKey key,
			TextSearchConfiguration defaultConfiguration,
			Map<String, TextSearchConfiguration> languageConfigurations) {

		Profile {
			key = Objects.requireNonNull(key, "key");
			defaultConfiguration = Objects.requireNonNull(
					defaultConfiguration, "defaultConfiguration");
			languageConfigurations = Map.copyOf(
					Objects.requireNonNull(languageConfigurations, "languageConfigurations"));
		}

		TextSearchConfiguration configurationFor(String language) {
			return languageConfigurations.getOrDefault(language, defaultConfiguration);
		}
	}

	record Gates(
			double minimumLanguageAwareSupportedMacroRecallAt3,
			double minimumLanguageAwareSupportedMacroNdcgAt3,
			int minimumStrictNonEnglishImprovementCount,
			int maximumSupportedQueryRegressionCount,
			boolean requireStableRepeatedRanking,
			boolean requireFallbackReportedSeparately,
			boolean requireProductionConfigurationUnchanged) {
	}

	record BoundPolicy(MultilingualLexicalEvaluationPolicy policy, String sha256) {

		BoundPolicy {
			policy = Objects.requireNonNull(policy, "policy");
			sha256 = Objects.requireNonNull(sha256, "sha256");
		}

		void validateReference(String expectedPolicyId, String expectedSha256) {
			if (!policy.policyId().equals(expectedPolicyId) || !sha256.equals(expectedSha256)) {
				throw new IllegalArgumentException("Policy identity or digest does not match its reference");
			}
		}
	}
}
