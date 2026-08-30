package com.openscholar.search.internal.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.Constructor;
import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.stream.Stream;

import com.openscholar.search.internal.persistence.RelatedTopicReuseHoldoutBundle.VerifiedFirstRunCommitment;
import com.openscholar.search.internal.persistence.RelatedTopicReuseHoldoutEvaluatorSeal.VerifiedEvaluatorSeal;
import com.openscholar.search.internal.persistence.RelatedTopicReuseHoldoutGitCollector.FreezeRecord;
import com.openscholar.search.internal.persistence.RelatedTopicReuseHoldoutGitCollector.VerifiedCleanCheckout;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class RelatedTopicReuseHoldoutFirstRunIdentityTests {

	private static final String BUNDLE_PROTOCOL_ID =
			"related-topic-reuse-holdout-bundle-v1";
	private static final String GOLDEN_RUN_KEY =
			"00b840377179192a0fb576345e3004db82b1347795371e71f58f0138df487b34";

	@Test
	void canonicalLengthPrefixedEncodingHasAPinnedGoldenRunKey() throws Exception {
		var identity = identity(baselineInputs());

		assertThat(RelatedTopicReuseHoldoutFirstRunIdentity.RUN_KEY_DOMAIN)
				.isEqualTo("openscholar.related-topic-reuse-holdout.first-run-key.v1");
		assertThat(RelatedTopicReuseHoldoutFirstRunIdentity.SCHEMA_VERSION).isOne();
		assertThat(identity.runKey()).isEqualTo(GOLDEN_RUN_KEY);
		assertThat(identity.runKeyBytes()).hasSize(32);
		assertThat(identity.finalityKey())
				.isEqualTo(new RelatedTopicReuseHoldoutFirstRunIdentity.FinalityKey(
						RelatedTopicReuseHoldoutPolicy.EVALUATION_PROTOCOL_ID,
						RelatedTopicReuseHoldoutPolicy.POLICY_ID));
	}

	@ParameterizedTest(name = "{0} alone changes the run key")
	@MethodSource("permittedRunKeyFieldChanges")
	void everyPermittedCommitmentAndCheckoutFieldIsIndividuallyKeySensitive(
			String field, RunInputs changedInputs) throws Exception {
		var baseline = identity(baselineInputs());
		var changed = identity(changedInputs);

		assertThat(differingFields(baselineInputs(), changedInputs)).containsExactly(field);
		assertThat(changed.runKey()).isNotEqualTo(baseline.runKey());
		assertThat(changed.finalityKey()).isEqualTo(baseline.finalityKey());
	}

	@ParameterizedTest(name = "{0} is frozen and rejected by first-run identity")
	@MethodSource("frozenCommitmentFieldChanges")
	void frozenCommitmentFieldsCannotBeVariedToMintAnotherRunKey(
			String field, RunInputs changedInputs) throws Exception {
		assertThat(differingFields(baselineInputs(), changedInputs)).containsExactly(field);
		assertThatThrownBy(() -> identity(changedInputs))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessage("first-run inputs do not match the frozen policy");
	}

	@ParameterizedTest(name = "{0} is frozen and rejected by checkout verification")
	@MethodSource("frozenCheckoutFieldChanges")
	void frozenCheckoutFieldsCannotLegallyReachRunKeyCalculation(
			String field, RunInputs changedInputs) {
		assertThat(differingFields(baselineInputs(), changedInputs)).containsExactly(field);
		assertThatThrownBy(() -> checkout(changedInputs))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessage("invalid external holdout freeze record");
	}

	private static Stream<Arguments> permittedRunKeyFieldChanges() {
		return Stream.of(
				variant("bundleId", builder ->
						builder.bundleId = "external-holdout-golden-v2"),
				variant("corpusId", builder ->
						builder.corpusId = "external-corpus-golden-v2"),
				variant("manifestSha256", builder ->
						builder.manifestSha256 = "12".repeat(32)),
				variant("manifestBytes", builder -> builder.manifestBytes++),
				variant("corpusSha256", builder ->
						builder.corpusSha256 = "23".repeat(32)),
				variant("corpusBytes", builder -> builder.corpusBytes++),
				variant("judgmentsSha256", builder ->
						builder.judgmentsSha256 = "34".repeat(32)),
				variant("judgmentsBytes", builder -> builder.judgmentsBytes++),
				variant("evaluatorRevision", builder ->
						builder.evaluatorRevision = "b1".repeat(20)),
				variant("evaluatorSourceSha256", builder ->
						builder.evaluatorSourceSha256 = "45".repeat(32)),
				variant("candidateSourceSha256", builder ->
						builder.candidateSourceSha256 = "56".repeat(32)));
	}

	private static Stream<Arguments> frozenCommitmentFieldChanges() {
		return Stream.of(
				variant("evaluationProtocolId", builder -> builder.evaluationProtocolId =
						"related-topic-reuse-holdout-evaluation-v2"),
				variant("policyId", builder -> builder.policyId =
						"related-topic-reuse-holdout-policy-v2"),
				variant("policySha256", builder ->
						builder.policySha256 = "4d".repeat(32)),
				variant("bundleProtocolId", builder -> builder.bundleProtocolId =
						"related-topic-reuse-holdout-bundle-v2"));
	}

	private static Stream<Arguments> frozenCheckoutFieldChanges() {
		return Stream.of(
				variant("freezeSchemaVersion", builder -> builder.freezeSchemaVersion = 2),
				variant("inventoryId", builder -> builder.inventoryId =
						"related-topic-reuse-holdout-source-inventory-v2"),
				variant("candidateRevision", builder ->
						builder.candidateRevision = "b2".repeat(20)));
	}

	private static Arguments variant(String field, Consumer<RunInputsBuilder> mutation) {
		RunInputsBuilder builder = baselineBuilder();
		mutation.accept(builder);
		return Arguments.of(field, builder.build());
	}

	private static RelatedTopicReuseHoldoutFirstRunIdentity identity(RunInputs inputs)
			throws Exception {
		return RelatedTopicReuseHoldoutFirstRunIdentity.fromVerified(
				commitment(inputs), checkout(inputs));
	}

	private static VerifiedFirstRunCommitment commitment(RunInputs inputs)
			throws Exception {
		Constructor<VerifiedFirstRunCommitment> constructor =
				VerifiedFirstRunCommitment.class.getDeclaredConstructor(
						Object.class,
						String.class,
						String.class,
						String.class,
						String.class,
						String.class,
						String.class,
						String.class,
						long.class,
						String.class,
						long.class,
						String.class,
						long.class);
		constructor.setAccessible(true);
		return constructor.newInstance(
				new Object(),
				inputs.evaluationProtocolId(),
				inputs.bundleProtocolId(),
				inputs.bundleId(),
				inputs.corpusId(),
				inputs.policyId(),
				inputs.policySha256(),
				inputs.manifestSha256(),
				inputs.manifestBytes(),
				inputs.corpusSha256(),
				inputs.corpusBytes(),
				inputs.judgmentsSha256(),
				inputs.judgmentsBytes());
	}

	private static VerifiedCleanCheckout checkout(RunInputs inputs) throws Exception {
		FreezeRecord freeze = new FreezeRecord(
				inputs.freezeSchemaVersion(),
				inputs.inventoryId(),
				inputs.evaluatorRevision(),
				inputs.evaluatorSourceSha256(),
				inputs.candidateRevision(),
				inputs.candidateSourceSha256());

		// Reflection keeps revision and digest variants independent. Production code can
		// obtain both opaque capabilities only from their full verification boundaries.
		Constructor<VerifiedEvaluatorSeal> sealConstructor =
				VerifiedEvaluatorSeal.class.getDeclaredConstructor(
						String.class,
						String.class,
						String.class,
						String.class,
						List.class);
		sealConstructor.setAccessible(true);
		VerifiedEvaluatorSeal seal = sealConstructor.newInstance(
				inputs.evaluatorRevision(),
				inputs.evaluatorSourceSha256(),
				inputs.candidateRevision(),
				inputs.candidateSourceSha256(),
				List.of());

		Constructor<VerifiedCleanCheckout> checkoutConstructor =
				VerifiedCleanCheckout.class.getDeclaredConstructor(
						FreezeRecord.class, VerifiedEvaluatorSeal.class);
		checkoutConstructor.setAccessible(true);
		return checkoutConstructor.newInstance(freeze, seal);
	}

	private static RunInputs baselineInputs() {
		return baselineBuilder().build();
	}

	private static List<String> differingFields(RunInputs baseline, RunInputs changed) {
		return Arrays.stream(RunInputs.class.getRecordComponents())
				.filter(component -> !Objects.equals(
						componentValue(component, baseline),
						componentValue(component, changed)))
				.map(RecordComponent::getName)
				.toList();
	}

	private static Object componentValue(RecordComponent component, RunInputs inputs) {
		try {
			return component.getAccessor().invoke(inputs);
		}
		catch (ReflectiveOperationException exception) {
			throw new AssertionError("could not inspect test input", exception);
		}
	}

	private static RunInputsBuilder baselineBuilder() {
		RunInputsBuilder builder = new RunInputsBuilder();
		builder.evaluationProtocolId =
				RelatedTopicReuseHoldoutPolicy.EVALUATION_PROTOCOL_ID;
		builder.bundleProtocolId = BUNDLE_PROTOCOL_ID;
		builder.bundleId = "external-holdout-golden-v1";
		builder.corpusId = "external-corpus-golden-v1";
		builder.policyId = RelatedTopicReuseHoldoutPolicy.POLICY_ID;
		builder.policySha256 = RelatedTopicReuseHoldoutPolicy.POLICY_SHA256;
		builder.manifestSha256 = "11".repeat(32);
		builder.manifestBytes = 631L;
		builder.corpusSha256 = "22".repeat(32);
		builder.corpusBytes = 4_387L;
		builder.judgmentsSha256 = "33".repeat(32);
		builder.judgmentsBytes = 1_211L;
		builder.freezeSchemaVersion =
				RelatedTopicReuseHoldoutGitCollector.FREEZE_SCHEMA_VERSION;
		builder.inventoryId = RelatedTopicReuseHoldoutGitCollector.INVENTORY_ID;
		builder.evaluatorRevision = "a1".repeat(20);
		builder.evaluatorSourceSha256 = "44".repeat(32);
		builder.candidateRevision =
				RelatedTopicReuseHoldoutPolicy.CANDIDATE_FREEZE_REVISION;
		builder.candidateSourceSha256 = "55".repeat(32);
		return builder;
	}

	private record RunInputs(
			String evaluationProtocolId,
			String bundleProtocolId,
			String bundleId,
			String corpusId,
			String policyId,
			String policySha256,
			String manifestSha256,
			long manifestBytes,
			String corpusSha256,
			long corpusBytes,
			String judgmentsSha256,
			long judgmentsBytes,
			int freezeSchemaVersion,
			String inventoryId,
			String evaluatorRevision,
			String evaluatorSourceSha256,
			String candidateRevision,
			String candidateSourceSha256) {
	}

	private static final class RunInputsBuilder {

		private String evaluationProtocolId;
		private String bundleProtocolId;
		private String bundleId;
		private String corpusId;
		private String policyId;
		private String policySha256;
		private String manifestSha256;
		private long manifestBytes;
		private String corpusSha256;
		private long corpusBytes;
		private String judgmentsSha256;
		private long judgmentsBytes;
		private int freezeSchemaVersion;
		private String inventoryId;
		private String evaluatorRevision;
		private String evaluatorSourceSha256;
		private String candidateRevision;
		private String candidateSourceSha256;

		private RunInputs build() {
			return new RunInputs(
					evaluationProtocolId,
					bundleProtocolId,
					bundleId,
					corpusId,
					policyId,
					policySha256,
					manifestSha256,
					manifestBytes,
					corpusSha256,
					corpusBytes,
					judgmentsSha256,
					judgmentsBytes,
					freezeSchemaVersion,
					inventoryId,
					evaluatorRevision,
					evaluatorSourceSha256,
					candidateRevision,
					candidateSourceSha256);
		}
	}
}
