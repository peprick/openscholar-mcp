package com.openscholar.paper.internal.persistence;

import java.net.URI;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import com.openscholar.paper.ProviderRecordCandidate;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(
		name = "provider_record",
		uniqueConstraints = @UniqueConstraint(
				name = "uk_provider_record_provider_record_id",
				columnNames = {"provider", "provider_record_id"}))
class ProviderRecordEntity {

	@Id
	private UUID id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "paper_id", nullable = false)
	private PaperEntity paper;

	@Column(nullable = false, length = 64)
	private String provider;

	@Column(name = "provider_record_id", nullable = false)
	private String providerRecordId;

	@Column(name = "provider_updated_at")
	private Instant providerUpdatedAt;

	@Column(name = "retrieved_at", nullable = false)
	private Instant retrievedAt;

	@Column(name = "source_url")
	private String sourceUrl;

	@Column(name = "reported_open_access", nullable = false)
	private boolean reportedOpenAccess;

	@Column(name = "landing_page_url")
	private String landingPageUrl;

	@Column(name = "pdf_url")
	private String pdfUrl;

	@JdbcTypeCode(SqlTypes.JSON)
	@Column(name = "metadata_fragment", nullable = false, columnDefinition = "jsonb")
	private Map<String, Object> metadataFragment;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	protected ProviderRecordEntity() {
	}

	private ProviderRecordEntity(
			UUID id,
			PaperEntity paper,
			String provider,
			String providerRecordId,
			Instant retrievedAt,
			Instant now) {
		this.id = id;
		this.paper = paper;
		this.provider = provider;
		this.providerRecordId = providerRecordId;
		this.retrievedAt = retrievedAt;
		this.metadataFragment = Map.of();
		this.createdAt = now;
		this.updatedAt = now;
	}

	static ProviderRecordEntity create(
			PaperEntity paper,
			ProviderRecordCandidate candidate,
			Map<String, Object> metadataFragment,
			Instant now) {
		ProviderRecordEntity record = new ProviderRecordEntity(
				UUID.randomUUID(),
				paper,
				normalizeProvider(candidate.provider()),
				cleanRequired(candidate.providerRecordId(), "Provider record identifier must not be blank"),
				candidate.retrievedAt(),
				now);
		record.apply(candidate, metadataFragment, now);
		return record;
	}

	boolean apply(
			ProviderRecordCandidate candidate,
			Map<String, Object> incomingMetadataFragment,
			Instant now) {
		if (candidate.retrievedAt().isBefore(retrievedAt)) {
			return false;
		}
		providerUpdatedAt = newer(providerUpdatedAt, candidate.providerUpdatedAt());
		retrievedAt = candidate.retrievedAt();
		sourceUrl = mergeUri(sourceUrl, candidate.sourceUrl());
		reportedOpenAccess = candidate.reportedOpenAccess();
		landingPageUrl = mergeUri(landingPageUrl, candidate.landingPageUrl());
		pdfUrl = mergeUri(pdfUrl, candidate.pdfUrl());
		if (incomingMetadataFragment != null && !incomingMetadataFragment.isEmpty()) {
			metadataFragment = Map.copyOf(incomingMetadataFragment);
		}
		updatedAt = now;
		return true;
	}

	private static Instant newer(Instant current, Instant incoming) {
		if (incoming == null) {
			return current;
		}
		return current == null || incoming.isAfter(current) ? incoming : current;
	}

	private static String mergeUri(String current, URI incoming) {
		return incoming == null ? current : incoming.toASCIIString();
	}

	static String normalizeProvider(String provider) {
		return cleanRequired(provider, "Provider must not be blank").toLowerCase(Locale.ROOT);
	}

	static String cleanRequired(String value, String message) {
		String clean = value == null ? "" : value.strip();
		if (clean.isEmpty()) {
			throw new IllegalArgumentException(message);
		}
		return clean;
	}

	UUID id() {
		return id;
	}

	UUID paperId() {
		return paper.id();
	}

	String provider() {
		return provider;
	}

	String providerRecordId() {
		return providerRecordId;
	}

	Instant retrievedAt() {
		return retrievedAt;
	}
}
