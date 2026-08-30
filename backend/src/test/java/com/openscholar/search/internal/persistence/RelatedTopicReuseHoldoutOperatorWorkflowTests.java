package com.openscholar.search.internal.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

import com.openscholar.search.internal.persistence.RelatedTopicReuseHoldoutGitCollector.FreezeRecord;
import org.junit.jupiter.api.Test;

class RelatedTopicReuseHoldoutOperatorWorkflowTests {

	@Test
	void exposesOneRawInputExecutionBoundaryWithoutCapabilityInjection() {
		List<Method> accepting = Arrays.stream(
				RelatedTopicReuseHoldoutOperatorWorkflow.class.getDeclaredMethods())
				.filter(method -> method.getName().equals("execute"))
				.toList();

		assertThat(accepting).singleElement().satisfies(method -> {
			assertThat(method.getParameterTypes()).containsExactly(
					Path.class, Path.class, FreezeRecord.class);
			assertThat(method.getReturnType()).isEqualTo(
					RelatedTopicReuseHoldoutOperatorWorkflow.PendingPublication.class);
		});
		List<? extends Constructor<?>> constructors = Arrays.stream(
				RelatedTopicReuseHoldoutOperatorWorkflow.class.getDeclaredConstructors())
				.map(constructor -> (Constructor<?>) constructor)
				.toList();
		assertThat(constructors)
				.filteredOn(constructor -> !Modifier.isPrivate(constructor.getModifiers()))
				.singleElement()
				.satisfies(constructor -> assertThat(constructor.getParameterTypes())
						.containsExactly(
								tools.jackson.databind.ObjectMapper.class,
								RelatedTopicReuseHoldoutPostgresFirstRunLedger.class,
								RelatedTopicReuseHoldoutPostgresRanker.class,
								RelatedTopicReuseHoldoutGitCollector.ProcessGitRunner.class));
		assertThat(constructors)
				.filteredOn(constructor -> Modifier.isPrivate(constructor.getModifiers()))
				.singleElement()
				.satisfies(constructor -> assertThat(constructor.getParameterTypes())
						.containsExactly(
								tools.jackson.databind.ObjectMapper.class,
								RelatedTopicReuseHoldoutPostgresFirstRunLedger.class,
								RelatedTopicReuseHoldoutBundle.LabelFreeRankingPhase.class,
								RelatedTopicReuseHoldoutGitCollector.ProcessGitRunner.class));
	}

	@Test
	void hasNoRetryResumeResetMutationOrPublicationSurface() {
		Set<String> forbiddenNames = Set.of(
				"retry", "resume", "reset", "delete", "update", "publish", "write");
		assertThat(Arrays.stream(
				RelatedTopicReuseHoldoutOperatorWorkflow.class.getDeclaredMethods())
				.map(Method::getName)
				.map(String::toLowerCase)
				.filter(name -> forbiddenNames.stream().anyMatch(name::contains))
				.toList()).isEmpty();
		assertThat(RelatedTopicReuseHoldoutOperatorWorkflow.class.getModifiers())
				.satisfies(modifiers -> assertThat(Modifier.isFinal(modifiers)).isTrue())
				.satisfies(modifiers -> assertThat(Modifier.isPublic(modifiers)).isFalse());
	}

	@Test
	void pendingPublicationIsOpaquePrivatelyConstructedAndNonAuthorizing() {
		Class<?> pending = RelatedTopicReuseHoldoutOperatorWorkflow.PendingPublication.class;
		assertThat(pending.getDeclaredConstructors())
				.singleElement()
				.satisfies(constructor -> {
					assertThat(Modifier.isPrivate(constructor.getModifiers())).isTrue();
					assertThat(constructor.getParameterTypes()).containsExactly(
							String.class,
							RelatedTopicReuseHoldoutScorer.VerifiedScoringOutcome.class,
							RelatedTopicReuseHoldoutEvidenceReport.class,
							RelatedTopicReuseHoldoutEvidenceReport.VerifiedArtifacts.class);
				});
		assertThat(Arrays.stream(pending.getDeclaredMethods())
				.filter(method -> Modifier.isPublic(method.getModifiers()))
				.toList()).isEmpty();
		assertThat(Arrays.stream(pending.getDeclaredMethods())
				.map(Method::getName)
				.toList()).contains(
						"readerFacing",
						"externalBundleAcceptanceAuthorized",
						"custodyReleaseAuthorized",
						"productActivationAuthorized");
	}
}
