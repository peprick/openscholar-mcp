package com.openscholar.privacy.internal;

import java.io.IOException;
import java.io.OutputStream;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

import com.openscholar.privacy.PrivacyExportTarget;
import com.openscholar.privacy.PrivacyExportTooLargeException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JsonGenerator;
import tools.jackson.core.StreamWriteFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.ObjectWriter;
import tools.jackson.databind.SerializationFeature;

@Component
class PrivacyExportWriter {

	private final JdbcPrivacyExportStore store;
	private final Clock clock;
	private final ObjectMapper objectMapper;
	private final ObjectWriter valueWriter;

	PrivacyExportWriter(
			JdbcPrivacyExportStore store,
			Clock clock,
			ObjectMapper objectMapper) {
		this.store = store;
		this.clock = clock;
		this.objectMapper = objectMapper;
		this.valueWriter = objectMapper.writer()
				.without(SerializationFeature.FLUSH_AFTER_WRITE_VALUE);
	}

	@Transactional(
			readOnly = true,
			isolation = Isolation.REPEATABLE_READ,
			timeout = PrivacyExportLimits.TIMEOUT_SECONDS,
			rollbackFor = Exception.class)
	void write(UUID userId, PrivacyExportTarget target) throws IOException {
		Objects.requireNonNull(userId, "userId");
		Objects.requireNonNull(target, "target");
		JdbcPrivacyExportStore.UserData user = store.loadUser(userId);
		JdbcPrivacyExportStore.Counts counts = store.preflightCounts(userId);
		if (counts.exceedsCombinedLimit()) {
			throw new PrivacyExportTooLargeException();
		}

		Instant generatedAt = clock.instant();
		RenderResult preflight;
		try {
			preflight = render(
					userId,
					user,
					generatedAt,
					OutputStream.nullOutputStream(),
					PrivacyExportLimits.MAX_SERIALIZED_BYTES);
		}
		catch (ByteLimitExceededException exception) {
			throw new PrivacyExportTooLargeException();
		}
		assertExpectedCounts(counts, preflight);

		try (OutputStream outputStream = new NonClosingOutputStream(target.open(preflight.bytes()))) {
			RenderResult exported;
			try {
				exported = render(
						userId,
						user,
						generatedAt,
						outputStream,
						preflight.bytes());
			}
			catch (ByteLimitExceededException exception) {
				throw new IllegalStateException(
						"Personal-data export changed within its database snapshot", exception);
			}
			assertExpectedCounts(counts, exported);
			if (exported.bytes() != preflight.bytes()) {
				throw new IllegalStateException("Personal-data export changed within its database snapshot");
			}
		}
	}

	private RenderResult render(
			UUID userId,
			JdbcPrivacyExportStore.UserData user,
			Instant generatedAt,
			OutputStream outputStream,
			long maximumBytes) {
		BoundedOutputStream bounded = new BoundedOutputStream(outputStream, maximumBytes);
		long[] rows = new long[3];
		try (JsonGenerator json = objectMapper.createGenerator(bounded)) {
			json.configure(StreamWriteFeature.AUTO_CLOSE_CONTENT, false);
			json.configure(StreamWriteFeature.AUTO_CLOSE_TARGET, false);
			json.writeStartObject();
			writeValue(json, "userId", userId);
			writeValue(json, "displayName", user.displayName());
			writeValue(json, "accountCreatedAt", user.createdAt());
			writeValue(json, "generatedAt", generatedAt);

			json.writeArrayPropertyStart("searches");
			store.forEachSearch(userId, search -> {
				valueWriter.writeValue(json, search);
				rows[0]++;
			});
			json.writeEndArray();

			json.writeArrayPropertyStart("collections");
			store.forEachCollection(userId, collection -> {
				valueWriter.writeValue(json, collection);
				rows[1]++;
			});
			json.writeEndArray();

			json.writeArrayPropertyStart("savedPapers");
			store.forEachSavedPaper(userId, savedPaper -> {
				valueWriter.writeValue(json, savedPaper);
				rows[2]++;
			});
			json.writeEndArray();
			json.writeEndObject();
			json.flush();
		}
		return new RenderResult(bounded.count(), rows[0], rows[1], rows[2]);
	}

	private void writeValue(JsonGenerator json, String name, Object value) {
		json.writeName(name);
		valueWriter.writeValue(json, value);
	}

	private static void assertExpectedCounts(
			JdbcPrivacyExportStore.Counts expected, RenderResult actual) {
		if (expected.searches() != actual.searches()
				|| expected.collections() != actual.collections()
				|| expected.savedPapers() != actual.savedPapers()) {
			throw new IllegalStateException("Personal-data export row counts changed within its database snapshot");
		}
	}

	private record RenderResult(
			long bytes, long searches, long collections, long savedPapers) {
	}

	private static class NonClosingOutputStream extends OutputStream {

		private final OutputStream delegate;

		private NonClosingOutputStream(OutputStream delegate) {
			this.delegate = Objects.requireNonNull(delegate, "delegate");
		}

		@Override
		public void write(int value) throws IOException {
			delegate.write(value);
		}

		@Override
		public void write(byte[] bytes, int offset, int length) throws IOException {
			delegate.write(bytes, offset, length);
		}

		@Override
		public void flush() throws IOException {
			delegate.flush();
		}
	}

	private static final class BoundedOutputStream extends NonClosingOutputStream {

		private final long maximumBytes;
		private long count;

		private BoundedOutputStream(OutputStream delegate, long maximumBytes) {
			super(delegate);
			if (maximumBytes < 0) {
				throw new IllegalArgumentException("maximumBytes must not be negative");
			}
			this.maximumBytes = maximumBytes;
		}

		@Override
		public void write(int value) throws IOException {
			ensureCapacity(1);
			super.write(value);
			count++;
		}

		@Override
		public void write(byte[] bytes, int offset, int length) throws IOException {
			Objects.checkFromIndexSize(offset, length, bytes.length);
			ensureCapacity(length);
			super.write(bytes, offset, length);
			count += length;
		}

		private void ensureCapacity(int length) {
			if (length > maximumBytes - count) {
				throw new ByteLimitExceededException();
			}
		}

		private long count() {
			return count;
		}
	}

	private static final class ByteLimitExceededException extends RuntimeException {
	}
}
