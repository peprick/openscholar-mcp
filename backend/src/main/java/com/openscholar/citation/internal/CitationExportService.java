package com.openscholar.citation.internal;

import java.util.Objects;
import java.util.UUID;

import com.openscholar.citation.CitationExport;
import com.openscholar.citation.CitationExportUseCase;
import com.openscholar.citation.CitationFormat;
import com.openscholar.paper.PaperCatalog;
import com.openscholar.paper.PaperNotFoundException;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

@Service
class CitationExportService implements CitationExportUseCase {

	private final PaperCatalog paperCatalog;
	private final BibtexCitationRenderer bibtexRenderer = new BibtexCitationRenderer();
	private final CslJsonCitationRenderer cslJsonRenderer;

	CitationExportService(PaperCatalog paperCatalog, ObjectMapper objectMapper) {
		this.paperCatalog = paperCatalog;
		this.cslJsonRenderer = new CslJsonCitationRenderer(objectMapper);
	}

	@Override
	public CitationExport export(UUID paperId, CitationFormat format) {
		Objects.requireNonNull(paperId, "paperId");
		Objects.requireNonNull(format, "format");
		CitationItem item = paperCatalog.findById(paperId)
				.map(CitationMetadataMapper::from)
				.orElseThrow(() -> new PaperNotFoundException(paperId));
		String body = switch (format) {
			case BIBTEX -> bibtexRenderer.render(item);
			case CSL_JSON -> cslJsonRenderer.render(item);
		};
		return new CitationExport(
				format,
				item.citationKey(),
				item.citationKey() + format.fileExtension(),
				format.mediaType(),
				body);
	}
}
