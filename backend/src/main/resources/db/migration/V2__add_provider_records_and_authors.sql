ALTER TABLE paper
    ADD COLUMN metadata_updated_at TIMESTAMPTZ;

UPDATE paper
SET metadata_updated_at = updated_at
WHERE metadata_updated_at IS NULL;

ALTER TABLE paper
    ALTER COLUMN metadata_updated_at SET NOT NULL;

CREATE TABLE provider_record (
    id UUID PRIMARY KEY,
    paper_id UUID NOT NULL REFERENCES paper (id) ON DELETE CASCADE,
    provider VARCHAR(64) NOT NULL CHECK (btrim(provider) <> ''),
    provider_record_id TEXT NOT NULL CHECK (btrim(provider_record_id) <> ''),
    provider_updated_at TIMESTAMPTZ,
    retrieved_at TIMESTAMPTZ NOT NULL,
    source_url TEXT,
    reported_open_access BOOLEAN NOT NULL DEFAULT FALSE,
    landing_page_url TEXT,
    pdf_url TEXT,
    metadata_fragment JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT chk_provider_record_metadata_fragment_object
        CHECK (jsonb_typeof(metadata_fragment) = 'object'),
    CONSTRAINT chk_provider_record_metadata_fragment_size
        CHECK (octet_length(metadata_fragment::text) <= 32768),
    CONSTRAINT uk_provider_record_provider_record_id
        UNIQUE (provider, provider_record_id),
    CONSTRAINT uk_provider_record_id_paper
        UNIQUE (id, paper_id)
);

CREATE INDEX idx_provider_record_paper ON provider_record (paper_id);
CREATE INDEX idx_provider_record_retrieved_at ON provider_record (paper_id, retrieved_at DESC);

CREATE TABLE author (
    id UUID PRIMARY KEY,
    display_name TEXT NOT NULL CHECK (btrim(display_name) <> ''),
    openalex_id VARCHAR(255),
    orcid VARCHAR(64),
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);

CREATE UNIQUE INDEX uk_author_openalex_id
    ON author (openalex_id)
    WHERE openalex_id IS NOT NULL;

CREATE UNIQUE INDEX uk_author_orcid
    ON author (orcid)
    WHERE orcid IS NOT NULL;

CREATE TABLE paper_author (
    id UUID PRIMARY KEY,
    paper_id UUID NOT NULL,
    provider_record_id UUID NOT NULL,
    author_id UUID NOT NULL REFERENCES author (id),
    author_position INTEGER NOT NULL CHECK (author_position >= 0),
    corresponding BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uk_paper_author_record_position
        UNIQUE (provider_record_id, author_position),
    CONSTRAINT fk_paper_author_provider_paper
        FOREIGN KEY (provider_record_id, paper_id)
        REFERENCES provider_record (id, paper_id) ON DELETE CASCADE
);

CREATE INDEX idx_paper_author_paper ON paper_author (paper_id);
CREATE INDEX idx_paper_author_record_order
    ON paper_author (provider_record_id, author_position);
CREATE INDEX idx_paper_author_author ON paper_author (author_id);
