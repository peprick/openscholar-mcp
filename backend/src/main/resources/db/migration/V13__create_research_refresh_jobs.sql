CREATE TABLE research_refresh_job (
    id UUID PRIMARY KEY,
    job_type VARCHAR(32) NOT NULL
        CHECK (job_type IN ('PAPER_ACCESS', 'SEARCH_METADATA')),
    target_id UUID NOT NULL,
    trigger_kind VARCHAR(16) NOT NULL
        CHECK (trigger_kind IN ('MANUAL', 'SCHEDULED', 'RETRY')),
    status VARCHAR(16) NOT NULL
        CHECK (status IN ('QUEUED', 'RUNNING', 'SUCCEEDED', 'FAILED')),
    attempt_count INTEGER NOT NULL DEFAULT 0 CHECK (attempt_count >= 0),
    max_attempts INTEGER NOT NULL CHECK (max_attempts BETWEEN 1 AND 10),
    available_at TIMESTAMPTZ NOT NULL,
    lease_token UUID,
    leased_until TIMESTAMPTZ,
    last_error_code VARCHAR(96),
    last_error_detail VARCHAR(500),
    created_at TIMESTAMPTZ NOT NULL,
    started_at TIMESTAMPTZ,
    completed_at TIMESTAMPTZ,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT ck_refresh_job_lease_state CHECK (
        (status = 'RUNNING' AND lease_token IS NOT NULL AND leased_until IS NOT NULL)
        OR
        (status <> 'RUNNING' AND lease_token IS NULL AND leased_until IS NULL)
    ),
    CONSTRAINT ck_refresh_job_completion_state CHECK (
        (status IN ('SUCCEEDED', 'FAILED') AND completed_at IS NOT NULL)
        OR
        (status IN ('QUEUED', 'RUNNING') AND completed_at IS NULL)
    ),
    CONSTRAINT ck_refresh_job_error_pair CHECK (
        (last_error_code IS NULL AND last_error_detail IS NULL)
        OR
        (last_error_code IS NOT NULL AND last_error_detail IS NOT NULL)
    )
);

CREATE UNIQUE INDEX uk_refresh_job_active_target
    ON research_refresh_job (job_type, target_id)
    WHERE status IN ('QUEUED', 'RUNNING');

CREATE INDEX idx_refresh_job_claim
    ON research_refresh_job (available_at, created_at, id)
    WHERE status IN ('QUEUED', 'RUNNING');

CREATE INDEX idx_refresh_job_recent
    ON research_refresh_job (created_at DESC, id DESC);
