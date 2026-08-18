package com.openscholar.paper;

import java.util.UUID;

public interface PaperDetailsUseCase {

	PaperDetailsView get(UUID paperId);
}
