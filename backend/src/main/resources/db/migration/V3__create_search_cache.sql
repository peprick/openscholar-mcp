CREATE TABLE search_snapshot (
    id UUID PRIMARY KEY,
    original_query TEXT NOT NULL CHECK (btrim(original_query) <> ''),
    normalized_query TEXT NOT NULL CHECK (btrim(normalized_query) <> ''),
    fingerprint VARCHAR(64) NOT NULL
        CHECK (fingerprint ~ '^[0-9a-f]{64}$'),
    fingerprint_version INTEGER NOT NULL CHECK (fingerprint_version > 0),
    pipeline_version VARCHAR(32) NOT NULL CHECK (btrim(pipeline_version) <> ''),
    filters JSONB NOT NULL CHECK (jsonb_typeof(filters) = 'object'),
    status VARCHAR(24) NOT NULL CHECK (status IN ('COMPLETED')),
    searched_at TIMESTAMPTZ NOT NULL,
    fresh_until TIMESTAMPTZ NOT NULL CHECK (fresh_until >= searched_at),
    provider_coverage JSONB NOT NULL CHECK (jsonb_typeof(provider_coverage) = 'array'),
    warnings JSONB NOT NULL CHECK (jsonb_typeof(warnings) = 'array'),
    total_provider_matches BIGINT CHECK (total_provider_matches >= 0),
    result_count INTEGER NOT NULL CHECK (result_count >= 0),
    next_cursor TEXT,
    created_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX idx_search_snapshot_fingerprint_freshness
    ON search_snapshot (fingerprint, fresh_until DESC, searched_at DESC)
    WHERE status = 'COMPLETED';
CREATE INDEX idx_search_snapshot_created_at ON search_snapshot (created_at);

CREATE TABLE search_result (
    id UUID PRIMARY KEY,
    search_id UUID NOT NULL REFERENCES search_snapshot (id) ON DELETE CASCADE,
    paper_id UUID NOT NULL REFERENCES paper (id) ON DELETE RESTRICT,
    paper_snapshot JSONB NOT NULL CHECK (jsonb_typeof(paper_snapshot) = 'object'),
    result_rank INTEGER NOT NULL CHECK (result_rank > 0),
    total_score DOUBLE PRECISION,
    reported_open_access BOOLEAN NOT NULL,
    landing_page_url TEXT,
    pdf_url TEXT,
    ranking_reasons JSONB NOT NULL CHECK (jsonb_typeof(ranking_reasons) = 'array'),
    provider_contributions JSONB NOT NULL CHECK (jsonb_typeof(provider_contributions) = 'array'),
    provider VARCHAR(32) NOT NULL CHECK (btrim(provider) <> ''),
    provider_record_id TEXT NOT NULL CHECK (btrim(provider_record_id) <> ''),
    retrieved_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uk_search_result_paper UNIQUE (search_id, paper_id),
    CONSTRAINT uk_search_result_rank UNIQUE (search_id, result_rank)
);

CREATE INDEX idx_search_result_search_rank ON search_result (search_id, result_rank);
CREATE INDEX idx_search_result_paper ON search_result (paper_id);
