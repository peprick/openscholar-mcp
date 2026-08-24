package com.openscholar.library.internal;

import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

import com.openscholar.library.CollectionNotFoundException;
import com.openscholar.library.OfflineCollectionPack;
import com.openscholar.library.OfflineCollectionPack.CollectionMetadata;
import com.openscholar.library.OfflineCollectionPack.PaperMetadata;
import com.openscholar.library.OfflineCollectionPackTooLargeException;
import com.openscholar.library.OfflineCollectionPackUseCase;
import com.openscholar.paper.PaperAuthorView;
import com.openscholar.paper.PaperCatalog;
import com.openscholar.paper.PaperIdentifier;
import com.openscholar.paper.PaperView;
import com.openscholar.security.CurrentUserIdProvider;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
class OfflineCollectionPackService implements OfflineCollectionPackUseCase {

	private static final Comparator<PaperAuthorView> AUTHOR_ORDER = Comparator
		.comparingInt(PaperAuthorView::position)
		.thenComparing(PaperAuthorView::displayName);

	private static final Comparator<PaperIdentifier> IDENTIFIER_ORDER = Comparator
		.comparing((PaperIdentifier identifier) -> identifier.type().name())
		.thenComparing(PaperIdentifier::namespace)
		.thenComparing(PaperIdentifier::value);

	private final LibraryCollectionRepository collectionRepository;

	private final SavedPaperRepository savedPaperRepository;

	private final PaperCatalog paperCatalog;

	private final CurrentUserIdProvider currentUser;

	private final Clock clock;

	OfflineCollectionPackService(LibraryCollectionRepository collectionRepository,
			SavedPaperRepository savedPaperRepository, PaperCatalog paperCatalog,
			CurrentUserIdProvider currentUser, Clock clock) {
		this.collectionRepository = collectionRepository;
		this.savedPaperRepository = savedPaperRepository;
		this.paperCatalog = paperCatalog;
		this.currentUser = currentUser;
		this.clock = clock;
	}

	@Override
	@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
	public OfflineCollectionPack getOfflinePack(UUID collectionId) {
		Objects.requireNonNull(collectionId, "collectionId");
		UUID ownerId = currentUser.currentUserId();
		LibraryCollectionEntity collection = collectionRepository.findByIdAndOwnerId(collectionId, ownerId)
			.orElseThrow(() -> new CollectionNotFoundException(collectionId));

		List<SavedPaperEntity> savedPapers = savedPaperRepository.findForOfflinePack(
				collection.id(), ownerId, PageRequest.of(0, MAX_PAPERS + 1));
		if (savedPapers.size() > MAX_PAPERS) {
			throw new OfflineCollectionPackTooLargeException();
		}

		LinkedHashSet<UUID> paperIds = savedPapers.stream()
			.map(SavedPaperEntity::paperId)
			.collect(Collectors.toCollection(LinkedHashSet::new));
		Map<UUID, PaperView> papers = paperCatalog.findAllByIds(paperIds);
		List<PaperMetadata> metadata = savedPapers.stream()
			.map(savedPaper -> toMetadata(savedPaper, requirePaper(papers, savedPaper.paperId())))
			.toList();

		return new OfflineCollectionPack(
				SCHEMA_VERSION,
				Instant.now(clock),
				new CollectionMetadata(collection.id(), collection.name(), collection.description()),
				metadata);
	}

	private static PaperMetadata toMetadata(SavedPaperEntity savedPaper, PaperView paper) {
		return new PaperMetadata(
				paper.id(),
				paper.title(),
				paper.authors().stream().sorted(AUTHOR_ORDER).map(PaperAuthorView::displayName).toList(),
				paper.publicationDate(),
				paper.publicationYear(),
				paper.documentType(),
				paper.language(),
				paper.venueName(),
				paper.identifiers().stream().sorted(IDENTIFIER_ORDER).toList(),
				paper.publisher(),
				paper.institution(),
				paper.volume(),
				paper.issue(),
				paper.pages(),
				paper.articleNumber(),
				paper.edition(),
				paper.isbn().stream().sorted().toList(),
				paper.issn().stream().sorted().toList(),
				paper.degree(),
				savedPaper.readingStatus(),
				savedPaper.tags());
	}

	private static PaperView requirePaper(Map<UUID, PaperView> papers, UUID paperId) {
		PaperView paper = papers.get(paperId);
		if (paper == null) {
			throw new IllegalStateException("A saved paper no longer exists: " + paperId);
		}
		return paper;
	}
}
