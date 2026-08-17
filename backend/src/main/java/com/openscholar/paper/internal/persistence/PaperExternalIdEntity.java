package com.openscholar.paper.internal.persistence;

import java.time.Instant;
import java.util.Locale;
import java.util.UUID;

import com.openscholar.paper.PaperIdentifierType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

@Entity
@Table(
		name = "paper_external_id",
		uniqueConstraints = @UniqueConstraint(
				name = "uk_paper_external_id_type_namespace_value",
				columnNames = {"id_type", "namespace", "normalized_value"}))
class PaperExternalIdEntity {

	@Id
	private UUID id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "paper_id", nullable = false)
	private PaperEntity paper;

	@Enumerated(EnumType.STRING)
	@Column(name = "id_type", nullable = false, length = 32)
	private PaperIdentifierType idType;

	@Column(nullable = false, length = 255)
	private String namespace;

	@Column(name = "normalized_value", nullable = false)
	private String normalizedValue;

	@Column(name = "raw_value", nullable = false)
	private String rawValue;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	protected PaperExternalIdEntity() {
	}

	private PaperExternalIdEntity(
			UUID id,
			PaperEntity paper,
			PaperIdentifierType idType,
			String namespace,
			String normalizedValue,
			String rawValue,
			Instant createdAt) {
		this.id = id;
		this.paper = paper;
		this.idType = idType;
		this.namespace = namespace;
		this.normalizedValue = normalizedValue;
		this.rawValue = rawValue;
		this.createdAt = createdAt;
	}

	static PaperExternalIdEntity create(
			PaperEntity paper, PaperIdentifierType idType, String rawValue, Instant now) {
		return create(paper, idType, "", rawValue, now);
	}

	static PaperExternalIdEntity create(
			PaperEntity paper,
			PaperIdentifierType idType,
			String namespace,
			String rawValue,
			Instant now) {
		String cleanValue = rawValue == null ? "" : rawValue.strip();
		if (cleanValue.isEmpty()) {
			throw new IllegalArgumentException("External paper identifier must not be blank");
		}
		String cleanNamespace = namespace == null ? "" : namespace.strip().toLowerCase(Locale.ROOT);
		if (idType == PaperIdentifierType.REPOSITORY && cleanNamespace.isEmpty()) {
			throw new IllegalArgumentException("Repository identifiers require a namespace");
		}
		return new PaperExternalIdEntity(
				UUID.randomUUID(),
				paper,
				idType,
				cleanNamespace,
				normalize(idType, cleanValue),
				cleanValue,
				now);
	}

	static String normalize(PaperIdentifierType type, String value) {
		String normalized = value.toLowerCase(Locale.ROOT);
		if (type == PaperIdentifierType.DOI) {
			normalized = normalized
					.replaceFirst("^https?://(?:dx\\.)?doi\\.org/", "")
					.replaceFirst("^doi:\\s*", "");
		}
		if (type == PaperIdentifierType.OPENALEX) {
			normalized = normalized.replaceFirst("^https?://openalex\\.org/", "");
		}
		return normalized.strip();
	}

	UUID id() {
		return id;
	}

	UUID paperId() {
		return paper.id();
	}

	PaperIdentifierType idType() {
		return idType;
	}

	String namespace() {
		return namespace;
	}

	String normalizedValue() {
		return normalizedValue;
	}

	String rawValue() {
		return rawValue;
	}
}
