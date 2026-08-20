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
}
