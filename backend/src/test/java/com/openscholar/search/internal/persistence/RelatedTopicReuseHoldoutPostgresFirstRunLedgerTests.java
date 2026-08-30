package com.openscholar.search.internal.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

import javax.sql.DataSource;

import com.openscholar.TestcontainersConfiguration;
import com.openscholar.search.internal.persistence.RelatedTopicReuseHoldoutBundle.VerifiedFirstRunCommitment;
import com.openscholar.search.internal.persistence.RelatedTopicReuseHoldoutEvaluatorSeal.RepositoryState;
import com.openscholar.search.internal.persistence.RelatedTopicReuseHoldoutEvaluatorSeal.SourceFile;
import com.openscholar.search.internal.persistence.RelatedTopicReuseHoldoutEvaluatorSeal.SourceRole;
import com.openscholar.search.internal.persistence.RelatedTopicReuseHoldoutEvaluatorSeal.VerifiedEvaluatorSeal;
import com.openscholar.search.internal.persistence.RelatedTopicReuseHoldoutGitCollector.FreezeRecord;
import com.openscholar.search.internal.persistence.RelatedTopicReuseHoldoutGitCollector.VerifiedCleanCheckout;
import com.openscholar.search.internal.persistence.RelatedTopicReuseHoldoutPostgresFirstRunLedger.AlreadyClaimedException;
import com.openscholar.search.internal.persistence.RelatedTopicReuseHoldoutPostgresFirstRunLedger.CommitOutcomeUnknownException;
import com.openscholar.search.internal.persistence.RelatedTopicReuseHoldoutPostgresFirstRunLedger.ContractException;
import com.openscholar.search.internal.persistence.RelatedTopicReuseHoldoutPostgresFirstRunLedger.LedgerException;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.datasource.DelegatingDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

@Testcontainers
@Execution(ExecutionMode.SAME_THREAD)
class RelatedTopicReuseHoldoutPostgresFirstRunLedgerTests {

	private static final String BOOTSTRAP_USER = "holdout_ledger_bootstrap";
	private static final String BOOTSTRAP_PASSWORD = "test-bootstrap-password";
	private static final String RUNTIME_PASSWORD = "test-runtime-password";
	private static final String AUDITOR_PASSWORD = "test-auditor-password";
	private static final String MIGRATION_SCHEMA = "holdout_ledger_migrations_v1";
	private static final String CORPUS_FILENAME = "holdout-corpus.json";
	private static final String JUDGMENTS_FILENAME = "judgments.json";
	private static final String TARGET_SEARCH = "target-owner-search";
	private static final String TARGET_COLLECTION = "target-owner-collection";
	private static final String OTHER_SEARCH = "other-owner-search";
	private static final String OTHER_COLLECTION = "other-owner-collection";
	private static final String CATALOG = "catalog-only";

	@Container
	private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
			DockerImageName.parse(TestcontainersConfiguration.POSTGRES_IMAGE)
					.asCompatibleSubstituteFor("postgres"))
			.withDatabaseName(RelatedTopicReuseHoldoutPostgresFirstRunLedger.DATABASE_NAME)
			.withUsername(BOOTSTRAP_USER)
			.withPassword(BOOTSTRAP_PASSWORD)
			.withCommand("postgres", "-c", "fsync=on");

	private static DataSource administratorDataSource;
	private static DataSource runtimeDataSource;
	private static DataSource auditorDataSource;

	@TempDir
	Path temporaryDirectory;

	@BeforeAll
	static void provisionDedicatedLedger() throws Exception {
		administratorDataSource = dataSource(BOOTSTRAP_USER, BOOTSTRAP_PASSWORD);
		try (Connection connection = administratorDataSource.getConnection();
				Statement statement = connection.createStatement()) {
			statement.execute("CREATE ROLE "
					+ RelatedTopicReuseHoldoutPostgresFirstRunLedger.OWNER_ROLE
					+ " NOLOGIN NOINHERIT");
			statement.execute("CREATE ROLE "
					+ RelatedTopicReuseHoldoutPostgresFirstRunLedger.RUNTIME_ROLE
					+ " LOGIN NOINHERIT PASSWORD '" + RUNTIME_PASSWORD + "'");
			statement.execute("CREATE ROLE "
					+ RelatedTopicReuseHoldoutPostgresFirstRunLedger.AUDITOR_ROLE
					+ " LOGIN NOINHERIT PASSWORD '" + AUDITOR_PASSWORD + "'");
			statement.execute("ALTER DATABASE "
					+ RelatedTopicReuseHoldoutPostgresFirstRunLedger.DATABASE_NAME
					+ " OWNER TO "
					+ RelatedTopicReuseHoldoutPostgresFirstRunLedger.OWNER_ROLE);
			statement.execute("ALTER ROLE "
					+ RelatedTopicReuseHoldoutPostgresFirstRunLedger.RUNTIME_ROLE
					+ " SET synchronous_commit = 'off'");
			statement.execute("CREATE SCHEMA " + MIGRATION_SCHEMA);
			statement.execute("REVOKE ALL PRIVILEGES ON SCHEMA "
					+ MIGRATION_SCHEMA + " FROM PUBLIC");
		}

		Flyway flyway = Flyway.configure()
				.dataSource(POSTGRES.getJdbcUrl(), BOOTSTRAP_USER, BOOTSTRAP_PASSWORD)
				.locations("classpath:db/holdout-ledger")
				.defaultSchema(MIGRATION_SCHEMA)
				.table("flyway_schema_history")
				.load();
		assertThat(flyway.migrate().migrationsExecuted).isOne();
		assertThat(flyway.migrate().migrationsExecuted).isZero();

		runtimeDataSource = dataSource(
				RelatedTopicReuseHoldoutPostgresFirstRunLedger.RUNTIME_ROLE,
				RUNTIME_PASSWORD);
		auditorDataSource = dataSource(
				RelatedTopicReuseHoldoutPostgresFirstRunLedger.AUDITOR_ROLE,
				AUDITOR_PASSWORD);
	}

	@BeforeEach
	void resetWithAdministratorAuthority() throws Exception {
		try (Connection connection = administratorDataSource.getConnection();
				Statement statement = connection.createStatement()) {
			statement.executeUpdate("DELETE FROM holdout_ledger_v1.first_run_claim_v1");
			statement.execute("COMMENT ON TABLE holdout_ledger_v1.first_run_claim_v1 IS '"
					+ RelatedTopicReuseHoldoutPostgresFirstRunLedger.CONTRACT_COMMENT + "'");
		}
	}

	@Test
	void provisioningIsIsolatedVersionedPermanentAndAppendOnly() throws Exception {
		try (Connection connection = administratorDataSource.getConnection();
				Statement statement = connection.createStatement();
				ResultSet result = statement.executeQuery("""
						SELECT c.relkind,
						       c.relpersistence,
						       owner.rolname,
						       pg_catalog.obj_description(c.oid, 'pg_class'),
						       count(*) FILTER (WHERE con.contype = 'p'),
						       count(*) FILTER (WHERE con.contype = 'u')
						FROM pg_catalog.pg_class c
						JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace
						JOIN pg_catalog.pg_roles owner ON owner.oid = c.relowner
						JOIN pg_catalog.pg_constraint con ON con.conrelid = c.oid
						WHERE n.nspname = 'holdout_ledger_v1'
						  AND c.relname = 'first_run_claim_v1'
						GROUP BY c.oid, owner.rolname
						""")) {
			assertThat(result.next()).isTrue();
			assertThat(result.getString(1)).isEqualTo("r");
			assertThat(result.getString(2)).isEqualTo("p");
			assertThat(result.getString(3))
					.isEqualTo(RelatedTopicReuseHoldoutPostgresFirstRunLedger.OWNER_ROLE);
			assertThat(result.getString(4))
					.isEqualTo(RelatedTopicReuseHoldoutPostgresFirstRunLedger.CONTRACT_COMMENT);
			assertThat(result.getLong(5)).isOne();
			assertThat(result.getLong(6)).isZero();
			assertThat(result.next()).isFalse();
		}
		assertThat(tableExists("public", "first_run_claim_v1")).isFalse();
		assertThat(tableExists("public", "paper")).isFalse();
	}

	@Test
	void commitsExactlyOnceBeforeMintingAnOpaqueCapability() throws Exception {
		SyntheticRun run = syntheticRun("first");
		var ledger = new RelatedTopicReuseHoldoutPostgresFirstRunLedger(runtimeDataSource);

		var committed = ledger.claim(run.commitment(), run.checkout());

		assertThat(committed.runKey()).hasSize(64);
		assertThat(committed.finalityKey().evaluationProtocolId())
				.isEqualTo(RelatedTopicReuseHoldoutPolicy.EVALUATION_PROTOCOL_ID);
		assertThat(committed.finalityKey().policyId())
				.isEqualTo(RelatedTopicReuseHoldoutPolicy.POLICY_ID);
		assertThat(committed.externalBundleAcceptanceAuthorized()).isFalse();
		assertThat(committed.custodyReleaseAuthorized()).isFalse();
		assertThat(committed.productActivationAuthorized()).isFalse();
		assertThat(claimCount()).isOne();
		assertThat(singleText("encode(run_key, 'hex')")).isEqualTo(committed.runKey());
		assertThat(singleText("synchronous_commit_setting")).isEqualTo("on");
		assertThat(singleText("evaluation_protocol_id"))
				.isEqualTo(RelatedTopicReuseHoldoutPolicy.EVALUATION_PROTOCOL_ID);
		assertThat(singleText("policy_id"))
				.isEqualTo(RelatedTopicReuseHoldoutPolicy.POLICY_ID);

		assertThatThrownBy(() -> new RelatedTopicReuseHoldoutPostgresFirstRunLedger(
				runtimeDataSource).claim(run.commitment(), run.checkout()))
				.isInstanceOf(AlreadyClaimedException.class)
				.hasMessage("HOLDOUT_LEDGER_FIRST_RUN_ALREADY_CLAIMED");
		assertThat(claimCount()).isOne();
	}

	@Test
	void composesVerifiedBundleCleanGitCheckoutDurableClaimAndRankingInOrder()
			throws Exception {
		ObjectMapper objectMapper = new ObjectMapper();
		Path bundleDirectory = writeValidBundle(
				objectMapper,
				temporaryDirectory.resolve("external-composed-holdout"));
		List<String> observedOrder = new ArrayList<>();

		var verifiedCorpus = RelatedTopicReuseHoldoutBundle.verifyCorpus(
				objectMapper, bundleDirectory);
		observedOrder.add("bundle-verified");
		var checkout = collectRealCleanCheckout(
				temporaryDirectory.resolve("clean-evaluator-clone"));
		observedOrder.add("checkout-verified");
		var committed = new RelatedTopicReuseHoldoutPostgresFirstRunLedger(
				runtimeDataSource).claim(verifiedCorpus.firstRunCommitment(), checkout);
		observedOrder.add("claim-committed");

		var completed = RelatedTopicReuseHoldoutBundle.completeRanking(
				verifiedCorpus,
				committed,
				rankingCorpus -> {
					assertThat(observedOrder).containsExactly(
							"bundle-verified", "checkout-verified", "claim-committed");
					assertThat(durableClaimCount()).isOne();
					assertThatThrownBy(() -> committed.consumeForRanking(verifiedCorpus))
							.isInstanceOf(LedgerException.class)
							.hasMessage("HOLDOUT_LEDGER_CLAIM_CAPABILITY_INVALID");
					observedOrder.add("ranking-callback");
					return emptyRankingObservation(verifiedCorpus);
				});
		observedOrder.add("ranking-completed");

		assertThat(completed.rankingSnapshot().candidateRevision())
				.isEqualTo(RelatedTopicReuseHoldoutPolicy.CANDIDATE_FREEZE_REVISION);
		assertThat(observedOrder).containsExactly(
				"bundle-verified",
				"checkout-verified",
				"claim-committed",
				"ranking-callback",
				"ranking-completed");
		AtomicInteger replayedCallbacks = new AtomicInteger();
		assertThatThrownBy(() -> RelatedTopicReuseHoldoutBundle.completeRanking(
				verifiedCorpus,
				committed,
				ignored -> {
					replayedCallbacks.incrementAndGet();
					return emptyRankingObservation(verifiedCorpus);
				}))
				.isInstanceOf(RelatedTopicReuseHoldoutBundle.VerificationException.class)
				.hasMessage("HOLDOUT_FIRST_RUN_CLAIM_INVALID");
		assertThat(replayedCallbacks).hasValue(0);
	}

	@Test
	void finalityKeyRejectsRepackagingOrEvaluatorChangesUnderTheSamePolicy()
			throws Exception {
		SyntheticRun first = syntheticRun("original");
		SyntheticRun changedBundleAndEvaluator = syntheticRun("changed");
		var ledger = new RelatedTopicReuseHoldoutPostgresFirstRunLedger(runtimeDataSource);
		String firstRunKey = ledger.claim(first.commitment(), first.checkout()).runKey();
		String changedRunKey = RelatedTopicReuseHoldoutFirstRunIdentity.fromVerified(
				changedBundleAndEvaluator.commitment(),
				changedBundleAndEvaluator.checkout()).runKey();

		assertThat(changedRunKey).isNotEqualTo(firstRunKey);
		assertThatThrownBy(() -> ledger.claim(
				changedBundleAndEvaluator.commitment(),
				changedBundleAndEvaluator.checkout()))
				.isInstanceOf(AlreadyClaimedException.class);
		assertThat(singleText("encode(run_key, 'hex')")).isEqualTo(firstRunKey);
		assertThat(claimCount()).isOne();
	}

	@Test
	void concurrentProcessesProduceOneAcknowledgedCommitAndOneDurableRow()
			throws Exception {
		SyntheticRun run = syntheticRun("concurrent");
		int contenders = 8;
		CountDownLatch ready = new CountDownLatch(contenders);
		CountDownLatch start = new CountDownLatch(1);
		AtomicInteger committed = new AtomicInteger();
		AtomicInteger rejected = new AtomicInteger();

		try (ExecutorService executor = Executors.newFixedThreadPool(contenders)) {
			List<Future<Object>> futures = java.util.stream.IntStream.range(0, contenders)
					.mapToObj(index -> executor.submit(() -> {
						ready.countDown();
						if (!start.await(10, TimeUnit.SECONDS)) {
							throw new IllegalStateException("concurrent ledger start timed out");
						}
						try {
							new RelatedTopicReuseHoldoutPostgresFirstRunLedger(runtimeDataSource)
									.claim(run.commitment(), run.checkout());
							committed.incrementAndGet();
						}
						catch (AlreadyClaimedException exception) {
							rejected.incrementAndGet();
						}
						return null;
					})).toList();
			assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
			start.countDown();
			for (Future<Object> future : futures) {
				future.get(Duration.ofSeconds(20).toMillis(), TimeUnit.MILLISECONDS);
			}
		}

		assertThat(committed).hasValue(1);
		assertThat(rejected).hasValue(contenders - 1);
		assertThat(claimCount()).isOne();
	}

	@Test
	void delegatedCommitAcknowledgmentLossReturnsNoCapabilityAndLeavesFinalClaim()
			throws Exception {
		SyntheticRun run = syntheticRun("ambiguous-commit");
		AtomicInteger commits = new AtomicInteger();
		DataSource ambiguous = commitFailureDataSource(true, commits);

		assertThatThrownBy(() -> new RelatedTopicReuseHoldoutPostgresFirstRunLedger(
				ambiguous).claim(run.commitment(), run.checkout()))
				.isInstanceOf(CommitOutcomeUnknownException.class)
				.hasMessage("HOLDOUT_LEDGER_COMMIT_OUTCOME_UNKNOWN");
		assertThat(commits).hasValue(1);
		assertThat(claimCount()).isOne();
		assertThatThrownBy(() -> new RelatedTopicReuseHoldoutPostgresFirstRunLedger(
				runtimeDataSource).claim(run.commitment(), run.checkout()))
				.isInstanceOf(AlreadyClaimedException.class);
	}

	@Test
	void preCommitConnectionFailureMintsNothingAndDoesNotRetryInternally()
			throws Exception {
		SyntheticRun run = syntheticRun("failed-commit");
		AtomicInteger commits = new AtomicInteger();
		DataSource ambiguous = commitFailureDataSource(false, commits);

		assertThatThrownBy(() -> new RelatedTopicReuseHoldoutPostgresFirstRunLedger(
				ambiguous).claim(run.commitment(), run.checkout()))
				.isInstanceOf(CommitOutcomeUnknownException.class);
		assertThat(commits).hasValue(1);
		assertThat(claimCount()).isZero();
	}

	@Test
	void runtimeCanOnlyInsertAllowedClaimColumnsAndAuditorCanOnlyRead()
			throws Exception {
		assertSqlRejected(runtimeDataSource,
				"SELECT * FROM holdout_ledger_v1.first_run_claim_v1");
		assertSqlRejected(runtimeDataSource,
				"UPDATE holdout_ledger_v1.first_run_claim_v1 SET schema_version = 1");
		assertSqlRejected(runtimeDataSource,
				"DELETE FROM holdout_ledger_v1.first_run_claim_v1");
		assertSqlRejected(runtimeDataSource,
				"TRUNCATE holdout_ledger_v1.first_run_claim_v1");
		assertSqlRejected(runtimeDataSource, "CREATE SCHEMA forbidden_schema");
		assertSqlRejected(runtimeDataSource, "CREATE TEMP TABLE forbidden_temp (id integer)");
		assertSqlRejected(runtimeDataSource, """
				INSERT INTO holdout_ledger_v1.first_run_claim_v1 (
				    schema_version, claimed_at
				) VALUES (1, clock_timestamp())
				""");

		try (Connection connection = auditorDataSource.getConnection();
				Statement statement = connection.createStatement();
				ResultSet result = statement.executeQuery(
						"SELECT count(*) FROM holdout_ledger_v1.first_run_claim_v1")) {
			assertThat(result.next()).isTrue();
			assertThat(result.getLong(1)).isZero();
		}
		assertSqlRejected(auditorDataSource,
				"DELETE FROM holdout_ledger_v1.first_run_claim_v1");
	}

	@Test
	void wrongRoleOrAlteredContractFailsBeforeInsertion() throws Exception {
		SyntheticRun run = syntheticRun("contract");
		assertThatThrownBy(() -> new RelatedTopicReuseHoldoutPostgresFirstRunLedger(
				administratorDataSource).claim(run.commitment(), run.checkout()))
				.isInstanceOf(ContractException.class)
				.hasMessage("HOLDOUT_LEDGER_DATABASE_CONTRACT_INVALID");
		assertThat(claimCount()).isZero();

		try (Connection connection = administratorDataSource.getConnection();
				Statement statement = connection.createStatement()) {
			statement.execute("COMMENT ON TABLE holdout_ledger_v1.first_run_claim_v1 IS 'altered'");
		}
		assertThatThrownBy(() -> new RelatedTopicReuseHoldoutPostgresFirstRunLedger(
				runtimeDataSource).claim(run.commitment(), run.checkout()))
				.isInstanceOf(ContractException.class);
		assertThat(claimCount()).isZero();
	}

	@Test
	void administratorAuthenticatedSessionCannotMasqueradeWithSetRole()
			throws Exception {
		SyntheticRun run = syntheticRun("session-user");
		DataSource masquerading = setRoleDataSource(
				administratorDataSource,
				RelatedTopicReuseHoldoutPostgresFirstRunLedger.RUNTIME_ROLE);

		assertThatThrownBy(() -> new RelatedTopicReuseHoldoutPostgresFirstRunLedger(
				masquerading).claim(run.commitment(), run.checkout()))
				.isInstanceOf(ContractException.class)
				.hasMessage("HOLDOUT_LEDGER_DATABASE_CONTRACT_INVALID");
		assertThat(claimCount()).isZero();
	}

	@Test
	void sameCountWrongPrimaryKeyFailsClosedBeforeInsertion() throws Exception {
		SyntheticRun run = syntheticRun("wrong-primary-key");
		try {
			executeAsAdministrator("""
					ALTER TABLE holdout_ledger_v1.first_run_claim_v1
					    DROP CONSTRAINT first_run_claim_v1_pk
					""");
			executeAsAdministrator("""
					ALTER TABLE holdout_ledger_v1.first_run_claim_v1
					    ADD CONSTRAINT first_run_claim_v1_pk PRIMARY KEY (run_key)
					""");

			assertContractRejected(run);
			assertThat(claimCount()).isZero();
		}
		finally {
			executeAsAdministrator("""
					ALTER TABLE holdout_ledger_v1.first_run_claim_v1
					    DROP CONSTRAINT IF EXISTS first_run_claim_v1_pk
					""");
			executeAsAdministrator("""
					ALTER TABLE holdout_ledger_v1.first_run_claim_v1
					    ADD CONSTRAINT first_run_claim_v1_pk
					    PRIMARY KEY (evaluation_protocol_id, policy_id)
					""");
		}
	}

	@Test
	void sameCountPermissiveCheckFailsClosedBeforeInsertion() throws Exception {
		SyntheticRun run = syntheticRun("permissive-check");
		try {
			executeAsAdministrator("""
					ALTER TABLE holdout_ledger_v1.first_run_claim_v1
					    DROP CONSTRAINT first_run_claim_v1_schema_check
					""");
			executeAsAdministrator("""
					ALTER TABLE holdout_ledger_v1.first_run_claim_v1
					    ADD CONSTRAINT first_run_claim_v1_schema_check CHECK (true)
					""");

			assertContractRejected(run);
			assertThat(claimCount()).isZero();
		}
		finally {
			executeAsAdministrator("""
					ALTER TABLE holdout_ledger_v1.first_run_claim_v1
					    DROP CONSTRAINT IF EXISTS first_run_claim_v1_schema_check
					""");
			executeAsAdministrator("""
					ALTER TABLE holdout_ledger_v1.first_run_claim_v1
					    ADD CONSTRAINT first_run_claim_v1_schema_check
					    CHECK (schema_version = 1)
					""");
		}
	}

	@Test
	void alteredDefaultCollationIndexAndAclEachFailClosed() throws Exception {
		SyntheticRun run = syntheticRun("executable-schema");
		try {
			executeAsAdministrator("""
					ALTER TABLE holdout_ledger_v1.first_run_claim_v1
					    ALTER COLUMN synchronous_commit_setting
					    SET DEFAULT 'on'::text
					""");
			assertContractRejected(run);
		}
		finally {
			executeAsAdministrator("""
					ALTER TABLE holdout_ledger_v1.first_run_claim_v1
					    ALTER COLUMN synchronous_commit_setting
					    SET DEFAULT current_setting('synchronous_commit')
					""");
		}

		try {
			executeAsAdministrator("""
					ALTER TABLE holdout_ledger_v1.first_run_claim_v1
					    ALTER COLUMN bundle_id TYPE text COLLATE "default"
					""");
			assertContractRejected(run);
		}
		finally {
			executeAsAdministrator("""
					ALTER TABLE holdout_ledger_v1.first_run_claim_v1
					    ALTER COLUMN bundle_id TYPE text COLLATE "C"
					""");
			executeAsAdministrator("""
					ALTER TABLE holdout_ledger_v1.first_run_claim_v1
					    DROP CONSTRAINT first_run_claim_v1_bundle_check
					""");
			executeAsAdministrator("""
					ALTER TABLE holdout_ledger_v1.first_run_claim_v1
					    ADD CONSTRAINT first_run_claim_v1_bundle_check
					    CHECK (octet_length(bundle_id) BETWEEN 3 AND 160
					        AND bundle_id ~ '^[a-z0-9]+(-[a-z0-9]+)*$')
					""");
		}

		try {
			executeAsAdministrator("""
					CREATE INDEX first_run_claim_v1_extra
					ON holdout_ledger_v1.first_run_claim_v1 (run_key)
					""");
			assertContractRejected(run);
			assertThat(claimCount()).isZero();
		}
		finally {
			executeAsAdministrator("""
					DROP INDEX IF EXISTS holdout_ledger_v1.first_run_claim_v1_extra
					""");
		}

		String rogueRole = "openscholar_holdout_ledger_rogue_test";
		try {
			executeAsAdministrator("CREATE ROLE " + rogueRole + " NOLOGIN NOINHERIT");
			executeAsAdministrator("""
					GRANT DELETE ON holdout_ledger_v1.first_run_claim_v1
					TO openscholar_holdout_ledger_rogue_test
					""");
			assertContractRejected(run);
			assertThat(claimCount()).isZero();
		}
		finally {
			executeAsAdministrator("""
					REVOKE DELETE ON holdout_ledger_v1.first_run_claim_v1
					FROM openscholar_holdout_ledger_rogue_test
					""");
			executeAsAdministrator("DROP ROLE IF EXISTS " + rogueRole);
		}
	}

	@Test
	void ownerDriftAndPrivilegeEscalationEachFailClosed() throws Exception {
		SyntheticRun run = syntheticRun("authority-drift");
		try {
			executeAsAdministrator("""
					ALTER TABLE holdout_ledger_v1.first_run_claim_v1
					    OWNER TO holdout_ledger_bootstrap
					""");
			assertContractRejected(run);
		}
		finally {
			executeAsAdministrator("""
					ALTER TABLE holdout_ledger_v1.first_run_claim_v1
					    OWNER TO openscholar_holdout_ledger_owner_v1
					""");
		}

		try {
			executeAsAdministrator("""
					GRANT MAINTAIN ON holdout_ledger_v1.first_run_claim_v1
					TO openscholar_holdout_ledger_runtime_v1
					""");
			assertContractRejected(run);
		}
		finally {
			executeAsAdministrator("""
					REVOKE MAINTAIN ON holdout_ledger_v1.first_run_claim_v1
					FROM openscholar_holdout_ledger_runtime_v1
					""");
		}

		try {
			executeAsAdministrator("""
					GRANT INSERT (claimed_at)
					ON holdout_ledger_v1.first_run_claim_v1
					TO openscholar_holdout_ledger_runtime_v1
					""");
			assertContractRejected(run);
			assertThat(claimCount()).isZero();
		}
		finally {
			executeAsAdministrator("""
					REVOKE INSERT (claimed_at)
					ON holdout_ledger_v1.first_run_claim_v1
					FROM openscholar_holdout_ledger_runtime_v1
					""");
		}
	}

	@Test
	void committedCapabilityIsBoundToTheExactCorpusAndStartsOnce()
			throws Exception {
		SyntheticRun run = syntheticRun("capability");
		var committed = new RelatedTopicReuseHoldoutPostgresFirstRunLedger(runtimeDataSource)
				.claim(run.commitment(), run.checkout());

		committed.consumeForRanking(run.verifiedCorpus());
		assertThatThrownBy(() -> committed.consumeForRanking(
				run.verifiedCorpus()))
				.isInstanceOf(LedgerException.class)
				.hasMessage("HOLDOUT_LEDGER_CLAIM_CAPABILITY_INVALID");
		SyntheticRun other = syntheticRun("other-capability");
		assertThatThrownBy(() -> committed.consumeForRanking(
				other.verifiedCorpus()))
				.isInstanceOf(LedgerException.class);
	}

	@Test
	void apiHasNoReadRetryResetUpdateDeleteLeaseOrCompletionSurface() {
		List<Method> accepting = Arrays.stream(
				RelatedTopicReuseHoldoutPostgresFirstRunLedger.class.getDeclaredMethods())
				.filter(method -> !Modifier.isPrivate(method.getModifiers()))
				.toList();
		assertThat(accepting).singleElement().satisfies(method -> {
			assertThat(method.getName()).isEqualTo("claim");
			assertThat(method.getParameterTypes()).containsExactly(
					VerifiedFirstRunCommitment.class, VerifiedCleanCheckout.class);
		});
		assertThat(RelatedTopicReuseHoldoutPostgresFirstRunLedger.class.getModifiers())
				.matches(Modifier::isFinal);
		assertThat(Arrays.stream(
				RelatedTopicReuseHoldoutPostgresFirstRunLedger.CommittedFirstRun.class
						.getDeclaredConstructors()))
				.singleElement()
				.satisfies(constructor ->
						assertThat(Modifier.isPrivate(constructor.getModifiers())).isTrue());
		assertThat(accepting)
				.noneMatch(method -> method.getName().matches(
						"(?i).*(read|find|list|retry|reset|delete|update|lease|complete|fail).*"));
	}

	@Test
	void runKeyIsCanonicalSensitiveAndSeparateFromTheStableFinalityKey()
			throws Exception {
		SyntheticRun alpha = syntheticRun("alpha");
		SyntheticRun beta = syntheticRun("beta");
		var alphaIdentity = RelatedTopicReuseHoldoutFirstRunIdentity.fromVerified(
				alpha.commitment(), alpha.checkout());
		var betaIdentity = RelatedTopicReuseHoldoutFirstRunIdentity.fromVerified(
				beta.commitment(), beta.checkout());

		assertThat(alphaIdentity.runKey()).matches("[0-9a-f]{64}");
		assertThat(betaIdentity.runKey()).isNotEqualTo(alphaIdentity.runKey());
		assertThat(betaIdentity.finalityKey()).isEqualTo(alphaIdentity.finalityKey());
		assertThat(alphaIdentity.runKeyBytes()).isNotSameAs(alphaIdentity.runKeyBytes());
		assertThat(alphaIdentity.runKeyBytes()).containsExactly(
				HexFormat.of().parseHex(alphaIdentity.runKey()));
	}

	private static Path writeValidBundle(ObjectMapper objectMapper, Path directory)
			throws Exception {
		var boundPolicy = RelatedTopicReuseHoldoutPolicy.loadFrozen(objectMapper);
		String bundleId = directory.getFileName().toString();
		String corpusId = "external-composed-corpus";
		Files.createDirectory(directory);

		ObjectNode corpus = bundleIdentity(
				objectMapper, boundPolicy, bundleId, corpusId);
		corpus.put("split", String.valueOf(boundPolicy.policy().corpus().split()));
		corpus.put("labelUnit", String.valueOf(boundPolicy.policy().labelUnit()));
		corpus.put("sourcePolicy", String.valueOf(boundPolicy.policy().sourcePolicy()));
		ArrayNode lineages = corpus.putArray("lineages");
		addLineage(lineages, TARGET_SEARCH, "TARGET_OWNER_SEARCH");
		addLineage(lineages, TARGET_COLLECTION, "TARGET_OWNER_COLLECTION");
		addLineage(lineages, OTHER_SEARCH, "OTHER_OWNER_SEARCH");
		addLineage(lineages, OTHER_COLLECTION, "OTHER_OWNER_COLLECTION");
		addLineage(lineages, CATALOG, "CATALOG_ONLY");
		ArrayNode candidates = corpus.putArray("candidates");
		for (int index = 1; index <= 40; index++) {
			addCandidate(candidates, index);
		}
		ArrayNode queries = corpus.putArray("queries");
		for (int index = 1; index <= 8; index++) {
			addQuery(queries, index, boundPolicy.policy().gates().cutoff());
		}

		ObjectNode judgments = bundleIdentity(
				objectMapper, boundPolicy, bundleId, corpusId);
		judgments.put("labelUnit", String.valueOf(boundPolicy.policy().labelUnit()));
		ArrayNode queryJudgments = judgments.putArray("queries");
		for (int queryIndex = 1; queryIndex <= 8; queryIndex++) {
			ObjectNode query = queryJudgments.addObject();
			query.put("queryKey", queryKey(queryIndex));
			ObjectNode grades = query.putObject("grades");
			for (int candidateIndex = 1; candidateIndex <= 30; candidateIndex++) {
				grades.put(
						candidateKey(candidateIndex),
						grade(queryIndex, candidateIndex));
			}
			addQueryAdversaries(query.putArray("adversaries"), queryIndex);
		}

		byte[] corpusBytes = objectMapper.writeValueAsBytes(corpus);
		byte[] judgmentsBytes = objectMapper.writeValueAsBytes(judgments);
		Files.write(directory.resolve(CORPUS_FILENAME), corpusBytes);
		Files.write(directory.resolve(JUDGMENTS_FILENAME), judgmentsBytes);
		ObjectNode manifest = objectMapper.createObjectNode();
		manifest.put("schemaVersion", 1);
		manifest.put("protocolId", boundPolicy.policy().bundle().protocolId());
		manifest.put("bundleId", bundleId);
		manifest.put("policyId", boundPolicy.policy().policyId());
		manifest.put("policySha256", boundPolicy.sha256());
		manifest.put("corpusId", corpusId);
		manifest.put("payloadBytes", corpusBytes.length + judgmentsBytes.length);
		ArrayNode files = manifest.putArray("files");
		addManifestFile(files, CORPUS_FILENAME, corpusBytes);
		addManifestFile(files, JUDGMENTS_FILENAME, judgmentsBytes);
		var required = boundPolicy.policy().requiredDeclarations();
		ObjectNode declarations = manifest.putObject("declarations");
		declarations.put("corpusAuthorship", required.corpusAuthorship());
		declarations.put("judgmentAuthorship", required.judgmentAuthorship());
		declarations.put("firstRunRule", required.firstRunRule());
		declarations.put("noRetuningRule", required.noRetuningRule());
		declarations.put("externalCustodyRule", required.externalCustodyRule());
		declarations.put("evaluatorFreezeRule", required.evaluatorFreezeRule());
		ArrayNode limitations = declarations.putArray("limitations");
		required.requiredLimitations().forEach(limitations::add);
		Files.write(directory.resolve("manifest.json"), objectMapper.writeValueAsBytes(manifest));
		return directory.toRealPath();
	}

	private static ObjectNode bundleIdentity(
			ObjectMapper objectMapper,
			RelatedTopicReuseHoldoutPolicy.BoundPolicy boundPolicy,
			String bundleId,
			String corpusId) {
		ObjectNode root = objectMapper.createObjectNode();
		root.put("schemaVersion", 1);
		root.put("protocolId", boundPolicy.policy().bundle().protocolId());
		root.put("bundleId", bundleId);
		root.put("policyId", boundPolicy.policy().policyId());
		root.put("policySha256", boundPolicy.sha256());
		root.put("corpusId", corpusId);
		return root;
	}

	private static void addLineage(ArrayNode lineages, String key, String kind) {
		lineages.addObject().put("key", key).put("kind", kind);
	}

	private static void addCandidate(ArrayNode candidates, int index) {
		ObjectNode candidate = candidates.addObject();
		candidate.put("key", candidateKey(index));
		candidate.put("lineageKey", lineageKey(index));
		candidate.put("title", "Independent Composed Holdout Study " + index);
		candidate.put(
				"abstractText", "External composed metadata abstract number " + index + ".");
		candidate.put("venueName", "Independent Composed Review Venue");
		candidate.put("publicationYear", publicationYear(index));
		candidate.put("documentType", index == 12 ? "PREPRINT" : "ARTICLE");
		candidate.put("language", index == 15 ? "fr" : "en");
		candidate.put("citationCount", index == 14 ? 1 : 50);
		candidate.put("reportedOpenAccess", index != 13);
		candidate.putArray("authors").add("Composed External Author " + index);
	}

	private static void addQuery(ArrayNode queries, int index, int cutoff) {
		ObjectNode query = queries.addObject();
		query.put("key", queryKey(index));
		query.put("text", "Independent composed holdout research topic " + index);
		query.put("kind", queryKind(index));
		query.put("cutoff", cutoff);
		ObjectNode filters = query.putObject("filters");
		if (index == 4) {
			filters.put("yearFrom", 2015);
			filters.put("yearTo", 2025);
			filters.putArray("documentTypes").add("ARTICLE");
			filters.put("openAccessOnly", true);
			filters.put("minimumCitations", 20);
			filters.putArray("languages").add("en");
		}
		else {
			filters.putNull("yearFrom");
			filters.putNull("yearTo");
			filters.putArray("documentTypes");
			filters.put("openAccessOnly", false);
			filters.put("minimumCitations", 0);
			filters.putArray("languages");
		}
	}

	private static void addQueryAdversaries(ArrayNode adversaries, int queryIndex) {
		if (queryIndex == 1) {
			addAdversary(adversaries, candidateKey(3), "OWNER_VISIBLE_TOPIC_DRIFT");
			addAdversary(adversaries, candidateKey(31), "OTHER_OWNER_TOPIC_MATCH");
			addAdversary(adversaries, candidateKey(36), "CATALOG_ONLY_TOPIC_MATCH");
		}
		else if (queryIndex <= 3) {
			addAdversary(adversaries, candidateKey(3), "OWNER_VISIBLE_TOPIC_DRIFT");
		}
		else if (queryIndex == 4) {
			for (int candidate = 10; candidate <= 15; candidate++) {
				addAdversary(adversaries, candidateKey(candidate), "FILTER_VIOLATION");
			}
		}
		else if (queryIndex <= 7) {
			addAdversary(adversaries, candidateKey(3), "AUTHOR_SUBSTRING_COLLISION");
		}
		else {
			addAdversary(adversaries, candidateKey(31), "OTHER_OWNER_TOPIC_MATCH");
		}
	}

	private static void addAdversary(
			ArrayNode adversaries, String candidateKey, String kind) {
		adversaries.addObject()
				.put("candidateKey", candidateKey)
				.put("kind", kind)
				.put("reason", "Independent composed adversarial review annotation.");
	}

	private static int grade(int queryIndex, int candidateIndex) {
		if (queryIndex <= 4) {
			return candidateIndex == 1 ? 3 : candidateIndex == 2 ? 2 : 0;
		}
		if (queryIndex <= 7) {
			return candidateIndex == 1 ? 3 : 0;
		}
		return 0;
	}

	private static int publicationYear(int candidateIndex) {
		return candidateIndex == 10 ? 2010 : candidateIndex == 11 ? 2030 : 2020;
	}

	private static String lineageKey(int candidateIndex) {
		if (candidateIndex <= 15) {
			return TARGET_SEARCH;
		}
		if (candidateIndex <= 30) {
			return TARGET_COLLECTION;
		}
		if (candidateIndex <= 33) {
			return OTHER_SEARCH;
		}
		if (candidateIndex <= 35) {
			return OTHER_COLLECTION;
		}
		return CATALOG;
	}

	private static String queryKind(int queryIndex) {
		if (queryIndex <= 3) {
			return "LEXICAL_BRIDGE_OPPORTUNITY";
		}
		if (queryIndex == 4) {
			return "FILTERED_LEXICAL_BRIDGE_OPPORTUNITY";
		}
		if (queryIndex <= 7) {
			return "AUTHOR_NO_RELATED_SIGNAL_CONTROL";
		}
		return "NO_SEED_FALLBACK_CONTROL";
	}

	private static String candidateKey(int index) {
		return "composed-holdout-candidate-" + index;
	}

	private static String queryKey(int index) {
		return "composed-holdout-query-" + index;
	}

	private static void addManifestFile(
			ArrayNode files, String filename, byte[] bytes) throws Exception {
		files.addObject()
				.put("filename", filename)
				.put("bytes", bytes.length)
				.put("sha256", sha256(bytes));
	}

	private static VerifiedCleanCheckout collectRealCleanCheckout(Path clone)
			throws Exception {
		Path git = locateGitExecutable();
		Path sourceRoot = findRepositoryRoot();
		gitSuccess(
				clone.getParent(),
				git,
				List.of(
						"clone",
						"--quiet",
						"--no-local",
						"--no-hardlinks",
						"--",
						sourceRoot.toString(),
						clone.toString()),
				new byte[0]);
		Path cleanRoot = clone.toRealPath();
		String evaluatorRevision = gitLine(
				cleanRoot, git, "rev-parse", "--verify", "HEAD^{commit}");
		List<SourceFile> evaluatorSources = committedSources(
				cleanRoot,
				git,
				evaluatorRevision,
				RelatedTopicReuseHoldoutGitCollector.evaluatorInventoryPaths());
		List<SourceFile> candidateSources = committedSources(
				cleanRoot,
				git,
				RelatedTopicReuseHoldoutPolicy.CANDIDATE_FREEZE_REVISION,
				RelatedTopicReuseHoldoutGitCollector.candidateInventoryPaths());
		String evaluatorSha256 = RelatedTopicReuseHoldoutEvaluatorSeal.sourceSha256(
				SourceRole.EVALUATOR, evaluatorRevision, evaluatorSources);
		String candidateSha256 = RelatedTopicReuseHoldoutEvaluatorSeal.sourceSha256(
				SourceRole.CANDIDATE,
				RelatedTopicReuseHoldoutPolicy.CANDIDATE_FREEZE_REVISION,
				candidateSources);
		FreezeRecord freeze = new FreezeRecord(
				RelatedTopicReuseHoldoutGitCollector.FREEZE_SCHEMA_VERSION,
				RelatedTopicReuseHoldoutGitCollector.INVENTORY_ID,
				evaluatorRevision,
				evaluatorSha256,
				RelatedTopicReuseHoldoutPolicy.CANDIDATE_FREEZE_REVISION,
				candidateSha256);
		return RelatedTopicReuseHoldoutGitCollector.verifyCleanCheckout(
				cleanRoot,
				freeze,
				new RelatedTopicReuseHoldoutGitCollector.ProcessGitRunner(git));
	}

	private static List<SourceFile> committedSources(
			Path root,
			Path git,
			String revision,
			List<String> inventoryPaths) throws Exception {
		List<String> arguments = new ArrayList<>(List.of(
				"ls-tree", "-r", "-z", "--full-tree", "--long", revision, "--"));
		arguments.addAll(inventoryPaths.stream()
				.map(path -> ":(top,literal)" + path)
				.toList());
		byte[] tree = gitSuccess(root, git, arguments, new byte[0]);
		List<GitTreeEntry> entries = new ArrayList<>();
		int start = 0;
		while (start < tree.length) {
			int end = indexOf(tree, (byte) 0, start);
			String record = new String(
					Arrays.copyOfRange(tree, start, end), StandardCharsets.UTF_8);
			int tab = record.indexOf('\t');
			String[] header = record.substring(0, tab).trim().split(" +");
			entries.add(new GitTreeEntry(
					Integer.parseInt(header[0]),
					record.substring(tab + 1),
					header[2],
					Integer.parseInt(header[3])));
			start = end + 1;
		}
		ByteArrayOutputStream requests = new ByteArrayOutputStream();
		for (GitTreeEntry entry : entries) {
			requests.writeBytes(entry.objectId().getBytes(StandardCharsets.US_ASCII));
			requests.write('\n');
		}
		byte[] batch = gitSuccess(
				root, git, List.of("cat-file", "--batch"), requests.toByteArray());
		List<SourceFile> sources = new ArrayList<>(entries.size());
		int offset = 0;
		for (GitTreeEntry entry : entries) {
			int headerEnd = indexOf(batch, (byte) '\n', offset);
			String header = new String(
					Arrays.copyOfRange(batch, offset, headerEnd),
					StandardCharsets.US_ASCII);
			assertThat(header).isEqualTo(entry.objectId() + " blob " + entry.bytes());
			int contentStart = headerEnd + 1;
			int contentEnd = Math.addExact(contentStart, entry.bytes());
			assertThat(batch[contentEnd]).isEqualTo((byte) '\n');
			sources.add(new SourceFile(
					entry.mode(),
					entry.path(),
					Arrays.copyOfRange(batch, contentStart, contentEnd)));
			offset = contentEnd + 1;
		}
		assertThat(offset).isEqualTo(batch.length);
		return List.copyOf(sources);
	}

	private static RelatedTopicReuseHoldoutRankingSnapshot.Observation
			emptyRankingObservation(
					RelatedTopicReuseHoldoutBundle.VerifiedCorpus verified) {
		var emptyRun = new RelatedTopicReuseHoldoutRankingSnapshot.RankingRun(
				List.of(), List.of(), List.of(), List.of(), List.of());
		List<RelatedTopicReuseHoldoutRankingSnapshot.QueryRanking> queries =
				verified.rankingCorpus().corpus().queries().stream()
						.map(query -> new RelatedTopicReuseHoldoutRankingSnapshot.QueryRanking(
								query.key(),
								emptyRun,
								emptyRun,
								new RelatedTopicReuseHoldoutRankingSnapshot.HiddenPerturbation(
										"hidden-other-owner",
										"hidden-catalog-only",
										List.of(),
										List.of())))
						.toList();
		return new RelatedTopicReuseHoldoutRankingSnapshot.Observation(
				RelatedTopicReuseHoldoutPolicy.CANDIDATE_FREEZE_REVISION,
				RelatedTopicReuseHoldoutRankingSnapshot.FROZEN_CUTOFF,
				queries.stream()
						.map(RelatedTopicReuseHoldoutRankingSnapshot.QueryRanking::queryKey)
						.toList(),
				queries,
				new RelatedTopicReuseHoldoutRankingSnapshot.StructuralCounters(0, 0));
	}

	private static Path findRepositoryRoot() throws IOException {
		Path candidate = Path.of("").toAbsolutePath().normalize();
		while (candidate != null && !Files.isDirectory(candidate.resolve(".git"))) {
			candidate = candidate.getParent();
		}
		if (candidate == null) {
			throw new IOException("repository root is unavailable");
		}
		return candidate.toRealPath();
	}

	private static Path locateGitExecutable() throws IOException {
		String pathValue = System.getenv("PATH");
		if (pathValue != null) {
			for (String value : pathValue.split(Pattern.quote(File.pathSeparator), -1)) {
				Path directory = Path.of(value);
				if (value.isEmpty() || !directory.isAbsolute()) {
					continue;
				}
				Path candidate = directory.resolve(
						File.separatorChar == '\\' ? "git.exe" : "git");
				if (Files.isRegularFile(candidate) && Files.isExecutable(candidate)) {
					return candidate.toRealPath();
				}
			}
		}
		throw new IOException("an absolute Git executable is required");
	}

	private static String gitLine(Path root, Path git, String... arguments)
			throws Exception {
		String value = new String(
				gitSuccess(root, git, List.of(arguments), new byte[0]),
				StandardCharsets.UTF_8).strip();
		assertThat(value).doesNotContain("\n", "\r");
		return value;
	}

	private static byte[] gitSuccess(
			Path root, Path git, List<String> arguments, byte[] standardInput)
			throws Exception {
		List<String> command = new ArrayList<>();
		command.add(git.toString());
		command.addAll(arguments);
		Process process = new ProcessBuilder(command)
				.directory(root.toFile())
				.redirectErrorStream(false)
				.start();
		AtomicReference<byte[]> stdout = new AtomicReference<>();
		AtomicReference<byte[]> stderr = new AtomicReference<>();
		AtomicReference<Throwable> readFailure = new AtomicReference<>();
		Thread out = readProcessOutput(process.getInputStream(), stdout, readFailure);
		Thread err = readProcessOutput(process.getErrorStream(), stderr, readFailure);
		try (var input = process.getOutputStream()) {
			input.write(standardInput);
		}
		if (!process.waitFor(30, TimeUnit.SECONDS)) {
			process.destroyForcibly();
			throw new IOException("test Git command timed out");
		}
		out.join();
		err.join();
		if (readFailure.get() != null) {
			throw new IOException("test Git output read failed", readFailure.get());
		}
		assertThat(process.exitValue())
				.as(() -> "Git stderr: "
						+ new String(stderr.get(), StandardCharsets.UTF_8))
				.isZero();
		return stdout.get();
	}

	private static Thread readProcessOutput(
			InputStream input,
			AtomicReference<byte[]> target,
			AtomicReference<Throwable> failure) {
		return Thread.ofVirtual().start(() -> {
			try (input; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
				input.transferTo(output);
				target.set(output.toByteArray());
			}
			catch (Throwable exception) {
				failure.compareAndSet(null, exception);
			}
		});
	}

	private static int indexOf(byte[] value, byte target, int start) {
		for (int index = start; index < value.length; index++) {
			if (value[index] == target) {
				return index;
			}
		}
		throw new AssertionError("test Git output was not terminated");
	}

	private static PGSimpleDataSource dataSource(String user, String password) {
		PGSimpleDataSource dataSource = new PGSimpleDataSource();
		dataSource.setURL(POSTGRES.getJdbcUrl());
		dataSource.setUser(user);
		dataSource.setPassword(password);
		dataSource.setConnectTimeout(10);
		dataSource.setSocketTimeout(15);
		return dataSource;
	}

	private static DataSource commitFailureDataSource(
			boolean delegateCommit, AtomicInteger commits) {
		return new DelegatingDataSource(runtimeDataSource) {
			@Override
			public Connection getConnection() throws SQLException {
				Connection delegate = super.getConnection();
				return (Connection) Proxy.newProxyInstance(
						Connection.class.getClassLoader(),
						new Class<?>[] {Connection.class},
						(proxy, method, arguments) -> {
							if (method.getName().equals("commit")
									&& method.getParameterCount() == 0) {
								commits.incrementAndGet();
								if (delegateCommit) {
									delegate.commit();
								}
								throw new SQLException("simulated commit acknowledgment loss");
							}
							try {
								return method.invoke(delegate, arguments);
							}
							catch (InvocationTargetException exception) {
								throw exception.getCause();
							}
						});
			}
		};
	}

	private static DataSource setRoleDataSource(DataSource delegate, String role) {
		return new DelegatingDataSource(delegate) {
			@Override
			public Connection getConnection() throws SQLException {
				Connection connection = super.getConnection();
				try (Statement statement = connection.createStatement()) {
					statement.execute("SET ROLE " + role);
					return connection;
				}
				catch (SQLException exception) {
					connection.close();
					throw exception;
				}
			}
		};
	}

	private static void assertContractRejected(SyntheticRun run) {
		assertThatThrownBy(() -> new RelatedTopicReuseHoldoutPostgresFirstRunLedger(
				runtimeDataSource).claim(run.commitment(), run.checkout()))
				.isInstanceOf(ContractException.class)
				.hasMessage("HOLDOUT_LEDGER_DATABASE_CONTRACT_INVALID");
	}

	private static void executeAsAdministrator(String sql) throws SQLException {
		try (Connection connection = administratorDataSource.getConnection();
				Statement statement = connection.createStatement()) {
			statement.execute(sql);
		}
	}

	private static SyntheticRun syntheticRun(String marker) throws Exception {
		String bundleId = "external-holdout-" + marker;
		String corpusId = "external-corpus-" + marker;
		String policySha = RelatedTopicReuseHoldoutPolicy.POLICY_SHA256;
		String corpusSha = sha256("corpus-" + marker);
		String judgmentsSha = sha256("judgments-" + marker);
		String manifestSha = sha256("manifest-" + marker);
		long manifestBytes = 512L + marker.length();
		long corpusBytes = 4_096L + marker.length();
		long judgmentsBytes = 1_024L + marker.length();

		Class<?> manifestFileType = Class.forName(
				RelatedTopicReuseHoldoutBundle.class.getName() + "$ManifestFile");
		Constructor<?> manifestFileConstructor = manifestFileType.getDeclaredConstructor(
				String.class, long.class, String.class);
		manifestFileConstructor.setAccessible(true);
		Object corpusFile = manifestFileConstructor.newInstance(
				"holdout-corpus.json", corpusBytes, corpusSha);
		Object judgmentsFile = manifestFileConstructor.newInstance(
				"judgments.json", judgmentsBytes, judgmentsSha);

		Class<?> manifestType = Class.forName(
				RelatedTopicReuseHoldoutBundle.class.getName() + "$Manifest");
		Constructor<?> manifestConstructor = manifestType.getDeclaredConstructor(
				String.class,
				String.class,
				String.class,
				String.class,
				String.class,
				long.class,
				List.class);
		manifestConstructor.setAccessible(true);
		Object manifest = manifestConstructor.newInstance(
				"related-topic-reuse-holdout-bundle-v1",
				bundleId,
				RelatedTopicReuseHoldoutPolicy.POLICY_ID,
				policySha,
				corpusId,
				corpusBytes + judgmentsBytes,
				List.of(corpusFile, judgmentsFile));

		var corpus = new RelatedTopicReuseHoldoutBundle.Corpus(
				corpusId, List.of(), List.of(), List.of());
		var rankingCorpus = new RelatedTopicReuseHoldoutBundle.RankingCorpus(
				"related-topic-reuse-holdout-bundle-v1",
				bundleId,
				corpusId,
				RelatedTopicReuseHoldoutPolicy.POLICY_ID,
				policySha,
				corpusSha,
				corpus);

		Constructor<RelatedTopicReuseHoldoutBundle.VerifiedCorpus> verifiedConstructor =
				RelatedTopicReuseHoldoutBundle.VerifiedCorpus.class.getDeclaredConstructor(
						manifestType,
						String.class,
						long.class,
						String.class,
						RelatedTopicReuseHoldoutBundle.RankingCorpus.class,
						long.class);
		verifiedConstructor.setAccessible(true);
		var verifiedCorpus = verifiedConstructor.newInstance(
				manifest,
				manifestSha,
				manifestBytes,
				RelatedTopicReuseHoldoutPolicy.EVALUATION_PROTOCOL_ID,
				rankingCorpus,
				corpusBytes);
		return new SyntheticRun(
				verifiedCorpus,
				verifiedCorpus.firstRunCommitment(),
				syntheticCheckout(marker));
	}

	private static VerifiedCleanCheckout syntheticCheckout(String marker) throws Exception {
		String evaluatorRevision = sha256("evaluator-revision-" + marker).substring(0, 40);
		String candidateRevision = RelatedTopicReuseHoldoutPolicy.CANDIDATE_FREEZE_REVISION;
		List<SourceFile> evaluatorFiles = List.of(new SourceFile(
				100644,
				"backend/src/test/java/Evaluator.java",
				("evaluator-" + marker).getBytes(StandardCharsets.UTF_8)));
		List<SourceFile> candidateFiles = List.of(new SourceFile(
				100644,
				"backend/src/main/java/Candidate.java",
				("candidate-" + marker).getBytes(StandardCharsets.UTF_8)));
		String evaluatorSha = RelatedTopicReuseHoldoutEvaluatorSeal.sourceSha256(
				SourceRole.EVALUATOR, evaluatorRevision, evaluatorFiles);
		String candidateSha = RelatedTopicReuseHoldoutEvaluatorSeal.sourceSha256(
				SourceRole.CANDIDATE, candidateRevision, candidateFiles);
		VerifiedEvaluatorSeal evaluatorSeal = RelatedTopicReuseHoldoutEvaluatorSeal.verify(
				evaluatorRevision,
				evaluatorSha,
				candidateRevision,
				candidateSha,
				new RepositoryState(
						evaluatorRevision, "", candidateRevision, candidateSha, true),
				evaluatorFiles,
				candidateFiles);
		FreezeRecord freeze = new FreezeRecord(
				RelatedTopicReuseHoldoutGitCollector.FREEZE_SCHEMA_VERSION,
				RelatedTopicReuseHoldoutGitCollector.INVENTORY_ID,
				evaluatorRevision,
				evaluatorSha,
				candidateRevision,
				candidateSha);
		Constructor<VerifiedCleanCheckout> constructor =
				VerifiedCleanCheckout.class.getDeclaredConstructor(
						FreezeRecord.class, VerifiedEvaluatorSeal.class);
		constructor.setAccessible(true);
		return constructor.newInstance(freeze, evaluatorSeal);
	}

	private static String sha256(String value) throws Exception {
		return sha256(value.getBytes(StandardCharsets.UTF_8));
	}

	private static String sha256(byte[] value) throws Exception {
		return HexFormat.of().formatHex(
				MessageDigest.getInstance("SHA-256").digest(value));
	}

	private static long claimCount() throws Exception {
		try (Connection connection = administratorDataSource.getConnection();
				Statement statement = connection.createStatement();
				ResultSet result = statement.executeQuery(
						"SELECT count(*) FROM holdout_ledger_v1.first_run_claim_v1")) {
			assertThat(result.next()).isTrue();
			return result.getLong(1);
		}
	}

	private static long durableClaimCount() {
		try {
			return claimCount();
		}
		catch (Exception exception) {
			throw new AssertionError("could not inspect the durable first-run claim", exception);
		}
	}

	private static String singleText(String expression) throws Exception {
		try (Connection connection = administratorDataSource.getConnection();
				Statement statement = connection.createStatement();
				ResultSet result = statement.executeQuery(
						"SELECT " + expression
								+ " FROM holdout_ledger_v1.first_run_claim_v1")) {
			assertThat(result.next()).isTrue();
			String value = result.getString(1);
			assertThat(result.next()).isFalse();
			return value;
		}
	}

	private static boolean tableExists(String schema, String table) throws Exception {
		try (Connection connection = administratorDataSource.getConnection();
				var statement = connection.prepareStatement("""
						SELECT EXISTS (
						    SELECT 1
						    FROM pg_catalog.pg_class c
						    JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace
						    WHERE n.nspname = ? AND c.relname = ?
						)
						""")) {
			statement.setString(1, schema);
			statement.setString(2, table);
			try (ResultSet result = statement.executeQuery()) {
				assertThat(result.next()).isTrue();
				return result.getBoolean(1);
			}
		}
	}

	private static void assertSqlRejected(DataSource dataSource, String sql) {
		assertThatThrownBy(() -> {
			try (Connection connection = dataSource.getConnection();
					Statement statement = connection.createStatement()) {
				statement.execute(sql);
			}
		}).isInstanceOf(SQLException.class);
	}

	private record SyntheticRun(
			RelatedTopicReuseHoldoutBundle.VerifiedCorpus verifiedCorpus,
			VerifiedFirstRunCommitment commitment,
			VerifiedCleanCheckout checkout) {
	}

	private record GitTreeEntry(
			int mode, String path, String objectId, int bytes) {
	}
}
