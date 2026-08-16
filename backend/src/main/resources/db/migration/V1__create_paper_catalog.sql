CREATE TABLE paper (
    id UUID PRIMARY KEY,
    title TEXT NOT NULL CHECK (btrim(title) <> ''),
    normalized_title TEXT NOT NULL CHECK (btrim(normalized_title) <> ''),
    abstract_text TEXT,
    publication_date DATE,
    publication_year INTEGER CHECK (publication_year BETWEEN 1000 AND 9999),
    document_type VARCHAR(32) NOT NULL,
    language VARCHAR(16),
    venue_name TEXT,
    citation_count INTEGER CHECK (citation_count >= 0),
    citation_count_as_of TIMESTAMPTZ,
    metadata_quality NUMERIC(5, 4) NOT NULL DEFAULT 0
        CHECK (metadata_quality BETWEEN 0 AND 1),
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX idx_paper_normalized_title ON paper (normalized_title);
CREATE INDEX idx_paper_publication_year ON paper (publication_year);

CREATE TABLE paper_external_id (
    id UUID PRIMARY KEY,
    paper_id UUID NOT NULL REFERENCES paper (id) ON DELETE CASCADE,
    id_type VARCHAR(32) NOT NULL,
    namespace VARCHAR(255) NOT NULL DEFAULT '',
    normalized_value TEXT NOT NULL CHECK (btrim(normalized_value) <> ''),
    raw_value TEXT NOT NULL CHECK (btrim(raw_value) <> ''),
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uk_paper_external_id_type_namespace_value
        UNIQUE (id_type, namespace, normalized_value)
);

CREATE INDEX idx_paper_external_id_paper ON paper_external_id (paper_id);
