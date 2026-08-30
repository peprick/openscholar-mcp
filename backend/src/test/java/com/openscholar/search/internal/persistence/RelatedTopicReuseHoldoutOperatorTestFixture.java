package com.openscholar.search.internal.persistence;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;

import com.openscholar.search.internal.persistence.RelatedTopicReuseHoldoutGitCollector.ProcessGitRunner;
import tools.jackson.databind.ObjectMapper;

/** Reflection-only seam for deterministic operator failure mechanics. */
final class RelatedTopicReuseHoldoutOperatorTestFixture {

	private RelatedTopicReuseHoldoutOperatorTestFixture() {
	}

	static RelatedTopicReuseHoldoutOperatorWorkflow workflow(
			ObjectMapper objectMapper,
			RelatedTopicReuseHoldoutPostgresFirstRunLedger ledger,
			RelatedTopicReuseHoldoutBundle.LabelFreeRankingPhase rankingPhase,
			ProcessGitRunner gitRunner) {
		try {
			Constructor<RelatedTopicReuseHoldoutOperatorWorkflow> constructor =
					RelatedTopicReuseHoldoutOperatorWorkflow.class.getDeclaredConstructor(
							ObjectMapper.class,
							RelatedTopicReuseHoldoutPostgresFirstRunLedger.class,
							RelatedTopicReuseHoldoutBundle.LabelFreeRankingPhase.class,
							ProcessGitRunner.class);
			constructor.setAccessible(true);
			return constructor.newInstance(
					objectMapper, ledger, rankingPhase, gitRunner);
		}
		catch (InvocationTargetException exception) {
			if (exception.getCause() instanceof RuntimeException runtimeException) {
				throw runtimeException;
			}
			throw new AssertionError(
					"unable to create synthetic operator workflow", exception.getCause());
		}
		catch (NoSuchMethodException
				| InstantiationException
				| IllegalAccessException exception) {
			throw new AssertionError(
					"unable to create synthetic operator workflow", exception);
		}
	}
}
