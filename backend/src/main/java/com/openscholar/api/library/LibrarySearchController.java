package com.openscholar.api.library;

import java.util.UUID;

import com.openscholar.library.LibraryPage;
import com.openscholar.library.LibraryUseCase;
import com.openscholar.library.ReadingStatus;
import com.openscholar.library.SavedPaperView;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequestMapping("/api/v1/library/papers")
public class LibrarySearchController {

	private final LibraryUseCase library;

	public LibrarySearchController(LibraryUseCase library) {
		this.library = library;
	}

	@GetMapping
	public LibraryPage<SavedPaperView> search(@RequestParam(required = false) @Size(max = 200) String q,
			@RequestParam(required = false) UUID collectionId,
			@RequestParam(required = false) ReadingStatus readingStatus,
			@RequestParam(required = false) @Size(max = 40) String tag,
			@RequestParam(defaultValue = "0") @Min(0) int page,
			@RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
		return library.searchSavedPapers(q, collectionId, readingStatus, tag, page, size);
	}

}
