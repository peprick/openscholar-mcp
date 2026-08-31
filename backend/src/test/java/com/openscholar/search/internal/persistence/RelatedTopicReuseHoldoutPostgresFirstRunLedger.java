package com.openscholar.search.internal.persistence;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import javax.sql.DataSource;

import com.openscholar.search.internal.persistence.RelatedTopicReuseHoldoutBundle.VerifiedFirstRunCommitment;
import com.openscholar.search.internal.persistence.RelatedTopicReuseHoldoutGitCollector.VerifiedCleanCheckout;
import com.openscholar.search.internal.persistence.RelatedTopicReuseHoldoutPostgresTlsConnectionFactory.VerifiedRuntimeConnectionSource;

/**
 * Insert-only PostgreSQL finality boundary for the preregistered first holdout run.
 *
 * <p>This class is deliberately not a Spring bean and its table is deliberately
 * absent from application Flyway. The supplied data source must authenticate as
 * the fixed, non-owner runtime role in the dedicated operator database.</p>
 */
final class RelatedTopicReuseHoldoutPostgresFirstRunLedger {

	static final String DATABASE_NAME = "openscholar_holdout_ledger_v1";
	static final String SCHEMA_NAME = "holdout_ledger_v1";
	static final String TABLE_NAME = "first_run_claim_v1";
	static final String RUNTIME_ROLE = "openscholar_holdout_ledger_runtime_v1";
	static final String OWNER_ROLE = "openscholar_holdout_ledger_owner_v1";
	static final String AUDITOR_ROLE = "openscholar_holdout_ledger_auditor_v1";
	static final String CONTRACT_COMMENT =
			"openscholar-related-topic-reuse-first-run-ledger-v1";

	private static final int NETWORK_TIMEOUT_MILLIS = 15_000;
	private static final Executor DIRECT_EXECUTOR = Runnable::run;
	private static final Set<String> RUNTIME_INSERT_COLUMNS = Set.of(
			"schema_version",
			"evaluation_protocol_id",
			"policy_id",
			"run_key",
			"bundle_protocol_id",
			"bundle_id",
			"corpus_id",
			"policy_sha256",
			"manifest_sha256",
			"manifest_bytes",
			"corpus_sha256",
			"corpus_bytes",
			"judgments_sha256",
			"judgments_bytes",
			"freeze_schema_version",
			"source_inventory_id",
			"evaluator_revision",
			"evaluator_source_sha256",
			"candidate_revision",
			"candidate_source_sha256");
	private static final List<ColumnContract> COLUMNS = List.of(
			column("schema_version", "smallint"),
			textColumn("evaluation_protocol_id"),
			textColumn("policy_id"),
			column("run_key", "bytea"),
			textColumn("bundle_protocol_id"),
			textColumn("bundle_id"),
			textColumn("corpus_id"),
			column("policy_sha256", "bytea"),
			column("manifest_sha256", "bytea"),
			column("manifest_bytes", "bigint"),
			column("corpus_sha256", "bytea"),
			column("corpus_bytes", "bigint"),
			column("judgments_sha256", "bytea"),
			column("judgments_bytes", "bigint"),
			column("freeze_schema_version", "smallint"),
			textColumn("source_inventory_id"),
			textColumn("evaluator_revision"),
			column("evaluator_source_sha256", "bytea"),
			textColumn("candidate_revision"),
			column("candidate_source_sha256", "bytea"),
			new ColumnContract(
					"claimed_at", "timestamp with time zone", true, null,
					"clock_timestamp()"),
			new ColumnContract(
					"synchronous_commit_setting", "text", true, "C",
					"current_setting('synchronous_commit'::text)"));

	private static final String INSERT_SQL = """
			INSERT INTO holdout_ledger_v1.first_run_claim_v1 (
			    schema_version,
			    evaluation_protocol_id,
			    policy_id,
			    run_key,
			    bundle_protocol_id,
			    bundle_id,
			    corpus_id,
			    policy_sha256,
			    manifest_sha256,
			    manifest_bytes,
			    corpus_sha256,
			    corpus_bytes,
			    judgments_sha256,
			    judgments_bytes,
			    freeze_schema_version,
			    source_inventory_id,
			    evaluator_revision,
			    evaluator_source_sha256,
			    candidate_revision,
			    candidate_source_sha256
			)
			VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
			ON CONFLICT DO NOTHING
			RETURNING 1
			""";

	private final ClaimConnectionSource connectionSource;

	RelatedTopicReuseHoldoutPostgresFirstRunLedger(
			VerifiedRuntimeConnectionSource connectionSource) {
		this(Objects.requireNonNull(
				connectionSource, "connectionSource")::openClaimConnection);
	}

	/** Reflection-only seam for plaintext synthetic mechanics tests. */
	private RelatedTopicReuseHoldoutPostgresFirstRunLedger(DataSource dataSource) {
		this(Objects.requireNonNull(dataSource, "dataSource")::getConnection);
	}

	private RelatedTopicReuseHoldoutPostgresFirstRunLedger(
			ClaimConnectionSource connectionSource) {
		this.connectionSource = Objects.requireNonNull(
				connectionSource, "connectionSource");
	}

	CommittedFirstRun claim(
			VerifiedFirstRunCommitment commitment,
			VerifiedCleanCheckout checkout) throws LedgerException {
		final RelatedTopicReuseHoldoutFirstRunIdentity identity;
		try {
			identity = RelatedTopicReuseHoldoutFirstRunIdentity.fromVerified(
					commitment, checkout);
		}
		catch (RuntimeException exception) {
			throw new InvalidClaimException();
		}

		Connection connection = null;
		boolean commitAttempted = false;
		boolean committed = false;
		try {
			connection = connectionSource.open();
			connection.setNetworkTimeout(DIRECT_EXECUTOR, NETWORK_TIMEOUT_MILLIS);
			if (connection.getNetworkTimeout() != NETWORK_TIMEOUT_MILLIS) {
				throw new ContractException();
			}
			if (!connection.getAutoCommit()) {
				throw new ContractException();
			}
			connection.setReadOnly(false);
			connection.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
			connection.setAutoCommit(false);
			configureTransaction(connection);
			verifyDatabaseContract(connection);
			if (!insertClaim(connection, identity)) {
				connection.rollback();
				throw new AlreadyClaimedException();
			}
			commitAttempted = true;
			connection.commit();
			committed = true;
		}
		catch (LedgerException exception) {
			rollbackIfUnambiguous(connection, commitAttempted);
			throw exception;
		}
		catch (SQLException | RuntimeException exception) {
			rollbackIfUnambiguous(connection, commitAttempted);
			if (commitAttempted) {
				throw new CommitOutcomeUnknownException();
			}
			throw new LedgerUnavailableException();
		}
		finally {
			closeQuietly(connection);
		}
		if (!committed) {
			throw new CommitOutcomeUnknownException();
		}
		return new CommittedFirstRun(identity, commitment, checkout);
	}

	private static void configureTransaction(Connection connection) throws SQLException {
		try (Statement statement = connection.createStatement()) {
			statement.execute("SET LOCAL synchronous_commit = 'on'");
			statement.execute("SET LOCAL lock_timeout = '5s'");
			statement.execute("SET LOCAL statement_timeout = '10s'");
			statement.execute("SET LOCAL idle_in_transaction_session_timeout = '15s'");
			statement.execute("SET LOCAL search_path = pg_catalog");
		}
	}

	private static void verifyDatabaseContract(Connection connection)
			throws SQLException, ContractException {
		verifySessionAndRole(connection);
		verifyFixedRoles(connection);
		verifyRelation(connection);
		verifyPrivileges(connection);
		verifyAclContract(connection);
		verifyColumns(connection);
		verifyConstraints(connection);
		verifyIndexes(connection);
	}

	private static void verifySessionAndRole(Connection connection)
			throws SQLException, ContractException {
		String sql = """
				SELECT current_database(),
				       session_user,
				       current_user,
				       current_setting('server_version_num')::integer,
				       current_setting('fsync'),
				       current_setting('synchronous_commit'),
				       current_setting('transaction_read_only'),
				       current_setting('transaction_isolation'),
				       r.rolsuper,
				       r.rolcreaterole,
				       r.rolcreatedb,
				       r.rolreplication,
				       r.rolbypassrls,
				       r.rolinherit,
				       r.rolcanlogin,
				       (SELECT count(*) FROM pg_catalog.pg_auth_members m
				        WHERE m.member = r.oid),
				       (SELECT count(*) FROM pg_catalog.pg_auth_members m
				        WHERE m.roleid = r.oid)
				FROM pg_catalog.pg_roles r
				WHERE r.rolname = current_user
				""";
		try (Statement statement = connection.createStatement()) {
			try (ResultSet result = statement.executeQuery(sql)) {
				if (!result.next()
						|| !DATABASE_NAME.equals(result.getString(1))
						|| !RUNTIME_ROLE.equals(result.getString(2))
						|| !RUNTIME_ROLE.equals(result.getString(3))
						|| result.getInt(4) / 10_000 != 17
						|| !"on".equals(result.getString(5))
						|| !"on".equals(result.getString(6))
						|| !"off".equals(result.getString(7))
						|| !"read committed".equals(result.getString(8))
						|| result.getBoolean(9)
						|| result.getBoolean(10)
						|| result.getBoolean(11)
						|| result.getBoolean(12)
						|| result.getBoolean(13)
						|| result.getBoolean(14)
						|| !result.getBoolean(15)
						|| result.getLong(16) != 0L
						|| result.getLong(17) != 0L
						|| result.next()) {
					throw new ContractException();
				}
			}
		}
	}

	private static void verifyFixedRoles(Connection connection)
			throws SQLException, ContractException {
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
				       (SELECT count(*) FROM pg_catalog.pg_auth_members m
				        WHERE m.member = r.oid),
				       (SELECT count(*) FROM pg_catalog.pg_auth_members m
				        WHERE m.roleid = r.oid)
				FROM pg_catalog.pg_roles r
				WHERE r.rolname IN (
				    'openscholar_holdout_ledger_auditor_v1',
				    'openscholar_holdout_ledger_owner_v1',
				    'openscholar_holdout_ledger_runtime_v1'
				)
				ORDER BY r.rolname
				""";
		List<RoleContract> observed = new ArrayList<>();
		try (Statement statement = connection.createStatement()) {
			try (ResultSet result = statement.executeQuery(sql)) {
				while (result.next()) {
					if (result.getBoolean(2)
							|| result.getBoolean(3)
							|| result.getBoolean(4)
							|| result.getBoolean(5)
							|| result.getBoolean(6)
							|| result.getBoolean(7)
							|| result.getInt(9) != -1
							|| result.getLong(10) != 0L
							|| result.getLong(11) != 0L) {
						throw new ContractException();
					}
					observed.add(new RoleContract(
							result.getString(1), result.getBoolean(8)));
				}
			}
		}
		if (!observed.equals(List.of(
				new RoleContract(AUDITOR_ROLE, true),
				new RoleContract(OWNER_ROLE, false),
				new RoleContract(RUNTIME_ROLE, true)))) {
			throw new ContractException();
		}
	}

	private static void verifyRelation(Connection connection)
			throws SQLException, ContractException {
		String sql = """
				SELECT c.relkind,
				       c.relpersistence,
				       c.relrowsecurity,
				       c.relforcerowsecurity,
				       c.relispartition,
				       c.relreplident,
				       am.amname,
				       pg_catalog.obj_description(c.oid, 'pg_class'),
				       database_owner.rolname,
				       schema_owner.rolname,
				       table_owner.rolname,
				       (SELECT count(*) FROM pg_catalog.pg_trigger t
				        WHERE t.tgrelid = c.oid AND NOT t.tgisinternal),
				       (SELECT count(*) FROM pg_catalog.pg_rewrite w
				        WHERE w.ev_class = c.oid AND w.rulename <> '_RETURN')
				FROM pg_catalog.pg_class c
				JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace
				JOIN pg_catalog.pg_database d ON d.datname = current_database()
				JOIN pg_catalog.pg_roles database_owner ON database_owner.oid = d.datdba
				JOIN pg_catalog.pg_roles schema_owner ON schema_owner.oid = n.nspowner
				JOIN pg_catalog.pg_roles table_owner ON table_owner.oid = c.relowner
				JOIN pg_catalog.pg_am am ON am.oid = c.relam
				WHERE n.nspname = 'holdout_ledger_v1'
				  AND c.relname = 'first_run_claim_v1'
				""";
		try (Statement statement = connection.createStatement()) {
			try (ResultSet result = statement.executeQuery(sql)) {
				if (!result.next()
						|| !"r".equals(result.getString(1))
						|| !"p".equals(result.getString(2))
						|| result.getBoolean(3)
						|| result.getBoolean(4)
						|| result.getBoolean(5)
						|| !"d".equals(result.getString(6))
						|| !"heap".equals(result.getString(7))
						|| !CONTRACT_COMMENT.equals(result.getString(8))
						|| !OWNER_ROLE.equals(result.getString(9))
						|| !OWNER_ROLE.equals(result.getString(10))
						|| !OWNER_ROLE.equals(result.getString(11))
						|| result.getLong(12) != 0L
						|| result.getLong(13) != 0L
						|| result.next()) {
					throw new ContractException();
				}
			}
		}
	}

	private static void verifyPrivileges(Connection connection)
			throws SQLException, ContractException {
		String sql = """
				SELECT pg_catalog.has_database_privilege(current_user, d.oid, 'CONNECT'),
				       pg_catalog.has_database_privilege(current_user, d.oid, 'CREATE'),
				       pg_catalog.has_database_privilege(current_user, d.oid, 'TEMP'),
				       pg_catalog.has_schema_privilege(current_user, n.oid, 'USAGE'),
				       pg_catalog.has_schema_privilege(current_user, n.oid, 'CREATE'),
				       pg_catalog.has_table_privilege(current_user, c.oid, 'INSERT'),
				       pg_catalog.has_table_privilege(current_user, c.oid, 'SELECT'),
				       pg_catalog.has_table_privilege(current_user, c.oid, 'UPDATE'),
				       pg_catalog.has_table_privilege(current_user, c.oid, 'DELETE'),
				       pg_catalog.has_table_privilege(current_user, c.oid, 'TRUNCATE'),
				       pg_catalog.has_table_privilege(current_user, c.oid, 'REFERENCES'),
				       pg_catalog.has_table_privilege(current_user, c.oid, 'TRIGGER'),
				       pg_catalog.has_table_privilege(current_user, c.oid, 'MAINTAIN'),
				       pg_catalog.has_any_column_privilege(current_user, c.oid, 'INSERT'),
				       pg_catalog.has_any_column_privilege(current_user, c.oid, 'SELECT'),
				       pg_catalog.has_any_column_privilege(current_user, c.oid, 'UPDATE'),
				       pg_catalog.has_any_column_privilege(current_user, c.oid, 'REFERENCES'),
				       pg_catalog.has_database_privilege('openscholar_holdout_ledger_auditor_v1', d.oid, 'CONNECT'),
				       pg_catalog.has_database_privilege('openscholar_holdout_ledger_auditor_v1', d.oid, 'CREATE'),
				       pg_catalog.has_database_privilege('openscholar_holdout_ledger_auditor_v1', d.oid, 'TEMP'),
				       pg_catalog.has_schema_privilege('openscholar_holdout_ledger_auditor_v1', n.oid, 'USAGE'),
				       pg_catalog.has_schema_privilege('openscholar_holdout_ledger_auditor_v1', n.oid, 'CREATE'),
				       pg_catalog.has_table_privilege('openscholar_holdout_ledger_auditor_v1', c.oid, 'SELECT'),
				       pg_catalog.has_table_privilege('openscholar_holdout_ledger_auditor_v1', c.oid, 'INSERT'),
				       pg_catalog.has_table_privilege('openscholar_holdout_ledger_auditor_v1', c.oid, 'UPDATE'),
				       pg_catalog.has_table_privilege('openscholar_holdout_ledger_auditor_v1', c.oid, 'DELETE'),
				       pg_catalog.has_table_privilege('openscholar_holdout_ledger_auditor_v1', c.oid, 'TRUNCATE'),
				       pg_catalog.has_table_privilege('openscholar_holdout_ledger_auditor_v1', c.oid, 'REFERENCES'),
				       pg_catalog.has_table_privilege('openscholar_holdout_ledger_auditor_v1', c.oid, 'TRIGGER'),
				       pg_catalog.has_table_privilege('openscholar_holdout_ledger_auditor_v1', c.oid, 'MAINTAIN'),
				       (SELECT count(*)
				        FROM pg_catalog.aclexplode(COALESCE(
				            d.datacl, pg_catalog.acldefault('d', d.datdba))) acl
				        WHERE acl.grantee = 0),
				       (SELECT count(*)
				        FROM pg_catalog.aclexplode(COALESCE(
				            n.nspacl, pg_catalog.acldefault('n', n.nspowner))) acl
				        WHERE acl.grantee = 0),
				       (SELECT count(*)
				        FROM pg_catalog.aclexplode(COALESCE(
				            c.relacl, pg_catalog.acldefault('r', c.relowner))) acl
				        WHERE acl.grantee = 0)
				FROM pg_catalog.pg_class c
				JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace
				JOIN pg_catalog.pg_database d ON d.datname = current_database()
				WHERE n.nspname = 'holdout_ledger_v1'
				  AND c.relname = 'first_run_claim_v1'
				""";
		try (Statement statement = connection.createStatement()) {
			try (ResultSet result = statement.executeQuery(sql)) {
				if (!result.next()
						|| !result.getBoolean(1)
						|| result.getBoolean(2)
						|| result.getBoolean(3)
						|| !result.getBoolean(4)
						|| result.getBoolean(5)
						|| anyTrue(result, 6, 13)
						|| !result.getBoolean(14)
						|| anyTrue(result, 15, 17)
						|| !result.getBoolean(18)
						|| result.getBoolean(19)
						|| result.getBoolean(20)
						|| !result.getBoolean(21)
						|| result.getBoolean(22)
						|| !result.getBoolean(23)
						|| anyTrue(result, 24, 30)
						|| result.getLong(31) != 0L
						|| result.getLong(32) != 0L
						|| result.getLong(33) != 0L
						|| result.next()) {
					throw new ContractException();
				}
			}
		}
	}

	private static boolean anyTrue(ResultSet result, int first, int last)
			throws SQLException {
		for (int index = first; index <= last; index++) {
			if (result.getBoolean(index)) {
				return true;
			}
		}
		return false;
	}

	private static void verifyAclContract(Connection connection)
			throws SQLException, ContractException {
		String sql = """
				SELECT 'database' AS scope,
				       '' AS object_name,
				       grantee.rolname,
				       acl.privilege_type,
				       acl.is_grantable
				FROM pg_catalog.pg_database d
				CROSS JOIN LATERAL pg_catalog.aclexplode(COALESCE(
				    d.datacl, pg_catalog.acldefault('d', d.datdba))) acl
				JOIN pg_catalog.pg_roles grantee ON grantee.oid = acl.grantee
				WHERE d.datname = current_database()
				  AND acl.grantee <> d.datdba
				UNION ALL
				SELECT 'schema', '', grantee.rolname, acl.privilege_type,
				       acl.is_grantable
				FROM pg_catalog.pg_namespace n
				CROSS JOIN LATERAL pg_catalog.aclexplode(COALESCE(
				    n.nspacl, pg_catalog.acldefault('n', n.nspowner))) acl
				JOIN pg_catalog.pg_roles grantee ON grantee.oid = acl.grantee
				WHERE n.nspname = 'holdout_ledger_v1'
				  AND acl.grantee <> n.nspowner
				UNION ALL
				SELECT 'table', '', grantee.rolname, acl.privilege_type,
				       acl.is_grantable
				FROM pg_catalog.pg_class c
				JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace
				CROSS JOIN LATERAL pg_catalog.aclexplode(COALESCE(
				    c.relacl, pg_catalog.acldefault('r', c.relowner))) acl
				JOIN pg_catalog.pg_roles grantee ON grantee.oid = acl.grantee
				WHERE n.nspname = 'holdout_ledger_v1'
				  AND c.relname = 'first_run_claim_v1'
				  AND acl.grantee <> c.relowner
				UNION ALL
				SELECT 'column', a.attname, grantee.rolname, acl.privilege_type,
				       acl.is_grantable
				FROM pg_catalog.pg_class c
				JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace
				JOIN pg_catalog.pg_attribute a ON a.attrelid = c.oid
				CROSS JOIN LATERAL pg_catalog.aclexplode(a.attacl) acl
				JOIN pg_catalog.pg_roles grantee ON grantee.oid = acl.grantee
				WHERE n.nspname = 'holdout_ledger_v1'
				  AND c.relname = 'first_run_claim_v1'
				  AND a.attnum > 0
				  AND NOT a.attisdropped
				ORDER BY scope, object_name, rolname, privilege_type
				""";
		List<AclContract> observed = new ArrayList<>();
		try (Statement statement = connection.createStatement()) {
			try (ResultSet result = statement.executeQuery(sql)) {
				while (result.next()) {
					observed.add(new AclContract(
							result.getString(1),
							result.getString(2),
							result.getString(3),
							result.getString(4),
							result.getBoolean(5)));
				}
			}
		}
		if (!observed.equals(expectedAcl())) {
			throw new ContractException();
		}
	}

	private static List<AclContract> expectedAcl() {
		List<AclContract> expected = new ArrayList<>();
		RUNTIME_INSERT_COLUMNS.stream().sorted().forEach(column -> expected.add(
				new AclContract("column", column, RUNTIME_ROLE, "INSERT", false)));
		expected.add(new AclContract("database", "", AUDITOR_ROLE, "CONNECT", false));
		expected.add(new AclContract("database", "", RUNTIME_ROLE, "CONNECT", false));
		expected.add(new AclContract("schema", "", AUDITOR_ROLE, "USAGE", false));
		expected.add(new AclContract("schema", "", RUNTIME_ROLE, "USAGE", false));
		expected.add(new AclContract("table", "", AUDITOR_ROLE, "SELECT", false));
		return List.copyOf(expected);
	}

	private static void verifyColumns(Connection connection)
			throws SQLException, ContractException {
		String sql = """
				SELECT a.attname,
				       pg_catalog.format_type(a.atttypid, a.atttypmod),
				       a.attnotnull,
				       a.attidentity,
				       a.attgenerated,
				       coll.collname,
				       pg_catalog.pg_get_expr(def.adbin, def.adrelid, false),
				       pg_catalog.has_column_privilege(current_user, c.oid, a.attnum, 'INSERT'),
				       pg_catalog.has_column_privilege(current_user, c.oid, a.attnum, 'SELECT'),
				       pg_catalog.has_column_privilege(current_user, c.oid, a.attnum, 'UPDATE'),
				       pg_catalog.has_column_privilege(current_user, c.oid, a.attnum, 'REFERENCES')
				FROM pg_catalog.pg_class c
				JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace
				JOIN pg_catalog.pg_attribute a ON a.attrelid = c.oid
				LEFT JOIN pg_catalog.pg_collation coll ON coll.oid = a.attcollation
				LEFT JOIN pg_catalog.pg_attrdef def
				       ON def.adrelid = a.attrelid AND def.adnum = a.attnum
				WHERE n.nspname = 'holdout_ledger_v1'
				  AND c.relname = 'first_run_claim_v1'
				  AND a.attnum > 0
				  AND NOT a.attisdropped
				ORDER BY a.attnum
				""";
		List<ColumnContract> observed = new ArrayList<>();
		try (Statement statement = connection.createStatement()) {
			try (ResultSet result = statement.executeQuery(sql)) {
				while (result.next()) {
					String name = result.getString(1);
					observed.add(new ColumnContract(
							name,
							result.getString(2),
							result.getBoolean(3),
							result.getString(6),
							result.getString(7)));
					boolean expectedInsert = RUNTIME_INSERT_COLUMNS.contains(name);
					if (!result.getString(4).isEmpty()
							|| !result.getString(5).isEmpty()
							|| result.getBoolean(8) != expectedInsert
							|| result.getBoolean(9)
							|| result.getBoolean(10)
							|| result.getBoolean(11)) {
						throw new ContractException();
					}
				}
			}
		}
		if (!observed.equals(COLUMNS)) {
			throw new ContractException();
		}
	}

	private static void verifyConstraints(Connection connection)
			throws SQLException, ContractException {
		String sql = """
				SELECT con.conname,
				       con.contype,
				       con.condeferrable,
				       con.condeferred,
				       con.convalidated,
				       con.connoinherit,
				       pg_catalog.pg_get_constraintdef(con.oid, false)
				FROM pg_catalog.pg_class c
				JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace
				JOIN pg_catalog.pg_constraint con ON con.conrelid = c.oid
				WHERE n.nspname = 'holdout_ledger_v1'
				  AND c.relname = 'first_run_claim_v1'
				ORDER BY con.conname
				""";
		List<ConstraintContract> observed = new ArrayList<>();
		try (Statement statement = connection.createStatement()) {
			try (ResultSet result = statement.executeQuery(sql)) {
				while (result.next()) {
					observed.add(new ConstraintContract(
							result.getString(1),
							result.getString(2),
							result.getBoolean(3),
							result.getBoolean(4),
							result.getBoolean(5),
							result.getBoolean(6),
							result.getString(7)));
				}
			}
		}
		if (!observed.equals(expectedConstraints())) {
			throw new ContractException();
		}
	}

	private static void verifyIndexes(Connection connection)
			throws SQLException, ContractException {
		String sql = """
				SELECT index_class.relname,
				       owner.rolname,
				       am.amname,
				       i.indisunique,
				       i.indnullsnotdistinct,
				       i.indisprimary,
				       i.indisexclusion,
				       i.indimmediate,
				       i.indisclustered,
				       i.indisvalid,
				       i.indcheckxmin,
				       i.indisready,
				       i.indislive,
				       i.indisreplident,
				       i.indnkeyatts,
				       i.indnatts,
				       pg_catalog.pg_get_indexdef(i.indexrelid),
				       pg_catalog.pg_get_expr(i.indexprs, i.indrelid, false),
				       pg_catalog.pg_get_expr(i.indpred, i.indrelid, false)
				FROM pg_catalog.pg_class table_class
				JOIN pg_catalog.pg_namespace n ON n.oid = table_class.relnamespace
				JOIN pg_catalog.pg_index i ON i.indrelid = table_class.oid
				JOIN pg_catalog.pg_class index_class ON index_class.oid = i.indexrelid
				JOIN pg_catalog.pg_roles owner ON owner.oid = index_class.relowner
				JOIN pg_catalog.pg_am am ON am.oid = index_class.relam
				WHERE n.nspname = 'holdout_ledger_v1'
				  AND table_class.relname = 'first_run_claim_v1'
				ORDER BY index_class.relname
				""";
		try (Statement statement = connection.createStatement()) {
			try (ResultSet result = statement.executeQuery(sql)) {
				if (!result.next()
						|| !"first_run_claim_v1_pk".equals(result.getString(1))
						|| !OWNER_ROLE.equals(result.getString(2))
						|| !"btree".equals(result.getString(3))
						|| !result.getBoolean(4)
						|| result.getBoolean(5)
						|| !result.getBoolean(6)
						|| result.getBoolean(7)
						|| !result.getBoolean(8)
						|| result.getBoolean(9)
						|| !result.getBoolean(10)
						|| result.getBoolean(11)
						|| !result.getBoolean(12)
						|| !result.getBoolean(13)
						|| result.getBoolean(14)
						|| result.getInt(15) != 2
						|| result.getInt(16) != 2
						|| !("CREATE UNIQUE INDEX first_run_claim_v1_pk ON "
								+ "holdout_ledger_v1.first_run_claim_v1 USING btree "
								+ "(evaluation_protocol_id, policy_id)")
								.equals(result.getString(17))
						|| result.getString(18) != null
						|| result.getString(19) != null
						|| result.next()) {
					throw new ContractException();
				}
			}
		}
	}

	private static ColumnContract column(String name, String type) {
		return new ColumnContract(name, type, true, null, null);
	}

	private static ColumnContract textColumn(String name) {
		return new ColumnContract(name, "text", true, "C", null);
	}

	private static List<ConstraintContract> expectedConstraints() {
		return List.of(
				check("first_run_claim_v1_bundle_check", """
						CHECK ((((octet_length(bundle_id) >= 3) AND (octet_length(bundle_id) <= 160)) AND (bundle_id ~ '^[a-z0-9]+(-[a-z0-9]+)*$'::text)))"""),
				check("first_run_claim_v1_bundle_protocol_check", """
						CHECK ((((octet_length(bundle_protocol_id) >= 3) AND (octet_length(bundle_protocol_id) <= 160)) AND (bundle_protocol_id ~ '^[a-z0-9]+(-[a-z0-9]+)*$'::text)))"""),
				check("first_run_claim_v1_candidate_revision_check",
						"CHECK ((candidate_revision ~ '^[0-9a-f]{40}$'::text))"),
				check("first_run_claim_v1_candidate_source_sha_check",
						"CHECK ((octet_length(candidate_source_sha256) = 32))"),
				check("first_run_claim_v1_corpus_bytes_check",
						"CHECK (((corpus_bytes >= 1) AND (corpus_bytes <= 786432)))"),
				check("first_run_claim_v1_corpus_check", """
						CHECK ((((octet_length(corpus_id) >= 3) AND (octet_length(corpus_id) <= 160)) AND (corpus_id ~ '^[a-z0-9]+(-[a-z0-9]+)*$'::text)))"""),
				check("first_run_claim_v1_corpus_sha_check",
						"CHECK ((octet_length(corpus_sha256) = 32))"),
				check("first_run_claim_v1_evaluation_protocol_check", """
						CHECK ((((octet_length(evaluation_protocol_id) >= 3) AND (octet_length(evaluation_protocol_id) <= 160)) AND (evaluation_protocol_id ~ '^[a-z0-9]+(-[a-z0-9]+)*$'::text)))"""),
				check("first_run_claim_v1_evaluator_revision_check",
						"CHECK ((evaluator_revision ~ '^[0-9a-f]{40}$'::text))"),
				check("first_run_claim_v1_evaluator_source_sha_check",
						"CHECK ((octet_length(evaluator_source_sha256) = 32))"),
				check("first_run_claim_v1_freeze_schema_check",
						"CHECK ((freeze_schema_version = 1))"),
				check("first_run_claim_v1_inventory_check", """
						CHECK ((((octet_length(source_inventory_id) >= 3) AND (octet_length(source_inventory_id) <= 160)) AND (source_inventory_id ~ '^[a-z0-9]+(-[a-z0-9]+)*$'::text)))"""),
				check("first_run_claim_v1_judgments_bytes_check",
						"CHECK (((judgments_bytes >= 1) AND (judgments_bytes <= 196608)))"),
				check("first_run_claim_v1_judgments_sha_check",
						"CHECK ((octet_length(judgments_sha256) = 32))"),
				check("first_run_claim_v1_manifest_bytes_check",
						"CHECK (((manifest_bytes >= 1) AND (manifest_bytes <= 65536)))"),
				check("first_run_claim_v1_manifest_sha_check",
						"CHECK ((octet_length(manifest_sha256) = 32))"),
				new ConstraintContract(
						"first_run_claim_v1_pk", "p", false, false, true, true,
						"PRIMARY KEY (evaluation_protocol_id, policy_id)"),
				check("first_run_claim_v1_policy_check", """
						CHECK ((((octet_length(policy_id) >= 3) AND (octet_length(policy_id) <= 160)) AND (policy_id ~ '^[a-z0-9]+(-[a-z0-9]+)*$'::text)))"""),
				check("first_run_claim_v1_policy_sha_check",
						"CHECK ((octet_length(policy_sha256) = 32))"),
				check("first_run_claim_v1_run_key_check",
						"CHECK ((octet_length(run_key) = 32))"),
				check("first_run_claim_v1_schema_check",
						"CHECK ((schema_version = 1))"),
				check("first_run_claim_v1_synchronous_commit_check",
						"CHECK ((synchronous_commit_setting = 'on'::text))"));
	}

	private static ConstraintContract check(String name, String definition) {
		return new ConstraintContract(
				name, "c", false, false, true, false, definition);
	}

	private static boolean insertClaim(
			Connection connection,
			RelatedTopicReuseHoldoutFirstRunIdentity identity) throws SQLException {
		VerifiedFirstRunCommitment commitment = identity.commitment();
		VerifiedCleanCheckout checkout = identity.checkout();
		try (PreparedStatement statement = connection.prepareStatement(INSERT_SQL)) {
			int index = 0;
			statement.setInt(++index, RelatedTopicReuseHoldoutFirstRunIdentity.SCHEMA_VERSION);
			statement.setString(++index, commitment.evaluationProtocolId());
			statement.setString(++index, commitment.policyId());
			statement.setBytes(++index, identity.runKeyBytes());
			statement.setString(++index, commitment.bundleProtocolId());
			statement.setString(++index, commitment.bundleId());
			statement.setString(++index, commitment.corpusId());
			statement.setBytes(++index, digestBytes(commitment.policySha256()));
			statement.setBytes(++index, digestBytes(commitment.manifestSha256()));
			statement.setLong(++index, commitment.manifestBytes());
			statement.setBytes(++index, digestBytes(commitment.corpusSha256()));
			statement.setLong(++index, commitment.corpusBytes());
			statement.setBytes(++index, digestBytes(commitment.judgmentsSha256()));
			statement.setLong(++index, commitment.judgmentsBytes());
			statement.setInt(++index, checkout.freezeSchemaVersion());
			statement.setString(++index, checkout.inventoryId());
			statement.setString(++index, checkout.evaluatorRevision());
			statement.setBytes(++index, digestBytes(checkout.evaluatorSourceSha256()));
			statement.setString(++index, checkout.candidateRevision());
			statement.setBytes(++index, digestBytes(checkout.candidateSourceSha256()));
			try (ResultSet result = statement.executeQuery()) {
				if (!result.next()) {
					return false;
				}
				if (result.getInt(1) != 1 || result.next()) {
					throw new SQLException("unexpected ledger insert result");
				}
				return true;
			}
		}
	}

	private static byte[] digestBytes(String value) {
		return HexFormat.of().parseHex(value);
	}

	private static void rollbackIfUnambiguous(
			Connection connection, boolean commitAttempted) {
		if (connection == null || commitAttempted) {
			return;
		}
		try {
			connection.rollback();
		}
		catch (SQLException ignored) {
			// The caller still receives a fail-closed exception and no capability.
		}
	}

	private static void closeQuietly(Connection connection) {
		if (connection == null) {
			return;
		}
		try {
			connection.close();
		}
		catch (SQLException ignored) {
			// A successful commit is already final; a failed commit remains ambiguous.
		}
	}

	static final class CommittedFirstRun {

		private final RelatedTopicReuseHoldoutFirstRunIdentity identity;
		private final VerifiedFirstRunCommitment commitment;
		private final VerifiedCleanCheckout checkout;
		private final AtomicBoolean rankingStarted = new AtomicBoolean();
		private final AtomicReference<RelatedTopicReuseHoldoutRankingSnapshot>
				completedRanking = new AtomicReference<>();

		private CommittedFirstRun(
				RelatedTopicReuseHoldoutFirstRunIdentity identity,
				VerifiedFirstRunCommitment commitment,
				VerifiedCleanCheckout checkout) {
			this.identity = Objects.requireNonNull(identity, "identity");
			this.commitment = Objects.requireNonNull(commitment, "commitment");
			this.checkout = Objects.requireNonNull(checkout, "checkout");
			if (this.identity.commitment() != this.commitment
					|| this.identity.checkout() != this.checkout) {
				throw new IllegalArgumentException("committed first-run identity is invalid");
			}
		}

		void consumeForRanking(
				RelatedTopicReuseHoldoutBundle.VerifiedCorpus verifiedCorpus)
				throws LedgerException {
			if (!commitment.authorizes(verifiedCorpus)) {
				throw new ClaimCapabilityException();
			}
			if (!rankingStarted.compareAndSet(false, true)) {
				throw new ClaimCapabilityException();
			}
		}

		FirstRunEvidence bindCompletedRanking(
				RelatedTopicReuseHoldoutBundle.VerifiedCorpus verifiedCorpus,
				RelatedTopicReuseHoldoutRankingSnapshot rankingSnapshot)
				throws LedgerException {
			RelatedTopicReuseHoldoutRankingSnapshot snapshot = Objects.requireNonNull(
					rankingSnapshot, "rankingSnapshot");
			if (!rankingStarted.get()
					|| !commitment.authorizes(verifiedCorpus)
					|| !snapshotMatchesCommitment(snapshot)
					|| !completedRanking.compareAndSet(null, snapshot)) {
				throw new ClaimCapabilityException();
			}
			return new FirstRunEvidence(identity, checkout, snapshot);
		}

		private boolean snapshotMatchesCommitment(
				RelatedTopicReuseHoldoutRankingSnapshot snapshot) {
			return commitment.evaluationProtocolId().equals(
						RelatedTopicReuseHoldoutPolicy.EVALUATION_PROTOCOL_ID)
					&& commitment.bundleId().equals(snapshot.bundleId())
					&& commitment.corpusId().equals(snapshot.corpusId())
					&& commitment.policySha256().equals(snapshot.policySha256())
					&& commitment.manifestSha256().equals(snapshot.manifestSha256())
					&& commitment.corpusSha256().equals(snapshot.corpusSha256())
					&& commitment.judgmentsSha256().equals(snapshot.judgmentsSha256())
					&& commitment.judgmentsBytes() == snapshot.judgmentsBytes()
					&& checkout.candidateRevision().equals(snapshot.candidateRevision());
		}

		String runKey() {
			return identity.runKey();
		}

		RelatedTopicReuseHoldoutFirstRunIdentity.FinalityKey finalityKey() {
			return identity.finalityKey();
		}

		boolean externalBundleAcceptanceAuthorized() {
			return false;
		}

		boolean custodyReleaseAuthorized() {
			return false;
		}

		boolean productActivationAuthorized() {
			return false;
		}
	}

	/**
	 * Opaque evidence that one exact ranking snapshot followed the acknowledged
	 * durable claim made from the collector-verified checkout.
	 */
	static final class FirstRunEvidence {

		private final String runKey;
		private final String evaluationProtocolId;
		private final String policyId;
		private final int freezeSchemaVersion;
		private final String inventoryId;
		private final RelatedTopicReuseHoldoutEvaluatorSeal.VerifiedEvaluatorSeal
				evaluatorSeal;
		private final RelatedTopicReuseHoldoutRankingSnapshot rankingSnapshot;

		private FirstRunEvidence(
				RelatedTopicReuseHoldoutFirstRunIdentity identity,
				VerifiedCleanCheckout checkout,
				RelatedTopicReuseHoldoutRankingSnapshot rankingSnapshot) {
			this(evidenceSeed(identity, checkout), rankingSnapshot);
		}

		private FirstRunEvidence(
				EvidenceSeed seed,
				RelatedTopicReuseHoldoutRankingSnapshot rankingSnapshot) {
			this(
					seed.runKey(),
					seed.evaluationProtocolId(),
					seed.policyId(),
					seed.freezeSchemaVersion(),
					seed.inventoryId(),
					seed.evaluatorSeal(),
					rankingSnapshot);
		}

		private FirstRunEvidence(
				String runKey,
				String evaluationProtocolId,
				String policyId,
				int freezeSchemaVersion,
				String inventoryId,
				RelatedTopicReuseHoldoutEvaluatorSeal.VerifiedEvaluatorSeal evaluatorSeal,
				RelatedTopicReuseHoldoutRankingSnapshot rankingSnapshot) {
			if (runKey == null
					|| !runKey.matches("[0-9a-f]{64}")
					|| !RelatedTopicReuseHoldoutPolicy.EVALUATION_PROTOCOL_ID.equals(
							evaluationProtocolId)
					|| !RelatedTopicReuseHoldoutPolicy.POLICY_ID.equals(policyId)
					|| freezeSchemaVersion
							!= RelatedTopicReuseHoldoutGitCollector.FREEZE_SCHEMA_VERSION
					|| !RelatedTopicReuseHoldoutGitCollector.INVENTORY_ID.equals(inventoryId)) {
				throw new IllegalArgumentException("invalid first-run evidence");
			}
			RelatedTopicReuseHoldoutEvaluatorSeal.VerifiedEvaluatorSeal frozenSeal =
					Objects.requireNonNull(evaluatorSeal, "evaluatorSeal");
			RelatedTopicReuseHoldoutRankingSnapshot frozenSnapshot =
					Objects.requireNonNull(rankingSnapshot, "rankingSnapshot");
			if (!frozenSeal.candidateRevision().equals(
					frozenSnapshot.candidateRevision())) {
				throw new IllegalArgumentException(
						"evaluator seal and ranking snapshot candidate revisions must match");
			}
			if (frozenSeal.externalBundleAcceptanceAuthorized()
					|| frozenSeal.custodyReleaseAuthorized()) {
				throw new IllegalArgumentException("invalid first-run evidence");
			}
			this.runKey = runKey;
			this.evaluationProtocolId = evaluationProtocolId;
			this.policyId = policyId;
			this.freezeSchemaVersion = freezeSchemaVersion;
			this.inventoryId = inventoryId;
			this.evaluatorSeal = frozenSeal;
			this.rankingSnapshot = frozenSnapshot;
		}

		private static EvidenceSeed evidenceSeed(
				RelatedTopicReuseHoldoutFirstRunIdentity identity,
				VerifiedCleanCheckout checkout) {
			RelatedTopicReuseHoldoutFirstRunIdentity frozenIdentity =
					Objects.requireNonNull(identity, "identity");
			VerifiedCleanCheckout frozenCheckout = Objects.requireNonNull(
					checkout, "checkout");
			if (frozenIdentity.checkout() != frozenCheckout) {
				throw new IllegalArgumentException("invalid first-run evidence");
			}
			RelatedTopicReuseHoldoutFirstRunIdentity.FinalityKey finalityKey =
					frozenIdentity.finalityKey();
			return new EvidenceSeed(
					frozenIdentity.runKey(),
					finalityKey.evaluationProtocolId(),
					finalityKey.policyId(),
					frozenCheckout.freezeSchemaVersion(),
					frozenCheckout.inventoryId(),
					frozenCheckout.evaluatorSeal());
		}

		String runKey() {
			return runKey;
		}

		String evaluationProtocolId() {
			return evaluationProtocolId;
		}

		String policyId() {
			return policyId;
		}

		int freezeSchemaVersion() {
			return freezeSchemaVersion;
		}

		String inventoryId() {
			return inventoryId;
		}

		RelatedTopicReuseHoldoutEvaluatorSeal.VerifiedEvaluatorSeal evaluatorSeal() {
			return evaluatorSeal;
		}

		boolean authorizes(RelatedTopicReuseHoldoutRankingSnapshot snapshot) {
			return snapshot != null && rankingSnapshot == snapshot;
		}

		boolean externalBundleAcceptanceAuthorized() {
			return false;
		}

		boolean custodyReleaseAuthorized() {
			return false;
		}

		boolean productActivationAuthorized() {
			return false;
		}

		private record EvidenceSeed(
				String runKey,
				String evaluationProtocolId,
				String policyId,
				int freezeSchemaVersion,
				String inventoryId,
				RelatedTopicReuseHoldoutEvaluatorSeal.VerifiedEvaluatorSeal evaluatorSeal) {
		}
	}

	static class LedgerException extends Exception {

		private LedgerException(String diagnostic) {
			super(diagnostic);
		}
	}

	static final class InvalidClaimException extends LedgerException {

		private InvalidClaimException() {
			super("HOLDOUT_LEDGER_CLAIM_INVALID");
		}
	}

	static final class ContractException extends LedgerException {

		private ContractException() {
			super("HOLDOUT_LEDGER_DATABASE_CONTRACT_INVALID");
		}
	}

	static final class AlreadyClaimedException extends LedgerException {

		private AlreadyClaimedException() {
			super("HOLDOUT_LEDGER_FIRST_RUN_ALREADY_CLAIMED");
		}
	}

	static final class LedgerUnavailableException extends LedgerException {

		private LedgerUnavailableException() {
			super("HOLDOUT_LEDGER_UNAVAILABLE");
		}
	}

	static final class CommitOutcomeUnknownException extends LedgerException {

		private CommitOutcomeUnknownException() {
			super("HOLDOUT_LEDGER_COMMIT_OUTCOME_UNKNOWN");
		}
	}

	static final class ClaimCapabilityException extends LedgerException {

		private ClaimCapabilityException() {
			super("HOLDOUT_LEDGER_CLAIM_CAPABILITY_INVALID");
		}
	}

	private record ColumnContract(
			String name,
			String type,
			boolean notNull,
			String collation,
			String defaultExpression) {
	}

	private record ConstraintContract(
			String name,
			String type,
			boolean deferrable,
			boolean initiallyDeferred,
			boolean validated,
			boolean noInherit,
			String definition) {
	}

	private record RoleContract(String name, boolean login) {
	}

	@FunctionalInterface
	private interface ClaimConnectionSource {

		Connection open() throws SQLException;
	}

	private record AclContract(
			String scope,
			String objectName,
			String role,
			String privilege,
			boolean grantable) {
	}
}
