package com.openscholar.citation;

import java.util.UUID;

public interface CitationExportUseCase {

	CitationExport export(UUID paperId, CitationFormat format);
}
