package com.openscholar.search.internal;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.slf4j.MDC;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.SimpleAsyncTaskExecutor;
import org.springframework.core.task.TaskDecorator;
import org.springframework.core.task.support.CompositeTaskDecorator;
import org.springframework.core.task.support.ContextPropagatingTaskDecorator;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(SearchProperties.class)
class SearchConfiguration {

	static final String EXECUTION_EXECUTOR_BEAN = "searchExecutionExecutor";

	@Bean(name = EXECUTION_EXECUTOR_BEAN, destroyMethod = "close")
	SimpleAsyncTaskExecutor searchExecutionExecutor() {
		SimpleAsyncTaskExecutor executor = new SimpleAsyncTaskExecutor("openscholar-search-");
		executor.setVirtualThreads(true);
		executor.setTaskDecorator(new CompositeTaskDecorator(List.of(
				new ContextPropagatingTaskDecorator(),
				mdcTaskDecorator())));
		executor.setCancelRemainingTasksOnClose(true);
		executor.setTaskTerminationTimeout(Duration.ofSeconds(5).toMillis());
		return executor;
	}

	private static TaskDecorator mdcTaskDecorator() {
		return task -> {
			Map<String, String> callerContext = MDC.getCopyOfContextMap();
			return () -> {
				Map<String, String> previousContext = MDC.getCopyOfContextMap();
				try {
					setMdc(callerContext);
					task.run();
				}
				finally {
					setMdc(previousContext);
				}
			};
		};
	}

	private static void setMdc(Map<String, String> context) {
		if (context == null || context.isEmpty()) {
			MDC.clear();
		}
		else {
			MDC.setContextMap(context);
		}
	}
}
