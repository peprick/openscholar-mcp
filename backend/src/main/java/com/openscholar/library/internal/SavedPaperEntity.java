package com.openscholar.library.internal;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

import com.openscholar.library.ReadingStatus;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

@Entity
@Table(name = "collection_paper")
class SavedPaperEntity {

	@Id
	private UUID id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "collection_id", nullable = false)
	private LibraryCollectionEntity collection;

	@Column(name = "paper_id", nullable = false)
	private UUID paperId;

	@Enumerated(EnumType.STRING)
	@Column(name = "reading_status", nullable = false, length = 16)
	private ReadingStatus readingStatus;

	@ElementCollection(fetch = FetchType.LAZY)
	@CollectionTable(name = "collection_paper_tag", joinColumns = @JoinColumn(name = "collection_paper_id"))
	@Column(name = "tag", nullable = false, length = 40)
	private Set<String> tags = new TreeSet<>();

	@Version
	private long version;

	@Column(name = "saved_at", nullable = false, updatable = false)
	private Instant savedAt;

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	protected SavedPaperEntity() {
	}

	private SavedPaperEntity(UUID id, LibraryCollectionEntity collection, UUID paperId, ReadingStatus readingStatus,
			Collection<String> tags, Instant now) {
		this.id = id;
		this.collection = collection;
		this.paperId = paperId;
		this.readingStatus = readingStatus;
		this.tags.addAll(tags);
		this.savedAt = now;
		this.updatedAt = now;
	}

	static SavedPaperEntity create(LibraryCollectionEntity collection, UUID paperId, ReadingStatus readingStatus,
			Collection<String> tags, Instant now) {
		return new SavedPaperEntity(UUID.randomUUID(), Objects.requireNonNull(collection, "collection"),
				Objects.requireNonNull(paperId, "paperId"), Objects.requireNonNull(readingStatus, "readingStatus"),
				Objects.requireNonNull(tags, "tags"), Objects.requireNonNull(now, "now"));
	}

	void update(ReadingStatus readingStatus, Collection<String> tags, Instant now) {
		this.readingStatus = Objects.requireNonNull(readingStatus, "readingStatus");
		this.tags.clear();
		this.tags.addAll(Objects.requireNonNull(tags, "tags"));
		this.updatedAt = Objects.requireNonNull(now, "now");
	}

	UUID id() {
		return id;
	}

	LibraryCollectionEntity collection() {
		return collection;
	}

	UUID paperId() {
		return paperId;
	}

	ReadingStatus readingStatus() {
		return readingStatus;
	}

	List<String> tags() {
		return tags.stream().sorted().toList();
	}

	Instant savedAt() {
		return savedAt;
	}

	Instant updatedAt() {
		return updatedAt;
	}

}
