package com.openscholar.api.citation;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import com.openscholar.citation.CitationExport;
import com.openscholar.citation.CitationExportUseCase;
import com.openscholar.citation.CitationFormat;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/papers/{paperId}")
public class CitationController {

	private final CitationExportUseCase citationExportUseCase;

	public CitationController(CitationExportUseCase citationExportUseCase) {
		this.citationExportUseCase = citationExportUseCase;
	}

	@GetMapping("/citation")
	public ResponseEntity<String> export(
			@PathVariable UUID paperId,
			@RequestParam(defaultValue = "bibtex") String format) {
		CitationExport export = citationExportUseCase.export(
				paperId, CitationFormat.fromApiValue(format));
		return ResponseEntity.ok()
				.contentType(MediaType.parseMediaType(export.mediaType() + ";charset=UTF-8"))
				.header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
						.filename(export.filename(), StandardCharsets.UTF_8)
						.build()
						.toString())
				.header("X-Content-Type-Options", "nosniff")
				.body(export.body());
	}
}
