package com.openscholar.search.internal.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.List;

import com.openscholar.TestcontainersConfiguration;
import com.openscholar.paper.DocumentType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.core.io.ClassPathResource;
import tools.jackson.databind.ObjectMapper;

class RelatedTopicReuseScalePolicyContractTests {

	private final ObjectMapper objectMapper = new ObjectMapper();

	@Test
	void scaleRunnerDeclaresExactlyOneFailClosedOptInEnvironmentGate() {
		EnabledIfEnvironmentVariable[] gates =
				OwnerScopedRelatedTopicReuseScaleEvaluationTests.class
						.getDeclaredAnnotationsByType(EnabledIfEnvironmentVariable.class);

		assertThat(gates).hasSize(1);
		assertThat(gates[0].named())
				.isEqualTo(RelatedTopicReuseScalePolicy.ENVIRONMENT_GATE);
		assertThat(gates[0].matches()).isEqualTo("true");
	}

	@Test
	void frozenPolicyBindsTheSubjectRuntimeCorpusWorkloadsAndDiagnosticBoundary()
			throws Exception {
		var bound = RelatedTopicReuseScalePolicy.loadFrozen(objectMapper);
		var policy = bound.policy();

		assertThat(bound.sha256()).isEqualTo(RelatedTopicReuseScalePolicy.POLICY_SHA256);
		assertThat(policy.schemaVersion()).isEqualTo(1);
		assertThat(policy.policyId()).isEqualTo(RelatedTopicReuseScalePolicy.POLICY_ID);
		assertThat(policy.status())
				.isEqualTo(RelatedTopicReuseScalePolicy.Status.DIAGNOSTIC_ONLY);
		assertThat(policy.subjectPolicyId())
				.isEqualTo(RelatedTopicReuseEvaluationPolicy.POLICY_ID);
		assertThat(policy.subjectPolicySha256())
				.isEqualTo(RelatedTopicReuseEvaluationPolicy.POLICY_SHA256);
		var subjectPolicy = RelatedTopicReuseEvaluationPolicy.loadFrozen(objectMapper);
		subjectPolicy.validateReference(
				policy.subjectPolicyId(), policy.subjectPolicySha256());
		assertThat(subjectPolicy.policy().candidate().seedEligibilityFeatures())
				.containsExactly(
						"TITLE_EXACT", "TITLE_PREFIX", "TITLE_CONTAINS", "POSTGRES_FULL_TEXT");
		assertThat(policy.environmentGate())
				.isEqualTo(RelatedTopicReuseScalePolicy.ENVIRONMENT_GATE);
		assertThat(policy.databaseImage()).isEqualTo(TestcontainersConfiguration.POSTGRES_IMAGE);
		assertThat(policy.postgresMajorVersion()).isEqualTo(17);
		assertThat(policy.sourcePolicy())
				.isEqualTo("DETERMINISTIC_SYNTHETIC_METADATA_ONLY");

		assertThat(policy.corpus()).isEqualTo(new RelatedTopicReuseScalePolicy.Corpus(
				"related-topic-reuse-scale-corpus-v1",
				20260829,
				RelatedTopicReuseScaleFixture.generatorSqlSha256(),
				100_000,
				40_000,
				10_000,
				25_000,
				25_000));
		assertThat(policy.workloads()).containsExactly(
				new RelatedTopicReuseScalePolicy.Workload(
						"no-seed-owner-scope-control",
						RelatedTopicReuseScalePolicy.WorkloadKind.NO_SEED_SCOPE_CONTROL,
						"orbital lichen spectroscopy",
						null,
						null,
						List.of(),
						false,
						0,
						List.of(),
						0),
				new RelatedTopicReuseScalePolicy.Workload(
						"one-seed-sparse-feedback",
						RelatedTopicReuseScalePolicy.WorkloadKind.ONE_SEED_SPARSE,
						"coastal erosion drone mapping",
						null,
						null,
						List.of(),
						false,
						0,
						List.of(),
						1),
				new RelatedTopicReuseScalePolicy.Workload(
						"two-seed-broad-feedback",
						RelatedTopicReuseScalePolicy.WorkloadKind.TWO_SEED_BROAD,
						"river microplastic community sensors",
						null,
						null,
						List.of(),
						false,
						0,
						List.of(),
						2),
				new RelatedTopicReuseScalePolicy.Workload(
						"fully-filtered-selective-feedback",
						RelatedTopicReuseScalePolicy.WorkloadKind.FULLY_FILTERED_SELECTIVE,
						"community wildfire smoke sensors",
						2022,
						2026,
						List.of(DocumentType.THESIS),
						true,
						5,
						List.of("en"),
						1));

		assertThat(policy.measurement()).isEqualTo(
				new RelatedTopicReuseScalePolicy.Measurement(
						RelatedTopicRankFusion.MAXIMUM_BASELINE_CANDIDATES,
						10,
						2,
						30,
						1,
						"WARM",
						"SYSTEM_NANO_TIME",
						"ROTATING_CONTROL_FEEDBACK_FUSION",
						"NEAREST_RANK",
						List.of(0.5d, 0.95d, 0.99d),
						1,
						"STDOUT_JSON_DIAGNOSTIC"));
		assertThat(policy.structuralGates()).isEqualTo(
				new RelatedTopicReuseScalePolicy.StructuralGates(
						true, true, true, true, true, 0, 0, 0, 0));
		assertThat(policy.interpretation()).isEqualTo(
				new RelatedTopicReuseScalePolicy.Interpretation(
						"REFERENCE_SHAPED_DIAGNOSTIC",
						"RECORD_ONLY",
						true,
						false));
	}

	@Test
	void strictParserRejectsDuplicateUnknownTrailingWrongTypedAndOversizedInput()
			throws Exception {
		String original = policyText();
		String duplicate = original.replace(
				"  \"policyId\": \"related-topic-reuse-scale-policy-v1\",",
				"  \"policyId\": \"related-topic-reuse-scale-policy-v1\",\n"
						+ "  \"policyId\": \"related-topic-reuse-scale-policy-v1\",");
		String unknownRoot = original.replace(
				"  \"schemaVersion\": 1,",
				"  \"schemaVersion\": 1,\n  \"unexpected\": true,");
		String unknownNested = original.replace(
				"    \"generatorSeed\": 20260829,",
				"    \"generatorSeed\": 20260829,\n    \"unexpected\": true,");
		String wrongInteger = original.replace(
				"    \"totalPaperCount\": 100000,",
				"    \"totalPaperCount\": \"100000\",");
		String wrongBoolean = original.replace(
				"    \"requireStableControl\": true,",
				"    \"requireStableControl\": \"true\",");

		assertThatThrownBy(() -> parse(duplicate)).isInstanceOf(Exception.class);
		assertThatThrownBy(() -> parse(unknownRoot))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("exactly");
		assertThatThrownBy(() -> parse(unknownNested))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("$.corpus")
				.hasMessageContaining("exactly");
		assertThatThrownBy(() -> parse(original + "\n{}"))
				.isInstanceOf(Exception.class);
		assertThatThrownBy(() -> parse(wrongInteger))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("$.corpus.totalPaperCount")
				.hasMessageContaining("integer");
		assertThatThrownBy(() -> parse(wrongBoolean))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("$.structuralGates.requireStableControl")
				.hasMessageContaining("boolean");
		assertThatThrownBy(() -> parseBytes(new byte[(64 * 1024) + 1]))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("65536 bytes");
		assertThatThrownBy(() -> parseBytes(new byte[0]))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("1 through 65536 bytes");
	}

	@Test
	void frozenReferenceRejectsRawWhitespaceDriftEvenWhenSemanticsAreUnchanged()
			throws Exception {
		String original = policyText();
		String rawDrift = original.replace(
				"{\n  \"schemaVersion\"", "{\n   \"schemaVersion\"");

		var rebound = parse(rawDrift);

		assertThat(rebound.policy())
				.isEqualTo(RelatedTopicReuseScalePolicy.loadFrozen(objectMapper).policy());
		assertThat(rebound.sha256()).isNotEqualTo(RelatedTopicReuseScalePolicy.POLICY_SHA256);
		assertThatThrownBy(() -> rebound.validateReference(
				RelatedTopicReuseScalePolicy.POLICY_ID,
				RelatedTopicReuseScalePolicy.POLICY_SHA256))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("does not match");
	}

	@Test
	void semanticDriftCannotRelaxScaleScopeMeasurementOrInterpretation() throws Exception {
		String original = policyText();

		assertSemanticDrift(
				original.replace(
						RelatedTopicReuseEvaluationPolicy.POLICY_SHA256,
						"0".repeat(64)),
				"identity");
		assertSemanticDrift(
				original.replace(
						"    \"targetSearchVisibleCount\": 40000,",
						"    \"targetSearchVisibleCount\": 40001,"),
				"corpus drifted");
		assertSemanticDrift(
				original.replace(
						RelatedTopicReuseScaleFixture.generatorSqlSha256(),
						"0".repeat(64)),
				"corpus drifted");
		assertSemanticDrift(
				original.replaceFirst(
						"\"expectedSeedCount\": 0", "\"expectedSeedCount\": 1"),
				"workloads drifted");
		assertSemanticDrift(
				original.replace(
						"      \"yearFrom\": 2022,", "      \"yearFrom\": 2021,"),
				"workloads drifted");
		assertSemanticDrift(
				original.replace(
						"    \"concurrency\": 1,", "    \"concurrency\": 2,"),
				"measurement contract drifted");
		assertSemanticDrift(
				original.replace(
						"    \"maximumOwnerScopeLeakCount\": 0,",
						"    \"maximumOwnerScopeLeakCount\": 1,"),
				"gates must remain fail-closed");
		assertSemanticDrift(
				original.replace(
						"    \"activationEvidence\": false",
						"    \"activationEvidence\": true"),
				"interpretation drifted");
	}

	private void assertSemanticDrift(String json, String message) {
		assertThatThrownBy(() -> parse(json))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining(message);
	}

	private RelatedTopicReuseScalePolicy.BoundPolicy parse(String json) throws Exception {
		return parseBytes(json.getBytes(StandardCharsets.UTF_8));
	}

	private RelatedTopicReuseScalePolicy.BoundPolicy parseBytes(byte[] bytes) throws Exception {
		return RelatedTopicReuseScalePolicy.parseBound(objectMapper, bytes);
	}

	private static String policyText() throws Exception {
		return new ClassPathResource(RelatedTopicReuseScalePolicy.RESOURCE_PATH)
				.getContentAsString(StandardCharsets.UTF_8);
	}
}
