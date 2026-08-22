package com.openscholar.security;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component
class OidcUserStore {

	private final JdbcClient jdbcClient;
	private final Clock clock;

	OidcUserStore(JdbcClient jdbcClient, Clock clock) {
		this.jdbcClient = jdbcClient;
		this.clock = clock;
	}

	@Transactional(propagation = Propagation.REQUIRES_NEW)
	UUID resolve(String issuer, String subject, String displayName) {
		return jdbcClient.sql("""
				INSERT INTO app_user (
				    id, display_name, created_at, identity_issuer, identity_subject
				)
				VALUES (:id, :displayName, :createdAt, :issuer, :subject)
				ON CONFLICT (identity_issuer, identity_subject)
				    WHERE identity_issuer IS NOT NULL AND identity_subject IS NOT NULL
				DO UPDATE SET display_name = EXCLUDED.display_name
				RETURNING id
				""")
				.param("id", UUID.randomUUID())
				.param("displayName", displayName)
				.param("createdAt", OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC))
				.param("issuer", issuer)
				.param("subject", subject)
				.query(UUID.class)
				.single();
	}
}
