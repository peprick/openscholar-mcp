package com.openscholar.search.internal.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import tools.jackson.databind.ObjectMapper;

class LocalCatalogTopicEvaluationContractTests {

	private final ObjectMapper objectMapper = new ObjectMapper();

	@Test
	void frozenPolicyBindsTheExactStrictSyntheticFixture() throws Exception {
		var fixture = LocalCatalogTopicEvaluationFixture.loadFrozen(objectMapper);
		var policy = LocalCatalogTopicEvaluationPolicy.loadFrozen(objectMapper);

		policy.policy().validateFixture(fixture);
		assertThat(fixture.fixture().candidates()).hasSize(25);
		assertThat(fixture.fixture().targetVisibleKeys()).hasSize(19);
		assertThat(fixture.fixture().queries()).hasSize(6);
		assertThat(fixture.sha256()).isEqualTo(LocalCatalogTopicEvaluationFixture.FIXTURE_SHA256);
		assertThat(policy.sha256()).isEqualTo(LocalCatalogTopicEvaluationPolicy.POLICY_SHA256);
	}

	@Test
	void fixtureRejectsDuplicateUnknownTrailingAndDanglingContent() throws Exception {
		String original = fixtureText();
		String duplicate = original.replaceFirst(
				"\\\"fixtureId\\\": \\\"local-catalog-topic-development-v1\\\",",
				"\\\"fixtureId\\\": \\\"local-catalog-topic-development-v1\\\",\n"
						+ "  \\\"fixtureId\\\": \\\"local-catalog-topic-development-v1\\\",");
		String unknown = original.replaceFirst(
				"\\\"schemaVersion\\\": 1,",
				"\\\"schemaVersion\\\": 1,\n  \\\"unexpected\\\": true,");
		String dangling = original.replaceFirst(
				"\\\"candidateKey\\\": \\\"other-rare-disease-exact\\\"",
				"\\\"candidateKey\\\": \\\"missing-candidate\\\"");

		assertThatThrownBy(() -> parseFixture(duplicate)).isInstanceOf(Exception.class);
		assertThatThrownBy(() -> parseFixture(unknown)).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> parseFixture(original + "\n{}"))
				.isInstanceOf(Exception.class);
		assertThatThrownBy(() -> parseFixture(dangling))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("unknown candidate");
	}

	@Test
	void policyRejectsWrongTypesOversizedInputAndFixtureDigestMismatch() throws Exception {
		String original = policyText();
		String wrongType = original.replaceFirst(
				"\\\"candidateCount\\\": 25", "\\\"candidateCount\\\": \\\"25\\\"");
		String mismatchedDigest = original.replace(
				LocalCatalogTopicEvaluationFixture.FIXTURE_SHA256, "0".repeat(64));

		assertThatThrownBy(() -> parsePolicy(wrongType))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("candidateCount");
		assertThatThrownBy(() -> LocalCatalogTopicEvaluationPolicy.parseBound(
				objectMapper, new byte[64 * 1024 + 1]))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("65536");

		var fixture = LocalCatalogTopicEvaluationFixture.loadFrozen(objectMapper);
		var policy = parsePolicy(mismatchedDigest);
		assertThatThrownBy(() -> policy.policy().validateFixture(fixture))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("digest/count bound");
	}

	@Test
	void fixtureRejectsIncompleteCrossOwnerAndSemanticallyMismatchedLabels() throws Exception {
		String original = fixtureText();
		String incompleteJudgments = original.replaceFirst(
				"\\\"rare-disease-exact\\\": 3, ", "");
		String crossOwnerJudgment = original.replaceFirst(
				"\\\"rare-disease-exact\\\": 3, ",
				"\\\"rare-disease-exact\\\": 3, \\\"other-rare-disease-exact\\\": 1, ");
		String mismatchedAdversary = original.replaceFirst(
				"\\\"OTHER_OWNER_SEARCH_EXACT_MATCH\\\"",
				"\\\"CATALOG_ONLY_EXACT_MATCH\\\"");
		String invalidVisibility = original.replaceFirst(
				"\\\"TARGET_OWNER_SEARCH\\\"", "\\\"PUBLIC_CATALOG\\\"");

		assertThatThrownBy(() -> parseFixture(incompleteJudgments))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("cover exactly");
		assertThatThrownBy(() -> parseFixture(crossOwnerJudgment))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("cover exactly");
		assertThatThrownBy(() -> parseFixture(mismatchedAdversary))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("must reference CATALOG_ONLY");
		assertThatThrownBy(() -> parseFixture(invalidVisibility))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("unsupported value");
	}

	private LocalCatalogTopicEvaluationFixture.BoundFixture parseFixture(String json)
			throws Exception {
		return LocalCatalogTopicEvaluationFixture.parseBound(
				objectMapper, json.getBytes(StandardCharsets.UTF_8));
	}

	private LocalCatalogTopicEvaluationPolicy.BoundPolicy parsePolicy(String json)
			throws Exception {
		return LocalCatalogTopicEvaluationPolicy.parseBound(
				objectMapper, json.getBytes(StandardCharsets.UTF_8));
	}

	private static String fixtureText() throws Exception {
		return new ClassPathResource(LocalCatalogTopicEvaluationFixture.RESOURCE_PATH)
				.getContentAsString(StandardCharsets.UTF_8);
	}

	private static String policyText() throws Exception {
		return new ClassPathResource(LocalCatalogTopicEvaluationPolicy.RESOURCE_PATH)
				.getContentAsString(StandardCharsets.UTF_8);
	}
}
