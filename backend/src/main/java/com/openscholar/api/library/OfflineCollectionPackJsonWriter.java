package com.openscholar.api.library;

import com.openscholar.library.OfflineCollectionPack;
import com.openscholar.library.OfflineCollectionPackTooLargeException;
import com.openscholar.library.OfflineCollectionPackUseCase;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

final class OfflineCollectionPackJsonWriter {

	private OfflineCollectionPackJsonWriter() {
	}

	static byte[] write(ObjectMapper objectMapper, OfflineCollectionPack pack) {
		return write(objectMapper, pack, OfflineCollectionPackUseCase.MAX_SERIALIZED_BYTES);
	}

	static byte[] write(ObjectMapper objectMapper, OfflineCollectionPack pack, int maximumBytes) {
		try {
			byte[] payload = objectMapper.writeValueAsBytes(pack);
			if (payload.length > maximumBytes) {
				throw new OfflineCollectionPackTooLargeException();
			}
			return payload;
		}
		catch (JacksonException exception) {
			throw new IllegalStateException("The offline metadata pack could not be serialized safely", exception);
		}
	}
}
