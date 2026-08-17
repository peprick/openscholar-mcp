package com.openscholar.access;

import java.util.UUID;

public interface PaperAccessUseCase {

	PaperAccessView get(UUID paperId);

	PaperAccessView resolve(UUID paperId, boolean forceRefresh);
}
