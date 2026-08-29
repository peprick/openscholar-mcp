package com.openscholar.paper.internal.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;

import com.openscholar.paper.internal.persistence.MultilingualLexicalEvaluationPolicy.ProfileKey;
import com.openscholar.paper.internal.persistence.MultilingualLexicalEvaluationPolicy.TextSearchConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import tools.jackson.databind.ObjectMapper;

class MultilingualLexicalEvaluationContractTests {

	private final ObjectMapper objectMapper = new ObjectMapper();

	@Test
	void frozenPolicyBindsTheExactStrictSyntheticFixtureAndConfigurationAllowlist()
			throws Exception {
		var fixture = MultilingualLexicalEvaluationFixture.loadFrozen(objectMapper);
		var policy = MultilingualLexicalEvaluationPolicy.loadFrozen(objectMapper);

		policy.policy().validateFixture(fixture);
		assertThat(fixture.fixture().candidates()).hasSize(15);
		assertThat(fixture.fixture().queries()).hasSize(5);
		assertThat(fixture.fixture().queries())
				.extracting(MultilingualLexicalEvaluationFixture.Query::language)
				.containsExactly("en", "de", "fr", "es", "ja");
		assertThat(fixture.sha256())
				.isEqualTo(MultilingualLexicalEvaluationFixture.FIXTURE_SHA256);
		assertThat(policy.sha256()).isEqualTo(MultilingualLexicalEvaluationPolicy.POLICY_SHA256);
		assertThat(policy.policy().profile(ProfileKey.PRODUCTION_ENGLISH)
				.configurationFor("de"))
				.isEqualTo(TextSearchConfiguration.ENGLISH);
		assertThat(policy.policy().profile(ProfileKey.SIMPLE).configurationFor("fr"))
				.isEqualTo(TextSearchConfiguration.SIMPLE);
		assertThat(policy.policy().profile(ProfileKey.LANGUAGE_AWARE).configurationFor("de"))
				.isEqualTo(TextSearchConfiguration.GERMAN);
		assertThat(policy.policy().profile(ProfileKey.LANGUAGE_AWARE).configurationFor("ja"))
				.isEqualTo(TextSearchConfiguration.SIMPLE);
	}

	@Test
	void fixtureRejectsDuplicateUnknownTrailingAndCrossLanguageJudgments() throws Exception {
		String original = fixtureText();
		String duplicate = original.replaceFirst(
				"\\\"fixtureId\\\": \\\"multilingual-lexical-development-v1\\\",",
				"\\\"fixtureId\\\": \\\"multilingual-lexical-development-v1\\\",\n"
						+ "  \\\"fixtureId\\\": \\\"multilingual-lexical-development-v1\\\",");
		String unknown = original.replaceFirst(
				"\\\"schemaVersion\\\": 1,",
				"\\\"schemaVersion\\\": 1,\n  \\\"unexpected\\\": true,");
		String crossLanguage = original.replaceFirst(
				"\\\"en-calibration-negative\\\": 0",
				"\\\"de-supply-chain-negative\\\": 0");

		assertThatThrownBy(() -> parseFixture(duplicate)).isInstanceOf(Exception.class);
		assertThatThrownBy(() -> parseFixture(unknown))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("exactly");
		assertThatThrownBy(() -> parseFixture(original + "\n{}"))
				.isInstanceOf(Exception.class);
		assertThatThrownBy(() -> parseFixture(crossLanguage))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("language-scoped");
	}

	@Test
	void fixtureRejectsWrongTypesMissingLanguageCoverageAndOversizedInput() throws Exception {
		String original = fixtureText();
		String wrongCutoffType = original.replaceFirst(
				"\\\"cutoff\\\": 3", "\\\"cutoff\\\": \\\"3\\\"");
		String duplicatedLanguage = original.replaceFirst(
				"\\\"language\\\": \\\"ja\\\",\n      \\\"text\\\": \\\"浮世絵 顔料 分光\\\"",
				"\\\"language\\\": \\\"en\\\",\n      \\\"text\\\": \\\"浮世絵 顔料 分光\\\"");

		assertThatThrownBy(() -> parseFixture(wrongCutoffType))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("cutoff");
		assertThatThrownBy(() -> parseFixture(duplicatedLanguage))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("query languages");
		assertThatThrownBy(() -> MultilingualLexicalEvaluationFixture.parseBound(
				objectMapper, new byte[128 * 1024 + 1]))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("131072");
	}

	@Test
	void policyRejectsUnsafeConfigurationRuntimeDriftAndFixtureDigestMismatch() throws Exception {
		String original = policyText();
		String unsafeConfiguration = original.replaceFirst(
				"\\\"defaultConfiguration\\\": \\\"english\\\"",
				"\\\"defaultConfiguration\\\": \\\"english'; drop table paper; --\\\"");
		String runtimeDrift = original.replaceFirst(
				"\\\"postgresMajor\\\": 17", "\\\"postgresMajor\\\": 16");
		String mismatchedDigest = original.replace(
				MultilingualLexicalEvaluationFixture.FIXTURE_SHA256, "0".repeat(64));

		assertThatThrownBy(() -> parsePolicy(unsafeConfiguration))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("non-allowlisted");
		assertThatThrownBy(() -> parsePolicy(runtimeDrift))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("runtime drifted");

		var fixture = MultilingualLexicalEvaluationFixture.loadFrozen(objectMapper);
		var policy = parsePolicy(mismatchedDigest);
		assertThatThrownBy(() -> policy.policy().validateFixture(fixture))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("digest/language bound");
	}

	@Test
	void policyRejectsDuplicateUnknownTrailingAndOversizedInput() throws Exception {
		String original = policyText();
		String duplicate = original.replaceFirst(
				"\\\"policyId\\\": \\\"multilingual-lexical-policy-v1\\\",",
				"\\\"policyId\\\": \\\"multilingual-lexical-policy-v1\\\",\n"
						+ "  \\\"policyId\\\": \\\"multilingual-lexical-policy-v1\\\",");
		String unknown = original.replaceFirst(
				"\\\"schemaVersion\\\": 1,",
				"\\\"schemaVersion\\\": 1,\n  \\\"unexpected\\\": true,");

		assertThatThrownBy(() -> parsePolicy(duplicate)).isInstanceOf(Exception.class);
		assertThatThrownBy(() -> parsePolicy(unknown))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("exactly");
		assertThatThrownBy(() -> parsePolicy(original + "\n{}"))
				.isInstanceOf(Exception.class);
		assertThatThrownBy(() -> MultilingualLexicalEvaluationPolicy.parseBound(
				objectMapper, new byte[64 * 1024 + 1]))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("65536");
	}

	private MultilingualLexicalEvaluationFixture.BoundFixture parseFixture(String json)
			throws Exception {
		return MultilingualLexicalEvaluationFixture.parseBound(
				objectMapper, json.getBytes(StandardCharsets.UTF_8));
	}

	private MultilingualLexicalEvaluationPolicy.BoundPolicy parsePolicy(String json)
			throws Exception {
		return MultilingualLexicalEvaluationPolicy.parseBound(
				objectMapper, json.getBytes(StandardCharsets.UTF_8));
	}

	private static String fixtureText() throws Exception {
		return new ClassPathResource(MultilingualLexicalEvaluationFixture.RESOURCE_PATH)
				.getContentAsString(StandardCharsets.UTF_8);
	}

	private static String policyText() throws Exception {
		return new ClassPathResource(MultilingualLexicalEvaluationPolicy.RESOURCE_PATH)
				.getContentAsString(StandardCharsets.UTF_8);
	}
}
