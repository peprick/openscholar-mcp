package com.openscholar.jobs.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import javax.sql.DataSource;

import com.openscholar.TestcontainersConfiguration;
import com.openscholar.jobs.EmbeddingBackfillCommand;
import com.openscholar.jobs.EmbeddingBackfillUseCase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
class EmbeddingBackfillInfrastructureTests {

	@Autowired
	private DataSource dataSource;

	@Autowired
	private EmbeddingBackfillUseCase backfillUseCase;

	@Autowired
	private PlatformTransactionManager transactionManager;

	@Test
	void serializesTheSameProfileOnDedicatedSessionsButAllowsDifferentProfiles() {
		PostgresEmbeddingBackfillLock lock = new PostgresEmbeddingBackfillLock(dataSource);
		EmbeddingBackfillLease firstProfile = lock.tryAcquire("profile-a-v1").orElseThrow();

		try (firstProfile) {
			assertThat(lock.tryAcquire("profile-a-v1")).isEmpty();
			EmbeddingBackfillLease otherProfile = lock.tryAcquire("profile-b-v1").orElseThrow();
			try (otherProfile) {
				assertThat(lock.tryAcquire("profile-b-v1")).isEmpty();
			}
		}

		EmbeddingBackfillLease reacquired = lock.tryAcquire("profile-a-v1").orElseThrow();
		try (reacquired) {
			assertThat(reacquired).isNotNull();
		}
	}

	@Test
	void refusesToStartBackfillInsideAnExistingTransaction() {
		TransactionTemplate transaction = new TransactionTemplate(transactionManager);

		transaction.executeWithoutResult(status -> assertThatThrownBy(() -> backfillUseCase.run(
				new EmbeddingBackfillCommand("unconfigured-profile-v1", null, 1, 1)))
				.isInstanceOf(IllegalTransactionStateException.class)
				.hasMessageContaining("Existing transaction"));
	}
}
