package com.openscholar.api.citation;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import com.openscholar.citation.CitationBatchExportUseCase;
import com.openscholar.citation.CitationExport;
import com.openscholar.citation.CitationFormat;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/citations")
public class CitationBatchController {

	private final CitationBatchExportUseCase citationExport;

	public CitationBatchController(CitationBatchExportUseCase citationExport) {
		this.citationExport = citationExport;
	}

	@PostMapping("/export")
	public ResponseEntity<String> export(@Valid @RequestBody BatchCitationRequest request) {
		CitationExport export = citationExport.exportBatch(request.paperIds(),
				CitationFormat.fromApiValue(request.format()));
		return ResponseEntity.ok()
			.contentType(MediaType.parseMediaType(export.mediaType() + ";charset=UTF-8"))
			.header(HttpHeaders.CONTENT_DISPOSITION,
					ContentDisposition.attachment()
						.filename(export.filename(), StandardCharsets.UTF_8)
						.build()
						.toString())
			.header("X-Content-Type-Options", "nosniff")
			.body(export.body());
	}

	public record BatchCitationRequest(@NotEmpty @Size(max = 100) List<@NotNull UUID> paperIds,
			@NotNull String format) {
	}

}
