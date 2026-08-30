REVOKE ALL PRIVILEGES ON DATABASE openscholar_holdout_ledger_v1 FROM PUBLIC;
GRANT CONNECT ON DATABASE openscholar_holdout_ledger_v1
    TO openscholar_holdout_ledger_runtime_v1,
       openscholar_holdout_ledger_auditor_v1;

REVOKE ALL PRIVILEGES ON SCHEMA public FROM PUBLIC;

CREATE SCHEMA holdout_ledger_v1
    AUTHORIZATION openscholar_holdout_ledger_owner_v1;
REVOKE ALL PRIVILEGES ON SCHEMA holdout_ledger_v1 FROM PUBLIC;
GRANT USAGE ON SCHEMA holdout_ledger_v1
    TO openscholar_holdout_ledger_runtime_v1,
       openscholar_holdout_ledger_auditor_v1;

SET ROLE openscholar_holdout_ledger_owner_v1;

CREATE TABLE holdout_ledger_v1.first_run_claim_v1 (
    schema_version SMALLINT NOT NULL,
    evaluation_protocol_id TEXT COLLATE "C" NOT NULL,
    policy_id TEXT COLLATE "C" NOT NULL,
    run_key BYTEA NOT NULL,
    bundle_protocol_id TEXT COLLATE "C" NOT NULL,
    bundle_id TEXT COLLATE "C" NOT NULL,
    corpus_id TEXT COLLATE "C" NOT NULL,
    policy_sha256 BYTEA NOT NULL,
    manifest_sha256 BYTEA NOT NULL,
    manifest_bytes BIGINT NOT NULL,
    corpus_sha256 BYTEA NOT NULL,
    corpus_bytes BIGINT NOT NULL,
    judgments_sha256 BYTEA NOT NULL,
    judgments_bytes BIGINT NOT NULL,
    freeze_schema_version SMALLINT NOT NULL,
    source_inventory_id TEXT COLLATE "C" NOT NULL,
    evaluator_revision TEXT COLLATE "C" NOT NULL,
    evaluator_source_sha256 BYTEA NOT NULL,
    candidate_revision TEXT COLLATE "C" NOT NULL,
    candidate_source_sha256 BYTEA NOT NULL,
    claimed_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    synchronous_commit_setting TEXT COLLATE "C" NOT NULL
        DEFAULT current_setting('synchronous_commit'),
    CONSTRAINT first_run_claim_v1_pk
        PRIMARY KEY (evaluation_protocol_id, policy_id),
    CONSTRAINT first_run_claim_v1_schema_check
        CHECK (schema_version = 1),
    CONSTRAINT first_run_claim_v1_evaluation_protocol_check
        CHECK (octet_length(evaluation_protocol_id) BETWEEN 3 AND 160
            AND evaluation_protocol_id ~ '^[a-z0-9]+(-[a-z0-9]+)*$'),
    CONSTRAINT first_run_claim_v1_policy_check
        CHECK (octet_length(policy_id) BETWEEN 3 AND 160
            AND policy_id ~ '^[a-z0-9]+(-[a-z0-9]+)*$'),
    CONSTRAINT first_run_claim_v1_run_key_check
        CHECK (octet_length(run_key) = 32),
    CONSTRAINT first_run_claim_v1_bundle_protocol_check
        CHECK (octet_length(bundle_protocol_id) BETWEEN 3 AND 160
            AND bundle_protocol_id ~ '^[a-z0-9]+(-[a-z0-9]+)*$'),
    CONSTRAINT first_run_claim_v1_bundle_check
        CHECK (octet_length(bundle_id) BETWEEN 3 AND 160
            AND bundle_id ~ '^[a-z0-9]+(-[a-z0-9]+)*$'),
    CONSTRAINT first_run_claim_v1_corpus_check
        CHECK (octet_length(corpus_id) BETWEEN 3 AND 160
            AND corpus_id ~ '^[a-z0-9]+(-[a-z0-9]+)*$'),
    CONSTRAINT first_run_claim_v1_policy_sha_check
        CHECK (octet_length(policy_sha256) = 32),
    CONSTRAINT first_run_claim_v1_manifest_sha_check
        CHECK (octet_length(manifest_sha256) = 32),
    CONSTRAINT first_run_claim_v1_manifest_bytes_check
        CHECK (manifest_bytes BETWEEN 1 AND 65536),
    CONSTRAINT first_run_claim_v1_corpus_sha_check
        CHECK (octet_length(corpus_sha256) = 32),
    CONSTRAINT first_run_claim_v1_corpus_bytes_check
        CHECK (corpus_bytes BETWEEN 1 AND 786432),
    CONSTRAINT first_run_claim_v1_judgments_sha_check
        CHECK (octet_length(judgments_sha256) = 32),
    CONSTRAINT first_run_claim_v1_judgments_bytes_check
        CHECK (judgments_bytes BETWEEN 1 AND 196608),
    CONSTRAINT first_run_claim_v1_freeze_schema_check
        CHECK (freeze_schema_version = 1),
    CONSTRAINT first_run_claim_v1_inventory_check
        CHECK (octet_length(source_inventory_id) BETWEEN 3 AND 160
            AND source_inventory_id ~ '^[a-z0-9]+(-[a-z0-9]+)*$'),
    CONSTRAINT first_run_claim_v1_evaluator_revision_check
        CHECK (evaluator_revision ~ '^[0-9a-f]{40}$'),
    CONSTRAINT first_run_claim_v1_evaluator_source_sha_check
        CHECK (octet_length(evaluator_source_sha256) = 32),
    CONSTRAINT first_run_claim_v1_candidate_revision_check
        CHECK (candidate_revision ~ '^[0-9a-f]{40}$'),
    CONSTRAINT first_run_claim_v1_candidate_source_sha_check
        CHECK (octet_length(candidate_source_sha256) = 32),
    CONSTRAINT first_run_claim_v1_synchronous_commit_check
        CHECK (synchronous_commit_setting = 'on')
);

COMMENT ON TABLE holdout_ledger_v1.first_run_claim_v1 IS
    'openscholar-related-topic-reuse-first-run-ledger-v1';

RESET ROLE;

REVOKE ALL PRIVILEGES ON TABLE holdout_ledger_v1.first_run_claim_v1 FROM PUBLIC;
GRANT INSERT (
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
) ON TABLE holdout_ledger_v1.first_run_claim_v1
    TO openscholar_holdout_ledger_runtime_v1;
GRANT SELECT ON TABLE holdout_ledger_v1.first_run_claim_v1
    TO openscholar_holdout_ledger_auditor_v1;
