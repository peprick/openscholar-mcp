package com.openscholar.jobs.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import com.openscholar.TestcontainersConfiguration;
import com.openscholar.jobs.ResearchRefreshJobNotRetryableException;
import com.openscholar.jobs.ResearchRefreshJobStatus;
import com.openscholar.jobs.ResearchRefreshJobTrigger;
import com.openscholar.jobs.ResearchRefreshJobType;
import com.openscholar.search.SearchFingerprintVersion;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
class ResearchRefreshJobStoreIntegrationTests {

	private static final Instant NOW = Instant.parse("2026-08-21T10:00:00Z");

	@Autowired
	private ResearchRefreshJobStore store;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@BeforeEach
	void clearJobs() {
		jdbcTemplate.update("DELETE FROM research_refresh_job");
	}

	@Test
	void deduplicatesActiveJobsAndListsTheDurableRecord() {
		UUID targetId = UUID.randomUUID();

		var first = store.enqueue(
				ResearchRefreshJobType.SEARCH_METADATA,
				targetId,
				ResearchRefreshJobTrigger.MANUAL,
				3,
				NOW);
		var duplicate = store.enqueue(
				ResearchRefreshJobType.SEARCH_METADATA,
				targetId,
				ResearchRefreshJobTrigger.SCHEDULED,
				3,
				NOW.plusSeconds(1));

		assertThat(duplicate.id()).isEqualTo(first.id());
		assertThat(duplicate.trigger()).isEqualTo(ResearchRefreshJobTrigger.MANUAL);
		assertThat(store.list(0, 20).items()).containsExactly(first);
		assertThat(store.list(0, 20).totalElements()).isEqualTo(1);
	}

	@Test
	void claimsAndCompletesAJobWithItsLeaseToken() {
		var queued = store.enqueue(
				ResearchRefreshJobType.PAPER_ACCESS,
				UUID.randomUUID(),
				ResearchRefreshJobTrigger.MANUAL,
				3,
				NOW);

		var claimed = store.claim(NOW, Duration.ofMinutes(2)).orElseThrow();

		assertThat(claimed.job().id()).isEqualTo(queued.id());
		assertThat(claimed.job().status()).isEqualTo(ResearchRefreshJobStatus.RUNNING);
		assertThat(claimed.job().attemptCount()).isEqualTo(1);
		assertThat(claimed.job().leasedUntil()).isEqualTo(NOW.plus(Duration.ofMinutes(2)));
		assertThat(store.claim(NOW.plusSeconds(1), Duration.ofMinutes(2))).isEmpty();
		assertThat(store.succeed(queued.id(), claimed.leaseToken(), NOW.plusSeconds(2))).isTrue();

		var completed = store.find(queued.id()).orElseThrow();
		assertThat(completed.status()).isEqualTo(ResearchRefreshJobStatus.SUCCEEDED);
		assertThat(completed.completedAt()).isEqualTo(NOW.plusSeconds(2));
		assertThat(completed.leasedUntil()).isNull();
	}

	@Test
	void appliesRetryBackoffThenAllowsAnExplicitRetryAfterExhaustion() {
		var queued = store.enqueue(
				ResearchRefreshJobType.SEARCH_METADATA,
				UUID.randomUUID(),
				ResearchRefreshJobTrigger.MANUAL,
				2,
				NOW);
		var firstAttempt = store.claim(NOW, Duration.ofMinutes(1)).orElseThrow();

		assertThat(store.fail(
				firstAttempt,
				"PROVIDER_UNAVAILABLE",
				"Temporary provider outage",
				true,
				NOW.plusSeconds(5),
				Duration.ofSeconds(30))).isTrue();
		assertThat(store.claim(NOW.plusSeconds(34), Duration.ofMinutes(1))).isEmpty();

		var secondAttempt = store.claim(NOW.plusSeconds(35), Duration.ofMinutes(1)).orElseThrow();
		assertThat(secondAttempt.job().attemptCount()).isEqualTo(2);
		assertThat(store.fail(
				secondAttempt,
				"PROVIDER_UNAVAILABLE",
				"Still unavailable",
				true,
				NOW.plusSeconds(40),
				Duration.ofSeconds(60))).isTrue();

		var failed = store.find(queued.id()).orElseThrow();
		assertThat(failed.status()).isEqualTo(ResearchRefreshJobStatus.FAILED);
		assertThat(failed.lastErrorCode()).isEqualTo("PROVIDER_UNAVAILABLE");
		assertThat(failed.completedAt()).isEqualTo(NOW.plusSeconds(40));

		var retried = store.retry(queued.id(), NOW.plusSeconds(50));
		assertThat(retried.status()).isEqualTo(ResearchRefreshJobStatus.QUEUED);
		assertThat(retried.trigger()).isEqualTo(ResearchRefreshJobTrigger.RETRY);
		assertThat(retried.attemptCount()).isZero();
		assertThat(retried.lastErrorCode()).isNull();
	}

	@Test
	void reclaimsAnExpiredLeaseAndRejectsCompletionByTheOldWorker() {
		var queued = store.enqueue(
				ResearchRefreshJobType.PAPER_ACCESS,
				UUID.randomUUID(),
				ResearchRefreshJobTrigger.MANUAL,
				3,
				NOW);
		var abandoned = store.claim(NOW, Duration.ofSeconds(10)).orElseThrow();
		var reclaimed = store.claim(NOW.plusSeconds(11), Duration.ofSeconds(10)).orElseThrow();

		assertThat(reclaimed.job().id()).isEqualTo(queued.id());
		assertThat(reclaimed.job().attemptCount()).isEqualTo(2);
		assertThat(reclaimed.leaseToken()).isNotEqualTo(abandoned.leaseToken());
		assertThat(store.succeed(queued.id(), abandoned.leaseToken(), NOW.plusSeconds(12))).isFalse();
		assertThat(store.succeed(queued.id(), reclaimed.leaseToken(), NOW.plusSeconds(12))).isTrue();
	}

	@Test
	void refusesToRetryAnOldFailureWhenTheTargetAlreadyHasANewerActiveJob() {
		UUID targetId = UUID.randomUUID();
		var first = store.enqueue(
				ResearchRefreshJobType.PAPER_ACCESS,
				targetId,
				ResearchRefreshJobTrigger.MANUAL,
				1,
				NOW);
		var claimed = store.claim(NOW, Duration.ofMinutes(1)).orElseThrow();
		store.fail(claimed, "FAILED", "Terminal failure", false, NOW.plusSeconds(1), Duration.ZERO);
		var active = store.enqueue(
				ResearchRefreshJobType.PAPER_ACCESS,
				targetId,
				ResearchRefreshJobTrigger.MANUAL,
				1,
				NOW.plusSeconds(2));

		assertThat(active.id()).isNotEqualTo(first.id());
		assertThatThrownBy(() -> store.retry(first.id(), NOW.plusSeconds(3)))
				.isInstanceOf(ResearchRefreshJobNotRetryableException.class);
		assertThat(store.find(active.id()).orElseThrow().status())
				.isEqualTo(ResearchRefreshJobStatus.QUEUED);
	}

	@Test
	void selectsOnlyLatestStaleProviderSearchesAndStaleAccessResolutions() {
		UUID paperId = insertPaper("Stale access paper");
		insertAccessResolution(paperId, NOW.minusSeconds(1));
		UUID freshPaperId = insertPaper("Fresh access paper");
		insertAccessResolution(freshPaperId, NOW.plusSeconds(1));

		String refreshedFingerprint = "a".repeat(64);
		insertSearchSnapshot(refreshedFingerprint, NOW.minusSeconds(20), NOW.minusSeconds(10));
		insertSearchSnapshot(refreshedFingerprint, NOW.minusSeconds(2), NOW.plusSeconds(30));
		String staleFingerprint = "b".repeat(64);
		UUID staleSearchId = insertSearchSnapshot(staleFingerprint, NOW.minusSeconds(20), NOW.minusSeconds(1));
		UUID staleLocalSearchId = insertSearchSnapshot(
				"c".repeat(64), NOW.minusSeconds(20), NOW.minusSeconds(1));
		jdbcTemplate.update("""
				UPDATE search_snapshot
				SET requested_mode = 'LOCAL', result_origin = 'LOCAL_CATALOG'
				WHERE id = ?
				""", staleLocalSearchId);
		insertSearchSnapshot(
				"d".repeat(64), NOW.minusSeconds(20), NOW.minusSeconds(1), 1);

		assertThat(store.staleAccessTargets(NOW, 10)).containsExactly(paperId);
		assertThat(store.staleSearchTargets(NOW, 10)).containsExactly(staleSearchId);
	}

	private UUID insertPaper(String title) {
		UUID id = UUID.randomUUID();
		jdbcTemplate.update("""
				INSERT INTO paper (
				    id, title, normalized_title, document_type, metadata_quality,
				    metadata_updated_at, version, created_at, updated_at
				)
				VALUES (?, ?, lower(?), 'ARTICLE', 0, ?, 0, ?, ?)
				""", id, title, title, databaseTime(NOW), databaseTime(NOW), databaseTime(NOW));
		return id;
	}

	private void insertAccessResolution(UUID paperId, Instant freshUntil) {
		Instant checkedAt = freshUntil.minusSeconds(60);
		jdbcTemplate.update("""
				INSERT INTO paper_access_resolution (
				    paper_id, status, checked_at, fresh_until, provider_coverage, warnings,
				    version, created_at, updated_at, lookup_fingerprint
				)
				VALUES (?, 'NOT_FOUND', ?, ?, '[]'::jsonb, '[]'::jsonb, 0, ?, ?, ?)
				""", paperId, databaseTime(checkedAt), databaseTime(freshUntil), databaseTime(checkedAt),
				databaseTime(checkedAt), "0".repeat(64));
	}

	private UUID insertSearchSnapshot(String fingerprint, Instant searchedAt, Instant freshUntil) {
		return insertSearchSnapshot(
				fingerprint, searchedAt, freshUntil, SearchFingerprintVersion.CURRENT);
	}

	private UUID insertSearchSnapshot(
			String fingerprint, Instant searchedAt, Instant freshUntil, int fingerprintVersion) {
		UUID id = UUID.randomUUID();
		jdbcTemplate.update("""
				INSERT INTO search_snapshot (
				    id, owner_id, original_query, normalized_query, fingerprint, fingerprint_version,
				    pipeline_version, filters, status, searched_at, fresh_until,
				    provider_coverage, warnings, total_provider_matches, result_count,
				    next_cursor, created_at
				)
				VALUES (?, '00000000-0000-0000-0000-000000000001', 'durable jobs', 'durable jobs', ?, ?, 'test-v1',
				        '{"documentTypes":[],"openAccessOnly":false,"minimumCitations":0,"languages":[],"pageSize":20,"cursor":"*"}'::jsonb,
				        'COMPLETED', ?, ?, '[]'::jsonb, '[]'::jsonb, 0, 0, NULL, ?)
				""", id, fingerprint, fingerprintVersion, databaseTime(searchedAt), databaseTime(freshUntil),
				databaseTime(searchedAt));
		return id;
	}

	private static OffsetDateTime databaseTime(Instant instant) {
		return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
	}
}
