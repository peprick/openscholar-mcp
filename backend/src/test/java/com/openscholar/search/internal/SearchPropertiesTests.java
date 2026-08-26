package com.openscholar.search.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.time.Duration;

import org.junit.jupiter.api.Test;

class SearchPropertiesTests {

	@Test
	void defaultsCoordinationWaitTimeoutToTwelveSeconds() {
		assertThat(new SearchProperties().getCoordinationWaitTimeout())
				.isEqualTo(Duration.ofSeconds(12));
	}

	@Test
	void acceptsACustomCoordinationWaitTimeout() {
		SearchProperties properties = new SearchProperties();

		properties.setCoordinationWaitTimeout(Duration.ofMillis(250));

		assertThat(properties.getCoordinationWaitTimeout()).isEqualTo(Duration.ofMillis(250));
	}

	@Test
	void defaultsExecutionTimeoutToEighteenSeconds() {
		assertThat(new SearchProperties().getExecutionTimeout())
				.isEqualTo(Duration.ofSeconds(18));
	}

	@Test
	void acceptsACustomExecutionTimeout() {
		SearchProperties properties = new SearchProperties();

		properties.setExecutionTimeout(Duration.ofMillis(750));

		assertThat(properties.getExecutionTimeout()).isEqualTo(Duration.ofMillis(750));
	}

	@Test
	void rejectsCoordinationWaitTimeoutsBelowOneMillisecond() {
		SearchProperties properties = new SearchProperties();

		assertThatIllegalArgumentException()
				.isThrownBy(() -> properties.setCoordinationWaitTimeout(null));
		assertThatIllegalArgumentException()
				.isThrownBy(() -> properties.setCoordinationWaitTimeout(Duration.ZERO));
		assertThatIllegalArgumentException()
				.isThrownBy(() -> properties.setCoordinationWaitTimeout(Duration.ofMillis(-1)));
		assertThatIllegalArgumentException()
				.isThrownBy(() -> properties.setCoordinationWaitTimeout(Duration.ofNanos(999_999)));
	}

	@Test
	void rejectsExecutionTimeoutsBelowOneMillisecond() {
		SearchProperties properties = new SearchProperties();

		assertThatIllegalArgumentException()
				.isThrownBy(() -> properties.setExecutionTimeout(null));
		assertThatIllegalArgumentException()
				.isThrownBy(() -> properties.setExecutionTimeout(Duration.ZERO));
		assertThatIllegalArgumentException()
				.isThrownBy(() -> properties.setExecutionTimeout(Duration.ofMillis(-1)));
		assertThatIllegalArgumentException()
				.isThrownBy(() -> properties.setExecutionTimeout(Duration.ofNanos(999_999)));
	}

	@Test
	void boundsProviderConcurrency() {
		SearchProperties properties = new SearchProperties();

		assertThat(properties.getProviderConcurrency()).isEqualTo(5);
		properties.setProviderConcurrency(8);
		assertThat(properties.getProviderConcurrency()).isEqualTo(8);
		assertThatIllegalArgumentException()
				.isThrownBy(() -> properties.setProviderConcurrency(0));
		assertThatIllegalArgumentException()
				.isThrownBy(() -> properties.setProviderConcurrency(17));
	}
}
