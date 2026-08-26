package com.openscholar.search.internal.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

class ProviderQualityComparativeScoringPolicyTests {

	private static final JsonMapper OBJECT_MAPPER = JsonMapper.builder().build();

	@TempDir
	Path temporaryDirectory;

	@Test
	void loadsTheDigestBoundFrozenMeasurementPolicyWithoutDefaultGates() throws Exception {
		ProviderQualityComparativeScoringPolicy.BoundPolicy bound =
				ProviderQualityComparativeScoringPolicy.loadBound(
						OBJECT_MAPPER, ProviderQualityComparativeScoringPolicy.RESOURCE_PATH);

		assertThat(bound.sha256())
				.isEqualTo(ProviderQualityComparativeScoringPolicy.POLICY_SHA256);
		assertThatCode(() -> bound.validateReference(
				ProviderQualityComparativeScoringPolicy.POLICY_ID,
				ProviderQualityComparativeScoringPolicy.POLICY_SHA256))
				.doesNotThrowAnyException();
		assertThat(ProviderQualityComparativeScoringPolicy.loadFrozen(OBJECT_MAPPER))
				.isEqualTo(bound.policy());

		ProviderQualityComparativeScoringPolicy policy = bound.policy();
		assertThat(policy.schemaVersion()).isEqualTo(1);
		assertThat(policy.policyId())
				.isEqualTo("provider-comparative-scoring-policy-v1");
		assertThat(policy.status())
				.isEqualTo(ProviderQualityComparativeScoringPolicy.Status.EVALUATION_ONLY);
		assertThat(policy.scenarios()).containsExactly(
				ProviderQualityComparativeScoringPolicy.Scenario.OPENALEX_ONLY,
				ProviderQualityComparativeScoringPolicy.Scenario.EUROPE_PMC_ONLY,
				ProviderQualityComparativeScoringPolicy.Scenario.FUSED);
		assertThat(policy.ranking()).isEqualTo(
				new ProviderQualityComparativeScoringPolicy.Ranking(
						20,
						10,
						5,
						20,
						ProviderQualityComparativeScoringPolicy.ClusterCredit
								.ONE_HIGHEST_GRADE_UNCREDITED_GOLD_WORK_PER_RESULT,
						ProviderQualityComparativeScoringPolicy.CreditTieBreak
								.GOLD_PAPER_KEY_ASCENDING,
						new ProviderQualityComparativeScoringPolicy.NoRelevantJudgments(
								ProviderQualityComparativeScoringPolicy.NoRelevantRelevanceMetric
										.UNDEFINED_EXCLUDE_FROM_MACRO,
								ProviderQualityComparativeScoringPolicy.NoRelevantRelevanceMetric
										.UNDEFINED_EXCLUDE_FROM_MACRO,
								ProviderQualityComparativeScoringPolicy.NoRelevantPrecision
										.ZERO_INCLUDE_IN_MACRO,
								ProviderQualityComparativeScoringPolicy.NoRelevantRelevanceMetric
										.UNDEFINED_EXCLUDE_FROM_MACRO)));
		assertThat(policy.deduplication()).isEqualTo(
				new ProviderQualityComparativeScoringPolicy.Deduplication(
						ProviderQualityComparativeScoringPolicy.DeduplicationMethod
								.PAIRWISE_PRECISION_RECALL_F1,
						new ProviderQualityComparativeScoringPolicy
								.DeduplicationUndefinedHandling(
										ProviderQualityComparativeScoringPolicy
												.UndefinedPrecisionWhen
												.TRUE_POSITIVE_PLUS_FALSE_POSITIVE_IS_ZERO,
										ProviderQualityComparativeScoringPolicy
												.UndefinedRecallWhen
												.TRUE_POSITIVE_PLUS_FALSE_NEGATIVE_IS_ZERO,
										ProviderQualityComparativeScoringPolicy.UndefinedF1When
												.PRECISION_OR_RECALL_IS_UNDEFINED,
										ProviderQualityComparativeScoringPolicy.UndefinedCounts
												.ALWAYS_PRESERVED)));
		assertThat(policy.metadata().method()).isEqualTo(
				ProviderQualityComparativeScoringPolicy.MetadataMethod.EXPECTED_FIELD_RECOVERY);
		assertThat(policy.limits()).isEqualTo(
				new ProviderQualityComparativeScoringPolicy.Limits(1_048_576, 50, 40));
		assertThat(policy.defaultEnablementGates()).isEmpty();
		assertThatThrownBy(() -> policy.scenarios().add(
				ProviderQualityComparativeScoringPolicy.Scenario.FUSED))
				.isInstanceOf(UnsupportedOperationException.class);
		assertThatThrownBy(() -> policy.defaultEnablementGates().add("NOT_A_GATE"))
				.isInstanceOf(UnsupportedOperationException.class);
	}

	@Test
	void requiresTheExactClosedSchemaAndOrderedScenarioSet() throws Exception {
		ObjectNode unknownRoot = validTree();
		unknownRoot.put("enableProviders", true);
		assertThatThrownBy(() -> parse(unknownRoot))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("Unknown keys at $")
				.hasMessageContaining("enableProviders");

		ObjectNode missingNestedField = validTree();
		((ObjectNode) missingNestedField.required("ranking")).remove("ndcgAt");
		assertThatThrownBy(() -> parse(missingNestedField))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("Missing keys at $.ranking")
				.hasMessageContaining("ndcgAt");

		ObjectNode reorderedScenarios = validTree();
		ArrayNode scenarios = (ArrayNode) reorderedScenarios.required("scenarios");
		scenarios.removeAll();
		scenarios.add("FUSED").add("OPENALEX_ONLY").add("EUROPE_PMC_ONLY");
		assertThatThrownBy(() -> parse(reorderedScenarios))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("scenarios must be ordered");

		ObjectNode unexpectedGate = validTree();
		((ArrayNode) unexpectedGate.required("defaultEnablementGates"))
				.add("MINIMUM_MACRO_NDCG");
		assertThatThrownBy(() -> parse(unexpectedGate))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("defines no gates");
	}

	@Test
	void rejectsDriftFromEveryFrozenScoringRuleAndLimit() throws Exception {
		ObjectNode cutoffDrift = validTree();
		((ObjectNode) cutoffDrift.required("ranking")).put("precisionAt", 10);
		assertThatThrownBy(() -> parse(cutoffDrift))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("ranking must freeze");

		ObjectNode creditDrift = validTree();
		((ObjectNode) creditDrift.required("ranking"))
				.put("clusterCredit", "EVERY_RESULT");
		assertThatThrownBy(() -> parse(creditDrift))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("$.ranking.clusterCredit has an unsupported value");

		ObjectNode tieBreakDrift = validTree();
		((ObjectNode) tieBreakDrift.required("ranking"))
				.put("creditTieBreak", "PROVIDER_ORDER");
		assertThatThrownBy(() -> parse(tieBreakDrift))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("$.ranking.creditTieBreak has an unsupported value");

		ObjectNode undefinedDrift = validTree();
		((ObjectNode) undefinedDrift.required("ranking")
				.required("noRelevantJudgments"))
				.put("precision", "UNDEFINED_EXCLUDE_FROM_MACRO");
		assertThatThrownBy(() -> parse(undefinedDrift))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining(
						"$.ranking.noRelevantJudgments.precision has an unsupported value");

		ObjectNode deduplicationDrift = validTree();
		((ObjectNode) deduplicationDrift.required("deduplication"))
				.put("method", "EXACT_CLUSTER_COUNT");
		assertThatThrownBy(() -> parse(deduplicationDrift))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("$.deduplication.method has an unsupported value");

		ObjectNode denominatorDrift = validTree();
		((ObjectNode) denominatorDrift.required("deduplication")
				.required("undefinedHandling"))
				.put("recallWhen", "EVALUATED_PAIR_COUNT_IS_ZERO");
		assertThatThrownBy(() -> parse(denominatorDrift))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining(
						"$.deduplication.undefinedHandling.recallWhen has an unsupported value");

		ObjectNode metadataDrift = validTree();
		((ObjectNode) metadataDrift.required("metadata"))
				.put("method", "RAW_FIELD_PRESENCE");
		assertThatThrownBy(() -> parse(metadataDrift))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("$.metadata.method has an unsupported value");

		ObjectNode limitDrift = validTree();
		((ObjectNode) limitDrift.required("limits")).put("maximumQueries", 51);
		assertThatThrownBy(() -> parse(limitDrift))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("limits must freeze");
	}

	@Test
	void rejectsDuplicateFieldsTrailingDocumentsAndOutOfBoundsByteArrays() {
		String json = validJson();
		byte[] duplicateField = json.replaceFirst(
				"\\\"schemaVersion\\\"\\s*:\\s*1,",
				"\"schemaVersion\": 1, \"schemaVersion\": 1,")
				.getBytes(StandardCharsets.UTF_8);

		assertThatThrownBy(() -> ProviderQualityComparativeScoringPolicy.parse(
				OBJECT_MAPPER, duplicateField))
				.isInstanceOf(JacksonException.class)
				.hasMessageContaining("Duplicate Object property");
		assertThatThrownBy(() -> ProviderQualityComparativeScoringPolicy.parse(
				OBJECT_MAPPER, (json + "\n{}").getBytes(StandardCharsets.UTF_8)))
				.isInstanceOf(JacksonException.class)
				.hasMessageContaining("Trailing token");
		assertThatThrownBy(() -> ProviderQualityComparativeScoringPolicy.parse(
				OBJECT_MAPPER, new byte[0]))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("1 through 1048576 bytes");
		assertThatThrownBy(() -> ProviderQualityComparativeScoringPolicy.parse(
				OBJECT_MAPPER,
				new byte[ProviderQualityComparativeScoringPolicy.MAXIMUM_INPUT_BYTES + 1]))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("1 through 1048576 bytes");
	}

	@Test
	void loadsOnlyBoundedRegularPathInputs() throws Exception {
		Path policyPath = temporaryDirectory.resolve("policy.json");
		Files.writeString(policyPath, validJson(), StandardCharsets.UTF_8);

		ProviderQualityComparativeScoringPolicy.BoundPolicy loaded =
				ProviderQualityComparativeScoringPolicy.loadBound(OBJECT_MAPPER, policyPath);
		assertThat(loaded.policy().policyId())
				.isEqualTo(ProviderQualityComparativeScoringPolicy.POLICY_ID);
		assertThat(loaded.sha256()).isEqualTo(ProviderQualityComparativeScoringPolicy.sha256(
				validJson().getBytes(StandardCharsets.UTF_8)));

		Path symbolicLink = temporaryDirectory.resolve("policy-link.json");
		Files.createSymbolicLink(symbolicLink, policyPath.getFileName());
		assertThatThrownBy(() -> ProviderQualityComparativeScoringPolicy.load(
				OBJECT_MAPPER, symbolicLink))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("regular non-symbolic-link file");

		Path oversized = temporaryDirectory.resolve("oversized.json");
		Files.write(oversized,
				new byte[ProviderQualityComparativeScoringPolicy.MAXIMUM_INPUT_BYTES + 1]);
		assertThatThrownBy(() -> ProviderQualityComparativeScoringPolicy.load(
				OBJECT_MAPPER, oversized))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("1 through 1048576 bytes");

		assertThatThrownBy(() -> ProviderQualityComparativeScoringPolicy.load(
				OBJECT_MAPPER, temporaryDirectory))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("regular non-symbolic-link file");
	}

	@Test
	void rejectsMismatchedOrNonCanonicalPolicyReferences() throws Exception {
		ProviderQualityComparativeScoringPolicy.BoundPolicy bound =
				ProviderQualityComparativeScoringPolicy.parseBound(
						OBJECT_MAPPER, validJson().getBytes(StandardCharsets.UTF_8));

		assertThatCode(() -> bound.validateReference(
				ProviderQualityComparativeScoringPolicy.POLICY_ID, bound.sha256()))
				.doesNotThrowAnyException();
		assertThatThrownBy(() -> bound.validateReference(
				"different-policy-v1", bound.sha256()))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("policy ID does not match");
		assertThatThrownBy(() -> bound.validateReference(
				ProviderQualityComparativeScoringPolicy.POLICY_ID, "A".repeat(64)))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("lowercase hexadecimal");
		assertThatThrownBy(() -> bound.validateReference(
				ProviderQualityComparativeScoringPolicy.POLICY_ID, "0".repeat(64)))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("SHA-256 does not match");
	}

	private static ProviderQualityComparativeScoringPolicy parse(ObjectNode root)
			throws Exception {
		return ProviderQualityComparativeScoringPolicy.parse(
				OBJECT_MAPPER, OBJECT_MAPPER.writeValueAsBytes(root));
	}

	private static ObjectNode validTree() throws Exception {
		return (ObjectNode) OBJECT_MAPPER.readTree(validJson());
	}

	private static String validJson() {
		return """
				{
				  "schemaVersion": 1,
				  "policyId": "provider-comparative-scoring-policy-v1",
				  "status": "EVALUATION_ONLY",
				  "scenarios": ["OPENALEX_ONLY", "EUROPE_PMC_ONLY", "FUSED"],
				  "ranking": {
				    "recallAt": 20,
				    "ndcgAt": 10,
				    "precisionAt": 5,
				    "reciprocalRankAt": 20,
				    "clusterCredit": "ONE_HIGHEST_GRADE_UNCREDITED_GOLD_WORK_PER_RESULT",
				    "creditTieBreak": "GOLD_PAPER_KEY_ASCENDING",
				    "noRelevantJudgments": {
				      "recall": "UNDEFINED_EXCLUDE_FROM_MACRO",
				      "ndcg": "UNDEFINED_EXCLUDE_FROM_MACRO",
				      "precision": "ZERO_INCLUDE_IN_MACRO",
				      "reciprocalRank": "UNDEFINED_EXCLUDE_FROM_MACRO"
				    }
				  },
				  "deduplication": {
				    "method": "PAIRWISE_PRECISION_RECALL_F1",
				    "undefinedHandling": {
				      "precisionWhen": "TRUE_POSITIVE_PLUS_FALSE_POSITIVE_IS_ZERO",
				      "recallWhen": "TRUE_POSITIVE_PLUS_FALSE_NEGATIVE_IS_ZERO",
				      "f1When": "PRECISION_OR_RECALL_IS_UNDEFINED",
				      "counts": "ALWAYS_PRESERVED"
				    }
				  },
				  "metadata": {"method": "EXPECTED_FIELD_RECOVERY"},
				  "limits": {
				    "maximumInputBytes": 1048576,
				    "maximumQueries": 50,
				    "maximumCandidatesPerQuery": 40
				  },
				  "defaultEnablementGates": []
				}
				""";
	}
}
