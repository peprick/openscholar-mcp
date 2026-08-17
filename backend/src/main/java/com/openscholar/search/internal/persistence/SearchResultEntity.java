package com.openscholar.search.internal.persistence;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.openscholar.paper.PaperView;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(
		name = "search_result",
		uniqueConstraints = {
			@UniqueConstraint(name = "uk_search_result_paper", columnNames = {"search_id", "paper_id"}),
			@UniqueConstraint(name = "uk_search_result_rank", columnNames = {"search_id", "result_rank"})
		})
class SearchResultEntity {

	@Id
	private UUID id;

	@Column(name = "search_id", nullable = false)
	private UUID searchId;

	@Column(name = "paper_id", nullable = false)
	private UUID paperId;

	@JdbcTypeCode(SqlTypes.JSON)
	@Column(name = "paper_snapshot", nullable = false, columnDefinition = "jsonb")
	private SearchPaperSnapshot paperSnapshot;

	@Column(name = "result_rank", nullable = false)
	private int resultRank;

	@Column(name = "total_score")
	private Double totalScore;

	@Column(name = "reported_open_access", nullable = false)
	private boolean reportedOpenAccess;

	@Column(name = "landing_page_url")
	private String landingPageUrl;

	@Column(name = "pdf_url")
	private String pdfUrl;

	@JdbcTypeCode(SqlTypes.JSON)
	@Column(name = "ranking_reasons", nullable = false, columnDefinition = "jsonb")
	private List<Map<String, Object>> rankingReasons;

	@JdbcTypeCode(SqlTypes.JSON)
	@Column(name = "provider_contributions", nullable = false, columnDefinition = "jsonb")
	private List<Map<String, Object>> providerContributions;

	@Column(nullable = false, length = 32)
	private String provider;

	@Column(name = "provider_record_id", nullable = false)
	private String providerRecordId;

	@Column(name = "retrieved_at", nullable = false)
	private Instant retrievedAt;

	protected SearchResultEntity() {
	}

	private SearchResultEntity(
			UUID searchId,
			PaperView paper,
			int resultRank,
			Double totalScore,
			boolean reportedOpenAccess,
			String landingPageUrl,
			String pdfUrl,
			List<Map<String, Object>> rankingReasons,
			List<Map<String, Object>> providerContributions,
			String provider,
			String providerRecordId,
			Instant retrievedAt) {
		this.id = UUID.randomUUID();
		this.searchId = searchId;
		this.paperId = paper.id();
		this.paperSnapshot = SearchPaperSnapshot.from(paper);
		this.resultRank = resultRank;
		this.totalScore = totalScore;
		this.reportedOpenAccess = reportedOpenAccess;
		this.landingPageUrl = landingPageUrl;
		this.pdfUrl = pdfUrl;
		this.rankingReasons = rankingReasons;
		this.providerContributions = providerContributions;
		this.provider = provider;
		this.providerRecordId = providerRecordId;
		this.retrievedAt = retrievedAt;
	}

	static SearchResultEntity create(
			UUID searchId,
			PaperView paper,
			int resultRank,
			Double totalScore,
			boolean reportedOpenAccess,
			String landingPageUrl,
			String pdfUrl,
			List<Map<String, Object>> rankingReasons,
			List<Map<String, Object>> providerContributions,
			String provider,
			String providerRecordId,
			Instant retrievedAt) {
		return new SearchResultEntity(
				searchId,
				paper,
				resultRank,
				totalScore,
				reportedOpenAccess,
				landingPageUrl,
				pdfUrl,
				rankingReasons,
				providerContributions,
				provider,
				providerRecordId,
				retrievedAt);
	}

	UUID paperId() {
		return paperId;
	}

	PaperView paperView() {
		return paperSnapshot.toView();
	}

	int resultRank() {
		return resultRank;
	}

	Double totalScore() {
		return totalScore;
	}

	boolean reportedOpenAccess() {
		return reportedOpenAccess;
	}

	String landingPageUrl() {
		return landingPageUrl;
	}

	String pdfUrl() {
		return pdfUrl;
	}

	List<Map<String, Object>> rankingReasons() {
		return rankingReasons;
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
