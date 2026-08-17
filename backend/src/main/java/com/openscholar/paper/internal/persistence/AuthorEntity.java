package com.openscholar.paper.internal.persistence;

import java.time.Instant;
import java.util.Locale;
import java.util.UUID;

import com.openscholar.paper.PaperAuthorCandidate;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "author")
class AuthorEntity {

	@Id
	private UUID id;

	@Column(name = "display_name", nullable = false)
	private String displayName;

	@Column(name = "openalex_id", length = 255)
	private String openAlexId;

	@Column(length = 64)
	private String orcid;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	protected AuthorEntity() {
	}

	private AuthorEntity(
			UUID id, String displayName, String openAlexId, String orcid, Instant now) {
		this.id = id;
		this.displayName = displayName;
		this.openAlexId = openAlexId;
		this.orcid = orcid;
		this.createdAt = now;
		this.updatedAt = now;
	}

	static AuthorEntity create(PaperAuthorCandidate candidate, Instant now) {
		return new AuthorEntity(
				UUID.randomUUID(),
				cleanDisplayName(candidate.displayName()),
				normalizeOpenAlexId(candidate.openAlexId()),
				normalizeOrcid(candidate.orcid()),
				now);
	}

	void enrich(PaperAuthorCandidate candidate, Instant now) {
		String incomingName = cleanOptional(candidate.displayName());
		if (incomingName != null) {
			displayName = incomingName;
		}
		String incomingOpenAlexId = normalizeOpenAlexId(candidate.openAlexId());
		if (openAlexId == null) {
			openAlexId = incomingOpenAlexId;
		} else if (incomingOpenAlexId != null && !openAlexId.equals(incomingOpenAlexId)) {
			throw new IllegalArgumentException("Author OpenAlex identifier conflict");
		}
		String incomingOrcid = normalizeOrcid(candidate.orcid());
		if (orcid == null) {
			orcid = incomingOrcid;
		} else if (incomingOrcid != null && !orcid.equals(incomingOrcid)) {
			throw new IllegalArgumentException("Author ORCID conflict");
		}
		updatedAt = now;
	}

	static String normalizeOpenAlexId(String value) {
		String clean = cleanOptional(value);
		if (clean == null) {
			return null;
		}
		return clean
				.toLowerCase(Locale.ROOT)
				.replaceFirst("^https?://openalex\\.org/", "");
	}

	static String normalizeOrcid(String value) {
		String clean = cleanOptional(value);
		if (clean == null) {
			return null;
		}
		return clean
				.toLowerCase(Locale.ROOT)
				.replaceFirst("^https?://orcid\\.org/", "");
	}

	private static String cleanDisplayName(String value) {
		String clean = cleanOptional(value);
		if (clean == null) {
			throw new IllegalArgumentException("Author display name must not be blank");
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

	UUID id() {
		return id;
	}

	String displayName() {
		return displayName;
	}

	String openAlexId() {
		return openAlexId;
	}

	String orcid() {
		return orcid;
	}
}
