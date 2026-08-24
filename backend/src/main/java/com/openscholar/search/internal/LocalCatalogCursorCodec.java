package com.openscholar.search.internal;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Component
class LocalCatalogCursorCodec {

	static final String PREFIX = "oslocal1.";
	static final int MAX_REMAINING_PAPERS = 128;

	private static final int VERSION = 1;
	private static final int MAX_CURSOR_LENGTH = 4096;
	private static final int UUID_BYTES = 16;
	private static final Pattern SHA_256 = Pattern.compile("[0-9a-f]{64}");

	private final ObjectMapper objectMapper;

	LocalCatalogCursorCodec(ObjectMapper objectMapper) {
		this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
	}

	CursorState decode(String cursor, String expectedQueryFingerprint) {
		String expectedFingerprint = requireFingerprint(expectedQueryFingerprint);
		if (cursor == null || cursor.isBlank() || cursor.equals("*")) {
			return CursorState.initialPage();
		}
		if (cursor.length() > MAX_CURSOR_LENGTH || !cursor.startsWith(PREFIX)) {
			throw invalidCursor();
		}
		try {
			byte[] json = Base64.getUrlDecoder().decode(cursor.substring(PREFIX.length()));
			CursorEnvelope envelope = objectMapper.readValue(json, CursorEnvelope.class);
			if (envelope == null
					|| envelope.version() != VERSION
					|| !expectedFingerprint.equals(envelope.queryFingerprint())) {
				throw invalidCursor();
			}
			List<UUID> remainingPaperIds = decodePaperIds(envelope.remainingPaperIds());
			return CursorState.continuation(remainingPaperIds);
		}
		catch (JacksonException | IllegalArgumentException exception) {
			if (exception instanceof IllegalArgumentException illegalArgumentException
					&& "Local search cursor is invalid".equals(illegalArgumentException.getMessage())) {
				throw illegalArgumentException;
			}
			throw invalidCursor();
		}
	}

	String encode(List<UUID> remainingPaperIds, String queryFingerprint) {
		List<UUID> ids = List.copyOf(Objects.requireNonNull(remainingPaperIds, "remainingPaperIds"));
		if (ids.isEmpty() || ids.size() > MAX_REMAINING_PAPERS || new HashSet<>(ids).size() != ids.size()) {
			throw new IllegalArgumentException(
					"Local search cursor must contain between 1 and " + MAX_REMAINING_PAPERS
							+ " unique paper IDs");
		}
		if (ids.stream().anyMatch(Objects::isNull)) {
			throw new IllegalArgumentException("Local search cursor paper IDs must not be null");
		}
		String fingerprint = requireFingerprint(queryFingerprint);
		try {
			String packedPaperIds = encodePaperIds(ids);
			String encoded = PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(
					objectMapper.writeValueAsString(
							new CursorEnvelope(VERSION, fingerprint, packedPaperIds))
							.getBytes(StandardCharsets.UTF_8));
			if (encoded.length() > MAX_CURSOR_LENGTH) {
				throw new IllegalStateException("Local search cursor exceeds the public cursor limit");
			}
			return encoded;
		}
		catch (JacksonException exception) {
			throw new IllegalStateException("Could not encode the local search cursor", exception);
		}
	}

	boolean isLocalCursor(String cursor) {
		return cursor != null && cursor.startsWith(PREFIX);
	}

	private static String encodePaperIds(List<UUID> ids) {
		ByteBuffer buffer = ByteBuffer.allocate(ids.size() * UUID_BYTES);
		for (UUID id : ids) {
			buffer.putLong(id.getMostSignificantBits());
			buffer.putLong(id.getLeastSignificantBits());
		}
		return Base64.getUrlEncoder().withoutPadding().encodeToString(buffer.array());
	}

	private static List<UUID> decodePaperIds(String packedPaperIds) {
		if (packedPaperIds == null || packedPaperIds.isBlank()) {
			throw invalidCursor();
		}
		byte[] bytes = Base64.getUrlDecoder().decode(packedPaperIds);
		if (bytes.length == 0
				|| bytes.length % UUID_BYTES != 0
				|| bytes.length / UUID_BYTES > MAX_REMAINING_PAPERS) {
			throw invalidCursor();
		}
		ByteBuffer buffer = ByteBuffer.wrap(bytes);
		var ids = new java.util.ArrayList<UUID>(bytes.length / UUID_BYTES);
		while (buffer.hasRemaining()) {
			ids.add(new UUID(buffer.getLong(), buffer.getLong()));
		}
		if (new HashSet<>(ids).size() != ids.size()) {
			throw invalidCursor();
		}
		return List.copyOf(ids);
	}

	private static String requireFingerprint(String value) {
		if (value == null || !SHA_256.matcher(value).matches()) {
			throw new IllegalArgumentException("Local search query fingerprint must be a lowercase SHA-256 value");
		}
		return value;
	}

	private static IllegalArgumentException invalidCursor() {
		return new IllegalArgumentException("Local search cursor is invalid");
	}

	record CursorState(boolean initial, List<UUID> remainingPaperIds) {

		CursorState {
			remainingPaperIds = List.copyOf(remainingPaperIds);
		}

		static CursorState initialPage() {
			return new CursorState(true, List.of());
		}

		static CursorState continuation(List<UUID> remainingPaperIds) {
			return new CursorState(false, remainingPaperIds);
		}
	}

	private record CursorEnvelope(int version, String queryFingerprint, String remainingPaperIds) {
	}
}
