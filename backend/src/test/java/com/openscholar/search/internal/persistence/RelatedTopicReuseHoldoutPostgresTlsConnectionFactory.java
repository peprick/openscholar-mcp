package com.openscholar.search.internal.persistence;

import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFilePermission;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.postgresql.ds.PGSimpleDataSource;

/**
 * Fixed TLS and read-only preflight boundary for the dedicated holdout ledger.
 *
 * <p>This class is deliberately outside Spring and accepts no JDBC URL, arbitrary
 * properties, alternate role, or caller-selected SSL implementation. It performs
 * one Phase-A probe, then returns a ledger already bound to a capability that may
 * open exactly one Phase-B claim connection. The same connection returned to the
 * ledger is independently preflighted before it is released.</p>
 */
final class RelatedTopicReuseHoldoutPostgresTlsConnectionFactory {

	static final int ENDPOINT_SCHEMA_VERSION = 1;
	static final int LOGIN_TIMEOUT_SECONDS = 10;
	static final int CONNECT_TIMEOUT_SECONDS = 10;
	static final int SSL_RESPONSE_TIMEOUT_MILLIS = 10_000;
	static final int SOCKET_TIMEOUT_SECONDS = 15;
	static final int NETWORK_TIMEOUT_MILLIS = 15_000;
	static final int STATEMENT_TIMEOUT_MILLIS = 10_000;
	static final String APPLICATION_NAME = "openscholar-holdout-ledger-v1";
	static final String POSTGRESQL_DRIVER_VERSION = "42.7.12";

	private static final int MAXIMUM_CA_BYTES = 1024 * 1024;
	private static final int MAXIMUM_PASSWORD_BYTES = 1024;
	private static final int MAXIMUM_PASSWORD_CHARACTERS = 512;
	private static final String SSL_FACTORY = "org.postgresql.ssl.LibPQFactory";
	private static final String SSL_HOSTNAME_VERIFIER =
			RelatedTopicReuseHoldoutStrictDnsSanHostnameVerifier.class.getName();
	private static final Pattern DNS_HOST = Pattern.compile(
			"(?=.{1,253}\\z)(?=.*[a-z])(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\\.)+"
					+ "[a-z](?:[a-z0-9-]{0,61}[a-z0-9])?");
	private static final Pattern IPV4_LITERAL = Pattern.compile(
			"(?:0|[1-9][0-9]?|1[0-9]{2}|2[0-4][0-9]|25[0-5])"
					+ "(?:\\.(?:0|[1-9][0-9]?|1[0-9]{2}|2[0-4][0-9]|25[0-5])){3}");
	private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");
	private static final Pattern TLS_PROTOCOL = Pattern.compile("TLSv1\\.[23]");
	private static final Pattern TLS_CIPHER = Pattern.compile("[A-Z0-9_-]{1,128}");
	private static final Set<String> DATABASE_INVENTORY = Set.of(
			RelatedTopicReuseHoldoutPostgresFirstRunLedger.DATABASE_NAME,
			"postgres",
			"template0",
			"template1");
	private static final List<RoleExpectation> ROLE_EXPECTATIONS = List.of(
			new RoleExpectation(
					RelatedTopicReuseHoldoutPostgresFirstRunLedger.AUDITOR_ROLE, true),
			new RoleExpectation(
					"openscholar_holdout_ledger_bootstrap_v1", false),
			new RoleExpectation(
					RelatedTopicReuseHoldoutPostgresFirstRunLedger.OWNER_ROLE, false),
			new RoleExpectation(
					RelatedTopicReuseHoldoutPostgresFirstRunLedger.RUNTIME_ROLE, true));
	private static final Set<PosixFilePermission> SECRET_DIRECTORY_PERMISSIONS =
			Set.of(
					PosixFilePermission.OWNER_READ,
					PosixFilePermission.OWNER_WRITE,
					PosixFilePermission.OWNER_EXECUTE);
	private static final Set<PosixFilePermission> SECRET_FILE_PERMISSIONS =
			Set.of(PosixFilePermission.OWNER_READ);
	private static final Executor DIRECT_EXECUTOR = Runnable::run;
	private static final SecureRandom SECURE_RANDOM = new SecureRandom();
	private static final ConnectionOpener POSTGRES_OPENER =
			RelatedTopicReuseHoldoutPostgresTlsConnectionFactory::openPostgresConnection;

	private RelatedTopicReuseHoldoutPostgresTlsConnectionFactory() {
	}

	static RelatedTopicReuseHoldoutPostgresFirstRunLedger preflight(
			EndpointRecord endpoint,
			RuntimeFiles files) throws PreflightException {
		return new RelatedTopicReuseHoldoutPostgresFirstRunLedger(
				preflightSource(endpoint, files, POSTGRES_OPENER));
	}

	private static VerifiedRuntimeConnectionSource preflightSource(
			EndpointRecord endpoint,
			RuntimeFiles files,
			ConnectionOpener opener) throws PreflightException {
		ValidatedConfiguration configuration = null;
		try {
			configuration = validate(endpoint, files);
			try (Connection connection = openVerifiedConnection(configuration, opener)) {
				// The close is part of the Phase-A probe and must succeed.
			}
			return new VerifiedRuntimeConnectionSource(configuration, opener);
		}
		catch (SafeFailure failure) {
			if (configuration != null) {
				configuration.destroyPasswordBinding();
			}
			throw new PreflightException(failure.diagnostic());
		}
		catch (SQLException | RuntimeException exception) {
			if (configuration != null) {
				configuration.destroyPasswordBinding();
			}
			throw new PreflightException("HOLDOUT_LEDGER_TLS_PREFLIGHT_FAILED");
		}
	}

	private static ValidatedConfiguration validate(
			EndpointRecord endpoint,
			RuntimeFiles files) throws SafeFailure {
		if (endpoint == null || files == null) {
			throw failure("HOLDOUT_LEDGER_TLS_CONFIGURATION_INVALID");
		}
		if (!POSTGRESQL_DRIVER_VERSION.equals(
				PGSimpleDataSource.class.getPackage().getImplementationVersion())) {
			throw failure("HOLDOUT_LEDGER_TLS_DRIVER_INVALID");
		}
		InetAddress expectedAddress = validateEndpoint(endpoint);
		String expectedOwner = exactText(
				files.expectedOwner(), 1, 200,
				"HOLDOUT_LEDGER_TLS_SECRET_INVALID");
		String expectedCaOwner = exactText(
				files.expectedCaOwner(), 1, 200,
				"HOLDOUT_LEDGER_TLS_CA_INVALID");
		if (expectedCaOwner.equals(expectedOwner)) {
			throw failure("HOLDOUT_LEDGER_TLS_CA_INVALID");
		}
		Path repository = canonicalDirectory(
				files.repositoryRoot(), "HOLDOUT_LEDGER_TLS_CONFIGURATION_INVALID");
		Path caCertificate = canonicalFile(
				files.caCertificate(), "HOLDOUT_LEDGER_TLS_CA_INVALID");
		Path passwordFile = canonicalFile(
				files.runtimePassword(), "HOLDOUT_LEDGER_TLS_SECRET_INVALID");
		if (caCertificate.startsWith(repository) || passwordFile.startsWith(repository)) {
			throw failure("HOLDOUT_LEDGER_TLS_CONFIGURATION_INVALID");
		}

		validateCaPath(caCertificate, expectedCaOwner, expectedOwner);
		ReadFile ca = readStableFile(
				caCertificate, MAXIMUM_CA_BYTES, "HOLDOUT_LEDGER_TLS_CA_INVALID");
		try {
			if (ca.bytes().length == 0
					|| !constantTimeDigestEquals(endpoint.caSha256(), ca.bytes())) {
				throw failure("HOLDOUT_LEDGER_TLS_CA_INVALID");
			}
		}
		finally {
			Arrays.fill(ca.bytes(), (byte) 0);
		}

		validateSecretPath(passwordFile, expectedOwner);
		ReadFile password = readStableFile(
				passwordFile,
				MAXIMUM_PASSWORD_BYTES,
				"HOLDOUT_LEDGER_TLS_SECRET_INVALID");
		byte[] passwordBindingKey = new byte[32];
		byte[] passwordBinding = null;
		char[] passwordCharacters = null;
		try {
			passwordCharacters = decodePassword(password.bytes());
			SECURE_RANDOM.nextBytes(passwordBindingKey);
			passwordBinding = hmacSha256(passwordBindingKey, password.bytes());
		}
		finally {
			Arrays.fill(password.bytes(), (byte) 0);
			if (passwordCharacters != null) {
				Arrays.fill(passwordCharacters, '\0');
			}
			if (passwordBinding == null) {
				Arrays.fill(passwordBindingKey, (byte) 0);
			}
		}
		return new ValidatedConfiguration(
				endpoint,
				expectedAddress,
				repository,
				caCertificate,
				ca.snapshot(),
				passwordFile,
				password.snapshot(),
				passwordBindingKey,
				passwordBinding,
				expectedCaOwner,
				expectedOwner);
	}

	private static InetAddress validateEndpoint(EndpointRecord endpoint)
			throws SafeFailure {
		if (endpoint.schemaVersion() != ENDPOINT_SCHEMA_VERSION
				|| endpoint.port() < 1
				|| endpoint.port() > 65_535
				|| endpoint.host() == null
				|| !DNS_HOST.matcher(endpoint.host()).matches()
				|| endpoint.serverAddress() == null
				|| !IPV4_LITERAL.matcher(endpoint.serverAddress()).matches()
				|| endpoint.serverVersion() == null
				|| endpoint.serverVersion().length() > 200
				|| !endpoint.serverVersion().equals(endpoint.serverVersion().strip())
				|| endpoint.serverVersion().codePoints().anyMatch(Character::isISOControl)
				|| endpoint.tlsProtocol() == null
				|| !TLS_PROTOCOL.matcher(endpoint.tlsProtocol()).matches()
				|| endpoint.tlsCipher() == null
				|| !TLS_CIPHER.matcher(endpoint.tlsCipher()).matches()
				|| endpoint.tlsBits() < 128
				|| endpoint.tlsBits() > 512
				|| endpoint.caSha256() == null
				|| !SHA256.matcher(endpoint.caSha256()).matches()) {
			throw failure("HOLDOUT_LEDGER_TLS_ENDPOINT_INVALID");
		}
		try {
			return InetAddress.getByAddress(ipv4Bytes(endpoint.serverAddress()));
		}
		catch (UnknownHostException exception) {
			throw failure("HOLDOUT_LEDGER_TLS_ENDPOINT_INVALID");
		}
	}

	private static void revalidate(ValidatedConfiguration expected) throws SafeFailure {
		if (!POSTGRESQL_DRIVER_VERSION.equals(
				PGSimpleDataSource.class.getPackage().getImplementationVersion())
				|| !validateEndpoint(expected.endpoint()).equals(expected.expectedAddress())
				|| !canonicalDirectory(
						expected.repository(),
						"HOLDOUT_LEDGER_TLS_CONFIGURATION_CHANGED")
						.equals(expected.repository())
				|| !canonicalFile(
						expected.caCertificate(),
						"HOLDOUT_LEDGER_TLS_CONFIGURATION_CHANGED")
						.equals(expected.caCertificate())
				|| !canonicalFile(
						expected.passwordFile(),
						"HOLDOUT_LEDGER_TLS_CONFIGURATION_CHANGED")
						.equals(expected.passwordFile())) {
			throw failure("HOLDOUT_LEDGER_TLS_CONFIGURATION_CHANGED");
		}
		validateCaPath(
				expected.caCertificate(),
				expected.expectedCaOwner(),
				expected.expectedOwner());
		ReadFile ca = readStableFile(
				expected.caCertificate(),
				MAXIMUM_CA_BYTES,
				"HOLDOUT_LEDGER_TLS_CONFIGURATION_CHANGED");
		try {
			if (!expected.caSnapshot().equals(ca.snapshot())
					|| !constantTimeDigestEquals(
							expected.endpoint().caSha256(), ca.bytes())) {
				throw failure("HOLDOUT_LEDGER_TLS_CONFIGURATION_CHANGED");
			}
		}
		finally {
			Arrays.fill(ca.bytes(), (byte) 0);
		}
		validateSecretPath(expected.passwordFile(), expected.expectedOwner());
	}

	private static Connection openVerifiedConnection(
			ValidatedConfiguration configuration,
			ConnectionOpener opener) throws SafeFailure {
		char[] password = readBoundPassword(configuration);
		Connection connection;
		try {
			connection = Objects.requireNonNull(opener, "opener")
					.open(configuration, password);
		}
		catch (SQLException | RuntimeException exception) {
			throw failure("HOLDOUT_LEDGER_TLS_CONNECTION_FAILED");
		}
		finally {
			Arrays.fill(password, '\0');
		}
		if (connection == null) {
			throw failure("HOLDOUT_LEDGER_TLS_CONNECTION_FAILED");
		}
		try {
			verifyClaimConnection(connection, configuration);
			return connection;
		}
		catch (SQLException | RuntimeException | SafeFailure exception) {
			closeQuietly(connection);
			if (exception instanceof SafeFailure safeFailure) {
				throw safeFailure;
			}
			throw failure("HOLDOUT_LEDGER_TLS_SESSION_INVALID");
		}
	}

	private static char[] readBoundPassword(ValidatedConfiguration configuration)
			throws SafeFailure {
		if (!canonicalFile(
				configuration.passwordFile(),
				"HOLDOUT_LEDGER_TLS_CONFIGURATION_CHANGED")
				.equals(configuration.passwordFile())) {
			throw failure("HOLDOUT_LEDGER_TLS_CONFIGURATION_CHANGED");
		}
		validateSecretPath(
				configuration.passwordFile(), configuration.expectedOwner());
		ReadFile password = readStableFile(
				configuration.passwordFile(),
				MAXIMUM_PASSWORD_BYTES,
				"HOLDOUT_LEDGER_TLS_SECRET_INVALID");
		char[] decoded = null;
		try {
			if (!configuration.passwordSnapshot().equals(password.snapshot())
					|| !configuration.passwordMatches(password.bytes())) {
				throw failure("HOLDOUT_LEDGER_TLS_CONFIGURATION_CHANGED");
			}
			decoded = decodePassword(password.bytes());
			return decoded;
		}
		finally {
			Arrays.fill(password.bytes(), (byte) 0);
			if (decoded != null && decoded.length == 0) {
				Arrays.fill(decoded, '\0');
			}
		}
	}

	private static Connection openPostgresConnection(
			ValidatedConfiguration configuration,
			char[] password) throws SQLException {
		PGSimpleDataSource dataSource = configuredDataSource(configuration);
		// JDBC's credential overload requires an immutable String. Keep it out of the
		// retained data source and all diagnostics; process teardown remains the only
		// way to guarantee reclamation of this short-lived copy.
		String transientPassword = new String(password);
		try {
			return dataSource.getConnection(
					RelatedTopicReuseHoldoutPostgresFirstRunLedger.RUNTIME_ROLE,
					transientPassword);
		}
		finally {
			transientPassword = null;
		}
	}

	private static PGSimpleDataSource configuredDataSource(
			ValidatedConfiguration configuration) {
		PGSimpleDataSource dataSource = new PGSimpleDataSource();
		dataSource.setServerNames(new String[] {configuration.endpoint().host()});
		dataSource.setPortNumbers(new int[] {configuration.endpoint().port()});
		dataSource.setDatabaseName(
				RelatedTopicReuseHoldoutPostgresFirstRunLedger.DATABASE_NAME);
		dataSource.setUser(RelatedTopicReuseHoldoutPostgresFirstRunLedger.RUNTIME_ROLE);
		dataSource.setPassword(null);
		dataSource.setProtocolVersion(3);
		dataSource.setAssumeMinServerVersion("17");
		dataSource.setApplicationName(APPLICATION_NAME);
		dataSource.setOptions("-c statement_timeout=" + STATEMENT_TIMEOUT_MILLIS);
		// pgJDBC implements its client-side timeout with a second, non-TLS
		// CancelRequest socket. Keep it disabled and use the server-enforced
		// startup setting plus the TLS socket/network deadlines instead.
		dataSource.setQueryTimeout(0);
		dataSource.setSsl(true);
		dataSource.setSslMode("verify-full");
		dataSource.setSslRootCert(configuration.caCertificate().toString());
		dataSource.setSslCert("");
		dataSource.setSslKey("");
		dataSource.setSslfactory(SSL_FACTORY);
		dataSource.setSslHostnameVerifier(SSL_HOSTNAME_VERIFIER);
		dataSource.setSslNegotiation("direct");
		dataSource.setRequireAuth("scram-sha-256");
		dataSource.setChannelBinding("require");
		dataSource.setScramMaxIterations(100_000);
		dataSource.setGssEncMode("disable");
		dataSource.setGssUseDefaultCreds(false);
		dataSource.setJaasLogin(false);
		dataSource.setUseSpNego(false);
		dataSource.setTargetServerType("primary");
		dataSource.setLoadBalanceHosts(false);
		dataSource.setHostRecheckSeconds(0);
		dataSource.setLoginTimeout(LOGIN_TIMEOUT_SECONDS);
		dataSource.setConnectTimeout(CONNECT_TIMEOUT_SECONDS);
		dataSource.setSslResponseTimeout(SSL_RESPONSE_TIMEOUT_MILLIS);
		dataSource.setSocketTimeout(SOCKET_TIMEOUT_SECONDS);
		dataSource.setCancelSignalTimeout(CONNECT_TIMEOUT_SECONDS);
		dataSource.setLogServerErrorDetail(false);
		dataSource.setLogUnclosedConnections(false);
		dataSource.setAllowEncodingChanges(false);
		return dataSource;
	}

	private static void verifyClaimConnection(
			Connection connection,
			ValidatedConfiguration configuration) throws SQLException, SafeFailure {
		boolean transactionStarted = false;
		try {
			connection.setNetworkTimeout(DIRECT_EXECUTOR, NETWORK_TIMEOUT_MILLIS);
			if (connection.getNetworkTimeout() != NETWORK_TIMEOUT_MILLIS
					|| !connection.getAutoCommit()
					|| connection.isReadOnly()) {
				throw failure("HOLDOUT_LEDGER_TLS_SESSION_INVALID");
			}
			connection.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
			connection.setReadOnly(true);
			connection.setAutoCommit(false);
			transactionStarted = true;
			verifyTransportAndIdentity(connection, configuration);
			verifyDatabaseInventory(connection);
			verifyRoleBoundary(connection);
			verifyRoleSettings(connection);
			connection.rollback();
			transactionStarted = false;
			connection.setAutoCommit(true);
			connection.setReadOnly(false);
			if (!connection.getAutoCommit() || connection.isReadOnly()) {
				throw failure("HOLDOUT_LEDGER_TLS_SESSION_INVALID");
			}
		}
		catch (SQLException | RuntimeException | SafeFailure exception) {
			if (transactionStarted) {
				rollbackQuietly(connection);
			}
			if (exception instanceof SafeFailure safeFailure) {
				throw safeFailure;
			}
			throw exception;
		}
	}

	private static void verifyTransportAndIdentity(
			Connection connection,
			ValidatedConfiguration configuration) throws SQLException, SafeFailure {
		String sql = """
				SELECT current_database(),
				       session_user,
				       current_user,
				       current_setting('application_name'),
				       current_setting('server_version'),
				       current_setting('server_version_num')::integer,
				       current_setting('fsync'),
				       current_setting('synchronous_commit'),
			       current_setting('default_transaction_read_only'),
			       current_setting('transaction_read_only'),
			       current_setting('transaction_isolation'),
			       current_setting('statement_timeout'),
			       pg_catalog.inet_server_addr()::text,
				       pg_catalog.inet_server_port(),
				       ssl.ssl,
				       ssl.version,
				       ssl.cipher,
				       ssl.bits,
				       ssl.client_dn,
				       ssl.issuer_dn
				FROM pg_catalog.pg_stat_ssl ssl
				WHERE ssl.pid = pg_catalog.pg_backend_pid()
				""";
		try (Statement statement = connection.createStatement();
				ResultSet result = statement.executeQuery(sql)) {
			if (!result.next()
					|| !RelatedTopicReuseHoldoutPostgresFirstRunLedger.DATABASE_NAME
							.equals(result.getString(1))
					|| !RelatedTopicReuseHoldoutPostgresFirstRunLedger.RUNTIME_ROLE
							.equals(result.getString(2))
					|| !RelatedTopicReuseHoldoutPostgresFirstRunLedger.RUNTIME_ROLE
							.equals(result.getString(3))
					|| !APPLICATION_NAME.equals(result.getString(4))
					|| !configuration.endpoint().serverVersion().equals(result.getString(5))
					|| result.getInt(6) / 10_000 != 17
					|| !"on".equals(result.getString(7))
					|| !"on".equals(result.getString(8))
					|| !"off".equals(result.getString(9))
					|| !"on".equals(result.getString(10))
					|| !"read committed".equals(result.getString(11))
					|| !"10s".equals(result.getString(12))
					|| !sameAddress(
							configuration.expectedAddress(), result.getString(13))
					|| result.getInt(14) != configuration.endpoint().port()
					|| !result.getBoolean(15)
					|| !configuration.endpoint().tlsProtocol().equals(result.getString(16))
					|| !configuration.endpoint().tlsCipher().equals(result.getString(17))
					|| result.getInt(18) != configuration.endpoint().tlsBits()
					|| result.getString(19) != null
					|| result.getString(20) != null
					|| result.next()) {
				throw failure("HOLDOUT_LEDGER_TLS_SESSION_INVALID");
			}
		}
	}

	private static void verifyDatabaseInventory(Connection connection)
			throws SQLException, SafeFailure {
		String sql = """
				SELECT d.datname,
				       d.datallowconn,
				       pg_catalog.has_database_privilege(
				           'openscholar_holdout_ledger_runtime_v1', d.oid, 'CONNECT'),
				       pg_catalog.has_database_privilege(
				           'openscholar_holdout_ledger_runtime_v1', d.oid, 'CREATE'),
				       pg_catalog.has_database_privilege(
				           'openscholar_holdout_ledger_runtime_v1', d.oid, 'TEMPORARY'),
				       pg_catalog.has_database_privilege(
				           'openscholar_holdout_ledger_auditor_v1', d.oid, 'CONNECT'),
				       pg_catalog.has_database_privilege(
				           'openscholar_holdout_ledger_auditor_v1', d.oid, 'CREATE'),
				       pg_catalog.has_database_privilege(
				           'openscholar_holdout_ledger_auditor_v1', d.oid, 'TEMPORARY')
				FROM pg_catalog.pg_database d
				ORDER BY d.datname
				""";
		Set<String> observed = new java.util.HashSet<>();
		try (Statement statement = connection.createStatement();
				ResultSet result = statement.executeQuery(sql)) {
			while (result.next()) {
				String database = result.getString(1);
				if (!DATABASE_INVENTORY.contains(database)
						|| !observed.add(database)
						|| anyNull(result, 2, 3, 4, 5, 6, 7, 8)) {
					throw failure("HOLDOUT_LEDGER_TLS_DATABASE_INVENTORY_INVALID");
				}
				boolean ledger = RelatedTopicReuseHoldoutPostgresFirstRunLedger
						.DATABASE_NAME.equals(database);
				if ((ledger && !result.getBoolean(2))
						|| ("template0".equals(database) && result.getBoolean(2))
						|| result.getBoolean(3) != ledger
						|| result.getBoolean(6) != ledger
						|| result.getBoolean(4)
						|| result.getBoolean(5)
						|| result.getBoolean(7)
						|| result.getBoolean(8)) {
					throw failure("HOLDOUT_LEDGER_TLS_DATABASE_INVENTORY_INVALID");
				}
			}
		}
		if (!observed.equals(DATABASE_INVENTORY)) {
			throw failure("HOLDOUT_LEDGER_TLS_DATABASE_INVENTORY_INVALID");
		}
	}

	private static void verifyRoleBoundary(Connection connection)
			throws SQLException, SafeFailure {
		String sql = """
				SELECT r.rolname,
				       r.rolsuper,
				       r.rolcreaterole,
				       r.rolcreatedb,
				       r.rolreplication,
				       r.rolbypassrls,
				       r.rolinherit,
				       r.rolcanlogin,
				       r.rolconnlimit,
				       r.rolconfig IS NULL,
				       (SELECT count(*) FROM pg_catalog.pg_auth_members m
				        WHERE m.member = r.oid),
				       (SELECT count(*) FROM pg_catalog.pg_auth_members m
				        WHERE m.roleid = r.oid)
				FROM pg_catalog.pg_roles r
				WHERE r.rolname IN (
				    'openscholar_holdout_ledger_auditor_v1',
				    'openscholar_holdout_ledger_bootstrap_v1',
				    'openscholar_holdout_ledger_owner_v1',
				    'openscholar_holdout_ledger_runtime_v1'
				)
				ORDER BY r.rolname
				""";
		List<RoleExpectation> observed = new ArrayList<>();
		try (Statement statement = connection.createStatement();
				ResultSet result = statement.executeQuery(sql)) {
			while (result.next()) {
				if (result.getBoolean(2)
						|| result.getBoolean(3)
						|| result.getBoolean(4)
						|| result.getBoolean(5)
						|| result.getBoolean(6)
						|| result.getBoolean(7)
						|| result.getInt(9) != -1
						|| !result.getBoolean(10)
						|| result.getLong(11) != 0L
						|| result.getLong(12) != 0L) {
					throw failure("HOLDOUT_LEDGER_TLS_ROLE_BOUNDARY_INVALID");
				}
				observed.add(new RoleExpectation(
						result.getString(1), result.getBoolean(8)));
			}
		}
		if (!observed.equals(ROLE_EXPECTATIONS)) {
			throw failure("HOLDOUT_LEDGER_TLS_ROLE_BOUNDARY_INVALID");
		}
	}

	private static void verifyRoleSettings(Connection connection)
			throws SQLException, SafeFailure {
		String sql = """
				SELECT count(*)
				FROM pg_catalog.pg_db_role_setting settings
				LEFT JOIN pg_catalog.pg_roles role ON role.oid = settings.setrole
				WHERE settings.setrole = 0
				   OR role.rolname IN (
				    'openscholar_holdout_ledger_auditor_v1',
				    'openscholar_holdout_ledger_bootstrap_v1',
				    'openscholar_holdout_ledger_owner_v1',
				    'openscholar_holdout_ledger_runtime_v1'
				)
				""";
		try (Statement statement = connection.createStatement();
				ResultSet result = statement.executeQuery(sql)) {
			if (!result.next() || result.getLong(1) != 0L || result.next()) {
				throw failure("HOLDOUT_LEDGER_TLS_ROLE_SETTINGS_INVALID");
			}
		}
	}

	private static boolean anyNull(ResultSet result, int... columns) throws SQLException {
		for (int column : columns) {
			result.getObject(column);
			if (result.wasNull()) {
				return true;
			}
		}
		return false;
	}

	private static boolean sameAddress(InetAddress expected, String observed) {
		if (observed == null || !IPV4_LITERAL.matcher(observed).matches()) {
			return false;
		}
		try {
			return expected.equals(InetAddress.getByAddress(ipv4Bytes(observed)));
		}
		catch (UnknownHostException exception) {
			return false;
		}
	}

	private static byte[] ipv4Bytes(String literal) {
		String[] octets = literal.split("\\.", -1);
		byte[] address = new byte[4];
		for (int index = 0; index < address.length; index++) {
			address[index] = (byte) Integer.parseInt(octets[index]);
		}
		return address;
	}

	private static void validateCaPath(
			Path caCertificate,
			String expectedCaOwner,
			String evaluatorOwner) throws SafeFailure {
		Path parent = caCertificate.getParent();
		if (parent == null) {
			throw failure("HOLDOUT_LEDGER_TLS_CA_INVALID");
		}
		canonicalDirectory(parent, "HOLDOUT_LEDGER_TLS_CA_INVALID");
		Set<PosixFilePermission> filePermissions = permissions(
				caCertificate, "HOLDOUT_LEDGER_TLS_CA_INVALID");
		rejectVisibleAcl(caCertificate, "HOLDOUT_LEDGER_TLS_CA_INVALID");
		if (!owner(parent, "HOLDOUT_LEDGER_TLS_CA_INVALID").equals(expectedCaOwner)
				|| !owner(caCertificate, "HOLDOUT_LEDGER_TLS_CA_INVALID")
						.equals(expectedCaOwner)
				|| filePermissions.contains(PosixFilePermission.GROUP_WRITE)
				|| filePermissions.contains(PosixFilePermission.OTHERS_WRITE)
				|| !Files.isReadable(caCertificate)
				|| Files.isWritable(caCertificate)
				|| linkCount(caCertificate, "HOLDOUT_LEDGER_TLS_CA_INVALID") != 1L) {
			throw failure("HOLDOUT_LEDGER_TLS_CA_INVALID");
		}
		Path current = parent;
		while (current != null) {
			rejectVisibleAcl(current, "HOLDOUT_LEDGER_TLS_CA_INVALID");
			Set<PosixFilePermission> observed = permissions(
					current, "HOLDOUT_LEDGER_TLS_CA_INVALID");
			String observedOwner = owner(current, "HOLDOUT_LEDGER_TLS_CA_INVALID");
			if (observed.contains(PosixFilePermission.GROUP_WRITE)
					|| observed.contains(PosixFilePermission.OTHERS_WRITE)
					|| Files.isWritable(current)
					|| (observedOwner.equals(evaluatorOwner)
							&& observed.contains(PosixFilePermission.OWNER_WRITE))) {
				throw failure("HOLDOUT_LEDGER_TLS_CA_INVALID");
			}
			current = current.getParent();
		}
	}

	private static void validateSecretPath(Path passwordFile, String expectedOwner)
			throws SafeFailure {
		Path parent = passwordFile.getParent();
		if (parent == null) {
			throw failure("HOLDOUT_LEDGER_TLS_SECRET_INVALID");
		}
		canonicalDirectory(parent, "HOLDOUT_LEDGER_TLS_SECRET_INVALID");
		rejectVisibleAcl(parent, "HOLDOUT_LEDGER_TLS_SECRET_INVALID");
		rejectVisibleAcl(passwordFile, "HOLDOUT_LEDGER_TLS_SECRET_INVALID");
		if (!permissions(parent, "HOLDOUT_LEDGER_TLS_SECRET_INVALID")
					.equals(SECRET_DIRECTORY_PERMISSIONS)
				|| !permissions(passwordFile, "HOLDOUT_LEDGER_TLS_SECRET_INVALID")
							.equals(SECRET_FILE_PERMISSIONS)
				|| !owner(parent, "HOLDOUT_LEDGER_TLS_SECRET_INVALID")
						.equals(expectedOwner)
				|| !owner(passwordFile, "HOLDOUT_LEDGER_TLS_SECRET_INVALID")
						.equals(expectedOwner)
				|| Files.isWritable(passwordFile)
				|| linkCount(passwordFile, "HOLDOUT_LEDGER_TLS_SECRET_INVALID") != 1L) {
			throw failure("HOLDOUT_LEDGER_TLS_SECRET_INVALID");
		}
	}

	private static Path canonicalDirectory(Path path, String diagnostic)
			throws SafeFailure {
		if (path == null || !path.isAbsolute() || !path.normalize().equals(path)
				|| Files.isSymbolicLink(path)
				|| !Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
			throw failure(diagnostic);
		}
		try {
			Path real = path.toRealPath();
			if (!real.equals(path)) {
				throw failure(diagnostic);
			}
			return real;
		}
		catch (IOException | RuntimeException exception) {
			throw failure(diagnostic);
		}
	}

	private static Path canonicalFile(Path path, String diagnostic) throws SafeFailure {
		if (path == null || !path.isAbsolute() || !path.normalize().equals(path)
				|| Files.isSymbolicLink(path)
				|| !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
			throw failure(diagnostic);
		}
		try {
			Path real = path.toRealPath();
			if (!real.equals(path)) {
				throw failure(diagnostic);
			}
			return real;
		}
		catch (IOException | RuntimeException exception) {
			throw failure(diagnostic);
		}
	}

	private static ReadFile readStableFile(Path path, int maximum, String diagnostic)
			throws SafeFailure {
		try {
			FileSnapshot before = snapshot(path, diagnostic);
			if (before.size() < 0L || before.size() > maximum) {
				throw failure(diagnostic);
			}
			byte[] bytes = new byte[Math.toIntExact(before.size())];
			Set<OpenOption> options = Set.of(
					StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS);
			try (SeekableByteChannel channel = Files.newByteChannel(path, options)) {
				ByteBuffer buffer = ByteBuffer.wrap(bytes);
				while (buffer.hasRemaining()) {
					if (channel.read(buffer) < 0) {
						throw failure(diagnostic);
					}
				}
				if (channel.read(ByteBuffer.allocate(1)) != -1) {
					throw failure(diagnostic);
				}
			}
			FileSnapshot after = snapshot(path, diagnostic);
			if (!before.equals(after)) {
				Arrays.fill(bytes, (byte) 0);
				throw failure(diagnostic);
			}
			return new ReadFile(bytes, after);
		}
		catch (SafeFailure failure) {
			throw failure;
		}
		catch (IOException | RuntimeException exception) {
			throw failure(diagnostic);
		}
	}

	private static FileSnapshot snapshot(Path path, String diagnostic)
			throws IOException, SafeFailure {
		BasicFileAttributes attributes = Files.readAttributes(
				path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
		if (!attributes.isRegularFile() || attributes.fileKey() == null) {
			throw failure(diagnostic);
		}
		return new FileSnapshot(
				attributes.fileKey().toString(),
				attributes.size(),
				attributes.lastModifiedTime(),
				permissions(path, diagnostic),
				owner(path, diagnostic),
				linkCount(path, diagnostic));
	}

	private static Set<PosixFilePermission> permissions(Path path, String diagnostic)
			throws SafeFailure {
		try {
			return Set.copyOf(Files.getPosixFilePermissions(
					path, LinkOption.NOFOLLOW_LINKS));
		}
		catch (IOException | RuntimeException exception) {
			throw failure(diagnostic);
		}
	}

	private static String owner(Path path, String diagnostic) throws SafeFailure {
		try {
			return Files.getOwner(path, LinkOption.NOFOLLOW_LINKS).getName();
		}
		catch (IOException | RuntimeException exception) {
			throw failure(diagnostic);
		}
	}

	private static long linkCount(Path path, String diagnostic) throws SafeFailure {
		try {
			Object value = Files.getAttribute(
					path, "unix:nlink", LinkOption.NOFOLLOW_LINKS);
			if (!(value instanceof Number number)) {
				throw failure(diagnostic);
			}
			return number.longValue();
		}
		catch (IOException | RuntimeException exception) {
			throw failure(diagnostic);
		}
	}

	private static void rejectVisibleAcl(Path path, String diagnostic)
			throws SafeFailure {
		try {
			AclFileAttributeView view = Files.getFileAttributeView(
					path, AclFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
			if (view != null && !view.getAcl().isEmpty()) {
				throw failure(diagnostic);
			}
		}
		catch (SafeFailure failure) {
			throw failure;
		}
		catch (IOException | RuntimeException exception) {
			throw failure(diagnostic);
		}
	}

	private static char[] decodePassword(byte[] bytes) throws SafeFailure {
		try {
			CharBuffer decoded = StandardCharsets.UTF_8.newDecoder()
					.onMalformedInput(CodingErrorAction.REPORT)
					.onUnmappableCharacter(CodingErrorAction.REPORT)
					.decode(ByteBuffer.wrap(bytes));
			if (decoded.length() < 1 || decoded.length() > MAXIMUM_PASSWORD_CHARACTERS) {
				throw failure("HOLDOUT_LEDGER_TLS_SECRET_INVALID");
			}
			char[] password = new char[decoded.length()];
			decoded.get(password);
			for (char character : password) {
				if (character == '\0' || character == '\r' || character == '\n') {
					Arrays.fill(password, '\0');
					throw failure("HOLDOUT_LEDGER_TLS_SECRET_INVALID");
				}
			}
			return password;
		}
		catch (CharacterCodingException exception) {
			throw failure("HOLDOUT_LEDGER_TLS_SECRET_INVALID");
		}
	}

	private static String exactText(
			String value, int minimum, int maximum, String diagnostic) throws SafeFailure {
		if (value == null
				|| value.length() < minimum
				|| value.length() > maximum
				|| !value.equals(value.strip())
				|| value.codePoints().anyMatch(Character::isISOControl)) {
			throw failure(diagnostic);
		}
		return value;
	}

	private static boolean constantTimeDigestEquals(String expected, byte[] bytes) {
		return MessageDigest.isEqual(
				HexFormat.of().parseHex(expected), sha256Digest().digest(bytes));
	}

	private static byte[] hmacSha256(byte[] key, byte[] bytes) {
		try {
			Mac mac = Mac.getInstance("HmacSHA256");
			mac.init(new SecretKeySpec(key, "HmacSHA256"));
			return mac.doFinal(bytes);
		}
		catch (java.security.GeneralSecurityException exception) {
			throw new IllegalStateException("HmacSHA256 unavailable");
		}
	}

	private static MessageDigest sha256Digest() {
		try {
			return MessageDigest.getInstance("SHA-256");
		}
		catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException("SHA-256 unavailable");
		}
	}

	private static void rollbackQuietly(Connection connection) {
		try {
			connection.rollback();
		}
		catch (SQLException | RuntimeException ignored) {
			// The preflight will fail and the connection will be closed.
		}
	}

	private static void closeQuietly(Connection connection) {
		try {
			connection.close();
		}
		catch (SQLException | RuntimeException ignored) {
			// Failure remains fail-closed and no connection is returned.
		}
	}

	private static SafeFailure failure(String diagnostic) {
		return new SafeFailure(diagnostic);
	}

	record EndpointRecord(
			int schemaVersion,
			String host,
			int port,
			String serverAddress,
			String serverVersion,
			String tlsProtocol,
			String tlsCipher,
			int tlsBits,
			String caSha256) {

		@Override
		public String toString() {
			return "EndpointRecord[schemaVersion=" + schemaVersion + "]";
		}
	}

	record RuntimeFiles(
			Path repositoryRoot,
			Path caCertificate,
			Path runtimePassword,
			String expectedCaOwner,
			String expectedOwner) {

		@Override
		public String toString() {
			return "RuntimeFiles[redacted]";
		}
	}

	static final class VerifiedRuntimeConnectionSource {

		private final ValidatedConfiguration configuration;
		private final ConnectionOpener opener;
		private final AtomicBoolean consumed = new AtomicBoolean();

		private VerifiedRuntimeConnectionSource(
				ValidatedConfiguration configuration,
				ConnectionOpener opener) {
			this.configuration = Objects.requireNonNull(configuration, "configuration");
			this.opener = Objects.requireNonNull(opener, "opener");
		}

		Connection openClaimConnection() throws SQLException {
			if (!consumed.compareAndSet(false, true)) {
				throw new SQLException("HOLDOUT_LEDGER_TLS_CONNECTION_ALREADY_CONSUMED");
			}
			try {
				revalidate(configuration);
				return openVerifiedConnection(configuration, opener);
			}
			catch (SafeFailure failure) {
				throw new SQLException(failure.diagnostic());
			}
			catch (RuntimeException exception) {
				throw new SQLException("HOLDOUT_LEDGER_TLS_CONNECTION_FAILED");
			}
			finally {
				configuration.destroyPasswordBinding();
			}
		}

		@Override
		public String toString() {
			return "VerifiedRuntimeConnectionSource[opaque]";
		}
	}

	static final class PreflightException extends Exception {

		private PreflightException(String diagnostic) {
			super(diagnostic);
		}
	}

	@FunctionalInterface
	private interface ConnectionOpener {

		Connection open(ValidatedConfiguration configuration, char[] password)
				throws SQLException;
	}

	private static final class ValidatedConfiguration {

		private final EndpointRecord endpoint;
		private final InetAddress expectedAddress;
		private final Path repository;
		private final Path caCertificate;
		private final FileSnapshot caSnapshot;
		private final Path passwordFile;
		private final FileSnapshot passwordSnapshot;
		private final byte[] passwordBindingKey;
		private final byte[] passwordBinding;
		private final String expectedCaOwner;
		private final String expectedOwner;

		private ValidatedConfiguration(
				EndpointRecord endpoint,
				InetAddress expectedAddress,
				Path repository,
				Path caCertificate,
				FileSnapshot caSnapshot,
				Path passwordFile,
				FileSnapshot passwordSnapshot,
				byte[] passwordBindingKey,
				byte[] passwordBinding,
				String expectedCaOwner,
				String expectedOwner) {
			this.endpoint = endpoint;
			this.expectedAddress = expectedAddress;
			this.repository = repository;
			this.caCertificate = caCertificate;
			this.caSnapshot = caSnapshot;
			this.passwordFile = passwordFile;
			this.passwordSnapshot = passwordSnapshot;
			this.passwordBindingKey = Objects.requireNonNull(
					passwordBindingKey, "passwordBindingKey");
			this.passwordBinding = Objects.requireNonNull(
					passwordBinding, "passwordBinding");
			this.expectedCaOwner = expectedCaOwner;
			this.expectedOwner = expectedOwner;
		}

		private EndpointRecord endpoint() {
			return endpoint;
		}

		private InetAddress expectedAddress() {
			return expectedAddress;
		}

		private Path repository() {
			return repository;
		}

		private Path caCertificate() {
			return caCertificate;
		}

		private FileSnapshot caSnapshot() {
			return caSnapshot;
		}

		private Path passwordFile() {
			return passwordFile;
		}

		private FileSnapshot passwordSnapshot() {
			return passwordSnapshot;
		}

		private String expectedOwner() {
			return expectedOwner;
		}

		private String expectedCaOwner() {
			return expectedCaOwner;
		}

		private boolean passwordMatches(byte[] password) {
			byte[] observed = hmacSha256(passwordBindingKey, password);
			try {
				return MessageDigest.isEqual(passwordBinding, observed);
			}
			finally {
				Arrays.fill(observed, (byte) 0);
			}
		}

		private void destroyPasswordBinding() {
			Arrays.fill(passwordBindingKey, (byte) 0);
			Arrays.fill(passwordBinding, (byte) 0);
		}

		@Override
		public String toString() {
			return "ValidatedConfiguration[redacted]";
		}
	}

	private record ReadFile(byte[] bytes, FileSnapshot snapshot) {
	}

	private record FileSnapshot(
			String fileKey,
			long size,
			FileTime lastModified,
			Set<PosixFilePermission> permissions,
			String owner,
			long linkCount) {

		private FileSnapshot {
			permissions = Set.copyOf(permissions);
		}
	}

	private record RoleExpectation(String name, boolean login) {
	}

	private static final class SafeFailure extends Exception {

		private final String diagnostic;

		private SafeFailure(String diagnostic) {
			this.diagnostic = diagnostic;
		}

		private String diagnostic() {
			return diagnostic;
		}
	}
}
