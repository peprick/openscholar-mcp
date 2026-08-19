package com.openscholar.jobs.internal;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.sql.DataSource;

import com.zaxxer.hikari.HikariDataSource;
import org.springframework.stereotype.Component;

@Component
final class PostgresEmbeddingBackfillLock implements EmbeddingBackfillLock {

	private static final String LOCK_NAMESPACE = "openscholar:embedding-backfill:";
	private static final String TRY_LOCK_SQL =
			"select pg_try_advisory_lock(hashtextextended(?, 0))";
	private static final String UNLOCK_SQL =
			"select pg_advisory_unlock(hashtextextended(?, 0))";

	private final DataSource dataSource;

	PostgresEmbeddingBackfillLock(DataSource dataSource) {
		this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
	}

	@Override
	public Optional<EmbeddingBackfillLease> tryAcquire(String profileKey) {
		requireIndependentStoreConnectionCapacity();
		String lockName = LOCK_NAMESPACE + Objects.requireNonNull(profileKey, "profileKey");
		Connection connection = null;
		try {
			connection = dataSource.getConnection();
			boolean acquired;
			try (PreparedStatement statement = connection.prepareStatement(TRY_LOCK_SQL)) {
				statement.setString(1, lockName);
				try (ResultSet resultSet = statement.executeQuery()) {
					if (!resultSet.next()) {
						throw new SQLException("PostgreSQL did not return an advisory-lock result");
					}
					acquired = resultSet.getBoolean(1);
				}
			}

			if (!acquired) {
				connection.close();
				return Optional.empty();
			}

			Connection dedicatedConnection = connection;
			connection = null;
			return Optional.of(new PostgresEmbeddingBackfillLease(dedicatedConnection, lockName));
		}
		catch (SQLException exception) {
			closeAfterFailure(connection, exception);
			throw new IllegalStateException("Could not acquire the embedding backfill lock", exception);
		}
	}

	private void requireIndependentStoreConnectionCapacity() {
		HikariDataSource hikariDataSource = requireHikariDataSource();
		if (hikariDataSource.getMaximumPoolSize() < 2) {
			throw new IllegalStateException(
					"Embedding backfill requires a database pool with at least two connections");
		}
	}

	private HikariDataSource requireHikariDataSource() {
		if (dataSource instanceof HikariDataSource hikariDataSource) {
			return hikariDataSource;
		}
		try {
			if (dataSource.isWrapperFor(HikariDataSource.class)) {
				return dataSource.unwrap(HikariDataSource.class);
			}
		}
		catch (SQLException exception) {
			throw new IllegalStateException(
					"Could not inspect the embedding backfill database pool", exception);
		}
		throw new IllegalStateException(
				"Embedding backfill requires a Hikari database pool so connection capacity can be verified");
	}

	private static void closeAfterFailure(Connection connection, SQLException failure) {
		if (connection == null) {
			return;
		}
		try {
			connection.close();
		}
		catch (SQLException closeFailure) {
			failure.addSuppressed(closeFailure);
		}
	}

	private static final class PostgresEmbeddingBackfillLease implements EmbeddingBackfillLease {

		private final Connection connection;
		private final String lockName;
		private final AtomicBoolean closed = new AtomicBoolean();

		private PostgresEmbeddingBackfillLease(Connection connection, String lockName) {
			this.connection = connection;
			this.lockName = lockName;
		}

		@Override
		public void close() {
			if (!closed.compareAndSet(false, true)) {
				return;
			}

			RuntimeException failure = null;
			try (PreparedStatement statement = connection.prepareStatement(UNLOCK_SQL)) {
				statement.setString(1, lockName);
				try (ResultSet resultSet = statement.executeQuery()) {
					if (!resultSet.next() || !resultSet.getBoolean(1)) {
						failure = new IllegalStateException(
								"PostgreSQL did not release the embedding backfill lock");
					}
				}
			}
			catch (SQLException exception) {
				failure = new IllegalStateException(
						"Could not release the embedding backfill lock", exception);
			}
			finally {
				try {
					connection.close();
				}
				catch (SQLException closeFailure) {
					if (failure == null) {
						failure = new IllegalStateException(
								"Could not close the embedding backfill lock connection", closeFailure);
					}
					else {
						failure.addSuppressed(closeFailure);
					}
				}
			}

			if (failure != null) {
				throw failure;
			}
		}
	}
}
