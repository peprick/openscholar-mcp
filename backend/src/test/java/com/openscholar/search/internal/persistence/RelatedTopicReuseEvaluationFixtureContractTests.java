package com.openscholar.search.internal.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;

import com.openscholar.search.internal.persistence.RelatedTopicReuseEvaluationFixture.LineageKind;
import com.openscholar.search.internal.persistence.RelatedTopicReuseEvaluationFixture.QueryKind;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import tools.jackson.databind.ObjectMapper;

class RelatedTopicReuseEvaluationFixtureContractTests {

	private final ObjectMapper objectMapper = new ObjectMapper();

	@Test
	void frozenFixtureBindsTheExactOwnerScopedDevelopmentShapeWithoutSeedOracles()
			throws Exception {
		var bound = RelatedTopicReuseEvaluationFixture.loadFrozen(objectMapper);
		var fixture = bound.fixture();

		assertThat(bound.sha256())
				.isEqualTo(RelatedTopicReuseEvaluationFixture.FIXTURE_SHA256);
		assertThat(fixture.lineages()).hasSize(11);
		assertThat(fixture.candidates()).hasSize(25);
		assertThat(fixture.targetVisibleKeys()).hasSize(19);
		assertThat(fixture.queries()).hasSize(5)
				.allSatisfy(query -> assertThat(query.judgments()).hasSize(19));
		assertThat(fixture.lineages())
				.filteredOn(lineage -> lineage.kind() == LineageKind.TARGET_OWNER_SEARCH)
				.hasSize(4);
		assertThat(fixture.lineages())
				.filteredOn(lineage -> lineage.kind() == LineageKind.TARGET_OWNER_COLLECTION)
				.hasSize(4);
		assertThat(fixture.queries())
				.filteredOn(query -> query.kind().opportunity())
				.hasSize(3);
		assertThat(fixture.queries())
				.filteredOn(query -> query.kind() == QueryKind.AUTHOR_NO_RELATED_SIGNAL_CONTROL)
				.singleElement();
		assertThat(fixture.queries())
				.filteredOn(query -> query.kind() == QueryKind.NO_SEED_FALLBACK_CONTROL)
				.singleElement()
				.satisfies(query -> assertThat(query.judgments().values())
						.containsOnly(0));

		var root = objectMapper.readTree(fixtureText());
		for (var query : root.required("queries")) {
			assertThat(query.propertyNames())
					.noneMatch(name -> name.toLowerCase(java.util.Locale.ROOT).contains("seed"));
		}
	}

	@Test
	void strictParserRejectsDuplicateUnknownTrailingAndOversizedInput() throws Exception {
		String original = fixtureText();
		String duplicate = original.replaceFirst(
				"\\\"fixtureId\\\": \\\"related-topic-reuse-development-v1\\\",",
				"\\\"fixtureId\\\": \\\"related-topic-reuse-development-v1\\\",\n"
						+ "  \\\"fixtureId\\\": \\\"related-topic-reuse-development-v1\\\",");
		String unknown = original.replaceFirst(
				"\\\"schemaVersion\\\": 1,",
				"\\\"schemaVersion\\\": 1,\n  \\\"unexpected\\\": true,");

		assertThatThrownBy(() -> parse(duplicate)).isInstanceOf(Exception.class);
		assertThatThrownBy(() -> parse(unknown))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("exactly");
		assertThatThrownBy(() -> parse(original + "\n{}"))
				.isInstanceOf(Exception.class);
		assertThatThrownBy(() -> RelatedTopicReuseEvaluationFixture.parseBound(
				objectMapper, new byte[RelatedTopicReuseEvaluationFixture.MAXIMUM_INPUT_BYTES + 1]))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("262144");
	}

	@Test
	void frozenReferenceRejectsSemanticallyEquivalentRawByteDrift() throws Exception {
		String byteDrift = fixtureText().replaceFirst(
				"\\{\\n  \\\"schemaVersion\\\"", "{\n   \"schemaVersion\"");
		var rebound = parse(byteDrift);

		assertThat(rebound.sha256())
				.isNotEqualTo(RelatedTopicReuseEvaluationFixture.FIXTURE_SHA256);
		assertThatThrownBy(() -> rebound.validateReference(
				RelatedTopicReuseEvaluationFixture.FIXTURE_ID,
				RelatedTopicReuseEvaluationFixture.FIXTURE_SHA256))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("digest drifted");
	}

	@Test
	void strictParserRejectsOracleSeedFieldsAndWrongScalarTypes() throws Exception {
		String original = fixtureText();
		String oracleSeed = original.replaceFirst(
				"\\\"cutoff\\\": 10,",
				"\\\"expectedSeedKeys\\\": [\\\"coastal-exact\\\"],\n"
						+ "      \\\"cutoff\\\": 10,");
		String wrongCutoff = original.replaceFirst(
				"\\\"cutoff\\\": 10", "\\\"cutoff\\\": \\\"10\\\"");
		String wrongOpenAccess = original.replaceFirst(
				"\\\"reportedOpenAccess\\\": true",
				"\\\"reportedOpenAccess\\\": \\\"true\\\"");

		assertThatThrownBy(() -> parse(oracleSeed))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("exactly");
		assertThatThrownBy(() -> parse(wrongCutoff))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("cutoff");
		assertThatThrownBy(() -> parse(wrongOpenAccess))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("reportedOpenAccess");
	}

	@Test
	void semanticValidationRejectsBrokenLineagesAndIncompleteTargetJudgments()
			throws Exception {
		String original = fixtureText();
		String brokenLineage = original.replaceFirst(
				"\\\"lineageKey\\\": \\\"target-search-coastal\\\"",
				"\\\"lineageKey\\\": \\\"missing-lineage\\\"");
		String incompleteJudgments = original.replaceFirst(
				", \\\"generic-control\\\": 0\n      }", "\n      }");

		assertThatThrownBy(() -> parse(brokenLineage))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("lineage");
		assertThatThrownBy(() -> parse(incompleteJudgments))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("every target-visible candidate");
	}

	@Test
	void semanticValidationProtectsEpisodeKindsFiltersAndZeroSeedGoldBoundary()
			throws Exception {
		String original = fixtureText();
		String wrongEpisodeKind = original.replaceFirst(
				"\\\"kind\\\": \\\"LEXICAL_BRIDGE_OPPORTUNITY\\\"",
				"\\\"kind\\\": \\\"AUTHOR_NO_RELATED_SIGNAL_CONTROL\\\"");
		String incompleteFilter = original.replaceFirst(
				"\\\"yearFrom\\\": 2022", "\\\"yearFrom\\\": null");
		String missingFilterAdversaryDimension = original.replaceFirst(
				"\\\"publicationYear\\\": 2021,\\n      \\\"documentType\\\": \\\"THESIS\\\",\\n      \\\"language\\\": \\\"en\\\"",
				"\\\"publicationYear\\\": 2022,\n      \\\"documentType\\\": \\\"THESIS\\\",\n      \\\"language\\\": \\\"es\\\"");
		String noSeedGoldLeak = replaceLast(
				original, "\"generic-control\": 0", "\"generic-control\": 3");

		assertThatThrownBy(() -> parse(wrongEpisodeKind))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("author control");
		assertThatThrownBy(() -> parse(incompleteFilter))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("every filter dimension");
		assertThatThrownBy(() -> parse(missingFilterAdversaryDimension))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("cover every request filter dimension");
		assertThatThrownBy(() -> parse(noSeedGoldLeak))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("no-seed fallback");
	}

	@Test
	void semanticValidationRejectsAdversariesThatCrossTheirOwnerBoundary() throws Exception {
		String wrongOwnerBoundary = fixtureText().replaceFirst(
				"\\\"candidateKey\\\": \\\"other-coastal-exact\\\"",
				"\\\"candidateKey\\\": \\\"coastal-exact\\\"");

		assertThatThrownBy(() -> parse(wrongOwnerBoundary))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("Other-owner adversaries");
	}

	private RelatedTopicReuseEvaluationFixture.BoundFixture parse(String json) throws Exception {
		return RelatedTopicReuseEvaluationFixture.parseBound(
				objectMapper, json.getBytes(StandardCharsets.UTF_8));
	}

	private static String fixtureText() throws Exception {
		return new ClassPathResource(RelatedTopicReuseEvaluationFixture.RESOURCE_PATH)
				.getContentAsString(StandardCharsets.UTF_8);
	}

	private static String replaceLast(String value, String target, String replacement) {
		int index = value.lastIndexOf(target);
		if (index < 0) {
			throw new IllegalArgumentException("target text was not found");
		}
		return value.substring(0, index) + replacement + value.substring(index + target.length());
	}
}
