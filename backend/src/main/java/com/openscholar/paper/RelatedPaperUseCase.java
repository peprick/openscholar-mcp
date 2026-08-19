package com.openscholar.paper;

import java.util.UUID;

public interface RelatedPaperUseCase {

	RelatedPapersView findRelated(UUID paperId, int limit);
}
