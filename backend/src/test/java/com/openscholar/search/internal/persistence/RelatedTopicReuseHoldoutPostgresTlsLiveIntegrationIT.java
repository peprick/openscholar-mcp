package com.openscholar.search.internal.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HexFormat;

import com.openscholar.search.internal.persistence.RelatedTopicReuseHoldoutPostgresTlsConnectionFactory.EndpointRecord;
import com.openscholar.search.internal.persistence.RelatedTopicReuseHoldoutPostgresTlsConnectionFactory.PreflightException;
import com.openscholar.search.internal.persistence.RelatedTopicReuseHoldoutPostgresTlsConnectionFactory.RuntimeFiles;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

/**
 * Explicit-only integration proof against the disposable PostgreSQL 17 TLS
 * harness. The class name deliberately falls outside Surefire's normal test
 * patterns; run it only through scripts/test-related-topic-reuse-holdout-tls.sh.
 */
@Execution(ExecutionMode.SAME_THREAD)
class RelatedTopicReuseHoldoutPostgresTlsLiveIntegrationIT {

	private static final String CONNECTION_FAILED =
			"HOLDOUT_LEDGER_TLS_CONNECTION_FAILED";

	@Test
	void realTlsPreflightAndBoundPhaseBConnectionSucceedExactlyOnce()
			throws Exception {
		EndpointRecord endpoint = tlsEndpoint(
				required("HOLDOUT_TLS_HOST"),
				requiredPort("HOLDOUT_TLS_PORT"),
				required("HOLDOUT_TLS_SERVER_ADDRESS"),
				path("HOLDOUT_CA_CERTIFICATE"));

		var ledger = RelatedTopicReuseHoldoutPostgresTlsConnectionFactory.preflight(
				endpoint, runtimeFiles(path("HOLDOUT_CA_CERTIFICATE"),
						path("HOLDOUT_RUNTIME_PASSWORD_FILE")));

		try (Connection connection = openBoundPhaseBConnection(ledger);
				Statement statement = connection.createStatement();
				ResultSet result = statement.executeQuery("""
						SELECT current_database(), current_user, ssl
						FROM pg_catalog.pg_stat_ssl
						WHERE pid = pg_catalog.pg_backend_pid()
						""")) {
			assertThat(result.next()).isTrue();
			assertThat(result.getString(1))
					.isEqualTo(RelatedTopicReuseHoldoutPostgresFirstRunLedger.DATABASE_NAME);
			assertThat(result.getString(2))
					.isEqualTo(RelatedTopicReuseHoldoutPostgresFirstRunLedger.RUNTIME_ROLE);
			assertThat(result.getBoolean(3)).isTrue();
			assertThat(result.next()).isFalse();
		}

		assertThatThrownBy(() -> openBoundPhaseBConnection(ledger))
				.isExactlyInstanceOf(SQLException.class)
				.hasMessage("HOLDOUT_LEDGER_TLS_CONNECTION_ALREADY_CONSUMED")
				.hasNoCause();
	}

	@Test
	void untrustedCaIsRejectedBeforeAConnectionIsReleased() throws Exception {
		Path untrustedCa = path("HOLDOUT_UNTRUSTED_CA_CERTIFICATE");
		assertThat(Files.mismatch(path("HOLDOUT_CA_CERTIFICATE"), untrustedCa))
				.as("the negative-case CA must differ from the trusted CA")
				.isNotEqualTo(-1L);
		assertConnectionRejected(
				tlsEndpoint(
					required("HOLDOUT_TLS_HOST"),
					requiredPort("HOLDOUT_TLS_PORT"),
					required("HOLDOUT_TLS_SERVER_ADDRESS"),
					untrustedCa),
				runtimeFiles(untrustedCa, path("HOLDOUT_RUNTIME_PASSWORD_FILE")));
	}

	@Test
	void dnsSanMismatchIsRejectedBeforeAConnectionIsReleased() throws Exception {
		Path trustedCa = path("HOLDOUT_CA_CERTIFICATE");
		String wrongHost = required("HOLDOUT_TLS_WRONG_SAN_HOST");
		assertHostResolvesTo(wrongHost, required("HOLDOUT_TLS_SERVER_ADDRESS"));
		assertConnectionRejected(
				tlsEndpoint(
					wrongHost,
					requiredPort("HOLDOUT_TLS_PORT"),
					required("HOLDOUT_TLS_SERVER_ADDRESS"),
					trustedCa),
				runtimeFiles(trustedCa, path("HOLDOUT_RUNTIME_PASSWORD_FILE")));
	}

	@Test
	void wrongScramPasswordIsRejectedBeforeAConnectionIsReleased() throws Exception {
		Path trustedCa = path("HOLDOUT_CA_CERTIFICATE");
		Path wrongPassword = path("HOLDOUT_WRONG_PASSWORD_FILE");
		assertThat(Files.mismatch(
				path("HOLDOUT_RUNTIME_PASSWORD_FILE"), wrongPassword))
				.as("the negative-case password must differ from the runtime password")
				.isNotEqualTo(-1L);
		assertConnectionRejected(
				tlsEndpoint(
					required("HOLDOUT_TLS_HOST"),
					requiredPort("HOLDOUT_TLS_PORT"),
					required("HOLDOUT_TLS_SERVER_ADDRESS"),
					trustedCa),
				runtimeFiles(trustedCa, wrongPassword));
	}

	@Test
	void directTlsCannotDowngradeToThePlaintextServer() throws Exception {
		Path trustedCa = path("HOLDOUT_CA_CERTIFICATE");
		String plaintextHost = required("HOLDOUT_PLAINTEXT_HOST");
		int plaintextPort = requiredPort("HOLDOUT_PLAINTEXT_PORT");
		assertHostResolvesTo(
				plaintextHost, required("HOLDOUT_PLAINTEXT_SERVER_ADDRESS"));
		assertTcpReachable(plaintextHost, plaintextPort);
		assertConnectionRejected(
				tlsEndpoint(
					plaintextHost,
					plaintextPort,
					required("HOLDOUT_PLAINTEXT_SERVER_ADDRESS"),
					trustedCa),
				runtimeFiles(trustedCa, path("HOLDOUT_RUNTIME_PASSWORD_FILE")));
	}

	private static EndpointRecord tlsEndpoint(
			String host, int port, String serverAddress, Path caCertificate)
			throws IOException {
		return new EndpointRecord(
				RelatedTopicReuseHoldoutPostgresTlsConnectionFactory.ENDPOINT_SCHEMA_VERSION,
				host,
				port,
				serverAddress,
				Files.readString(path("HOLDOUT_SERVER_VERSION_FILE")).strip(),
				required("HOLDOUT_TLS_PROTOCOL"),
				required("HOLDOUT_TLS_CIPHER"),
				requiredInteger("HOLDOUT_TLS_BITS"),
				sha256(caCertificate));
	}

	private static RuntimeFiles runtimeFiles(Path caCertificate, Path passwordFile) {
		return new RuntimeFiles(
				path("HOLDOUT_REPOSITORY_ROOT"),
				caCertificate,
				passwordFile,
				required("HOLDOUT_EXPECTED_CA_OWNER"),
				required("HOLDOUT_EXPECTED_OWNER"));
	}

	private static void assertConnectionRejected(
			EndpointRecord endpoint, RuntimeFiles files) {
		assertThatThrownBy(() ->
				RelatedTopicReuseHoldoutPostgresTlsConnectionFactory.preflight(
						endpoint, files))
				.isExactlyInstanceOf(PreflightException.class)
				.hasMessage(CONNECTION_FAILED)
				.hasNoCause();
	}

	private static void assertHostResolvesTo(String host, String expectedAddress)
			throws IOException {
		assertThat(InetAddress.getAllByName(host))
				.extracting(InetAddress::getHostAddress)
				.containsExactly(expectedAddress);
	}

	private static void assertTcpReachable(String host, int port) throws IOException {
		try (Socket socket = new Socket()) {
			socket.connect(new InetSocketAddress(host, port), 5_000);
		}
	}

	private static Connection openBoundPhaseBConnection(
			RelatedTopicReuseHoldoutPostgresFirstRunLedger ledger) throws SQLException {
		try {
			Field sourceField = RelatedTopicReuseHoldoutPostgresFirstRunLedger.class
					.getDeclaredField("connectionSource");
			sourceField.setAccessible(true);
			Object source = sourceField.get(ledger);

			Class<?> sourceType = Class.forName(
					RelatedTopicReuseHoldoutPostgresFirstRunLedger.class.getName()
							+ "$ClaimConnectionSource");
			Method open = sourceType.getDeclaredMethod("open");
			open.setAccessible(true);
			return (Connection) open.invoke(source);
		}
		catch (InvocationTargetException exception) {
			Throwable cause = exception.getCause();
			if (cause instanceof SQLException sqlException) {
				throw sqlException;
			}
			throw new AssertionError("unexpected Phase-B connection failure", cause);
		}
		catch (ReflectiveOperationException | RuntimeException exception) {
			throw new AssertionError(
					"the ledger's bounded Phase-B test seam is unavailable", exception);
		}
	}

	private static Path path(String name) {
		return Path.of(required(name));
	}

	private static String required(String name) {
		String value = System.getenv(name);
		if (value == null || value.isBlank()) {
			throw new IllegalStateException(name + " is required by the live TLS harness");
		}
		return value;
	}

	private static int requiredPort(String name) {
		int value = requiredInteger(name);
		if (value < 1 || value > 65_535) {
			throw new IllegalStateException(name + " must be a valid TCP port");
		}
		return value;
	}

	private static int requiredInteger(String name) {
		try {
			return Integer.parseInt(required(name));
		}
		catch (NumberFormatException exception) {
			throw new IllegalStateException(name + " must be an integer", exception);
		}
	}

	private static String sha256(Path path) throws IOException {
		try {
			return HexFormat.of().formatHex(
					MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)));
		}
		catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException("SHA-256 is unavailable", exception);
		}
	}
}
