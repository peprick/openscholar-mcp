package com.openscholar.citation;

import java.util.List;
import java.util.UUID;

public interface CitationBatchExportUseCase {

	CitationExport exportBatch(List<UUID> paperIds, CitationFormat format);

}
