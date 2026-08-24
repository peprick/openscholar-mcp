package com.openscholar.library.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.groups.Tuple.tuple;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.IntStream;

import com.openscholar.library.OfflineCollectionPack;
import com.openscholar.library.OfflineCollectionPackTooLargeException;
import com.openscholar.library.OfflineCollectionPackUseCase;
import com.openscholar.library.CollectionNotFoundException;
import com.openscholar.library.ReadingStatus;
import com.openscholar.paper.DocumentType;
import com.openscholar.paper.PaperAuthorView;
import com.openscholar.paper.PaperCatalog;
import com.openscholar.paper.PaperIdentifier;
import com.openscholar.paper.PaperIdentifierType;
import com.openscholar.paper.PaperView;
import com.openscholar.security.CurrentUserIdProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageRequest;

@ExtendWith(MockitoExtension.class)
class OfflineCollectionPackServiceTests {

	private static final Instant NOW = Instant.parse("2026-08-24T12:30:00Z");

	private static final UUID OWNER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

	@Mock
	private LibraryCollectionRepository collectionRepository;

	@Mock
	private SavedPaperRepository savedPaperRepository;

	@Mock
	private PaperCatalog paperCatalog;

	@Mock
	private CurrentUserIdProvider currentUser;

	private OfflineCollectionPackService service;

	private LibraryCollectionEntity collection;

	@BeforeEach
	void setUp() {
		service = new OfflineCollectionPackService(collectionRepository, savedPaperRepository, paperCatalog,
				currentUser, Clock.fixed(NOW, ZoneOffset.UTC));
		collection = LibraryCollectionEntity.create(OWNER_ID, "Offline foundations", "Review first", NOW);
	}

	@Test
	void resolvesTheOwnerBeforeReadingAnyMembershipOrPaperMetadata() {
		when(currentUser.currentUserId()).thenReturn(OWNER_ID);
		when(collectionRepository.findByIdAndOwnerId(collection.id(), OWNER_ID)).thenReturn(Optional.empty());

		assertThatThrownBy(() -> service.getOfflinePack(collection.id()))
			.isInstanceOf(CollectionNotFoundException.class);
		verifyNoInteractions(savedPaperRepository, paperCatalog);
	}

	@Test
	void rejectsTheSentinelItemBeforeHydratingCanonicalMetadata() {
		visibleCollection();
		List<SavedPaperEntity> sentinel = IntStream.rangeClosed(0, OfflineCollectionPackUseCase.MAX_PAPERS)
			.mapToObj(ignored -> savedPaper(UUID.randomUUID()))
			.toList();
		when(savedPaperRepository.findForOfflinePack(collection.id(), OWNER_ID,
				PageRequest.of(0, OfflineCollectionPackUseCase.MAX_PAPERS + 1))).thenReturn(sentinel);

		assertThatThrownBy(() -> service.getOfflinePack(collection.id()))
			.isInstanceOf(OfflineCollectionPackTooLargeException.class);
		verifyNoInteractions(paperCatalog);
	}

	@Test
	void acceptsExactlyFiveHundredPapersAndPreservesTheBoundedRepositoryOrder() {
		visibleCollection();
		List<SavedPaperEntity> savedPapers = new ArrayList<>();
		Map<UUID, PaperView> papers = new LinkedHashMap<>();
		IntStream.range(0, OfflineCollectionPackUseCase.MAX_PAPERS).forEach(index -> {
			UUID paperId = new UUID(0, index + 1L);
			savedPapers.add(savedPaper(paperId));
			papers.put(paperId, paper(paperId, "Paper " + index));
		});
		when(savedPaperRepository.findForOfflinePack(collection.id(), OWNER_ID,
				PageRequest.of(0, OfflineCollectionPackUseCase.MAX_PAPERS + 1))).thenReturn(savedPapers);
		when(paperCatalog.findAllByIds(anyCollection())).thenReturn(papers);

		OfflineCollectionPack result = service.getOfflinePack(collection.id());

		assertThat(result.schemaVersion()).isEqualTo(1);
		assertThat(result.generatedAt()).isEqualTo(NOW);
		assertThat(result.papers()).hasSize(OfflineCollectionPackUseCase.MAX_PAPERS);
		assertThat(result.papers()).extracting(OfflineCollectionPack.PaperMetadata::paperId)
			.containsExactlyElementsOf(savedPapers.stream().map(SavedPaperEntity::paperId).toList());
	}

	@Test
	void includesOnlyCreditedNamesAndSortsEveryNestedSetDeterministically() {
		visibleCollection();
		UUID paperId = UUID.randomUUID();
		SavedPaperEntity savedPaper = SavedPaperEntity.create(collection, paperId, ReadingStatus.READING,
				List.of("zeta", "alpha"), NOW);
		PaperView paper = new PaperView(
				paperId,
				"Deterministic metadata",
				"This abstract must not be copied",
				LocalDate.of(2025, 4, 3),
				2025,
				DocumentType.THESIS,
				"en",
				"Repository",
				999,
				NOW,
				List.of(
						new PaperIdentifier(PaperIdentifierType.REPOSITORY, "z", "2"),
						new PaperIdentifier(PaperIdentifierType.DOI, "", "10.1/example"),
						new PaperIdentifier(PaperIdentifierType.REPOSITORY, "a", "1")),
				List.of(
						new PaperAuthorView(UUID.randomUUID(), "Third", "secret-orcid", "secret-openalex", 2,
								false),
						new PaperAuthorView(UUID.randomUUID(), "First", null, null, 0, true),
						new PaperAuthorView(UUID.randomUUID(), "Second", null, null, 1, false)),
				"Publisher",
				"Institution",
				"4",
				"2",
				"10-20",
				"e7",
				"Second",
				List.of("z-isbn", "a-isbn"),
				List.of("z-issn", "a-issn"),
				"PhD");
		when(savedPaperRepository.findForOfflinePack(collection.id(), OWNER_ID,
				PageRequest.of(0, OfflineCollectionPackUseCase.MAX_PAPERS + 1))).thenReturn(List.of(savedPaper));
		when(paperCatalog.findAllByIds(anyCollection())).thenReturn(Map.of(paperId, paper));

		OfflineCollectionPack.PaperMetadata result = service.getOfflinePack(collection.id()).papers().getFirst();

		assertThat(result.authors()).containsExactly("First", "Second", "Third");
		assertThat(result.identifiers()).extracting(
				identifier -> identifier.type().name(), PaperIdentifier::namespace, PaperIdentifier::value)
			.containsExactly(
					tuple("DOI", "", "10.1/example"),
					tuple("REPOSITORY", "a", "1"),
					tuple("REPOSITORY", "z", "2"));
		assertThat(result.isbn()).containsExactly("a-isbn", "z-isbn");
		assertThat(result.issn()).containsExactly("a-issn", "z-issn");
		assertThat(result.tags()).containsExactly("alpha", "zeta");
		verify(paperCatalog).findAllByIds(anyCollection());
	}

	private SavedPaperEntity savedPaper(UUID paperId) {
		return SavedPaperEntity.create(collection, paperId, ReadingStatus.UNREAD, List.of(), NOW);
	}

	private void visibleCollection() {
		when(currentUser.currentUserId()).thenReturn(OWNER_ID);
		when(collectionRepository.findByIdAndOwnerId(collection.id(), OWNER_ID)).thenReturn(Optional.of(collection));
	}

	private static PaperView paper(UUID paperId, String title) {
		return new PaperView(paperId, title, null, null, null, DocumentType.ARTICLE, null, null, null, null,
				List.of(), List.of());
	}
}
