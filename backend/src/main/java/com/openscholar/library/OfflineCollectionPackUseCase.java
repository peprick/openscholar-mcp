package com.openscholar.library;

import java.util.UUID;

public interface OfflineCollectionPackUseCase {

	int SCHEMA_VERSION = 1;

	int MAX_PAPERS = 500;

	int MAX_SERIALIZED_BYTES = 1_048_576;

	OfflineCollectionPack getOfflinePack(UUID collectionId);
}
