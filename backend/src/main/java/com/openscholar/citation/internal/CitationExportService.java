package com.openscholar.citation.internal;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import com.openscholar.citation.CitationBatchExportUseCase;
import com.openscholar.citation.CitationExport;
import com.openscholar.citation.CitationExportUseCase;
import com.openscholar.citation.CitationFormat;
import com.openscholar.paper.PaperCatalog;
import com.openscholar.paper.PaperNotFoundException;
import com.openscholar.paper.PaperView;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

@Service
class CitationExportService implements CitationExportUseCase, CitationBatchExportUseCase {

	private static final int MAX_BATCH_SIZE = 100;

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

	@Override
	public CitationExport exportBatch(List<UUID> paperIds, CitationFormat format) {
		Objects.requireNonNull(paperIds, "paperIds");
		Objects.requireNonNull(format, "format");
		if (paperIds.isEmpty() || paperIds.size() > MAX_BATCH_SIZE) {
			throw new IllegalArgumentException("Citation batches must contain between 1 and 100 papers");
		}
		LinkedHashSet<UUID> distinctIds = new LinkedHashSet<>();
		for (UUID paperId : paperIds) {
			distinctIds.add(Objects.requireNonNull(paperId, "paperId"));
		}
		if (distinctIds.size() != paperIds.size()) {
			throw new IllegalArgumentException("Citation batch paper IDs must be distinct");
		}
		Map<UUID, PaperView> papers = paperCatalog.findAllByIds(distinctIds);
		List<CitationItem> items = distinctIds.stream()
				.map(paperId -> {
					PaperView paper = papers.get(paperId);
					if (paper == null) {
						throw new PaperNotFoundException(paperId);
					}
					return CitationMetadataMapper.from(paper);
				})
				.toList();
		String body = switch (format) {
			case BIBTEX -> items.stream()
					.map(bibtexRenderer::render)
					.collect(java.util.stream.Collectors.joining("\n"));
			case CSL_JSON -> cslJsonRenderer.render(items);
		};
		String basename = "openscholar-citations-" + items.size();
		return new CitationExport(
				format,
				basename,
				basename + format.fileExtension(),
				format.mediaType(),
				body);
	}
}
