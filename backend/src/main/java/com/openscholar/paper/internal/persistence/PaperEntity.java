package com.openscholar.paper.internal.persistence;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Locale;
import java.util.UUID;

import com.openscholar.paper.DocumentType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

@Entity
@Table(name = "paper")
class PaperEntity {

	@Id
	private UUID id;

	@Column(nullable = false)
	private String title;

	@Column(name = "normalized_title", nullable = false)
	private String normalizedTitle;

	@Column(name = "abstract_text")
	private String abstractText;

	@Column(name = "publication_date")
	private LocalDate publicationDate;

	@Column(name = "publication_year")
	private Integer publicationYear;

	@Enumerated(EnumType.STRING)
	@Column(name = "document_type", nullable = false, length = 32)
	private DocumentType documentType;

	@Column(length = 16)
	private String language;

	@Column(name = "venue_name")
	private String venueName;

	@Column(name = "citation_count")
	private Integer citationCount;

	@Column(name = "citation_count_as_of")
	private Instant citationCountAsOf;

	@Column(name = "metadata_quality", nullable = false, precision = 5, scale = 4)
	private BigDecimal metadataQuality;

	@Version
	private long version;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	protected PaperEntity() {
	}

	private PaperEntity(UUID id, String title, String normalizedTitle, DocumentType documentType, Instant now) {
		this.id = id;
		this.title = title;
		this.normalizedTitle = normalizedTitle;
		this.documentType = documentType;
		this.metadataQuality = BigDecimal.ZERO;
		this.createdAt = now;
		this.updatedAt = now;
	}

	static PaperEntity create(String title, DocumentType documentType, Instant now) {
		String cleanTitle = title == null ? "" : title.strip();
		if (cleanTitle.isEmpty()) {
			throw new IllegalArgumentException("Paper title must not be blank");
		}
		return new PaperEntity(
				UUID.randomUUID(),
				cleanTitle,
				normalizeTitle(cleanTitle),
				documentType,
				now);
	}

	private static String normalizeTitle(String title) {
		return title.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").strip();
	}

	UUID id() {
		return id;
	}

	String title() {
		return title;
	}
}
