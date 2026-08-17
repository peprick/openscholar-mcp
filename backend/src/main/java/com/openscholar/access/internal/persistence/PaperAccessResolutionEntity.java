package com.openscholar.access.internal.persistence;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.openscholar.access.AccessStatus;
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
@Table(name = "paper_access_resolution")
class PaperAccessResolutionEntity {

	@Id
	@Column(name = "paper_id")
	private UUID paperId;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 32)
	private AccessStatus status;

	@Column(name = "checked_at", nullable = false)
	private Instant checkedAt;

	@Column(name = "fresh_until", nullable = false)
	private Instant freshUntil;

	@Column(name = "lookup_fingerprint", nullable = false, length = 64)
	private String lookupFingerprint;

	@JdbcTypeCode(SqlTypes.JSON)
	@Column(name = "provider_coverage", nullable = false, columnDefinition = "jsonb")
	private List<Map<String, Object>> providerCoverage;

	@JdbcTypeCode(SqlTypes.JSON)
	@Column(nullable = false, columnDefinition = "jsonb")
	private List<String> warnings;

	@Version
	private long version;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	protected PaperAccessResolutionEntity() {
	}

	private PaperAccessResolutionEntity(UUID paperId, Instant now) {
		this.paperId = paperId;
		this.createdAt = now;
		this.updatedAt = now;
	}

	static PaperAccessResolutionEntity create(
			UUID paperId,
			AccessStatus status,
			Instant checkedAt,
			Instant freshUntil,
			String lookupFingerprint,
			List<Map<String, Object>> providerCoverage,
			List<String> warnings,
			Instant now) {
		PaperAccessResolutionEntity entity = new PaperAccessResolutionEntity(paperId, now);
		entity.apply(status, checkedAt, freshUntil, lookupFingerprint, providerCoverage, warnings, now);
		return entity;
	}

	void apply(
			AccessStatus status,
			Instant checkedAt,
			Instant freshUntil,
			String lookupFingerprint,
			List<Map<String, Object>> providerCoverage,
			List<String> warnings,
			Instant now) {
		this.status = status;
		this.checkedAt = checkedAt;
		this.freshUntil = freshUntil;
		this.lookupFingerprint = lookupFingerprint;
		this.providerCoverage = List.copyOf(providerCoverage);
		this.warnings = List.copyOf(warnings);
		this.updatedAt = now;
	}

	UUID paperId() {
		return paperId;
	}

	AccessStatus status() {
		return status;
	}

	Instant checkedAt() {
		return checkedAt;
	}

	Instant freshUntil() {
		return freshUntil;
	}

	String lookupFingerprint() {
		return lookupFingerprint;
	}

	List<Map<String, Object>> providerCoverage() {
		return providerCoverage;
	}

	List<String> warnings() {
		return warnings;
	}
}
