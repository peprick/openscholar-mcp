package com.openscholar.access.internal;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import com.openscholar.access.AccessRefreshTooSoonException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
class PaperAccessForceRefreshGuard {

	private final JdbcClient jdbcClient;

	PaperAccessForceRefreshGuard(JdbcClient jdbcClient) {
		this.jdbcClient = jdbcClient;
	}

	@Transactional
	public void claim(UUID paperId, Instant now, Duration cooldown) {
		Instant cutoff = now.minus(cooldown);
		int claimed = jdbcClient.sql("""
				INSERT INTO paper_access_refresh_guard (paper_id, last_forced_at)
				VALUES (:paperId, :now)
				ON CONFLICT (paper_id) DO UPDATE
				SET last_forced_at = EXCLUDED.last_forced_at
				WHERE paper_access_refresh_guard.last_forced_at <= :cutoff
				""")
				.param("paperId", paperId)
				.param("now", OffsetDateTime.ofInstant(now, ZoneOffset.UTC))
				.param("cutoff", OffsetDateTime.ofInstant(cutoff, ZoneOffset.UTC))
				.update();
		if (claimed == 1) {
			return;
		}

		Instant lastForcedAt = jdbcClient.sql("""
				SELECT last_forced_at
				FROM paper_access_refresh_guard
				WHERE paper_id = :paperId
				""")
				.param("paperId", paperId)
				.query(OffsetDateTime.class)
				.single()
				.toInstant();
		Duration retryAfter = Duration.between(now, lastForcedAt.plus(cooldown));
		throw new AccessRefreshTooSoonException(retryAfter.isNegative() ? Duration.ZERO : retryAfter);
	}
}
