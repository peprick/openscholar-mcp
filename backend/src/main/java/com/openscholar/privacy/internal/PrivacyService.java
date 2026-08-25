package com.openscholar.privacy.internal;

import java.io.IOException;
import java.util.UUID;

import com.openscholar.privacy.PrivacyExportTarget;
import com.openscholar.privacy.PrivacyUseCase;
import com.openscholar.security.CurrentUserIdProvider;
import com.openscholar.security.OidcSecurityProperties;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class PrivacyService implements PrivacyUseCase {

	private final JdbcClient jdbcClient;
	private final CurrentUserIdProvider currentUser;
	private final OidcSecurityProperties securityProperties;
	private final PrivacyExportConcurrencyGate exportConcurrency;
	private final PrivacyExportWriter exportWriter;

	PrivacyService(
			JdbcClient jdbcClient,
			CurrentUserIdProvider currentUser,
			OidcSecurityProperties securityProperties,
			PrivacyExportConcurrencyGate exportConcurrency,
			PrivacyExportWriter exportWriter) {
		this.jdbcClient = jdbcClient;
		this.currentUser = currentUser;
		this.securityProperties = securityProperties;
		this.exportConcurrency = exportConcurrency;
		this.exportWriter = exportWriter;
	}

	@Override
	public void exportPersonalData(PrivacyExportTarget target) throws IOException {
		UUID userId = currentUser.currentUserId();
		try (PrivacyExportConcurrencyGate.Permit ignored = exportConcurrency.acquire(userId)) {
			exportWriter.write(userId, target);
		}
	}

	@Override
	@Transactional
	public void deletePersonalData() {
		UUID userId = currentUser.currentUserId();
		jdbcClient.sql("""
				DELETE FROM research_refresh_job
				WHERE job_type = 'SEARCH_METADATA'
				  AND target_id IN (
				      SELECT id FROM search_snapshot WHERE owner_id = :userId
				  )
				""")
				.param("userId", userId)
				.update();
		jdbcClient.sql("DELETE FROM search_snapshot WHERE owner_id = :userId")
				.param("userId", userId)
				.update();
		jdbcClient.sql("DELETE FROM library_collection WHERE owner_id = :userId")
				.param("userId", userId)
				.update();
		if (securityProperties.enabled()) {
			jdbcClient.sql("DELETE FROM app_user WHERE id = :userId")
					.param("userId", userId)
					.update();
		}
	}

}
