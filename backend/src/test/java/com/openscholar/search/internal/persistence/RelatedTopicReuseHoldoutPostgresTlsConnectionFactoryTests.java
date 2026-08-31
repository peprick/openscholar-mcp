package com.openscholar.search.internal.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import javax.sql.DataSource;

import com.openscholar.search.internal.persistence.RelatedTopicReuseHoldoutPostgresTlsConnectionFactory.EndpointRecord;
import com.openscholar.search.internal.persistence.RelatedTopicReuseHoldoutPostgresTlsConnectionFactory.PreflightException;
import com.openscholar.search.internal.persistence.RelatedTopicReuseHoldoutPostgresTlsConnectionFactory.RuntimeFiles;
import com.openscholar.search.internal.persistence.RelatedTopicReuseHoldoutPostgresTlsConnectionFactory.VerifiedRuntimeConnectionSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.postgresql.ds.PGSimpleDataSource;

class RelatedTopicReuseHoldoutPostgresTlsConnectionFactoryTests {

	private static final String PASSWORD = "synthetic-runtime-secret-do-not-leak";
	private static final Set<PosixFilePermission> CA_DIRECTORY_PERMISSIONS = Set.of(
			PosixFilePermission.OWNER_READ,
			PosixFilePermission.OWNER_EXECUTE,
			PosixFilePermission.GROUP_READ,
			PosixFilePermission.GROUP_EXECUTE,
			PosixFilePermission.OTHERS_READ,
			PosixFilePermission.OTHERS_EXECUTE);
	private static final Set<PosixFilePermission> CA_FILE_PERMISSIONS = Set.of(
			PosixFilePermission.OWNER_READ,
			PosixFilePermission.GROUP_READ,
			PosixFilePermission.OTHERS_READ);
	private static final Set<PosixFilePermission> SECRET_DIRECTORY_PERMISSIONS = Set.of(
			PosixFilePermission.OWNER_READ,
			PosixFilePermission.OWNER_WRITE,
			PosixFilePermission.OWNER_EXECUTE);
	private static final Set<PosixFilePermission> SECRET_FILE_PERMISSIONS = Set.of(
			PosixFilePermission.OWNER_READ);

	@TempDir
	Path temporaryDirectory;

	@Test
	void configuresOneFixedVerifyFullPostgresDataSourceWithoutStoredPassword()
			throws Exception {
		Materials materials = materials("data-source");

		PGSimpleDataSource dataSource = RelatedTopicReuseHoldoutPostgresTlsTestFixture
				.configuredDataSource(materials.endpoint(), materials.files());

		assertThat(dataSource.getServerNames()).containsExactly("ledger.example.test");
		assertThat(dataSource.getPortNumbers()).containsExactly(6432);
		assertThat(dataSource.getDatabaseName())
				.isEqualTo(RelatedTopicReuseHoldoutPostgresFirstRunLedger.DATABASE_NAME);
		assertThat(dataSource.getUser())
				.isEqualTo(RelatedTopicReuseHoldoutPostgresFirstRunLedger.RUNTIME_ROLE);
		assertThat(dataSource.getPassword()).isNull();
		assertThat(dataSource.getProtocolVersion()).isEqualTo(3);
		assertThat(dataSource.getAssumeMinServerVersion()).isEqualTo("17");
		assertThat(dataSource.getApplicationName())
				.isEqualTo(RelatedTopicReuseHoldoutPostgresTlsConnectionFactory.APPLICATION_NAME);
		assertThat(dataSource.getOptions()).isEqualTo("-c statement_timeout=10000");
		assertThat(dataSource.getQueryTimeout()).isZero();
		assertThat(dataSource.getSsl()).isTrue();
		assertThat(dataSource.getSslMode()).isEqualTo("verify-full");
		assertThat(dataSource.getSslRootCert()).isEqualTo(materials.caCertificate().toString());
		assertThat(dataSource.getSslCert()).isEmpty();
		assertThat(dataSource.getSslKey()).isEmpty();
		assertThat(dataSource.getSslfactory())
				.isEqualTo("org.postgresql.ssl.LibPQFactory");
		assertThat(dataSource.getSslHostnameVerifier())
				.isEqualTo(RelatedTopicReuseHoldoutStrictDnsSanHostnameVerifier.class
						.getName());
		assertThat(dataSource.getSslNegotiation()).isEqualTo("direct");
		assertThat(dataSource.getRequireAuth()).isEqualTo("scram-sha-256");
		assertThat(dataSource.getChannelBinding()).isEqualTo("require");
		assertThat(dataSource.getScramMaxIterations()).isEqualTo(100_000);
		assertThat(dataSource.getGssEncMode()).isEqualTo("disable");
		assertThat(dataSource.getGssUseDefaultCreds()).isFalse();
		assertThat(dataSource.getJaasLogin()).isFalse();
		assertThat(dataSource.getUseSpNego()).isFalse();
		assertThat(dataSource.getTargetServerType()).isEqualTo("primary");
		// PGJDBC 42.7.x's boolean getter reports property presence, not its value.
		assertThat(dataSource.getProperty("loadBalanceHosts")).isEqualTo("false");
		assertThat(dataSource.getHostRecheckSeconds()).isZero();
		assertThat(dataSource.getSocketFactory()).isNull();
		assertThat(dataSource.getLoginTimeout())
				.isEqualTo(RelatedTopicReuseHoldoutPostgresTlsConnectionFactory
						.LOGIN_TIMEOUT_SECONDS);
		assertThat(dataSource.getConnectTimeout())
				.isEqualTo(RelatedTopicReuseHoldoutPostgresTlsConnectionFactory
						.CONNECT_TIMEOUT_SECONDS);
		assertThat(dataSource.getSslResponseTimeout())
				.isEqualTo(RelatedTopicReuseHoldoutPostgresTlsConnectionFactory
						.SSL_RESPONSE_TIMEOUT_MILLIS);
		assertThat(dataSource.getSocketTimeout())
				.isEqualTo(RelatedTopicReuseHoldoutPostgresTlsConnectionFactory
						.SOCKET_TIMEOUT_SECONDS);
		assertThat(dataSource.getCancelSignalTimeout())
				.isEqualTo(RelatedTopicReuseHoldoutPostgresTlsConnectionFactory
						.CONNECT_TIMEOUT_SECONDS);
		assertThat(dataSource.getLogServerErrorDetail()).isFalse();
		assertThat(dataSource.getLogUnclosedConnections()).isFalse();
		assertThat(dataSource.getAllowEncodingChanges()).isFalse();
		assertThat(dataSource.getURL()).doesNotContain(PASSWORD, "password=");
	}

	@Test
	void exposesOnlyStructuredInputsAndAPrivateOpaqueSingleUseSource()
			throws Exception {
		Class<?> factory = RelatedTopicReuseHoldoutPostgresTlsConnectionFactory.class;
		assertThat(factory.getModifiers())
				.satisfies(modifiers -> assertThat(Modifier.isFinal(modifiers)).isTrue())
				.satisfies(modifiers -> assertThat(Modifier.isPublic(modifiers)).isFalse());
		assertThat(factory.getDeclaredConstructors())
				.singleElement()
				.satisfies(constructor -> assertThat(
						Modifier.isPrivate(constructor.getModifiers())).isTrue());

		List<Method> ordinaryMethods = Arrays.stream(factory.getDeclaredMethods())
				.filter(method -> !Modifier.isPrivate(method.getModifiers()))
				.toList();
		assertThat(ordinaryMethods).singleElement().satisfies(method -> {
			assertThat(method.getName()).isEqualTo("preflight");
			assertThat(method.getParameterTypes())
					.containsExactly(EndpointRecord.class, RuntimeFiles.class);
			assertThat(method.getReturnType())
					.isEqualTo(RelatedTopicReuseHoldoutPostgresFirstRunLedger.class);
		});
		assertThat(ordinaryMethods)
				.flatExtracting(method -> Arrays.asList(method.getParameterTypes()))
				.doesNotContain(
						DataSource.class,
						PGSimpleDataSource.class,
						Properties.class,
						Map.class,
						URI.class,
						String.class,
						char[].class,
						byte[].class);
		assertThat(Arrays.stream(EndpointRecord.class.getRecordComponents())
				.map(component -> component.getName().toLowerCase())
				.toList()).noneMatch(name -> name.contains("jdbc") || name.contains("password"));
		assertThat(Arrays.stream(RuntimeFiles.class.getRecordComponents())
				.filter(component -> component.getName().equals("runtimePassword")))
				.singleElement()
				.satisfies(component -> assertThat(component.getType()).isEqualTo(Path.class));

		Class<?> source = VerifiedRuntimeConnectionSource.class;
		assertThat(source.getInterfaces()).doesNotContain(DataSource.class);
		assertThat(source.getDeclaredConstructors())
				.singleElement()
				.satisfies(constructor -> assertThat(
						Modifier.isPrivate(constructor.getModifiers())).isTrue());
		assertThat(Arrays.stream(source.getDeclaredFields()))
				.allSatisfy(field -> assertThat(Modifier.isPrivate(field.getModifiers())).isTrue());
		assertThat(Arrays.stream(source.getDeclaredMethods())
				.filter(method -> !Modifier.isPrivate(method.getModifiers()))
				.map(Method::getName)
				.toList()).containsExactlyInAnyOrder("openClaimConnection", "toString");

		Materials materials = materials("single-use");
		ConnectionProbe phaseA = new ConnectionProbe(materials.endpoint());
		ConnectionProbe phaseB = new ConnectionProbe(materials.endpoint());
		AtomicInteger attempts = new AtomicInteger();
		VerifiedRuntimeConnectionSource verified =
				RelatedTopicReuseHoldoutPostgresTlsTestFixture.preflight(
						materials.endpoint(), materials.files(), ignored -> {
							int attempt = attempts.getAndIncrement();
							return attempt == 0 ? phaseA.connection() : phaseB.connection();
						});

		Connection opened = verified.openClaimConnection();

		assertThat(opened).isSameAs(phaseB.connection());
		assertThat(attempts).hasValue(2);
		assertThatThrownBy(verified::openClaimConnection)
				.isInstanceOf(SQLException.class)
				.hasMessage("HOLDOUT_LEDGER_TLS_CONNECTION_ALREADY_CONSUMED")
				.hasNoCause();
		assertThat(attempts).hasValue(2);
		opened.close();
	}

	@Test
	void rejectsInvalidEndpointAndCaDigestBeforeOpeningAConnection() throws Exception {
		Materials invalidEndpoint = materials("invalid-endpoint");
		EndpointRecord endpoint = invalidEndpoint.endpoint();
		EndpointRecord queryHost = new EndpointRecord(
				endpoint.schemaVersion(),
				"ledger.example.test?sslmode=require",
				endpoint.port(),
				endpoint.serverAddress(),
				endpoint.serverVersion(),
				endpoint.tlsProtocol(),
				endpoint.tlsCipher(),
				endpoint.tlsBits(),
				endpoint.caSha256());
		assertRejectedWithoutOpen(
				queryHost,
				invalidEndpoint.files(),
				"HOLDOUT_LEDGER_TLS_ENDPOINT_INVALID");

		Materials digestMismatch = materials("digest-mismatch");
		EndpointRecord valid = digestMismatch.endpoint();
		EndpointRecord wrongDigest = new EndpointRecord(
				valid.schemaVersion(),
				valid.host(),
				valid.port(),
				valid.serverAddress(),
				valid.serverVersion(),
				valid.tlsProtocol(),
				valid.tlsCipher(),
				valid.tlsBits(),
				"0".repeat(64));
		assertRejectedWithoutOpen(
				wrongDigest,
				digestMismatch.files(),
				"HOLDOUT_LEDGER_TLS_CA_INVALID");

		Materials relativePath = materials("relative-path");
		RuntimeFiles relative = new RuntimeFiles(
				relativePath.repository(),
				relativePath.caCertificate(),
				Path.of("runtime-password"),
				relativePath.caOwner(),
				relativePath.owner());
		assertRejectedWithoutOpen(
				relativePath.endpoint(), relative, "HOLDOUT_LEDGER_TLS_SECRET_INVALID");
	}

	@Test
	void rejectsSecretPermissionHardLinkAndSymlinkBeforeOpeningAConnection()
			throws Exception {
		Materials permissions = materials("secret-permissions");
		Files.setPosixFilePermissions(
				permissions.passwordFile(),
				Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
		assertRejectedWithoutOpen(
				permissions.endpoint(), permissions.files(),
				"HOLDOUT_LEDGER_TLS_SECRET_INVALID");

		Materials hardLink = materials("secret-hard-link");
		Files.createLink(
				hardLink.root().resolve("second-password-link"), hardLink.passwordFile());
		assertRejectedWithoutOpen(
				hardLink.endpoint(), hardLink.files(),
				"HOLDOUT_LEDGER_TLS_SECRET_INVALID");

		Materials symlink = materials("secret-symlink");
		Path passwordSymlink = symlink.root().resolve("password-symlink");
		Files.createSymbolicLink(passwordSymlink, symlink.passwordFile());
		RuntimeFiles symlinkFiles = new RuntimeFiles(
				symlink.repository(),
				symlink.caCertificate(),
				passwordSymlink,
				symlink.caOwner(),
				symlink.owner());
		assertRejectedWithoutOpen(
				symlink.endpoint(), symlinkFiles,
				"HOLDOUT_LEDGER_TLS_SECRET_INVALID");
	}

	@Test
	void rejectsCaPermissionHardLinkAndSymlinkBeforeOpeningAConnection()
			throws Exception {
		Materials permissions = syntheticCaMaterials("ca-permissions");
		Files.setPosixFilePermissions(
				permissions.caCertificate(),
				Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.GROUP_WRITE));
		assertRejectedWithoutOpen(
				permissions.endpoint(), permissions.files(),
				"HOLDOUT_LEDGER_TLS_CA_INVALID");

		Materials hardLink = syntheticCaMaterials("ca-hard-link");
		Files.createLink(hardLink.root().resolve("second-ca-link"), hardLink.caCertificate());
		assertRejectedWithoutOpen(
				hardLink.endpoint(), hardLink.files(),
				"HOLDOUT_LEDGER_TLS_CA_INVALID");

		Materials symlink = syntheticCaMaterials("ca-symlink");
		Path caSymlink = symlink.root().resolve("ca-symlink.pem");
		Files.createSymbolicLink(caSymlink, symlink.caCertificate());
		RuntimeFiles symlinkFiles = new RuntimeFiles(
				symlink.repository(),
				caSymlink,
				symlink.passwordFile(),
				symlink.caOwner(),
				symlink.files().expectedOwner());
		assertRejectedWithoutOpen(
				symlink.endpoint(), symlinkFiles,
				"HOLDOUT_LEDGER_TLS_CA_INVALID");
	}

	@Test
	void preflightsEachReturnedConnectionReadOnlyRollsBackAndRestoresWritableState()
			throws Exception {
		Materials materials = materials("transaction");
		ConnectionProbe phaseA = new ConnectionProbe(materials.endpoint());
		ConnectionProbe phaseB = new ConnectionProbe(materials.endpoint());
		AtomicReference<char[]> observedPassword = new AtomicReference<>();
		AtomicInteger attempts = new AtomicInteger();

		VerifiedRuntimeConnectionSource verified =
				RelatedTopicReuseHoldoutPostgresTlsTestFixture.preflight(
						materials.endpoint(), materials.files(), password -> {
							assertThat(password).containsExactly(PASSWORD.toCharArray());
							observedPassword.set(password);
							return attempts.getAndIncrement() == 0
									? phaseA.connection()
									: phaseB.connection();
						});

		assertThat(observedPassword.get()).containsOnly('\0');
		assertSuccessfulProbe(phaseA, true);
		Connection returned = verified.openClaimConnection();

		assertThat(returned).isSameAs(phaseB.connection());
		assertThat(observedPassword.get()).containsOnly('\0');
		assertSuccessfulProbe(phaseB, false);
		assertThat(phaseB.autoCommit()).isTrue();
		assertThat(phaseB.readOnly()).isFalse();
		assertThat(phaseB.closed()).isFalse();
		returned.close();
	}

	@Test
	void sessionFailureRollsBackClosesAndReturnsOnlyAStableSafeDiagnostic()
			throws Exception {
		Materials materials = materials("invalid-session");
		ConnectionProbe invalid = new ConnectionProbe(materials.endpoint());
		invalid.setEncrypted(false);

		assertThatThrownBy(() -> RelatedTopicReuseHoldoutPostgresTlsTestFixture.preflight(
				materials.endpoint(), materials.files(), ignored -> invalid.connection()))
				.isInstanceOf(PreflightException.class)
				.hasMessage("HOLDOUT_LEDGER_TLS_SESSION_INVALID")
				.hasNoCause()
				.satisfies(failure -> assertNoSensitiveText(failure, materials));
		assertThat(invalid.rollbacks()).isOne();
		assertThat(invalid.commits()).isZero();
		assertThat(invalid.closed()).isTrue();
	}

	@Test
	void rejectsGlobalOrFixedRoleSettingsAndQueriesTheGlobalSettingBoundary()
			throws Exception {
		Materials materials = materials("role-settings");
		ConnectionProbe invalid = new ConnectionProbe(materials.endpoint());
		invalid.setRoleSettingsCount(1L);

		assertProbeRejected(
				materials,
				invalid,
				"HOLDOUT_LEDGER_TLS_ROLE_SETTINGS_INVALID");
		assertThat(invalid.queries())
				.filteredOn(sql -> sql.contains("pg_db_role_setting"))
				.singleElement()
				.asString()
				.contains("settings.setrole = 0");
	}

	@Test
	void rejectsUnexpectedDatabaseAndCrossDatabaseConnectDrift() throws Exception {
		Materials extraDatabase = materials("extra-database");
		ConnectionProbe extra = new ConnectionProbe(extraDatabase.endpoint());
		extra.setUnexpectedDatabase(true);
		assertProbeRejected(
				extraDatabase,
				extra,
				"HOLDOUT_LEDGER_TLS_DATABASE_INVENTORY_INVALID");

		Materials runtimeConnect = materials("runtime-cross-database-connect");
		ConnectionProbe runtime = new ConnectionProbe(runtimeConnect.endpoint());
		runtime.setRuntimeCrossDatabaseConnect(true);
		assertProbeRejected(
				runtimeConnect,
				runtime,
				"HOLDOUT_LEDGER_TLS_DATABASE_INVENTORY_INVALID");

		Materials auditorConnect = materials("auditor-cross-database-connect");
		ConnectionProbe auditor = new ConnectionProbe(auditorConnect.endpoint());
		auditor.setAuditorCrossDatabaseConnect(true);
		assertProbeRejected(
				auditorConnect,
				auditor,
				"HOLDOUT_LEDGER_TLS_DATABASE_INVENTORY_INVALID");
	}

	@Test
	void rejectsRoleMembershipLoginAndConfigurationDrift() throws Exception {
		for (RoleDrift drift : RoleDrift.values()) {
			if (drift == RoleDrift.NONE) {
				continue;
			}
			Materials materials = materials("role-drift-" + drift.name().toLowerCase());
			ConnectionProbe invalid = new ConnectionProbe(materials.endpoint());
			invalid.setRoleDrift(drift);

			assertProbeRejected(
					materials,
					invalid,
					"HOLDOUT_LEDGER_TLS_ROLE_BOUNDARY_INVALID");
		}
	}

	@Test
	void rejectsRepositoryContainedCaAndPasswordBeforeOpeningAConnection()
			throws Exception {
		Materials caInside = materials("repository-ca");
		Path repositoryCa = Files.writeString(
				caInside.repository().resolve("untrusted-ca.pem"),
				"repository-controlled-ca\n",
				StandardCharsets.US_ASCII).toRealPath();
		Files.setPosixFilePermissions(repositoryCa, CA_FILE_PERMISSIONS);
		EndpointRecord caEndpoint = endpointWithCaDigest(
				caInside.endpoint(), sha256(Files.readAllBytes(repositoryCa)));
		RuntimeFiles containedCaFiles = new RuntimeFiles(
				caInside.repository(),
				repositoryCa,
				caInside.passwordFile(),
				Files.getOwner(repositoryCa).getName(),
				"synthetic-distinct-evaluator-owner");
		assertRejectedWithoutOpen(
				caEndpoint,
				containedCaFiles,
				"HOLDOUT_LEDGER_TLS_CONFIGURATION_INVALID");

		Materials passwordInside = materials("repository-password");
		Path repositoryPassword = Files.writeString(
				passwordInside.repository().resolve("runtime-password"),
				PASSWORD,
				StandardCharsets.UTF_8).toRealPath();
		Files.setPosixFilePermissions(repositoryPassword, SECRET_FILE_PERMISSIONS);
		RuntimeFiles containedPasswordFiles = new RuntimeFiles(
				passwordInside.repository(),
				passwordInside.caCertificate(),
				repositoryPassword,
				passwordInside.caOwner(),
				passwordInside.owner());
		assertRejectedWithoutOpen(
				passwordInside.endpoint(),
				containedPasswordFiles,
				"HOLDOUT_LEDGER_TLS_CONFIGURATION_INVALID");
	}

	@Test
	void openerFailureAndAllDiagnosticObjectsHideSecretsAndAbsolutePaths()
			throws Exception {
		Materials materials = materials("redaction");
		String injected = PASSWORD + ' ' + materials.passwordFile() + ' '
				+ materials.caCertificate();

		assertThatThrownBy(() -> RelatedTopicReuseHoldoutPostgresTlsTestFixture.preflight(
				materials.endpoint(),
				materials.files(),
				ignored -> throwSql(injected)))
				.isInstanceOf(PreflightException.class)
				.hasMessage("HOLDOUT_LEDGER_TLS_CONNECTION_FAILED")
				.hasNoCause()
				.satisfies(failure -> assertNoSensitiveText(failure, materials));
		assertThat(materials.endpoint().toString())
				.doesNotContain(materials.endpoint().host(), materials.endpoint().serverAddress());
		assertThat(materials.files().toString())
				.doesNotContain(
						PASSWORD,
						materials.repository().toString(),
						materials.caCertificate().toString(),
						materials.passwordFile().toString());
		assertThat(RelatedTopicReuseHoldoutPostgresTlsTestFixture.validatedConfigurationText(
				materials.endpoint(), materials.files()))
				.isEqualTo("ValidatedConfiguration[redacted]");
	}

	@Test
	void fileMutationAfterPhaseAPreflightConsumesSourceWithoutASecondOpen()
			throws Exception {
		Materials materials = materials("mutation");
		ConnectionProbe phaseA = new ConnectionProbe(materials.endpoint());
		AtomicInteger attempts = new AtomicInteger();
		VerifiedRuntimeConnectionSource verified =
				RelatedTopicReuseHoldoutPostgresTlsTestFixture.preflight(
						materials.endpoint(), materials.files(), ignored -> {
							attempts.incrementAndGet();
							return phaseA.connection();
						});
		Files.setPosixFilePermissions(
				materials.passwordFile(),
				Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
		Files.writeString(materials.passwordFile(), "changed-secret", StandardCharsets.UTF_8);
		Files.setPosixFilePermissions(materials.passwordFile(), SECRET_FILE_PERMISSIONS);

		assertThatThrownBy(verified::openClaimConnection)
				.isInstanceOf(SQLException.class)
				.hasMessage("HOLDOUT_LEDGER_TLS_CONFIGURATION_CHANGED")
				.hasNoCause()
				.satisfies(failure -> assertNoSensitiveText(failure, materials));
		assertThat(attempts).hasValue(1);
		assertThatThrownBy(verified::openClaimConnection)
				.isInstanceOf(SQLException.class)
				.hasMessage("HOLDOUT_LEDGER_TLS_CONNECTION_ALREADY_CONSUMED");
		assertThat(attempts).hasValue(1);
	}

	private static Connection throwSql(String message) throws SQLException {
		throw new SQLException(message, new IllegalStateException(message));
	}

	private static EndpointRecord endpointWithCaDigest(
			EndpointRecord endpoint, String caSha256) {
		return new EndpointRecord(
				endpoint.schemaVersion(),
				endpoint.host(),
				endpoint.port(),
				endpoint.serverAddress(),
				endpoint.serverVersion(),
				endpoint.tlsProtocol(),
				endpoint.tlsCipher(),
				endpoint.tlsBits(),
				caSha256);
	}

	private static void assertProbeRejected(
			Materials materials, ConnectionProbe probe, String diagnostic) {
		AtomicInteger attempts = new AtomicInteger();
		assertThatThrownBy(() -> RelatedTopicReuseHoldoutPostgresTlsTestFixture.preflight(
				materials.endpoint(),
				materials.files(),
				ignored -> {
					attempts.incrementAndGet();
					return probe.connection();
				}))
				.isInstanceOf(PreflightException.class)
				.hasMessage(diagnostic)
				.hasNoCause()
				.satisfies(failure -> assertNoSensitiveText(failure, materials));
		assertThat(attempts).hasValue(1);
		assertThat(probe.rollbacks()).isOne();
		assertThat(probe.commits()).isZero();
		assertThat(probe.closed()).isTrue();
	}

	private static void assertSuccessfulProbe(ConnectionProbe probe, boolean closed) {
		assertThat(probe.networkTimeout())
				.isEqualTo(RelatedTopicReuseHoldoutPostgresTlsConnectionFactory
						.NETWORK_TIMEOUT_MILLIS);
		assertThat(probe.isolation()).isEqualTo(Connection.TRANSACTION_READ_COMMITTED);
		assertThat(probe.rollbacks()).isOne();
		assertThat(probe.commits()).isZero();
		assertThat(probe.queryTimeouts()).isEmpty();
		assertThat(probe.queries())
				.anySatisfy(sql -> assertThat(sql).contains("pg_stat_ssl"))
				.anySatisfy(sql -> assertThat(sql).contains("pg_database"))
				.anySatisfy(sql -> assertThat(sql).contains("pg_auth_members"))
				.anySatisfy(sql -> assertThat(sql).contains("pg_db_role_setting"))
				.hasSize(4);
		assertThat(probe.events()).containsSubsequence(
				"read-only:true",
				"auto-commit:false",
				"rollback",
				"auto-commit:true",
				"read-only:false");
		assertThat(probe.closed()).isEqualTo(closed);
	}

	private static void assertNoSensitiveText(Throwable failure, Materials materials) {
		String text = failure.toString() + Arrays.toString(failure.getStackTrace());
		assertThat(text).doesNotContain(
				PASSWORD,
				materials.repository().toString(),
				materials.caCertificate().toString(),
				materials.passwordFile().toString(),
				"password=");
	}

	private void assertRejectedWithoutOpen(
			EndpointRecord endpoint, RuntimeFiles files, String diagnostic) {
		AtomicInteger attempts = new AtomicInteger();
		assertThatThrownBy(() -> RelatedTopicReuseHoldoutPostgresTlsTestFixture.preflight(
				endpoint,
				files,
				ignored -> {
					attempts.incrementAndGet();
					throw new AssertionError("invalid input reached the connection opener");
				}))
				.isInstanceOf(PreflightException.class)
				.hasMessage(diagnostic)
				.hasNoCause();
		assertThat(attempts).hasValue(0);
	}

	private Materials materials(String name) throws Exception {
		return materials(name, true);
	}

	private Materials syntheticCaMaterials(String name) throws Exception {
		return materials(name, false);
	}

	private Materials materials(String name, boolean systemCa) throws Exception {
		Path testRoot = Files.createDirectory(temporaryDirectory.resolve(name)).toRealPath();
		assertThat(testRoot.getFileSystem().supportedFileAttributeViews())
				.contains("posix", "unix");
		Path repository = Files.createDirectory(testRoot.resolve("repository")).toRealPath();
		Path caDirectory;
		Path caCertificate;
		if (systemCa) {
			caCertificate = systemCaCertificate();
			caDirectory = caCertificate.getParent();
		}
		else {
			caDirectory = Files.createDirectory(testRoot.resolve("ca")).toRealPath();
			caCertificate = Files.writeString(
					caDirectory.resolve("ledger-ca.pem"),
					"synthetic-ca-for-unit-tests\n",
					StandardCharsets.US_ASCII).toRealPath();
			Files.setPosixFilePermissions(caCertificate, CA_FILE_PERMISSIONS);
			Files.setPosixFilePermissions(caDirectory, CA_DIRECTORY_PERMISSIONS);
		}
		Path secretDirectory = Files.createDirectory(testRoot.resolve("secrets")).toRealPath();
		Path passwordFile = Files.writeString(
				secretDirectory.resolve("runtime-password"),
				PASSWORD,
				StandardCharsets.UTF_8).toRealPath();
		Files.setPosixFilePermissions(secretDirectory, SECRET_DIRECTORY_PERMISSIONS);
		Files.setPosixFilePermissions(passwordFile, SECRET_FILE_PERMISSIONS);
		String owner = Files.getOwner(passwordFile).getName();
		String caOwner = Files.getOwner(caCertificate).getName();
		if (systemCa) {
			assertThat(caOwner)
					.as("system CA must be owned independently from the evaluator")
					.isNotEqualTo(owner);
		}
		EndpointRecord endpoint = new EndpointRecord(
				RelatedTopicReuseHoldoutPostgresTlsConnectionFactory.ENDPOINT_SCHEMA_VERSION,
				"ledger.example.test",
				6432,
				"127.0.0.1",
				"17.6 (synthetic build)",
				"TLSv1.3",
				"TLS_AES_256_GCM_SHA384",
				256,
				sha256(Files.readAllBytes(caCertificate)));
		RuntimeFiles files = new RuntimeFiles(
				repository,
				caCertificate,
				passwordFile,
				caOwner,
				systemCa ? owner : "synthetic-distinct-evaluator-owner");
		return new Materials(
				testRoot,
				repository,
				caDirectory,
				caCertificate,
				secretDirectory,
				passwordFile,
				caOwner,
				owner,
				endpoint,
				files);
	}

	private static Path systemCaCertificate() throws IOException {
		for (String candidate : List.of(
				"/etc/ssl/cert.pem",
				"/etc/ssl/certs/ca-certificates.crt")) {
			Path path = Path.of(candidate);
			if (Files.isRegularFile(path)) {
				return path.toRealPath();
			}
		}
		throw new AssertionError("no canonical system CA fixture is available");
	}

	private static String sha256(byte[] bytes) {
		try {
			return HexFormat.of().formatHex(
					MessageDigest.getInstance("SHA-256").digest(bytes));
		}
		catch (NoSuchAlgorithmException exception) {
			throw new AssertionError(exception);
		}
	}

	private record Materials(
			Path root,
			Path repository,
			Path caDirectory,
			Path caCertificate,
			Path secretDirectory,
			Path passwordFile,
			String caOwner,
			String owner,
			EndpointRecord endpoint,
			RuntimeFiles files) {
	}

	private enum RoleDrift {
		NONE,
		MEMBERSHIP,
		LOGIN,
		CONFIGURATION
	}

	private static final class ConnectionProbe implements InvocationHandler {

		private final EndpointRecord endpoint;
		private final Connection connection;
		private final List<String> events = new ArrayList<>();
		private final List<String> queries = new ArrayList<>();
		private final List<Integer> queryTimeouts = new ArrayList<>();
		private boolean autoCommit = true;
		private boolean readOnly;
		private boolean closed;
		private boolean encrypted = true;
		private boolean unexpectedDatabase;
		private boolean runtimeCrossDatabaseConnect;
		private boolean auditorCrossDatabaseConnect;
		private int networkTimeout;
		private int isolation;
		private int rollbacks;
		private int commits;
		private long roleSettingsCount;
		private RoleDrift roleDrift = RoleDrift.NONE;

		private ConnectionProbe(EndpointRecord endpoint) {
			this.endpoint = endpoint;
			this.connection = (Connection) Proxy.newProxyInstance(
					Connection.class.getClassLoader(),
					new Class<?>[] {Connection.class},
					this);
		}

		@Override
		public Object invoke(Object proxy, Method method, Object[] arguments) throws Throwable {
			return switch (method.getName()) {
				case "setNetworkTimeout" -> {
					networkTimeout = (int) arguments[1];
					events.add("network-timeout:" + networkTimeout);
					yield null;
				}
				case "getNetworkTimeout" -> networkTimeout;
				case "getAutoCommit" -> autoCommit;
				case "setAutoCommit" -> {
					autoCommit = (boolean) arguments[0];
					events.add("auto-commit:" + autoCommit);
					yield null;
				}
				case "isReadOnly" -> readOnly;
				case "setReadOnly" -> {
					readOnly = (boolean) arguments[0];
					events.add("read-only:" + readOnly);
					yield null;
				}
				case "setTransactionIsolation" -> {
					isolation = (int) arguments[0];
					events.add("isolation:" + isolation);
					yield null;
				}
				case "getTransactionIsolation" -> isolation;
				case "createStatement" -> statement();
				case "rollback" -> {
					rollbacks++;
					events.add("rollback");
					yield null;
				}
				case "commit" -> {
					commits++;
					events.add("commit");
					yield null;
				}
				case "close" -> {
					closed = true;
					events.add("close");
					yield null;
				}
				case "isClosed" -> closed;
				case "unwrap" -> throw new SQLException("not a wrapper");
				case "isWrapperFor" -> false;
				case "toString" -> "SyntheticTlsConnection";
				case "hashCode" -> System.identityHashCode(proxy);
				case "equals" -> proxy == arguments[0];
				default -> throw new AssertionError("unexpected connection method: " + method);
			};
		}

		private Statement statement() {
			return (Statement) Proxy.newProxyInstance(
					Statement.class.getClassLoader(),
					new Class<?>[] {Statement.class},
					(proxy, method, arguments) -> switch (method.getName()) {
						case "setQueryTimeout" -> {
							queryTimeouts.add((int) arguments[0]);
							yield null;
						}
						case "executeQuery" -> {
							String sql = (String) arguments[0];
							queries.add(sql);
							yield result(rows(sql));
						}
						case "close" -> null;
						case "toString" -> "SyntheticTlsStatement";
						case "hashCode" -> System.identityHashCode(proxy);
						case "equals" -> proxy == arguments[0];
						default -> throw new AssertionError(
								"unexpected statement method: " + method);
					});
		}

		private List<List<Object>> rows(String sql) {
			if (sql.contains("pg_stat_ssl")) {
				return List.of(Arrays.asList(
						RelatedTopicReuseHoldoutPostgresFirstRunLedger.DATABASE_NAME,
						RelatedTopicReuseHoldoutPostgresFirstRunLedger.RUNTIME_ROLE,
						RelatedTopicReuseHoldoutPostgresFirstRunLedger.RUNTIME_ROLE,
						RelatedTopicReuseHoldoutPostgresTlsConnectionFactory.APPLICATION_NAME,
						endpoint.serverVersion(),
						170_006,
						"on",
						"on",
						"off",
						"on",
						"read committed",
						"10s",
						endpoint.serverAddress(),
						endpoint.port(),
						encrypted,
						endpoint.tlsProtocol(),
						endpoint.tlsCipher(),
						endpoint.tlsBits(),
						null,
						null));
			}
			if (sql.contains("pg_database")) {
				List<List<Object>> databases = new ArrayList<>();
				databases.add(databaseRow(
						RelatedTopicReuseHoldoutPostgresFirstRunLedger.DATABASE_NAME,
						true,
						true,
						true));
				databases.add(databaseRow(
						"postgres",
						true,
						runtimeCrossDatabaseConnect,
						auditorCrossDatabaseConnect));
				databases.add(databaseRow("template0", false, false, false));
				databases.add(databaseRow("template1", true, false, false));
				if (unexpectedDatabase) {
					databases.add(databaseRow("unexpected", true, false, false));
				}
				return List.copyOf(databases);
			}
			if (sql.contains("pg_auth_members")) {
				return List.of(
						roleRow(RelatedTopicReuseHoldoutPostgresFirstRunLedger.AUDITOR_ROLE, true),
						roleRow("openscholar_holdout_ledger_bootstrap_v1", false),
						roleRow(RelatedTopicReuseHoldoutPostgresFirstRunLedger.OWNER_ROLE, false),
						roleRow(RelatedTopicReuseHoldoutPostgresFirstRunLedger.RUNTIME_ROLE, true));
			}
			if (sql.contains("pg_db_role_setting")) {
				return List.of(List.of(roleSettingsCount));
			}
			throw new AssertionError("unexpected preflight query: " + sql);
		}

		private static List<Object> databaseRow(
				String database,
				boolean allowsConnections,
				boolean runtimeConnect,
				boolean auditorConnect) {
			return List.of(
					database,
					allowsConnections,
					runtimeConnect,
					false,
					false,
					auditorConnect,
					false,
					false);
		}

		private List<Object> roleRow(String role, boolean login) {
			boolean runtime = RelatedTopicReuseHoldoutPostgresFirstRunLedger.RUNTIME_ROLE
					.equals(role);
			return List.of(
					role,
					false,
					false,
					false,
					false,
					false,
					false,
					runtime && roleDrift == RoleDrift.LOGIN ? !login : login,
					-1,
					!(runtime && roleDrift == RoleDrift.CONFIGURATION),
					runtime && roleDrift == RoleDrift.MEMBERSHIP ? 1L : 0L,
					0L);
		}

		private static ResultSet result(List<List<Object>> rows) {
			class ResultHandler implements InvocationHandler {

				private int row = -1;
				private boolean wasNull;

				@Override
				public Object invoke(Object proxy, Method method, Object[] arguments) {
					return switch (method.getName()) {
						case "next" -> ++row < rows.size();
						case "getString" -> {
							Object value = value(arguments);
							yield value == null ? null : value.toString();
						}
						case "getInt" -> ((Number) value(arguments)).intValue();
						case "getLong" -> ((Number) value(arguments)).longValue();
						case "getBoolean" -> (Boolean) value(arguments);
						case "getObject" -> value(arguments);
						case "wasNull" -> wasNull;
						case "close" -> null;
						case "toString" -> "SyntheticTlsResultSet";
						case "hashCode" -> System.identityHashCode(proxy);
						case "equals" -> proxy == arguments[0];
						default -> throw new AssertionError(
								"unexpected result-set method: " + method);
					};
				}

				private Object value(Object[] arguments) {
					if (row < 0 || row >= rows.size()) {
						throw new AssertionError("result-set cursor is not on a row");
					}
					Object value = rows.get(row).get((int) arguments[0] - 1);
					wasNull = value == null;
					return value;
				}
			}
			return (ResultSet) Proxy.newProxyInstance(
					ResultSet.class.getClassLoader(),
					new Class<?>[] {ResultSet.class},
					new ResultHandler());
		}

		private Connection connection() {
			return connection;
		}

		private List<String> events() {
			return List.copyOf(events);
		}

		private List<String> queries() {
			return List.copyOf(queries);
		}

		private List<Integer> queryTimeouts() {
			return List.copyOf(queryTimeouts);
		}

		private boolean autoCommit() {
			return autoCommit;
		}

		private boolean readOnly() {
			return readOnly;
		}

		private boolean closed() {
			return closed;
		}

		private int networkTimeout() {
			return networkTimeout;
		}

		private int isolation() {
			return isolation;
		}

		private int rollbacks() {
			return rollbacks;
		}

		private int commits() {
			return commits;
		}

		private void setEncrypted(boolean encrypted) {
			this.encrypted = encrypted;
		}

		private void setUnexpectedDatabase(boolean unexpectedDatabase) {
			this.unexpectedDatabase = unexpectedDatabase;
		}

		private void setRuntimeCrossDatabaseConnect(boolean runtimeCrossDatabaseConnect) {
			this.runtimeCrossDatabaseConnect = runtimeCrossDatabaseConnect;
		}

		private void setAuditorCrossDatabaseConnect(boolean auditorCrossDatabaseConnect) {
			this.auditorCrossDatabaseConnect = auditorCrossDatabaseConnect;
		}

		private void setRoleSettingsCount(long roleSettingsCount) {
			this.roleSettingsCount = roleSettingsCount;
		}

		private void setRoleDrift(RoleDrift roleDrift) {
			this.roleDrift = roleDrift;
		}
	}
}
