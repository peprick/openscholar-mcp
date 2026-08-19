package com.openscholar.library.internal;

import java.time.Clock;
import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import com.openscholar.library.CollectionDetailsView;
import com.openscholar.library.CollectionNotFoundException;
import com.openscholar.library.CollectionSummaryView;
import com.openscholar.library.LibraryPage;
import com.openscholar.library.LibraryUseCase;
import com.openscholar.library.ReadingStatus;
import com.openscholar.library.SavedPaperNotFoundException;
import com.openscholar.library.SavedPaperView;
import com.openscholar.paper.PaperCatalog;
import com.openscholar.paper.PaperNotFoundException;
import com.openscholar.paper.PaperView;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class LibraryService implements LibraryUseCase {

	private static final int MAX_PAGE_SIZE = 100;

	private static final int MAX_TAG_COUNT = 10;

	private static final int MAX_TAG_LENGTH = 40;

	private static final int MAX_QUERY_LENGTH = 200;

	private static final Pattern WHITESPACE = Pattern.compile("[\\s\\p{Z}\\uFEFF]+");

	private final LibraryCollectionRepository collectionRepository;

	private final SavedPaperRepository savedPaperRepository;

	private final PaperCatalog paperCatalog;

	private final Clock clock;

	LibraryService(LibraryCollectionRepository collectionRepository, SavedPaperRepository savedPaperRepository,
			PaperCatalog paperCatalog, Clock clock) {
		this.collectionRepository = collectionRepository;
		this.savedPaperRepository = savedPaperRepository;
		this.paperCatalog = paperCatalog;
		this.clock = clock;
	}

	@Override
	@Transactional(readOnly = true)
	public LibraryPage<CollectionSummaryView> listCollections(int page, int size) {
		PageRequest request = pageRequest(page, size, Sort.by(Sort.Order.desc("updatedAt"), Sort.Order.asc("id")));
		Page<LibraryCollectionEntity> result = collectionRepository.findByOwnerId(LocalLibraryUser.ID, request);
		Map<UUID, Long> counts = paperCounts(result.getContent());
		return page(result, collection -> toSummary(collection, counts.getOrDefault(collection.id(), 0L)));
	}

	@Override
	@Transactional
	public CollectionSummaryView createCollection(String name, String description) {
		LibraryCollectionEntity collection = collectionRepository
			.save(LibraryCollectionEntity.create(LocalLibraryUser.ID, name, description, Instant.now(clock)));
		return toSummary(collection, 0);
	}

	@Override
	@Transactional(readOnly = true)
	public CollectionDetailsView getCollection(UUID collectionId, int page, int size) {
		LibraryCollectionEntity collection = findCollection(collectionId, false);
		Page<SavedPaperEntity> savedPapers = savedPaperRepository.findByCollection_Id(collection.id(),
				pageRequest(page, size, Sort.by(Sort.Order.desc("savedAt"), Sort.Order.asc("id"))));
		LibraryPage<SavedPaperView> papers = mapSavedPapers(savedPapers);
		return new CollectionDetailsView(collection.id(), collection.name(), collection.description(),
				savedPapers.getTotalElements(), collection.createdAt(), collection.updatedAt(), papers);
	}

	@Override
	@Transactional
	public CollectionSummaryView updateCollection(UUID collectionId, String name, String description) {
		LibraryCollectionEntity collection = findCollection(collectionId, true);
		collection.update(name, description, Instant.now(clock));
		return toSummary(collection, savedPaperRepository.countByCollection_Id(collection.id()));
	}

	@Override
	@Transactional
	public void deleteCollection(UUID collectionId) {
		LibraryCollectionEntity collection = findCollection(collectionId, true);
		collectionRepository.delete(collection);
	}

	@Override
	@Transactional
	public SavedPaperView addPaper(UUID collectionId, UUID paperId, ReadingStatus readingStatus,
			Collection<String> tags) {
		PaperView paper = findPaper(paperId);
		LibraryCollectionEntity collection = findCollection(collectionId, true);
		List<String> normalizedTags = normalizeTags(tags);
		Instant now = Instant.now(clock);
		SavedPaperEntity savedPaper = savedPaperRepository.findByCollection_IdAndPaperId(collection.id(), paper.id())
			.map(existing -> {
				existing.update(readingStatus, normalizedTags, now);
				return existing;
			})
			.orElseGet(() -> savedPaperRepository
				.save(SavedPaperEntity.create(collection, paper.id(), readingStatus, normalizedTags, now)));
		return toView(savedPaper, paper);
	}

	@Override
	@Transactional
	public SavedPaperView updatePaper(UUID collectionId, UUID paperId, ReadingStatus readingStatus,
			Collection<String> tags) {
		PaperView paper = findPaper(paperId);
		LibraryCollectionEntity collection = findCollection(collectionId, true);
		SavedPaperEntity savedPaper = savedPaperRepository.findByCollection_IdAndPaperId(collection.id(), paper.id())
			.orElseThrow(() -> new SavedPaperNotFoundException(collection.id(), paper.id()));
		savedPaper.update(readingStatus, normalizeTags(tags), Instant.now(clock));
		return toView(savedPaper, paper);
	}

	@Override
	@Transactional
	public void removePaper(UUID collectionId, UUID paperId) {
		LibraryCollectionEntity collection = findCollection(collectionId, true);
		savedPaperRepository.findByCollection_IdAndPaperId(collection.id(), paperId)
			.ifPresent(savedPaperRepository::delete);
	}

	@Override
	@Transactional(readOnly = true)
	public LibraryPage<SavedPaperView> searchSavedPapers(String query, UUID collectionId, ReadingStatus readingStatus,
			String tag, int page, int size) {
		if (collectionId != null) {
			findCollection(collectionId, false);
		}
		String normalizedQuery = normalizeQuery(query);
		String normalizedTag = tag == null || tag.isBlank() ? "" : normalizeTag(tag);
		Page<SavedPaperEntity> result = savedPaperRepository.search(LocalLibraryUser.ID, normalizedQuery, collectionId,
				readingStatus == null ? "" : readingStatus.name(), normalizedTag,
				pageRequest(page, size, Sort.unsorted()));
		return mapSavedPapers(result);
	}

	private LibraryCollectionEntity findCollection(UUID collectionId, boolean lock) {
		Objects.requireNonNull(collectionId, "collectionId");
		return (lock ? collectionRepository.findLockedByIdAndOwnerId(collectionId, LocalLibraryUser.ID)
				: collectionRepository.findByIdAndOwnerId(collectionId, LocalLibraryUser.ID))
			.orElseThrow(() -> new CollectionNotFoundException(collectionId));
	}

	private PaperView findPaper(UUID paperId) {
		Objects.requireNonNull(paperId, "paperId");
		return paperCatalog.findById(paperId).orElseThrow(() -> new PaperNotFoundException(paperId));
	}

	private Map<UUID, Long> paperCounts(List<LibraryCollectionEntity> collections) {
		if (collections.isEmpty()) {
			return Map.of();
		}
		return savedPaperRepository.countForCollections(collections.stream().map(LibraryCollectionEntity::id).toList())
			.stream()
			.collect(Collectors.toMap(SavedPaperRepository.CollectionPaperCount::getCollectionId,
					SavedPaperRepository.CollectionPaperCount::getPaperCount));
	}

	private LibraryPage<SavedPaperView> mapSavedPapers(Page<SavedPaperEntity> savedPapers) {
		LinkedHashSet<UUID> ids = savedPapers.getContent()
			.stream()
			.map(SavedPaperEntity::paperId)
			.collect(Collectors.toCollection(LinkedHashSet::new));
		Map<UUID, PaperView> papers = paperCatalog.findAllByIds(ids);
		List<SavedPaperView> views = savedPapers.getContent()
			.stream()
			.map(saved -> toView(saved, requirePaper(papers, saved.paperId())))
			.toList();
		return new LibraryPage<>(views, savedPapers.getNumber(), savedPapers.getSize(), savedPapers.getTotalElements(),
				savedPapers.getTotalPages());
	}

	private static PaperView requirePaper(Map<UUID, PaperView> papers, UUID paperId) {
		PaperView paper = papers.get(paperId);
		if (paper == null) {
			throw new IllegalStateException("A saved paper no longer exists: " + paperId);
		}
		return paper;
	}

	private static SavedPaperView toView(SavedPaperEntity saved, PaperView paper) {
		return new SavedPaperView(saved.collection().id(), saved.collection().name(), paper.id(), paper.title(),
				paper.authors().stream().map(author -> author.displayName()).toList(), paper.publicationYear(),
				paper.documentType(), saved.readingStatus(), saved.tags(), saved.savedAt(), saved.updatedAt());
	}

	private static CollectionSummaryView toSummary(LibraryCollectionEntity collection, long paperCount) {
		return new CollectionSummaryView(collection.id(), collection.name(), collection.description(), paperCount,
				collection.createdAt(), collection.updatedAt());
	}

	private static <S, T> LibraryPage<T> page(Page<S> source, Function<S, T> mapper) {
		return new LibraryPage<>(source.getContent().stream().map(mapper).toList(), source.getNumber(),
				source.getSize(), source.getTotalElements(), source.getTotalPages());
	}

	private static PageRequest pageRequest(int page, int size, Sort sort) {
		if (page < 0 || size < 1 || size > MAX_PAGE_SIZE) {
			throw new IllegalArgumentException("Page must be non-negative and size must be between 1 and 100");
		}
		return PageRequest.of(page, size, sort);
	}

	private static List<String> normalizeTags(Collection<String> tags) {
		Objects.requireNonNull(tags, "tags");
		if (tags.size() > MAX_TAG_COUNT) {
			throw new IllegalArgumentException("A saved paper can have at most 10 tags");
		}
		List<String> normalizedValues = tags.stream().map(LibraryService::normalizeTag).toList();
		LinkedHashSet<String> normalized = new LinkedHashSet<>(normalizedValues);
		if (normalized.size() != normalizedValues.size()) {
			throw new IllegalArgumentException("Tags must be distinct after normalization");
		}
		return normalized.stream().sorted().toList();
	}

	private static String normalizeTag(String value) {
		String normalized = value == null ? "" : collapseWhitespace(value).toLowerCase(Locale.ROOT);
		if (normalized.isEmpty() || normalized.length() > MAX_TAG_LENGTH) {
			throw new IllegalArgumentException("Tags must contain between 1 and 40 characters");
		}
		return normalized;
	}

	private static String normalizeQuery(String value) {
		String normalized = value == null ? "" : collapseWhitespace(value).toLowerCase(Locale.ROOT);
		if (normalized.length() > MAX_QUERY_LENGTH) {
			throw new IllegalArgumentException("Library query must not exceed 200 characters");
		}
		return normalized.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
	}

	private static String collapseWhitespace(String value) {
		return WHITESPACE.matcher(value.strip()).replaceAll(" ").strip();
	}

}
