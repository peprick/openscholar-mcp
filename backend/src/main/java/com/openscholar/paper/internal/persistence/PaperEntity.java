package com.openscholar.paper.internal.persistence;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

import com.openscholar.paper.CanonicalPaperCandidate;
import com.openscholar.paper.DocumentType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

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

	private String publisher;

	private String institution;

	private String volume;

	private String issue;

	private String pages;

	@Column(name = "article_number")
	private String articleNumber;

	private String edition;

	@JdbcTypeCode(SqlTypes.JSON)
	@Column(nullable = false, columnDefinition = "jsonb")
	private List<String> isbn;

	@JdbcTypeCode(SqlTypes.JSON)
	@Column(nullable = false, columnDefinition = "jsonb")
	private List<String> issn;

	private String degree;

	@Column(name = "citation_count")
	private Integer citationCount;

	@Column(name = "citation_count_as_of")
	private Instant citationCountAsOf;

	@Column(name = "metadata_quality", nullable = false, precision = 5, scale = 4)
	private BigDecimal metadataQuality;

	@Column(name = "metadata_updated_at", nullable = false)
	private Instant metadataUpdatedAt;

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
		this.isbn = List.of();
		this.issn = List.of();
		this.metadataQuality = BigDecimal.ZERO;
		this.metadataUpdatedAt = now;
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

	static PaperEntity create(CanonicalPaperCandidate candidate, Instant metadataTimestamp, Instant now) {
		PaperEntity paper = create(candidate.title(), candidate.documentType(), now);
		paper.metadataUpdatedAt = Objects.requireNonNull(metadataTimestamp, "metadataTimestamp");
		paper.apply(candidate, metadataTimestamp, now);
		return paper;
	}

	void apply(CanonicalPaperCandidate candidate, Instant incomingTimestamp, Instant now) {
		Objects.requireNonNull(candidate, "candidate");
		Objects.requireNonNull(incomingTimestamp, "incomingTimestamp");
		Objects.requireNonNull(now, "now");

		boolean incomingIsAtLeastAsRecent = !incomingTimestamp.isBefore(metadataUpdatedAt);
		if (incomingIsAtLeastAsRecent) {
			setTitle(candidate.title());
			documentType = candidate.documentType();
		}

		abstractText = mergeText(abstractText, candidate.abstractText(), incomingIsAtLeastAsRecent);
		mergePublication(candidate.publicationDate(), candidate.publicationYear(), incomingIsAtLeastAsRecent);
		language = mergeText(language, candidate.language(), incomingIsAtLeastAsRecent);
		venueName = mergeText(venueName, candidate.venueName(), incomingIsAtLeastAsRecent);
		publisher = mergeText(publisher, candidate.publisher(), incomingIsAtLeastAsRecent);
		institution = mergeText(institution, candidate.institution(), incomingIsAtLeastAsRecent);
		volume = mergeText(volume, candidate.volume(), incomingIsAtLeastAsRecent);
		issue = mergeText(issue, candidate.issue(), incomingIsAtLeastAsRecent);
		pages = mergeText(pages, candidate.pages(), incomingIsAtLeastAsRecent);
		articleNumber = mergeText(articleNumber, candidate.articleNumber(), incomingIsAtLeastAsRecent);
		edition = mergeText(edition, candidate.edition(), incomingIsAtLeastAsRecent);
		isbn = mergeList(isbn, candidate.isbn(), incomingIsAtLeastAsRecent);
		issn = mergeList(issn, candidate.issn(), incomingIsAtLeastAsRecent);
		degree = mergeText(degree, candidate.degree(), incomingIsAtLeastAsRecent);

		if (incomingIsAtLeastAsRecent) {
			metadataUpdatedAt = incomingTimestamp;
		}
		updatedAt = now;
	}

	void applyCitation(Integer incomingCount, Instant incomingAsOf, Instant now) {
		if (incomingCount == null || incomingAsOf == null) {
			return;
		}
		if (incomingCount < 0) {
			throw new IllegalArgumentException("Citation count must not be negative");
		}
		if (citationCountAsOf == null || !incomingAsOf.isBefore(citationCountAsOf)) {
			citationCount = incomingCount;
			citationCountAsOf = incomingAsOf;
			updatedAt = now;
		}
	}

	void updateMetadataQuality(BigDecimal quality, Instant now) {
		Objects.requireNonNull(quality, "quality");
		if (quality.compareTo(BigDecimal.ZERO) < 0 || quality.compareTo(BigDecimal.ONE) > 0) {
			throw new IllegalArgumentException("Metadata quality must be between zero and one");
		}
		metadataQuality = quality;
		updatedAt = now;
	}

	private void setTitle(String value) {
		String cleanTitle = cleanRequired(value, "Paper title must not be blank");
		title = cleanTitle;
		normalizedTitle = normalizeTitle(cleanTitle);
	}

	private static String mergeText(String current, String incoming, boolean replace) {
		String cleanIncoming = cleanOptional(incoming);
		if (cleanIncoming == null) {
			return current;
		}
		return current == null || replace ? cleanIncoming : current;
	}

	private static <T> T mergeValue(T current, T incoming, boolean replace) {
		if (incoming == null) {
			return current;
		}
		return current == null || replace ? incoming : current;
	}

	private static List<String> mergeList(List<String> current, List<String> incoming, boolean replace) {
		if (incoming == null || incoming.isEmpty()) {
			return current == null ? List.of() : current;
		}
		return current == null || current.isEmpty() || replace ? List.copyOf(incoming) : current;
	}

	private void mergePublication(
			LocalDate incomingDate, Integer incomingYear, boolean replace) {
		boolean missingDateCanBeEnriched = incomingDate != null
				&& publicationDate == null
				&& (publicationYear == null || publicationYear == incomingDate.getYear());
		if (incomingDate != null && (replace || missingDateCanBeEnriched)) {
			publicationDate = incomingDate;
			publicationYear = incomingDate.getYear();
			return;
		}
		if (publicationDate != null) {
			publicationYear = publicationDate.getYear();
			return;
		}
		publicationYear = mergeValue(publicationYear, incomingYear, replace);
	}

	private static String cleanRequired(String value, String message) {
		String clean = cleanOptional(value);
		if (clean == null) {
			throw new IllegalArgumentException(message);
		}
		return clean;
	}

	private static String cleanOptional(String value) {
		if (value == null) {
			return null;
		}
		String clean = value.strip();
		return clean.isEmpty() ? null : clean;
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

	String abstractText() {
		return abstractText;
	}

	LocalDate publicationDate() {
		return publicationDate;
	}

	Integer publicationYear() {
		return publicationYear;
	}

	DocumentType documentType() {
		return documentType;
	}

	String language() {
		return language;
	}

	String venueName() {
		return venueName;
	}

	String publisher() {
		return publisher;
	}

	String institution() {
		return institution;
	}

	String volume() {
		return volume;
	}

	String issue() {
		return issue;
	}

	String pages() {
		return pages;
	}

	String articleNumber() {
		return articleNumber;
	}

	String edition() {
		return edition;
	}

	List<String> isbn() {
		return isbn;
	}

	List<String> issn() {
		return issn;
	}

	String degree() {
		return degree;
	}

	Integer citationCount() {
		return citationCount;
	}

	Instant citationCountAsOf() {
		return citationCountAsOf;
	}

	BigDecimal metadataQuality() {
		return metadataQuality;
	}

	Instant metadataUpdatedAt() {
		return metadataUpdatedAt;
	}
}
