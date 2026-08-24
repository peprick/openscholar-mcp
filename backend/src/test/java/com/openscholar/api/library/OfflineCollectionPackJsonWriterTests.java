package com.openscholar.api.library;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;

import com.openscholar.library.OfflineCollectionPack;
import com.openscholar.library.OfflineCollectionPack.CollectionMetadata;
import com.openscholar.library.OfflineCollectionPack.PaperMetadata;
import com.openscholar.library.OfflineCollectionPackTooLargeException;
import com.openscholar.library.OfflineCollectionPackUseCase;
import com.openscholar.library.ReadingStatus;
import com.openscholar.paper.DocumentType;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

class OfflineCollectionPackJsonWriterTests {

	private static final UUID COLLECTION_ID = UUID.fromString("00000000-0000-0000-0000-000000000010");

	private static final UUID PAPER_ID = UUID.fromString("00000000-0000-0000-0000-000000000020");

	private final ObjectMapper objectMapper = JsonMapper.builder().build();

	@Test
	void acceptsTheExactUtf8ByteLimitAndRejectsOneAdditionalByte() throws Exception {
		int maximumBytes = OfflineCollectionPackUseCase.MAX_SERIALIZED_BYTES;
		int oneCharacterSize = objectMapper.writeValueAsBytes(pack("x")).length;
		String exactTitle = "x".repeat(1 + maximumBytes - oneCharacterSize);

		byte[] exact = OfflineCollectionPackJsonWriter.write(objectMapper, pack(exactTitle));

		assertThat(exact).hasSize(maximumBytes);
		assertThatThrownBy(() -> OfflineCollectionPackJsonWriter.write(objectMapper, pack(exactTitle + "x")))
			.isInstanceOf(OfflineCollectionPackTooLargeException.class)
			.hasMessage("The collection exceeds the supported offline metadata pack limits.")
			.satisfies(exception -> assertThat(exception.getMessage()).doesNotContain(exactTitle));
	}

	@Test
	void measuresUtf8BytesRatherThanJavaCharacters() throws Exception {
		OfflineCollectionPack pack = pack("é".repeat(400));
		String json = objectMapper.writeValueAsString(pack);
		byte[] utf8 = objectMapper.writeValueAsBytes(pack);

		assertThat(utf8.length).isGreaterThan(json.length());
		assertThatThrownBy(() -> OfflineCollectionPackJsonWriter.write(objectMapper, pack, json.length()))
			.isInstanceOf(OfflineCollectionPackTooLargeException.class);
	}

	private static OfflineCollectionPack pack(String title) {
		return new OfflineCollectionPack(
				1,
				null,
				new CollectionMetadata(COLLECTION_ID, "Offline reading", null),
				List.of(new PaperMetadata(
						PAPER_ID,
						title,
						List.of(),
						null,
						null,
						DocumentType.ARTICLE,
						null,
						null,
						List.of(),
						null,
						null,
						null,
						null,
						null,
						null,
						null,
						List.of(),
						List.of(),
						null,
						ReadingStatus.UNREAD,
						List.of())));
	}
}
