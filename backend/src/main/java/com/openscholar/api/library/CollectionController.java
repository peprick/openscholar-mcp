package com.openscholar.api.library;

import java.net.URI;
import java.util.List;
import java.util.UUID;

import com.openscholar.library.CollectionDetailsView;
import com.openscholar.library.CollectionSummaryView;
import com.openscholar.library.LibraryPage;
import com.openscholar.library.LibraryUseCase;
import com.openscholar.library.OfflineCollectionPackUseCase;
import com.openscholar.library.ReadingStatus;
import com.openscholar.library.SavedPaperView;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.ObjectMapper;

@Validated
@RestController
@RequestMapping("/api/v1/collections")
public class CollectionController {

	private final LibraryUseCase library;

	private final OfflineCollectionPackUseCase offlinePacks;

	private final ObjectMapper objectMapper;

	public CollectionController(LibraryUseCase library, OfflineCollectionPackUseCase offlinePacks,
			ObjectMapper objectMapper) {
		this.library = library;
		this.offlinePacks = offlinePacks;
		this.objectMapper = objectMapper;
	}

	@GetMapping
	public LibraryPage<CollectionSummaryView> list(@RequestParam(defaultValue = "0") @Min(0) int page,
			@RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
		return library.listCollections(page, size);
	}

	@PostMapping
	public ResponseEntity<CollectionSummaryView> create(@Valid @RequestBody CollectionRequest request) {
		CollectionSummaryView created = library.createCollection(request.name(), request.description());
		return ResponseEntity.created(URI.create("/api/v1/collections/" + created.collectionId())).body(created);
	}

	@GetMapping("/{collectionId}")
	public CollectionDetailsView get(@PathVariable UUID collectionId,
			@RequestParam(defaultValue = "0") @Min(0) int page,
			@RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
		return library.getCollection(collectionId, page, size);
	}

	@GetMapping(value = "/{collectionId}/offline-pack", produces = MediaType.APPLICATION_JSON_VALUE)
	public ResponseEntity<byte[]> getOfflinePack(@PathVariable UUID collectionId) {
		byte[] payload = OfflineCollectionPackJsonWriter.write(objectMapper,
				offlinePacks.getOfflinePack(collectionId));
		return ResponseEntity.ok()
			.contentType(MediaType.APPLICATION_JSON)
			.contentLength(payload.length)
			.cacheControl(CacheControl.noStore())
			.header("X-Content-Type-Options", "nosniff")
			.body(payload);
	}

	@PatchMapping("/{collectionId}")
	public CollectionSummaryView update(@PathVariable UUID collectionId,
			@Valid @RequestBody CollectionRequest request) {
		return library.updateCollection(collectionId, request.name(), request.description());
	}

	@DeleteMapping("/{collectionId}")
	public ResponseEntity<Void> delete(@PathVariable UUID collectionId) {
		library.deleteCollection(collectionId);
		return ResponseEntity.noContent().build();
	}

	@PutMapping("/{collectionId}/papers/{paperId}")
	public SavedPaperView addPaper(@PathVariable UUID collectionId, @PathVariable UUID paperId,
			@Valid @RequestBody SavedPaperRequest request) {
		return library.addPaper(collectionId, paperId, request.readingStatus(), request.tags());
	}

	@PatchMapping("/{collectionId}/papers/{paperId}")
	public SavedPaperView updatePaper(@PathVariable UUID collectionId, @PathVariable UUID paperId,
			@Valid @RequestBody SavedPaperRequest request) {
		return library.updatePaper(collectionId, paperId, request.readingStatus(), request.tags());
	}

	@DeleteMapping("/{collectionId}/papers/{paperId}")
	public ResponseEntity<Void> removePaper(@PathVariable UUID collectionId, @PathVariable UUID paperId) {
		library.removePaper(collectionId, paperId);
		return ResponseEntity.noContent().build();
	}

	public record CollectionRequest(@NotBlank @Size(max = 120) String name, @Size(max = 1000) String description) {
	}

	public record SavedPaperRequest(@NotNull ReadingStatus readingStatus,
			@NotNull @Size(max = 10) List<@NotBlank @Size(max = 40) String> tags) {
	}

}
