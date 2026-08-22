package com.openscholar.jobs.internal;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.openscholar.jobs.ResearchRefreshJobNotFoundException;
import com.openscholar.jobs.ResearchRefreshJobNotRetryableException;
import com.openscholar.jobs.ResearchRefreshJobPage;
import com.openscholar.jobs.ResearchRefreshJobStatus;
import com.openscholar.jobs.ResearchRefreshJobTrigger;
import com.openscholar.jobs.ResearchRefreshJobType;
import com.openscholar.jobs.ResearchRefreshJobView;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
class ResearchRefreshJobStore {

	private static final String COLUMNS = """
			id, job_type, target_id, trigger_kind, status, attempt_count, max_attempts,
			available_at, leased_until, last_error_code, last_error_detail, created_at,
			started_at, completed_at, updated_at
			""";
	private static final String QUALIFIED_JOB_COLUMNS = """
			job.id, job.job_type, job.target_id, job.trigger_kind, job.status,
			job.attempt_count, job.max_attempts, job.available_at, job.leased_until,
			job.last_error_code, job.last_error_detail, job.created_at, job.started_at,
			job.completed_at, job.updated_at
			""";

	private final JdbcClient jdbcClient;

	ResearchRefreshJobStore(JdbcClient jdbcClient) {
		this.jdbcClient = jdbcClient;
	}

	@Transactional
	ResearchRefreshJobView enqueue(
			ResearchRefreshJobType jobType,
			UUID targetId,
			ResearchRefreshJobTrigger trigger,
			int maxAttempts,
			Instant now) {
		UUID jobId = UUID.randomUUID();
		int inserted = jdbcClient.sql("""
				INSERT INTO research_refresh_job (
				    id, job_type, target_id, trigger_kind, status, attempt_count, max_attempts,
				    available_at, created_at, updated_at
				)
				VALUES (:id, :jobType, :targetId, :trigger, 'QUEUED', 0, :maxAttempts,
				        :now, :now, :now)
				ON CONFLICT (job_type, target_id)
				    WHERE status IN ('QUEUED', 'RUNNING')
				DO NOTHING
				""")
				.param("id", jobId)
				.param("jobType", jobType.name())
				.param("targetId", targetId)
				.param("trigger", trigger.name())
				.param("maxAttempts", maxAttempts)
				.param("now", databaseTime(now))
				.update();
		if (inserted == 1) {
			return require(jobId);
		}
		return jdbcClient.sql("SELECT " + COLUMNS + " FROM research_refresh_job "
					+ "WHERE job_type = :jobType AND target_id = :targetId "
					+ "AND status IN ('QUEUED', 'RUNNING') ORDER BY created_at, id LIMIT 1")
				.param("jobType", jobType.name())
				.param("targetId", targetId)
				.query(ResearchRefreshJobStore::map)
				.optional()
				.orElseThrow(() -> new IllegalStateException("Active refresh job disappeared after deduplication"));
	}

	@Transactional(readOnly = true)
	Optional<ResearchRefreshJobView> find(UUID jobId) {
		return jdbcClient.sql("SELECT " + COLUMNS + " FROM research_refresh_job WHERE id = :id")
				.param("id", jobId)
				.query(ResearchRefreshJobStore::map)
				.optional();
	}

	@Transactional(readOnly = true)
	Optional<ResearchRefreshJobView> findVisible(UUID jobId, UUID ownerId) {
		return jdbcClient.sql("""
				SELECT
				""" + QUALIFIED_JOB_COLUMNS + """
				FROM research_refresh_job job
				WHERE job.id = :id
				  AND (
				      job.job_type = 'PAPER_ACCESS'
				      OR EXISTS (
				          SELECT 1
				          FROM search_snapshot snapshot
				          WHERE snapshot.id = job.target_id AND snapshot.owner_id = :ownerId
				      )
				  )
				""")
				.param("id", jobId)
				.param("ownerId", ownerId)
				.query(ResearchRefreshJobStore::map)
				.optional();
	}

	@Transactional(readOnly = true)
	ResearchRefreshJobPage list(int page, int size) {
		long total = jdbcClient.sql("SELECT count(*) FROM research_refresh_job")
				.query(Long.class)
				.single();
		long offset = Math.multiplyExact((long) page, size);
		List<ResearchRefreshJobView> items = jdbcClient.sql(
					"SELECT " + COLUMNS + " FROM research_refresh_job "
							+ "ORDER BY created_at DESC, id DESC LIMIT :size OFFSET :offset")
				.param("size", size)
				.param("offset", offset)
				.query(ResearchRefreshJobStore::map)
				.list();
		int totalPages = total == 0 ? 0 : Math.toIntExact((total + size - 1) / size);
		return new ResearchRefreshJobPage(items, page, size, total, totalPages);
	}

	@Transactional(readOnly = true)
	ResearchRefreshJobPage listVisible(UUID ownerId, int page, int size) {
		String visible = """
				(job.job_type = 'PAPER_ACCESS' OR EXISTS (
				    SELECT 1
				    FROM search_snapshot snapshot
				    WHERE snapshot.id = job.target_id AND snapshot.owner_id = :ownerId
				))
				""";
		long total = jdbcClient.sql("SELECT count(*) FROM research_refresh_job job WHERE " + visible)
				.param("ownerId", ownerId)
				.query(Long.class)
				.single();
		long offset = Math.multiplyExact((long) page, size);
		List<ResearchRefreshJobView> items = jdbcClient.sql(
					"SELECT " + QUALIFIED_JOB_COLUMNS + " FROM research_refresh_job job WHERE "
							+ visible + " ORDER BY job.created_at DESC, job.id DESC LIMIT :size OFFSET :offset")
				.param("ownerId", ownerId)
				.param("size", size)
				.param("offset", offset)
				.query(ResearchRefreshJobStore::map)
				.list();
		int totalPages = total == 0 ? 0 : Math.toIntExact((total + size - 1) / size);
		return new ResearchRefreshJobPage(items, page, size, total, totalPages);
	}

	@Transactional
	ResearchRefreshJobView retry(UUID jobId, Instant now) {
		Optional<ResearchRefreshJobView> updated = jdbcClient.sql("""
				UPDATE research_refresh_job
				SET trigger_kind = 'RETRY', status = 'QUEUED', attempt_count = 0,
				    available_at = :now, lease_token = NULL, leased_until = NULL,
				    last_error_code = NULL, last_error_detail = NULL,
				    started_at = NULL, completed_at = NULL, updated_at = :now
				WHERE id = :id AND status = 'FAILED'
				  AND NOT EXISTS (
				      SELECT 1
				      FROM research_refresh_job active
				      WHERE active.job_type = research_refresh_job.job_type
				        AND active.target_id = research_refresh_job.target_id
				        AND active.status IN ('QUEUED', 'RUNNING')
				  )
				RETURNING
				""" + COLUMNS)
				.param("id", jobId)
				.param("now", databaseTime(now))
				.query(ResearchRefreshJobStore::map)
				.optional();
		if (updated.isPresent()) {
			return updated.orElseThrow();
		}
		if (find(jobId).isEmpty()) {
			throw new ResearchRefreshJobNotFoundException(jobId);
		}
		throw new ResearchRefreshJobNotRetryableException(jobId);
	}

	@Transactional
	ResearchRefreshJobView retryVisible(UUID jobId, UUID ownerId, Instant now) {
		Optional<ResearchRefreshJobView> updated = jdbcClient.sql("""
				UPDATE research_refresh_job job
				SET trigger_kind = 'RETRY', status = 'QUEUED', attempt_count = 0,
				    available_at = :now, lease_token = NULL, leased_until = NULL,
				    last_error_code = NULL, last_error_detail = NULL,
				    started_at = NULL, completed_at = NULL, updated_at = :now
				WHERE job.id = :id AND job.status = 'FAILED'
				  AND (
				      job.job_type = 'PAPER_ACCESS'
				      OR EXISTS (
				          SELECT 1
				          FROM search_snapshot snapshot
				          WHERE snapshot.id = job.target_id AND snapshot.owner_id = :ownerId
				      )
				  )
				  AND NOT EXISTS (
				      SELECT 1
				      FROM research_refresh_job active
				      WHERE active.job_type = job.job_type
				        AND active.target_id = job.target_id
				        AND active.status IN ('QUEUED', 'RUNNING')
				  )
				RETURNING
				""" + COLUMNS)
				.param("id", jobId)
				.param("ownerId", ownerId)
				.param("now", databaseTime(now))
				.query(ResearchRefreshJobStore::map)
				.optional();
		if (updated.isPresent()) {
			return updated.orElseThrow();
		}
		if (findVisible(jobId, ownerId).isEmpty()) {
			throw new ResearchRefreshJobNotFoundException(jobId);
		}
		throw new ResearchRefreshJobNotRetryableException(jobId);
	}

	@Transactional
	Optional<ClaimedResearchRefreshJob> claim(Instant now, Duration leaseDuration) {
		jdbcClient.sql("""
				UPDATE research_refresh_job
				SET status = 'FAILED', lease_token = NULL, leased_until = NULL,
				    last_error_code = 'JOB_LEASE_EXHAUSTED',
				    last_error_detail = 'The worker lease expired after the configured attempt budget.',
				    completed_at = :now, updated_at = :now
				WHERE status = 'RUNNING' AND leased_until <= :now AND attempt_count >= max_attempts
				""")
				.param("now", databaseTime(now))
				.update();

		UUID leaseToken = UUID.randomUUID();
		Instant leasedUntil = now.plus(leaseDuration);
		return jdbcClient.sql("""
				WITH candidate AS (
				    SELECT id
				    FROM research_refresh_job
				    WHERE (
				        status = 'QUEUED' AND available_at <= :now AND attempt_count < max_attempts
				    ) OR (
				        status = 'RUNNING' AND leased_until <= :now AND attempt_count < max_attempts
				    )
				    ORDER BY available_at, created_at, id
				    FOR UPDATE SKIP LOCKED
				    LIMIT 1
				)
				UPDATE research_refresh_job job
				SET status = 'RUNNING', attempt_count = attempt_count + 1,
				    lease_token = :leaseToken, leased_until = :leasedUntil,
				    started_at = COALESCE(started_at, :now), updated_at = :now
				FROM candidate
				WHERE job.id = candidate.id
				RETURNING
				""" + QUALIFIED_JOB_COLUMNS)
				.param("now", databaseTime(now))
				.param("leaseToken", leaseToken)
				.param("leasedUntil", databaseTime(leasedUntil))
				.query((resultSet, rowNumber) ->
						new ClaimedResearchRefreshJob(map(resultSet, rowNumber), leaseToken))
				.optional();
	}

	@Transactional
	boolean succeed(UUID jobId, UUID leaseToken, Instant now) {
		return jdbcClient.sql("""
				UPDATE research_refresh_job
				SET status = 'SUCCEEDED', lease_token = NULL, leased_until = NULL,
				    last_error_code = NULL, last_error_detail = NULL,
				    completed_at = :now, updated_at = :now
				WHERE id = :id AND status = 'RUNNING' AND lease_token = :leaseToken
				""")
				.param("id", jobId)
				.param("leaseToken", leaseToken)
				.param("now", databaseTime(now))
				.update() == 1;
	}

	@Transactional
	boolean fail(
			ClaimedResearchRefreshJob claimed,
			String errorCode,
			String errorDetail,
			boolean retryable,
			Instant now,
			Duration retryDelay) {
		ResearchRefreshJobView job = claimed.job();
		boolean retry = retryable && job.attemptCount() < job.maxAttempts();
		String status = retry ? "QUEUED" : "FAILED";
		Instant availableAt = retry ? now.plus(retryDelay) : now;
		return jdbcClient.sql("""
				UPDATE research_refresh_job
				SET status = :status, lease_token = NULL, leased_until = NULL,
				    available_at = :availableAt, last_error_code = :errorCode,
				    last_error_detail = :errorDetail,
				    completed_at = CASE WHEN :retry THEN NULL ELSE :now END,
				    updated_at = :now
				WHERE id = :id AND status = 'RUNNING' AND lease_token = :leaseToken
				""")
				.param("status", status)
				.param("availableAt", databaseTime(availableAt))
				.param("errorCode", bounded(errorCode, 96))
				.param("errorDetail", bounded(errorDetail, 500))
				.param("retry", retry)
				.param("now", databaseTime(now))
				.param("id", job.id())
				.param("leaseToken", claimed.leaseToken())
				.update() == 1;
	}

	@Transactional(readOnly = true)
	List<UUID> staleSearchTargets(Instant now, int limit) {
		return jdbcClient.sql("""
				SELECT latest.id
				FROM (
				    SELECT DISTINCT ON (owner_id, fingerprint)
				           id, owner_id, fingerprint, fresh_until, searched_at
				    FROM search_snapshot
				    WHERE status = 'COMPLETED'
				    ORDER BY owner_id, fingerprint, searched_at DESC, id DESC
				) latest
				WHERE latest.fresh_until <= :now
				ORDER BY latest.fresh_until, latest.id
				LIMIT :limit
				""")
				.param("now", databaseTime(now))
				.param("limit", limit)
				.query(UUID.class)
				.list();
	}

	@Transactional(readOnly = true)
	List<UUID> staleAccessTargets(Instant now, int limit) {
		return jdbcClient.sql("""
				SELECT paper_id
				FROM paper_access_resolution
				WHERE fresh_until <= :now
				ORDER BY fresh_until, paper_id
				LIMIT :limit
				""")
				.param("now", databaseTime(now))
				.param("limit", limit)
				.query(UUID.class)
				.list();
	}

	private ResearchRefreshJobView require(UUID jobId) {
		return find(jobId).orElseThrow(() -> new ResearchRefreshJobNotFoundException(jobId));
	}

	private static ResearchRefreshJobView map(ResultSet resultSet, int rowNumber) throws SQLException {
		return new ResearchRefreshJobView(
				resultSet.getObject("id", UUID.class),
				ResearchRefreshJobType.valueOf(resultSet.getString("job_type")),
				resultSet.getObject("target_id", UUID.class),
				ResearchRefreshJobTrigger.valueOf(resultSet.getString("trigger_kind")),
				ResearchRefreshJobStatus.valueOf(resultSet.getString("status")),
				resultSet.getInt("attempt_count"),
				resultSet.getInt("max_attempts"),
				instant(resultSet, "available_at"),
				instant(resultSet, "leased_until"),
				resultSet.getString("last_error_code"),
				resultSet.getString("last_error_detail"),
				instant(resultSet, "created_at"),
				instant(resultSet, "started_at"),
				instant(resultSet, "completed_at"),
				instant(resultSet, "updated_at"));
	}

	private static Instant instant(ResultSet resultSet, String column) throws SQLException {
		java.sql.Timestamp timestamp = resultSet.getTimestamp(column);
		return timestamp == null ? null : timestamp.toInstant();
	}

	private static OffsetDateTime databaseTime(Instant instant) {
		return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
	}

	private static String bounded(String value, int maximumLength) {
		String safe = value == null || value.isBlank() ? "Unspecified refresh failure" : value.strip();
		return safe.length() <= maximumLength ? safe : safe.substring(0, maximumLength);
	}

	record ClaimedResearchRefreshJob(ResearchRefreshJobView job, UUID leaseToken) {
	}
}
