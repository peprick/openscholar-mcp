package com.openscholar.privacy.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import com.openscholar.privacy.PrivacyExport;
import com.openscholar.privacy.PrivacyExportTooLargeException;
import com.openscholar.privacy.PrivacyExportTarget;
import org.junit.jupiter.api.Test;
import org.mockito.Answers;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

class PrivacyExportWriterTests {

	private static final UUID USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

	private static final UUID FIRST_COLLECTION_ID =
			UUID.fromString("00000000-0000-0000-0000-000000000010");

	private static final UUID SECOND_COLLECTION_ID =
			UUID.fromString("00000000-0000-0000-0000-000000000020");

	private static final Instant GENERATED_AT = Instant.parse("2026-08-25T10:15:30Z");

	private static final ObjectMapper OBJECT_MAPPER = JsonMapper.builder().build();

	@Test
	void declaresTheExactUtf8LengthPreservesOrderAndDoesNotCloseTheCallerStream() throws Exception {
		List<PrivacyExport.PrivacyCollection> collections = List.of(
				new PrivacyExport.PrivacyCollection(
						FIRST_COLLECTION_ID,
						"Méthodes",
						null,
						Instant.parse("2026-08-20T09:00:00Z"),
						Instant.parse("2026-08-21T10:00:00Z")),
				new PrivacyExport.PrivacyCollection(
						SECOND_COLLECTION_ID,
						"Review",
						"Second",
						Instant.parse("2026-08-22T11:00:00Z"),
						Instant.parse("2026-08-23T12:00:00Z")));
		StoreFixture fixture = storeFixture(
				new JdbcPrivacyExportStore.UserData(null, null),
				new JdbcPrivacyExportStore.Counts(0, 2, 0),
				collections);

		AtomicLong declaredLength = new AtomicLong(-1);
		AtomicInteger openCalls = new AtomicInteger();
		TrackingOutputStream output = new TrackingOutputStream();
		PrivacyExportTarget target = contentLength -> {
			openCalls.incrementAndGet();
			declaredLength.set(contentLength);
			return output;
		};

		writer(fixture.store()).write(USER_ID, target);

		String expected = """
				{"userId":"00000000-0000-0000-0000-000000000001","displayName":null,"accountCreatedAt":null,"generatedAt":"2026-08-25T10:15:30Z","searches":[],"collections":[{"collectionId":"00000000-0000-0000-0000-000000000010","name":"Méthodes","description":null,"createdAt":"2026-08-20T09:00:00Z","updatedAt":"2026-08-21T10:00:00Z"},{"collectionId":"00000000-0000-0000-0000-000000000020","name":"Review","description":"Second","createdAt":"2026-08-22T11:00:00Z","updatedAt":"2026-08-23T12:00:00Z"}],"savedPapers":[]}""";
		assertThat(output.toByteArray()).isEqualTo(expected.getBytes(java.nio.charset.StandardCharsets.UTF_8));
		assertThat(declaredLength).hasValue(output.size());
		assertThat(openCalls).hasValue(1);
		assertThat(output.closed).isFalse();
		assertThat(output.flushCalls).isEqualTo(2);
		assertThat(fixture.searchPasses()).hasValue(2);
		assertThat(fixture.collectionPasses()).hasValue(2);
		assertThat(fixture.savedPaperPasses()).hasValue(2);
	}

	@Test
	void rejectsAnOversizedRecordCountBeforeOpeningOrVisitingTheTarget() {
		StoreFixture fixture = storeFixture(
				new JdbcPrivacyExportStore.UserData("User", GENERATED_AT),
				new JdbcPrivacyExportStore.Counts(PrivacyExportLimits.PER_BRANCH_PREFLIGHT_LIMIT, 0, 0),
				List.of());
		PrivacyExportTarget target = contentLength -> {
			throw new AssertionError("The export target must not open before record-limit validation");
		};

		assertThatThrownBy(() -> writer(fixture.store()).write(USER_ID, target))
				.isInstanceOf(PrivacyExportTooLargeException.class)
				.hasMessage("The personal-data export exceeds the supported record or byte limit");

		assertThat(fixture.searchPasses()).hasValue(0);
		assertThat(fixture.collectionPasses()).hasValue(0);
		assertThat(fixture.savedPaperPasses()).hasValue(0);
	}

	@Test
	void rejectsAnOversizedUtf8PayloadBeforeOpeningTheTarget() {
		String oversizedName = "é".repeat(Math.toIntExact(PrivacyExportLimits.MAX_SERIALIZED_BYTES / 2 + 1));
		StoreFixture fixture = storeFixture(
				new JdbcPrivacyExportStore.UserData("User", GENERATED_AT),
				new JdbcPrivacyExportStore.Counts(0, 1, 0),
				List.of(new PrivacyExport.PrivacyCollection(
						FIRST_COLLECTION_ID, oversizedName, null, null, null)));
		AtomicInteger openCalls = new AtomicInteger();
		PrivacyExportTarget target = contentLength -> {
			openCalls.incrementAndGet();
			return new ByteArrayOutputStream();
		};

		assertThatThrownBy(() -> writer(fixture.store()).write(USER_ID, target))
				.isInstanceOf(PrivacyExportTooLargeException.class)
				.hasMessage("The personal-data export exceeds the supported record or byte limit");

		assertThat(openCalls).hasValue(0);
	}

	private static PrivacyExportWriter writer(JdbcPrivacyExportStore store) {
		return new PrivacyExportWriter(
				store,
				Clock.fixed(GENERATED_AT, ZoneOffset.UTC),
				OBJECT_MAPPER);
	}

	private static StoreFixture storeFixture(
			JdbcPrivacyExportStore.UserData user,
			JdbcPrivacyExportStore.Counts counts,
			List<PrivacyExport.PrivacyCollection> collections) {
		JdbcClient jdbcClient = mock(JdbcClient.class);
		AtomicInteger searchPasses = new AtomicInteger();
		AtomicInteger collectionPasses = new AtomicInteger();
		AtomicInteger savedPaperPasses = new AtomicInteger();
		doAnswer(invocation -> statement(
				invocation.getArgument(0),
				user,
				counts,
				collections,
				searchPasses,
				collectionPasses,
				savedPaperPasses))
				.when(jdbcClient).sql(any(String.class));
		return new StoreFixture(
				new JdbcPrivacyExportStore(jdbcClient),
				searchPasses,
				collectionPasses,
				savedPaperPasses);
	}

	@SuppressWarnings({"rawtypes", "unchecked"})
	private static JdbcClient.StatementSpec statement(
			String sql,
			JdbcPrivacyExportStore.UserData user,
			JdbcPrivacyExportStore.Counts counts,
			List<PrivacyExport.PrivacyCollection> collections,
			AtomicInteger searchPasses,
			AtomicInteger collectionPasses,
			AtomicInteger savedPaperPasses) {
		JdbcClient.StatementSpec statement = mock(JdbcClient.StatementSpec.class, Answers.RETURNS_SELF);
		if (sql.contains("FROM app_user")) {
			JdbcClient.MappedQuerySpec mapped = mock(JdbcClient.MappedQuerySpec.class);
			doAnswer(invocation -> user).when(mapped).single();
			doAnswer(invocation -> mapped).when(statement).query(any(RowMapper.class));
			return statement;
		}
		if (sql.contains("WITH bounded_searches")) {
			JdbcClient.MappedQuerySpec mapped = mock(JdbcClient.MappedQuerySpec.class);
			doAnswer(invocation -> counts).when(mapped).single();
			doAnswer(invocation -> mapped).when(statement).query(any(RowMapper.class));
			return statement;
		}
		if (sql.contains("FROM search_snapshot")) {
			doAnswer(invocation -> {
				searchPasses.incrementAndGet();
				return null;
			}).when(statement).query(any(RowCallbackHandler.class));
			return statement;
		}
		if (sql.contains("FROM collection_paper saved")) {
			doAnswer(invocation -> {
				savedPaperPasses.incrementAndGet();
				return null;
			}).when(statement).query(any(RowCallbackHandler.class));
			return statement;
		}
		if (sql.contains("FROM library_collection")) {
			doAnswer(invocation -> {
				collectionPasses.incrementAndGet();
				RowCallbackHandler visitor = invocation.getArgument(0);
				for (PrivacyExport.PrivacyCollection collection : collections) {
					visitor.processRow(collectionRow(collection));
				}
				return null;
			}).when(statement).query(any(RowCallbackHandler.class));
			return statement;
		}
		throw new AssertionError("Unexpected privacy export SQL: " + sql);
	}

	private static ResultSet collectionRow(PrivacyExport.PrivacyCollection collection) throws Exception {
		ResultSet resultSet = mock(ResultSet.class);
		doAnswer(invocation -> collection.collectionId())
				.when(resultSet).getObject("id", UUID.class);
		doAnswer(invocation -> collection.name()).when(resultSet).getString("name");
		doAnswer(invocation -> collection.description()).when(resultSet).getString("description");
		doAnswer(invocation -> timestamp(collection.createdAt())).when(resultSet).getTimestamp("created_at");
		doAnswer(invocation -> timestamp(collection.updatedAt())).when(resultSet).getTimestamp("updated_at");
		return resultSet;
	}

	private static Timestamp timestamp(Instant instant) {
		return instant == null ? null : Timestamp.from(instant);
	}

	private static final class TrackingOutputStream extends ByteArrayOutputStream {

		private boolean closed;
		private int flushCalls;

		@Override
		public void flush() throws IOException {
			flushCalls++;
			super.flush();
		}

		@Override
		public void close() throws IOException {
			closed = true;
			super.close();
		}
	}

	private record StoreFixture(
			JdbcPrivacyExportStore store,
			AtomicInteger searchPasses,
			AtomicInteger collectionPasses,
			AtomicInteger savedPaperPasses) {
	}
}
