package com.openscholar.search.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class LocalCatalogCursorCodecTests {

	private static final String FINGERPRINT = "a".repeat(64);

	private final LocalCatalogCursorCodec codec = new LocalCatalogCursorCodec(JsonMapper.builder().build());

	@Test
	void treatsMissingAndInitialCursorsAsTheFirstPage() {
		assertThat(codec.decode(null, FINGERPRINT).initial()).isTrue();
		assertThat(codec.decode("", FINGERPRINT).initial()).isTrue();
		assertThat(codec.decode("*", FINGERPRINT).initial()).isTrue();
	}

	@Test
	void roundTripsTheMaximumBoundedCandidateSet() {
		List<UUID> paperIds = new ArrayList<>();
		for (int index = 0; index < LocalCatalogCursorCodec.MAX_REMAINING_PAPERS; index++) {
			paperIds.add(UUID.randomUUID());
		}

		String cursor = codec.encode(paperIds, FINGERPRINT);

		assertThat(cursor).startsWith(LocalCatalogCursorCodec.PREFIX).hasSizeLessThanOrEqualTo(4096);
		assertThat(codec.isLocalCursor(cursor)).isTrue();
		assertThat(codec.decode(cursor, FINGERPRINT))
				.satisfies(state -> {
					assertThat(state.initial()).isFalse();
					assertThat(state.remainingPaperIds()).containsExactlyElementsOf(paperIds);
				});
	}

	@Test
	void rejectsCrossQueryCursorReuse() {
		String cursor = codec.encode(List.of(UUID.randomUUID()), FINGERPRINT);

		assertThatThrownBy(() -> codec.decode(cursor, "b".repeat(64)))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessage("Local search cursor is invalid");
	}

	@Test
	void rejectsMalformedForeignEmptyOversizedAndDuplicateCandidateSets() {
		UUID duplicate = UUID.randomUUID();
		byte[] oversized = new byte[(LocalCatalogCursorCodec.MAX_REMAINING_PAPERS + 1) * 16];

		assertThatThrownBy(() -> codec.decode("provider-cursor", FINGERPRINT))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessage("Local search cursor is invalid");
		assertThatThrownBy(() -> codec.decode(LocalCatalogCursorCodec.PREFIX + "%%%", FINGERPRINT))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessage("Local search cursor is invalid");
		assertThatThrownBy(() -> codec.decode(cursorJson(2, packed(List.of(UUID.randomUUID())), FINGERPRINT),
				FINGERPRINT))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessage("Local search cursor is invalid");
		assertThatThrownBy(() -> codec.decode(cursorJson(1, "", FINGERPRINT), FINGERPRINT))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessage("Local search cursor is invalid");
		assertThatThrownBy(() -> codec.decode(cursorJson(1,
				Base64.getUrlEncoder().withoutPadding().encodeToString(oversized), FINGERPRINT), FINGERPRINT))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessage("Local search cursor is invalid");
		assertThatThrownBy(() -> codec.decode(
				cursorJson(1, packed(List.of(duplicate, duplicate)), FINGERPRINT), FINGERPRINT))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessage("Local search cursor is invalid");
	}

	@Test
	void validatesEncodingInputsAndExpectedFingerprint() {
		List<UUID> oversized = new ArrayList<>();
		for (int index = 0; index <= LocalCatalogCursorCodec.MAX_REMAINING_PAPERS; index++) {
			oversized.add(UUID.randomUUID());
		}
		UUID duplicate = UUID.randomUUID();

		assertThatThrownBy(() -> codec.encode(List.of(), FINGERPRINT))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("between 1 and");
		assertThatThrownBy(() -> codec.encode(oversized, FINGERPRINT))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("between 1 and");
		assertThatThrownBy(() -> codec.encode(List.of(duplicate, duplicate), FINGERPRINT))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("unique paper IDs");
		assertThatThrownBy(() -> codec.encode(List.of(UUID.randomUUID()), "not-a-fingerprint"))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("lowercase SHA-256");
		assertThatThrownBy(() -> codec.decode("*", "A".repeat(64)))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("lowercase SHA-256");
	}

	private static String packed(List<UUID> ids) {
		ByteBuffer buffer = ByteBuffer.allocate(ids.size() * 16);
		ids.forEach(id -> buffer.putLong(id.getMostSignificantBits()).putLong(id.getLeastSignificantBits()));
		return Base64.getUrlEncoder().withoutPadding().encodeToString(buffer.array());
	}

	private static String cursorJson(int version, String packedPaperIds, String fingerprint) {
		String json = """
				{"version":%d,"queryFingerprint":"%s","remainingPaperIds":"%s"}
				""".formatted(version, fingerprint, packedPaperIds).strip();
		return LocalCatalogCursorCodec.PREFIX + Base64.getUrlEncoder().withoutPadding()
				.encodeToString(json.getBytes(StandardCharsets.UTF_8));
	}
}
