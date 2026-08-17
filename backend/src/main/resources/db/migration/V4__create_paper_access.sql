CREATE TABLE paper_access_resolution (
    paper_id UUID PRIMARY KEY REFERENCES paper (id) ON DELETE CASCADE,
    status VARCHAR(32) NOT NULL,
    checked_at TIMESTAMPTZ NOT NULL,
    fresh_until TIMESTAMPTZ NOT NULL CHECK (fresh_until >= checked_at),
    provider_coverage JSONB NOT NULL CHECK (jsonb_typeof(provider_coverage) = 'array'),
    warnings JSONB NOT NULL CHECK (jsonb_typeof(warnings) = 'array'),
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX idx_paper_access_resolution_freshness
    ON paper_access_resolution (fresh_until, checked_at DESC);

CREATE TABLE paper_version (
    id UUID PRIMARY KEY,
    paper_id UUID NOT NULL REFERENCES paper (id) ON DELETE CASCADE,
    source VARCHAR(32) NOT NULL CHECK (btrim(source) <> ''),
    source_location_key VARCHAR(64) NOT NULL
        CHECK (source_location_key ~ '^[0-9a-f]{64}$'),
    active BOOLEAN NOT NULL DEFAULT TRUE,
    is_best BOOLEAN NOT NULL DEFAULT FALSE,
    version_type VARCHAR(32) NOT NULL,
    host_type VARCHAR(32) NOT NULL,
    access_status VARCHAR(32) NOT NULL,
    landing_url TEXT,
    pdf_url TEXT,
    host_domain VARCHAR(255),
    license_code VARCHAR(255),
    evidence TEXT,
    content_handling VARCHAR(32) NOT NULL DEFAULT 'LINK_ONLY',
    verification_status VARCHAR(32) NOT NULL,
    verification_http_status INTEGER,
    verification_content_type VARCHAR(255),
    verification_failure_code VARCHAR(64),
    provider_updated_at TIMESTAMPTZ,
    retrieved_at TIMESTAMPTZ NOT NULL,
    last_seen_at TIMESTAMPTZ NOT NULL,
    verified_at TIMESTAMPTZ,
    retention_allowed BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT chk_paper_version_url_present
        CHECK (landing_url IS NOT NULL OR pdf_url IS NOT NULL),
    CONSTRAINT chk_paper_version_retention_policy
        CHECK (retention_allowed = FALSE),
    CONSTRAINT uk_paper_version_source_location
        UNIQUE (paper_id, source, source_location_key)
);

CREATE INDEX idx_paper_version_paper_active
    ON paper_version (paper_id, active, is_best DESC, verified_at DESC);
CREATE INDEX idx_paper_version_host_domain ON paper_version (host_domain);
