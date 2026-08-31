# Related-topic reuse holdout operator runbook

## Status and authority

This runbook defines the production provisioning and execution contract for the
future related-topic reuse blind-holdout operator. It supplements the
[preregistered protocol](RELATED_TOPIC_REUSE_HOLDOUT_PROTOCOL.md); it does not
replace that protocol or relax any gate in it.

The repository now contains a package-private, in-memory,
filesystem-write-free `RelatedTopicReuseHoldoutOperatorWorkflow`. It composes
clean-checkout collection, staged bundle intake, the durable claim, ranking,
post-ranking judgment verification, scoring, schema-v2 evidence creation, and
exact in-memory verification, then returns an opaque non-authorizing
`PendingPublication`. A separate package-private
`RelatedTopicReuseHoldoutPostgresTlsConnectionFactory` now implements the local
typed runtime-file, authenticated PostgreSQL TLS, read-only preflight, and
single-use Phase-B connection boundary described below. Neither utility builds or
isolates the evaluator, provisions infrastructure, or publishes files.

This repository still has no supported live operator command, frozen runnable
evaluator, custody-authorized publisher, provisioned target ledger, or real
external bundle. The workflow, connection factory, ledger, collector, ranker,
scorer, and report classes remain package-private test utilities. Therefore:

- this document does **not** authorize a custodian to release a real bundle;
- the provisioning steps below do **not** make the in-memory workflow a live
  operator;
- an administrator or operator must not assemble an ad hoc command from test
  methods; and
- a passing future holdout would still not authorize product activation.

The first real run remains unauthorized until the complete live operator and its
supporting controls are committed and reviewed, its exact revision and source
inventory are frozen outside this repository, the controls in this runbook are
automated or independently attested, and the custodian explicitly authorizes
release.

## Required separation

Use a dedicated PostgreSQL cluster for the first-run ledger. A separate database
inside the application cluster is not sufficient: PostgreSQL login roles are
cluster-wide, shared administrators can alter either database, and a new or
overlooked database can reintroduce `CONNECT` access. The dedicated cluster must:

- contain no OpenScholar application database or application role;
- have a separate network boundary, persistent storage, backup policy, and
  administrator credential from the application stack;
- be unreachable from the ordinary backend, frontend, workers, and provider
  clients;
- expose only the fixed ledger endpoint to the future evaluator and auditor;
- run one exact, reviewed PostgreSQL 17 server image or build; and
- not be the ephemeral Testcontainers database or the database in
  `deploy/compose.production.yaml`.

If Docker is used, deploy the ledger as a separate project with its own network
and persistent volume. Do not join it to the application network, mount the
Docker socket into it or the evaluator, or reuse application database secrets.
An image digest proves which image was selected; it does not prove administrator,
storage, host-kernel, or runtime integrity.

The fixed database and object identities are:

| Object | Required identity |
| --- | --- |
| Database | `openscholar_holdout_ledger_v1` |
| Ledger schema | `holdout_ledger_v1` |
| Claim table | `first_run_claim_v1` |
| Flyway history schema | `holdout_ledger_migrations_v1` |
| Table owner | `openscholar_holdout_ledger_owner_v1` |
| Evaluator login | `openscholar_holdout_ledger_runtime_v1` |
| Audit login | `openscholar_holdout_ledger_auditor_v1` |
| Provisioning principal | `openscholar_holdout_ledger_bootstrap_v1` |

Do not point the evaluator at an alias that can resolve to multiple unreviewed
servers. The approved DNS name, port, database, server build, CA digest, and
certificate identity form one endpoint-schema-v2 record retained outside the
repository. Its certificate identity is the lowercase 64-hex SHA-256 of the exact
leaf X.509 certificate's DER encoding.

## Responsibilities and four-role boundary

Separate the following responsibilities. One person may fill multiple roles only
when the external review policy explicitly accepts that reduced independence.

| Responsibility | Permitted authority | Prohibited during a run |
| --- | --- | --- |
| Cluster administrator | Create and secure the cluster, roles, database, TLS, storage, and backups | Supplying evaluator inputs or changing the ledger after approval |
| Holdout custodian | Retain the bundle and external freeze record; authorize the one release | Database administration, evaluator builds, or viewing candidate output before release |
| Evaluator operator | Run the reviewed preflight and, later, the one-shot operator | Bootstrap, owner, auditor, application, provider, or general host-admin credentials |
| Auditor/reviewer | Read the ledger and review retained evidence | Inserting, updating, deleting, truncating, resetting, or retrying a claim |

Provision exactly four named ledger roles:

1. `openscholar_holdout_ledger_bootstrap_v1` is the administrator-managed
   provisioning principal. It may receive narrowly scoped, temporary authority
   during an approved maintenance window. At every evaluator preflight it must be
   `NOLOGIN`, have no password, hold no membership in or over any of the other
   three roles, and be absent from the evaluator environment. A separate
   break-glass cluster administrator may re-enable it for a reviewed future
   migration.
2. `openscholar_holdout_ledger_owner_v1` is `NOLOGIN NOINHERIT`, owns the fixed
   database, ledger schema, and table, and has no role memberships in either
   direction. Its authority is never available to the evaluator.
3. `openscholar_holdout_ledger_runtime_v1` is `LOGIN NOINHERIT`, has no elevated
   role attributes or memberships, can connect only to the ledger database, and
   receives only schema `USAGE` plus column-level `INSERT` on the exact columns
   granted by the migration. It has no table-level `INSERT`, reads, mutation,
   truncation, references, triggers, maintenance, database/schema creation, or
   temporary-object privilege.
4. `openscholar_holdout_ledger_auditor_v1` is `LOGIN NOINHERIT`, has no elevated
   role attributes or memberships, can connect only to the ledger database, and
   receives only schema `USAGE` plus `SELECT` on the claim table. Its credential
   is never available to the evaluator.

All three permanent ledger roles must be `NOSUPERUSER NOCREATEDB NOCREATEROLE
NOREPLICATION NOBYPASSRLS`, have connection limit `-1`, and have no membership in
or over any role. The bootstrap role must also have no residual elevated attribute
or membership outside an approved maintenance window. Do not grant one role to
another as a convenience. Any temporary bootstrap-to-owner ability needed to run
the migration must be revoked before preflight.

## Administrator-managed prerequisites

The cluster administrator must complete and independently record these controls
before the custodian is asked to expose a bundle:

1. Install the exact approved PostgreSQL 17 image digest or server-package build.
   Record the immutable artifact identity and complete `server_version` output.
   The current ledger verifies PostgreSQL major version 17 and exact catalog
   rendering; it does not independently identify the image or patch build.
2. Put the cluster on separately governed persistent storage. Enable `fsync` and
   require synchronous commit. Do not use an ephemeral container filesystem.
3. Configure the approved TLS endpoint and host firewall. Permit only the
   evaluator runtime role and auditor role from their fixed sources. Administrative
   access must use a separate path.
4. Create the four roles and fixed database. Set the database owner to the
   no-login owner role. Provision runtime and auditor credentials through the
   approved secret manager; never embed password literals in checked-in SQL,
   shell history, command arguments, Compose metadata, or logs.
5. Revoke all `PUBLIC` privileges on the ledger database and its `public` schema.
   Prohibit database `CREATE` and `TEMPORARY` for runtime and auditor.
6. Revoke runtime and auditor `CONNECT`, `CREATE`, and `TEMPORARY` on **every
   other database in the cluster**, including `postgres` and `template1`.
   `template0` must remain non-connectable. Prohibit creation of another database
   without a new access review and revocation pass.
7. Apply only the isolated forward migration described below, validate its
   checksum and resulting catalog, revoke temporary provisioning authority, and
   disable the bootstrap login.
8. Establish external audit, backup, retention, and incident procedures. A
   database owner, superuser, host administrator, or storage administrator can
   still alter or remove a claim; SQL grants do not create immutable evidence.

The final access review must enumerate every database rather than assuming that a
database name list is complete. From an administrative connection, review the
equivalent of:

```sql
SELECT datname,
       datallowconn,
       has_database_privilege(
           'openscholar_holdout_ledger_runtime_v1', oid, 'CONNECT') AS runtime_connect,
       has_database_privilege(
           'openscholar_holdout_ledger_auditor_v1', oid, 'CONNECT') AS auditor_connect
FROM pg_catalog.pg_database
ORDER BY datname;
```

The two privilege columns must be true only for
`openscholar_holdout_ledger_v1`. They must be false for every other database,
whether or not `datallowconn` is currently true. Also inspect explicit ACL rows,
role attributes, role memberships, per-role/per-database settings, `pg_hba.conf`,
and the fixed database, schema, and table owners. Store sanitized query results
with the approval record; do not store passwords or connection strings.

The package-private TLS connection factory's read-only preflight now enumerates
exactly `openscholar_holdout_ledger_v1`, `postgres`, `template0`, and `template1`;
requires runtime and auditor `CONNECT` only on the ledger database; rejects their
`CREATE` or `TEMPORARY` privilege on every database; and requires `template0` to
remain non-connectable. It also checks the four fixed role identities, disabled
bootstrap login, role attributes, zero memberships in either direction, and no
per-role, per-database, or database-wide default settings. These runtime-visible
catalog checks do not provision or revoke anything and do not prove
`pg_hba.conf`, firewall, DNS, administrator, host, or storage controls. The
administrator remains responsible for those controls and for independently
retaining the sanitized access review.

## TLS and server identity

Plaintext database traffic and unauthenticated encryption are prohibited. The
package-private connection factory now accepts only a closed typed endpoint
record and closed runtime-file record—never a caller-supplied JDBC URL, query
parameters, role, socket, pool, or SSL implementation—and configures pgJDBC
42.7.12 to enforce:

- `sslmode=verify-full`;
- an absolute, canonical, non-symlink CA file whose SHA-256 equals the externally
  approved endpoint record;
- a server certificate that chains to that CA and whose subject alternative name
  matches the exact configured DNS name; the current typed profile does not accept
  an IP literal as its server name;
- an endpoint-schema-v2 leaf pin equal to the lowercase 64-hex SHA-256 of that
  exact leaf certificate's DER encoding;
- no user-supplied JDBC query parameters, trust-all manager, `sslmode=require`,
  fallback to plaintext, or alternate Unix socket;
- direct PostgreSQL 17 TLS through the fixed LibPQ SSL factory, preserving normal
  PKIX path validation, and a fixed verifier that accepts only the exact pinned
  leaf and exact DNS subject alternative name—never CN fallback or a wildcard—plus
  SCRAM-SHA-256 authentication only and required channel binding;
- no ambient client certificate, private key, GSS, JAAS, or SPNEGO credential;
- separately administered `hostssl` rules restricted to the fixed database and
  two login roles; and
- an observed encrypted session, checked through the current session's
  `pg_stat_ssl` row, in addition to driver certificate verification.

The Phase-A preflight validates the exact server address, port, complete server
version, TLS protocol, cipher and bit count from the same connection, along with
the fixed database, runtime role, and application name. The current endpoint
record accepts a canonical IPv4 literal for that expected server address; it does
not accept IPv6 or a second address. `pg_stat_ssl` and catalog values are
supplementary evidence. They do not replace the factory's CA and hostname
verification, and the database name returned by the server does not authenticate
the network endpoint.

Endpoint schema v2 binds the canonical CA digest, the lowercase DER leaf SHA-256,
and the observed TLS properties. Normal PKIX validation, exact DNS SAN validation,
and the exact leaf pin must all pass. The explicit-only
`scripts/test-related-topic-reuse-holdout-tls.sh` harness exercises this boundary
against disposable real PostgreSQL 17 TLS and plaintext containers,
runtime-generated certificates, Linux POSIX owners and modes, and the fixed
catalog on an internal test network. Its seven tests cover successful Phase A and
single-use Phase B, a real synthetic ledger claim with durable replay rejection,
then reject an untrusted CA, DNS SAN mismatch, wrong SCRAM password, plaintext
downgrade, and wrong leaf pin. That local proof is not
evidence that a production endpoint, `pg_hba.conf`, firewall, DNS route,
administrator, storage boundary, or target deployment satisfies this section.

The pin assumes that endpoint schema v2 and its runtime configuration are trusted
launcher inputs retained through an independent control. An attacker able to
replace both can substitute a different endpoint and matching pin together; the
digest is an integrity binding, not an authority or signature.

Record the lowercase DER leaf SHA-256, issuer, subject alternative name, validity
interval, CA digest, endpoint, and exact server build before release. Certificate
rotation changes the approved schema-v2 endpoint record and leaf pin and requires
another preflight; the schema version itself remains v2. Never weaken verification
to work around an expired or mismatched certificate.

## Secret-file contract

Store each database password and every private client key in a separate absolute
regular file inside an operator-owned `0700` directory. Each secret file must:

- be mode `0400`, owned by the exact OS account for its bounded phase, and have
  exactly one hard link where the platform exposes link counts;
- not be a symbolic link, repository file, environment file, command argument,
  image layer, broad temporary file, or Compose interpolation value;
- be opened only by the process that needs that role and never printed in a
  diagnostic, exception, process listing, or retained evidence; and
- be unmounted or made inaccessible immediately after its bounded phase.

The migration credential is available only during migration. The runtime
credential is available only to the evaluator's ledger connection. The auditor
credential is available only to the independent audit step. The no-login owner
and disabled bootstrap role have no runtime password file.

The CA certificate is public rather than secret, but its file must be immutable to
the evaluator and checked against the externally retained digest. A private mTLS
key, when used, follows the `0400` secret-file contract.

The local factory implements this contract for its runtime password: both the
`0700` parent and `0400` file must have the expected evaluator owner, the file must
have one hard link, and the canonical absolute password and CA paths must be
outside the repository. The CA parent and file must have a separate expected
non-evaluator owner, must reject group/other writes, and every ancestor must reject
group/other writes plus evaluator-owned owner-write access. The factory rejects
symlinks and file changes between Phase A and Phase B and checks the CA digest in
constant time. It binds password content with fresh per-instance keyed bytes rather
than retaining a password SHA-256 string, then wipes that binding when the Phase-B
connection is consumed. It rejects non-empty ACLs when Java exposes an
`AclFileAttributeView` and requires the CA plus every ancestor to be non-writable
by the evaluator process.

These pathname checks are repeated but are not descriptor-relative opens and do
not eliminate replacement races by the CA owner, a host administrator, or another
authority able to mutate an ancestor. Provisioning and custody must prevent that
pathname TOCTOU; the implementation does not issue, mount, rotate, or remove
either file.

The Java boundary compares trusted expected-owner names with the names reported by
the filesystem. It does not bind the evaluator name to the process's numeric
effective UID, reject a UID-0 evaluator, or detect multiple names for one UID. The
future launcher must verify and record numeric UIDs, reject root execution, and
then provide the reviewed names; these are external preconditions, not claims made
by the current factory.

The launcher must also use native target-filesystem tooling to prove that the CA,
its ancestry, and the password path have no extra POSIX/NFSv4 ACL grants. Java does
not expose every Unix ACL implementation, so an absent Java ACL view is not proof
of ACL absence or of password confidentiality from other principals.

The evaluator environment must not contain application database credentials,
provider API keys, OIDC credentials, the local MCP key, registry credentials,
cloud credentials, signing keys, SSH agents, Git credential helpers, or a Docker
socket. Use an exact environment allowlist rather than deleting a few known names.

## Forward-only ledger migration

The checked-in migration is
[`V1__create_related_topic_reuse_first_run_ledger.sql`](../backend/src/test/resources/db/holdout-ledger/V1__create_related_topic_reuse_first_run_ledger.sql).
It assumes that the database and three permanent ledger roles already exist. It
does not create the cluster, database, roles, TLS configuration, storage, secrets,
or cross-database revocations.

Apply it as an isolated Flyway migration, never through application Flyway, with:

- location `classpath:db/holdout-ledger`;
- default/history schema `holdout_ledger_migrations_v1`;
- history table `flyway_schema_history`;
- validation enabled before migration;
- baseline-on-migrate disabled; and
- clean and repair disabled.

Run migration before any real bundle is mounted. The migration principal may
receive temporary ability to `SET ROLE` to the no-login owner because the
migration deliberately creates the ledger objects under that owner. Revoke that
ability afterward, disable the bootstrap principal, and prove that all permanent
roles again have zero memberships in either direction.

The first migration invocation must report exactly one applied migration. An
immediate validation/re-run must report zero new migrations and the expected
checksum. Never edit an applied migration, run Flyway `clean` or `repair`, change
the history row, or perform a down migration. A schema change requires a new
forward migration, full review of the catalog contract, and a newly frozen
evaluator revision.

The runtime and auditor must have no access to the Flyway history schema. After
migration, connect separately as runtime and auditor and prove the access matrix;
do not infer it from administrator access.

## Exact evaluator and toolchain

The evaluator must be built from a standalone clean clone at the exact externally
retained evaluator revision. The external freeze record must bind the source
inventory identity, evaluator revision and source digest, and frozen candidate
revision and source digest described by the protocol.

Before a custodian releases a bundle, pin and record:

- the canonical absolute Git executable, its SHA-256, and complete version;
- the exact JDK 21 distribution or build-image digest;
- the Maven Wrapper distribution URL and SHA-256 from
  [`maven-wrapper.properties`](../backend/.mvn/wrapper/maven-wrapper.properties);
- the Maven settings, toolchains, mirrors, repositories, and dependency-cache
  manifest used for the build;
- every build/runtime container digest and platform; and
- the exact sanitized environment, locale, timezone, network policy, and command.

The JDK image currently pinned in the [backend Dockerfile](../backend/Dockerfile)
and the PostgreSQL image recorded in
[`production-images.lock`](../deploy/production-images.lock) are useful reviewed
identities, not automatic approval to reuse the application deployment or its
Docker build cache. The final operator must explicitly freeze the identities it
uses.

The existing [`verify-clean-clone.sh`](../scripts/verify-clean-clone.sh) is a
general repository verification helper. It permits a host Java version newer than
21, downloads dependencies, can reuse Docker layers, and uses the local Docker
socket. It is not the holdout operator and is not sufficient evidence for a real
run.

Use a disposable `HOME`, temporary directory, and Maven repository with no ambient
settings, toolchains, proxies, credentials, or hooks. Resolve and verify all
dependencies before bundle release; then make the approved source, build output,
and dependency repository read-only and disable network access. Reverify the clean
checkout and built-artifact digest immediately before the run. A source digest
does not by itself prove that the executed bytecode came from that source.

## Bounded database behavior

The initial operator profile must use a simple one-shot data source, or a pool
with maximum size one and no background reconnect. It must set and test these
upper bounds before claim:

| Boundary | Maximum |
| --- | ---: |
| Login/data-source acquisition | 10 seconds |
| TCP/TLS connection establishment | 10 seconds |
| Socket read | 15 seconds |
| JDBC network timeout after acquisition | 15 seconds |
| Ledger lock wait | 5 seconds |
| Ledger statement | 10 seconds |
| Idle ledger transaction | 15 seconds |

The factory configures a simple pgJDBC source with 10-second login and connection
timeouts, a 10-second TLS-response timeout, and a 15-second socket timeout. Its
read-only Phase-A connection and the exact Phase-B connection both set and verify
the 15-second JDBC network timeout; the ledger independently sets it again before
the claim and applies the lock, statement, and idle-transaction bounds. The
10-second statement bound is server-enforced through fixed startup options,
verified during preflight, and reasserted transaction-locally. JDBC client query
timeouts remain zero: pgJDBC's cancellation mechanism opens a second plaintext
CancelRequest socket, which this boundary prohibits. The factory returns a ledger
already bound to its private one-use connection source.
The first claim can open exactly one Phase-B connection, revalidates the CA and
password files first, and preflights that same connection before handing it to the
ledger claim path. The source is not returned to an ordinary caller.

The explicit-only
`scripts/test-related-topic-reuse-holdout-tls-timeouts.sh` harness now exercises
four real local deadline paths: a saturated TCP accept queue, a direct-TLS
downstream stall, a downstream stall after the exact Phase-B connection has been
verified, and server cancellation of `pg_sleep(30)`. It checks the applicable
10- and 15-second stage bounds, the socket-timeout cause or `57014` SQL state, and
absence of a reconnect. The pinned runner's fixed 180-second one-shot watchdog
terminates the explicit test process rather than restarting it. Equal login and connect bounds mean the first test proves
bounded acquisition rather than attributing one specific pgJDBC timer. DNS and
process startup still require an external hard deadline, and this in-container
test watchdog is not production process-supervisor evidence. Expiry after a claim attempt
remains a consumed or ambiguous run; a timeout is never permission to retry.

Disable driver, pool, orchestration, shell, and service-manager retry. Health
checks may observe readiness before the bundle is mounted, but they must not issue
a claim or silently replace the server during an evaluation.

## Required ordering

The future live operator must enforce two phases with an OS-level boundary. A
written checklist or operator memory is not enough.

The local factory models this order by returning an in-memory ledger bound to a
private one-use connection source, but it does not itself create the required
OS/process separation. A future live runner must preserve the fail-closed
Phase-A-to-Phase-B binding without treating the package-private object as
isolation evidence.

### Phase A: before holdout mount

1. Start from the exact standalone clone and verify the external freeze record.
2. Verify Git, JDK, Maven, dependency, container, environment, and server-build
   identities.
3. Resolve dependencies, compile the evaluator, run the approved non-holdout test
   gate, and retain the built-artifact digest.
4. Apply and validate the forward-only ledger migration if it was not already
   applied in the approved maintenance window.
5. Run the factory's read-only Phase-A preflight to verify the configured TLS
   session, endpoint and server identity, exact database inventory, cross-database
   runtime/auditor privileges, disabled bootstrap, fixed role memberships and
   settings, and configured connection bounds. Independently verify the migration,
   `pg_hba.conf`, firewall, the independently retained endpoint-schema-v2 leaf
   identity, storage, administrator controls, and real timeout behavior that the
   factory cannot prove.
6. Remove bootstrap, owner, auditor, application, provider, network, registry,
   build-service, and Docker authorities from the evaluator environment.
7. Make the verified clone, artifact, dependency cache, and configuration
   read-only. Disable external network access.

Any failure in Phase A occurs before custody release and may be corrected and
rechecked without consuming the holdout, provided no bundle or judgment
commitment was exposed to the evaluator environment.

### Phase B: after custodian release

1. The custodian exposes one approved external bundle through a read-only,
   absolute, non-repository mount. Do not copy it into the clone, build context,
   cache, image, or ordinary temporary directory.
2. Reverify the frozen checkout, artifact, environment, endpoint record, runtime
   files, and read-only bundle layout. Use only the bound ledger returned by Phase
   A; its first claim opens the sole Phase-B connection, revalidates the files, and
   preflights that exact connection before entering the claim transaction. Perform
   staged label-free corpus intake; do not open judgments before rankings are
   frozen.
3. Derive the exact first-run identity and execute the single durable ledger
   claim with that runtime connection.
4. Only after an acknowledged commit returns the opaque claim capability, consume
   that capability and invoke the fixed ranking callback immediately.
5. Freeze the ranking snapshot, then perform the post-ranking manifest/corpus
   recheck, open and validate judgments, score once, and construct canonical
   evidence in the mandatory in-process order.
6. Transfer evidence only through the separately reviewed custody/publisher
   boundary. The repository currently has a read-only retained-bundle verifier but
   no native exclusive publisher that authorizes this step.

Do not build, download, migrate, rotate a certificate, change a role, switch an
endpoint, restart the database, or run a fallible general-purpose setup step after
the bundle is mounted. No ranking callback may receive the external bundle path,
judgments, staged coordinator object, application database, or provider access.

## No-retry and failure rules

There is exactly one eligible attempt for the policy version. Do not wrap the
operator in a retry loop, CI rerun, service restart policy, queue, scheduler, or
automatic failover mechanism.

| Observed point of failure | Required response |
| --- | --- |
| Phase A, before bundle release | Correct the environment and repeat the complete preflight. |
| After bundle release but before claim | Stop, preserve evidence, revoke access, and refer the event to custodian/reviewer. Do not automatically rerun. |
| Ledger reports already claimed | Stop. Do not alter, delete, reset, restore, or replace the ledger. |
| Commit acknowledgment is lost or outcome is unknown | Treat the run as consumed/ambiguous. Stop and never retry the claim automatically. Auditor inspection is evidence for incident review, not a new-attempt authorization. |
| Commit is acknowledged and any later step fails | The first run is final and failed. Preserve the claim and available evidence; do not rerank or rescore the same holdout. |
| Ranking/scoring completes | Preserve exact evidence. A pass still grants no custody-release, reader-exposure, or product-activation authority. |

Once a real bundle has been released, every abnormal exit is an incident rather
than permission to improvise. Only an independently reviewed, newly versioned
policy and genuinely new holdout can establish another blind evaluation after a
consumed or compromised run.

Never restore the ledger to a point before an accepted or possibly accepted
claim, truncate/delete/update its table, edit its primary key, change clocks to
manufacture another attempt, or replace the database with an empty copy. Restore
drills must use isolated copies and must never become the live finality endpoint.

## Automation boundary

The future reviewed operator may safely automate fail-closed operations whose
inputs and outputs are exact:

- environment and secret-file validation;
- immutable toolchain, checkout, source, artifact, dependency, image, CA, and
  server-build digest checks;
- TLS peer, database inventory, role, membership, ACL, migration, storage-setting,
  and timeout preflight;
- a forward-only migration against the fixed database using the separately
  mounted migration credential;
- removal of the migration authority before the evaluator phase;
- one invocation of the supported claim-to-ranking-to-scoring call graph; and
- bounded, sanitized evidence projection and exact verification.

Keep these controls administrator-managed or external to the evaluator:

- cluster/database creation and deletion;
- role and password issuance, rotation, revocation, and recovery;
- CA/certificate issuance and DNS/firewall changes;
- storage, backup, restore, WORM retention, and administrator separation;
- holdout authorship, confidentiality, access history, and custody release;
- freeze-record custody, reviewer identity, signatures, and trusted timestamps;
  and
- approval of a new policy/bundle after any failed or compromised run.

Automation must stop on an unknown field, extra database, extra privilege, role
membership, TLS mismatch, build drift, timeout drift, migration drift, dirty
checkout, existing claim, or ambiguous commit. It must never “repair” those
conditions automatically.

## Go/no-go record

Before release, two reviewers should sign a sanitized record containing at least:

- protocol and policy IDs;
- evaluator and candidate revisions, inventory identity, and source digests;
- Git, JDK, Maven, dependency-cache, build-artifact, and container identities;
- exact PostgreSQL image/build, endpoint, CA, and server-certificate identities;
- migration version and checksum;
- the complete database inventory and role/ACL preflight result;
- timeout-test results;
- confirmation that bootstrap is disabled and all non-runtime credentials are
  absent from the evaluator;
- confirmation that the ledger uses separately governed persistent storage;
- the approved operator command/artifact digest and its no-retry supervisor
  policy; and
- custodian authorization tied to the exact external freeze record.

After execution, retain the claim audit, canonical artifacts, exact exit state,
and incident record outside the repository according to the approved retention
policy. Do not retain raw passwords, private keys, JDBC URLs containing secrets,
absolute bundle paths, authorization headers, or unapproved copies of the holdout.
SHA-256 commitments detect byte changes; they are not signatures, trusted time,
or proof of independent authorship.

## Implementation references and limits

- [Related-topic reuse holdout protocol](RELATED_TOPIC_REUSE_HOLDOUT_PROTOCOL.md)
  defines the frozen schema, ordering, gates, and current non-eligibility boundary.
- [`RelatedTopicReuseHoldoutGitCollector`](../backend/src/test/java/com/openscholar/search/internal/persistence/RelatedTopicReuseHoldoutGitCollector.java)
  verifies the bounded clean-checkout/source contract but does not authenticate
  Git or the build toolchain.
- [`RelatedTopicReuseHoldoutBundle`](../backend/src/test/java/com/openscholar/search/internal/persistence/RelatedTopicReuseHoldoutBundle.java)
  implements staged corpus intake and private judgment release mechanics, not an
  OS isolation boundary.
- [`RelatedTopicReuseHoldoutPostgresFirstRunLedger`](../backend/src/test/java/com/openscholar/search/internal/persistence/RelatedTopicReuseHoldoutPostgresFirstRunLedger.java)
  verifies and commits the runtime append-only claim. The ordinary factory API
  returns a ledger already bound to its private verified source; the raw
  `DataSource` constructor and direct source seam are reached only by explicit
  reflection-based mechanics fixtures.
- [`RelatedTopicReuseHoldoutPostgresTlsConnectionFactory`](../backend/src/test/java/com/openscholar/search/internal/persistence/RelatedTopicReuseHoldoutPostgresTlsConnectionFactory.java)
  implements closed typed endpoint/runtime-file intake, pgJDBC `verify-full`,
  normal PKIX plus exact DNS SAN and endpoint-schema-v2 leaf-pin validation,
  runtime-visible Phase-A catalog and TLS preflight, and a returned ledger bound
  to one revalidated Phase-B connection. Its pathname checks retain the documented
  TOCTOU/administrator trust boundary. It does not provision a cluster, inspect
  `pg_hba.conf`, prove firewall/DNS/storage/administrator integrity, impose a
  process-supervisor deadline, or supply production target evidence.
- [`test-related-topic-reuse-holdout-tls.sh`](../scripts/test-related-topic-reuse-holdout-tls.sh)
  composes a disposable pinned PostgreSQL TLS target, a reachable plaintext
  negative target, generated materials, test-only ledger provisioning, and a
  non-root explicit Maven `*IT` runner. Its seven tests include successful
  preflight/single-use access, a real synthetic claim with durable replay
  rejection, and untrusted-CA, SAN-mismatch, wrong-SCRAM, plaintext-downgrade,
  and wrong-leaf-pin rejection. It publishes no database port and removes generated volumes after
  the run. It is local integration evidence only: it is not a live evaluator,
  supported deployment, reusable credential provisioner, persistent ledger,
  firewall/DNS attestation, or custody boundary.
- [`test-related-topic-reuse-holdout-tls-timeouts.sh`](../scripts/test-related-topic-reuse-holdout-tls-timeouts.sh)
  overlays that disposable target with a fixed-address non-root runner and four
  in-JVM transport fault tests. It proves bounded local TCP acquisition, TLS
  handshake, verified-connection read, and server-statement failure without a
  retry, under a fixed 180-second test-process watchdog; it is not an external
  supervisor, production network test, or target
  deployment attestation.
- [`RelatedTopicReuseHoldoutOperatorWorkflow`](../backend/src/test/java/com/openscholar/search/internal/persistence/RelatedTopicReuseHoldoutOperatorWorkflow.java)
  preserves the supported claim-to-evidence call graph and failure states in
  memory without publishing files; it is package-private and supplies no live
  launcher, build isolation, provisioning, or custody boundary.
- [`RelatedTopicReuseHoldoutEvidenceReport`](../backend/src/test/java/com/openscholar/search/internal/persistence/RelatedTopicReuseHoldoutEvidenceReport.java)
  constructs schema-v2 canonical in-memory artifacts only from the opaque durable
  first-run evidence and its exact snapshot. Its report identity binds the run
  key, evaluation protocol and policy, freeze schema version, source inventory,
  complete snapshot, and scoring result; the retained-bundle verifier remains
  read-only and does not publish those bytes atomically.
- [Operations runbook](OPERATIONS_RUNBOOK.md) and
  [supply-chain security](SUPPLY_CHAIN_SECURITY.md) describe general project
  controls. They do not turn the application production stack into a holdout
  environment.

Testcontainers, synthetic fixtures, and the disposable real-TLS harness prove
local mechanics only. They do not establish a production TLS endpoint, external
custody, production provisioning, a genuine blind result, or operational
authorization.
