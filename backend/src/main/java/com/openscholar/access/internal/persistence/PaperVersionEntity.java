package com.openscholar.access.internal.persistence;

import java.net.URI;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;

import com.openscholar.access.AccessHostType;
import com.openscholar.access.AccessStatus;
import com.openscholar.access.AccessVerificationStatus;
import com.openscholar.access.AccessVersionType;
import com.openscholar.access.internal.ResolvedAccessLocation;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

@Entity
@Table(
		name = "paper_version",
		uniqueConstraints = @UniqueConstraint(
				name = "uk_paper_version_source_location",
				columnNames = {"paper_id", "source", "source_location_key"}))
class PaperVersionEntity {

	@Id
	private UUID id;

	@Column(name = "paper_id", nullable = false)
	private UUID paperId;

	@Column(nullable = false, length = 32)
	private String source;

	@Column(name = "source_location_key", nullable = false, length = 64)
	private String sourceLocationKey;

	@Column(nullable = false)
	private boolean active;

	@Column(name = "is_best", nullable = false)
	private boolean best;

	@Enumerated(EnumType.STRING)
	@Column(name = "version_type", nullable = false, length = 32)
	private AccessVersionType versionType;

	@Enumerated(EnumType.STRING)
	@Column(name = "host_type", nullable = false, length = 32)
	private AccessHostType hostType;

	@Enumerated(EnumType.STRING)
	@Column(name = "access_status", nullable = false, length = 32)
	private AccessStatus accessStatus;

	@Column(name = "landing_url", columnDefinition = "text")
	private String landingUrl;

	@Column(name = "pdf_url", columnDefinition = "text")
	private String pdfUrl;

	@Column(name = "host_domain", length = 255)
	private String hostDomain;

	@Column(name = "license_code", length = 255)
	private String licenseCode;

	@Column(columnDefinition = "text")
	private String evidence;

	@Column(name = "content_handling", nullable = false, length = 32)
	private String contentHandling;

	@Enumerated(EnumType.STRING)
	@Column(name = "verification_status", nullable = false, length = 32)
	private AccessVerificationStatus verificationStatus;

	@Column(name = "verification_http_status")
	private Integer verificationHttpStatus;

	@Column(name = "verification_content_type", length = 255)
	private String verificationContentType;

	@Column(name = "verification_failure_code", length = 64)
	private String verificationFailureCode;

	@Column(name = "provider_updated_at")
	private Instant providerUpdatedAt;

	@Column(name = "retrieved_at", nullable = false)
	private Instant retrievedAt;

	@Column(name = "last_seen_at", nullable = false)
	private Instant lastSeenAt;

	@Column(name = "verified_at")
	private Instant verifiedAt;

	@Column(name = "retention_allowed", nullable = false)
	private boolean retentionAllowed;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	protected PaperVersionEntity() {
	}

	private PaperVersionEntity(
			UUID id, UUID paperId, String source, String sourceLocationKey, Instant now) {
		this.id = id;
		this.paperId = paperId;
		this.source = source;
		this.sourceLocationKey = sourceLocationKey;
		this.createdAt = now;
		this.updatedAt = now;
	}

	static PaperVersionEntity create(
			UUID paperId, String source, String locationKey, ResolvedAccessLocation location, Instant now) {
		PaperVersionEntity entity = new PaperVersionEntity(
				UUID.randomUUID(), paperId, normalizeSource(source), locationKey, now);
		entity.apply(location, now);
		return entity;
	}

	void apply(ResolvedAccessLocation location, Instant now) {
		active = true;
		best = location.best();
		versionType = location.versionType();
		hostType = location.hostType();
		accessStatus = location.accessStatus();
		landingUrl = asString(location.landingPageUrl());
		pdfUrl = asString(location.pdfUrl());
		hostDomain = host(location.pdfUrl() != null ? location.pdfUrl() : location.landingPageUrl());
		licenseCode = bounded(location.license(), 255);
		evidence = bounded(location.evidence(), 4096);
		contentHandling = "LINK_ONLY";
		verificationStatus = location.verificationStatus();
		verificationHttpStatus = location.verificationHttpStatus();
		verificationContentType = bounded(location.verificationContentType(), 255);
		verificationFailureCode = bounded(location.verificationFailureCode(), 64);
		providerUpdatedAt = location.providerUpdatedAt();
		retrievedAt = location.retrievedAt();
		lastSeenAt = now;
		verifiedAt = location.verifiedAt();
		retentionAllowed = false;
		updatedAt = now;
	}

	void deactivate(Instant now) {
		active = false;
		best = false;
		updatedAt = now;
	}

	private static String normalizeSource(String source) {
		String clean = source == null ? "" : source.strip().toUpperCase(Locale.ROOT);
		if (clean.isEmpty() || clean.length() > 32) {
			throw new IllegalArgumentException("Access source must contain between 1 and 32 characters");
		}
		return clean;
	}

	private static String asString(URI value) {
		return value == null ? null : value.toASCIIString();
	}

	private static String host(URI value) {
		return value == null || value.getHost() == null
				? null
				: value.getHost().toLowerCase(Locale.ROOT);
	}

	private static String bounded(String value, int length) {
		if (value == null || value.isBlank()) {
			return null;
		}
		String clean = value.strip();
		return clean.length() <= length ? clean : clean.substring(0, length);
	}

	UUID id() {
		return id;
	}

	String source() {
		return source;
	}

	boolean active() {
		return active;
	}

	boolean best() {
		return best;
	}

	AccessVersionType versionType() {
		return versionType;
	}

	AccessHostType hostType() {
		return hostType;
	}

	AccessStatus accessStatus() {
		return accessStatus;
	}

	String landingUrl() {
		return landingUrl;
	}

	String pdfUrl() {
		return pdfUrl;
	}

	String hostDomain() {
		return hostDomain;
	}

	String licenseCode() {
		return licenseCode;
	}

	String evidence() {
		return evidence;
	}

	String contentHandling() {
		return contentHandling;
	}

	AccessVerificationStatus verificationStatus() {
		return verificationStatus;
	}

	Integer verificationHttpStatus() {
		return verificationHttpStatus;
	}

	String verificationContentType() {
		return verificationContentType;
	}

	String verificationFailureCode() {
		return verificationFailureCode;
	}

	Instant providerUpdatedAt() {
		return providerUpdatedAt;
	}

	Instant retrievedAt() {
		return retrievedAt;
	}

	Instant lastSeenAt() {
		return lastSeenAt;
	}

	Instant verifiedAt() {
		return verifiedAt;
	}
}
