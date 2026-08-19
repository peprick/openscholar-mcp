package com.openscholar.jobs.internal;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DelegatingDataSource;

class PostgresEmbeddingBackfillLockTests {

	@Test
	void rejectsASingleConnectionPoolBeforeHoldingItsOnlyConnection() {
		try (HikariDataSource dataSource = new HikariDataSource()) {
			dataSource.setMaximumPoolSize(1);

			assertThatThrownBy(() -> new PostgresEmbeddingBackfillLock(dataSource)
					.tryAcquire("paper-semantic-v1"))
					.isInstanceOf(IllegalStateException.class)
					.hasMessageContaining("at least two connections");
			assertThatThrownBy(() -> new PostgresEmbeddingBackfillLock(
					new DelegatingDataSource(dataSource)).tryAcquire("paper-semantic-v1"))
					.isInstanceOf(IllegalStateException.class)
					.hasMessageContaining("at least two connections");
		}
	}
}
