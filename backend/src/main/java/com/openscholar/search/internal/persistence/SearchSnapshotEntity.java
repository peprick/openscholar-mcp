package com.openscholar.search.internal.persistence;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "search_snapshot")
class SearchSnapshotEntity {

	@Id
	private UUID id;

	@Column(name = "original_query", nullable = false)
	private String originalQuery;

	@Column(name = "normalized_query", nullable = false)
	private String normalizedQuery;

	@Column(nullable = false, length = 64)
	private String fingerprint;

	@Column(name = "fingerprint_version", nullable = false)
	private int fingerprintVersion;

	@Column(name = "pipeline_version", nullable = false, length = 32)
	private String pipelineVersion;

	@JdbcTypeCode(SqlTypes.JSON)
	@Column(nullable = false, columnDefinition = "jsonb")
	private Map<String, Object> filters;

	@Column(nullable = false, length = 24)
	private String status;

	@Column(name = "searched_at", nullable = false)
	private Instant searchedAt;

	@Column(name = "fresh_until", nullable = false)
	private Instant freshUntil;

	@JdbcTypeCode(SqlTypes.JSON)
	@Column(name = "provider_coverage", nullable = false, columnDefinition = "jsonb")
	private List<Map<String, Object>> providerCoverage;

	@JdbcTypeCode(SqlTypes.JSON)
	@Column(nullable = false, columnDefinition = "jsonb")
	private List<String> warnings;

	@Column(name = "total_provider_matches")
	private Long totalProviderMatches;

	@Column(name = "result_count", nullable = false)
	private int resultCount;

	@Column(name = "next_cursor")
	private String nextCursor;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	protected SearchSnapshotEntity() {
	}

	private SearchSnapshotEntity(
			UUID id,
			String originalQuery,
			String normalizedQuery,
			String fingerprint,
			int fingerprintVersion,
			String pipelineVersion,
			Map<String, Object> filters,
			Instant searchedAt,
			Instant freshUntil,
			List<Map<String, Object>> providerCoverage,
			List<String> warnings,
			long totalProviderMatches,
			int resultCount,
			String nextCursor) {
		this.id = id;
		this.originalQuery = originalQuery;
		this.normalizedQuery = normalizedQuery;
		this.fingerprint = fingerprint;
		this.fingerprintVersion = fingerprintVersion;
		this.pipelineVersion = pipelineVersion;
		this.filters = filters;
		this.status = "COMPLETED";
		this.searchedAt = searchedAt;
		this.freshUntil = freshUntil;
		this.providerCoverage = providerCoverage;
		this.warnings = warnings;
		this.totalProviderMatches = totalProviderMatches;
		this.resultCount = resultCount;
		this.nextCursor = nextCursor;
		this.createdAt = searchedAt;
	}

	static SearchSnapshotEntity completed(
			String originalQuery,
			String normalizedQuery,
			String fingerprint,
			int fingerprintVersion,
			String pipelineVersion,
			Map<String, Object> filters,
			Instant searchedAt,
			Instant freshUntil,
			List<Map<String, Object>> providerCoverage,
			List<String> warnings,
			long totalProviderMatches,
			int resultCount,
			String nextCursor) {
		return new SearchSnapshotEntity(
				UUID.randomUUID(),
				originalQuery,
				normalizedQuery,
				fingerprint,
				fingerprintVersion,
				pipelineVersion,
				filters,
				searchedAt,
				freshUntil,
				providerCoverage,
				warnings,
				totalProviderMatches,
				resultCount,
				nextCursor);
	}

	UUID id() {
		return id;
	}

	String originalQuery() {
		return originalQuery;
	}

	String fingerprint() {
		return fingerprint;
	}

	Map<String, Object> filters() {
		return filters;
	}

	Instant searchedAt() {
		return searchedAt;
	}

	Instant freshUntil() {
		return freshUntil;
	}

	List<Map<String, Object>> providerCoverage() {
		return providerCoverage;
	}

	List<String> warnings() {
		return warnings;
	}

	String nextCursor() {
		return nextCursor;
	}
}
