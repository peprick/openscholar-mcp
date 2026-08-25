package com.openscholar.privacy.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import com.openscholar.TestcontainersConfiguration;
import com.openscholar.privacy.PrivacyUseCase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
class PrivacyStreamingSnapshotIntegrationTests {

	private static final UUID LOCAL_USER_ID =
			UUID.fromString("00000000-0000-0000-0000-000000000001");

	@Autowired
	private PrivacyUseCase privacy;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Autowired
	private PlatformTransactionManager transactionManager;

	@Autowired
	private ObjectMapper objectMapper;

	@BeforeEach
	@AfterEach
	void removeCollections() {
		jdbcTemplate.update("DELETE FROM library_collection WHERE owner_id = ?", LOCAL_USER_ID);
	}

	@Test
	void bothPassesReadOneRepeatableDatabaseSnapshot() throws Exception {
		String insertedAfterPreflight = "not visible in active export " + UUID.randomUUID();
		ByteArrayOutputStream body = new ByteArrayOutputStream();
		AtomicLong declaredLength = new AtomicLong(-1);

		privacy.exportPersonalData(contentLength -> {
			declaredLength.set(contentLength);
			TransactionTemplate concurrentWrite = new TransactionTemplate(transactionManager);
			concurrentWrite.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
			concurrentWrite.executeWithoutResult(status -> insertCollection(insertedAfterPreflight));
			return body;
		});

		assertThat(declaredLength).hasValue(body.size());
		JsonNode export = objectMapper.readTree(body.toByteArray());
		assertThat(export.required("collections")).hasSize(0);
		assertThat(export.toString()).doesNotContain(insertedAfterPreflight);
		assertThat(jdbcTemplate.queryForObject(
				"SELECT count(*) FROM library_collection WHERE owner_id = ?",
				Integer.class,
				LOCAL_USER_ID)).isOne();
	}

	@Test
	void outputFailureEndsTheTransactionAndLeavesTheDatabaseUsable() throws Exception {
		FailingOutputStream disconnectedClient = new FailingOutputStream();

		assertThatThrownBy(() -> privacy.exportPersonalData(contentLength -> disconnectedClient))
				.isInstanceOf(RuntimeException.class)
				.hasRootCauseInstanceOf(IOException.class);

		assertThat(disconnectedClient.closed).isFalse();
		assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
		assertThat(jdbcTemplate.queryForObject("SELECT 1", Integer.class)).isOne();

		ByteArrayOutputStream recoveredBody = new ByteArrayOutputStream();
		AtomicLong recoveredLength = new AtomicLong(-1);
		privacy.exportPersonalData(contentLength -> {
			recoveredLength.set(contentLength);
			return recoveredBody;
		});
		assertThat(recoveredLength).hasValue(recoveredBody.size());
		assertThat(recoveredBody.size()).isPositive();
	}

	private void insertCollection(String name) {
		OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
		jdbcTemplate.update("""
				INSERT INTO library_collection (
				    id, owner_id, name, description, version, created_at, updated_at
				)
				VALUES (?, ?, ?, NULL, 0, ?, ?)
				""", UUID.randomUUID(), LOCAL_USER_ID, name, now, now);
	}

	private static final class FailingOutputStream extends OutputStream {

		private boolean closed;

		@Override
		public void write(int value) throws IOException {
			throw new IOException("simulated disconnected download");
		}

		@Override
		public void write(byte[] bytes, int offset, int length) throws IOException {
			throw new IOException("simulated disconnected download");
		}

		@Override
		public void close() {
			closed = true;
		}
	}
}
