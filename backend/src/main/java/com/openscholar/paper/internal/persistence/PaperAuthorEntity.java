package com.openscholar.paper.internal.persistence;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

@Entity
@Table(
		name = "paper_author",
		uniqueConstraints = @UniqueConstraint(
				name = "uk_paper_author_record_position",
				columnNames = {"provider_record_id", "author_position"}))
class PaperAuthorEntity {

	@Id
	private UUID id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "paper_id", nullable = false)
	private PaperEntity paper;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "provider_record_id", nullable = false)
	private ProviderRecordEntity providerRecord;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "author_id", nullable = false)
	private AuthorEntity author;

	@Column(name = "author_position", nullable = false)
	private int position;

	@Column(nullable = false)
	private boolean corresponding;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	protected PaperAuthorEntity() {
	}

	private PaperAuthorEntity(
			UUID id,
			PaperEntity paper,
			ProviderRecordEntity providerRecord,
			AuthorEntity author,
			int position,
			boolean corresponding,
			Instant now) {
		this.id = id;
		this.paper = paper;
		this.providerRecord = providerRecord;
		this.author = author;
		this.position = position;
		this.corresponding = corresponding;
		this.createdAt = now;
		this.updatedAt = now;
	}

	static PaperAuthorEntity create(
			PaperEntity paper,
			ProviderRecordEntity providerRecord,
			AuthorEntity author,
			int position,
			boolean corresponding,
			Instant now) {
		if (position < 0) {
			throw new IllegalArgumentException("Author position must not be negative");
		}
		return new PaperAuthorEntity(
				UUID.randomUUID(), paper, providerRecord, author, position, corresponding, now);
	}

	UUID paperId() {
		return paper.id();
	}

	UUID providerRecordId() {
		return providerRecord.id();
	}

	AuthorEntity author() {
		return author;
	}

	int position() {
		return position;
	}

	boolean corresponding() {
		return corresponding;
	}

	Instant providerRetrievedAt() {
		return providerRecord.retrievedAt();
	}
}
