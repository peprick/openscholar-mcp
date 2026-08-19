CREATE EXTENSION IF NOT EXISTS vector WITH SCHEMA public;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM pg_extension extension
        JOIN pg_namespace namespace
            ON namespace.oid = extension.extnamespace
        WHERE extension.extname = 'vector'
          AND namespace.nspname = 'public'
    ) THEN
        RAISE EXCEPTION 'The vector extension must be installed in schema public';
    END IF;
END
$$;

CREATE TABLE embedding_profile (
    profile_key VARCHAR(128) PRIMARY KEY
        CHECK (profile_key ~ '^[a-z0-9][a-z0-9._-]{2,127}$'),
    provider VARCHAR(64) NOT NULL
        CHECK (btrim(provider) <> '' AND provider = btrim(provider)),
    model VARCHAR(255) NOT NULL
        CHECK (btrim(model) <> '' AND model = btrim(model)),
    model_revision VARCHAR(255) NOT NULL
        CHECK (
            btrim(model_revision) <> ''
            AND model_revision = btrim(model_revision)
            AND lower(btrim(model_revision)) NOT IN ('main', 'master', 'latest')
        ),
    content_kind VARCHAR(32) NOT NULL
        CHECK (content_kind IN ('TITLE_ABSTRACT')),
    input_policy_version SMALLINT NOT NULL
        CHECK (input_policy_version > 0),
    dimensions SMALLINT NOT NULL
        CHECK (dimensions BETWEEN 1 AND 2000),
    distance_metric VARCHAR(16) NOT NULL
        CHECK (distance_metric = 'COSINE'),
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uk_embedding_profile_definition UNIQUE (
        provider,
        model,
        model_revision,
        content_kind,
        input_policy_version,
        dimensions,
        distance_metric
    ),
    CONSTRAINT uk_embedding_profile_key_dimensions
        UNIQUE (profile_key, dimensions)
);

CREATE TABLE paper_embedding (
    paper_id UUID NOT NULL
        REFERENCES paper (id) ON DELETE CASCADE,
    profile_key VARCHAR(128) NOT NULL,
    dimensions SMALLINT NOT NULL,
    content_checksum VARCHAR(64) NOT NULL
        CHECK (content_checksum ~ '^[0-9a-f]{64}$'),
    embedding public.vector NOT NULL,
    embedded_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (paper_id, profile_key),
    CONSTRAINT fk_paper_embedding_profile_dimension
        FOREIGN KEY (profile_key, dimensions)
        REFERENCES embedding_profile (profile_key, dimensions)
        ON DELETE RESTRICT,
    CONSTRAINT chk_paper_embedding_dimensions
        CHECK (public.vector_dims(embedding) = dimensions),
    CONSTRAINT chk_paper_embedding_non_zero
        CHECK (public.vector_norm(embedding) > 0)
);

CREATE INDEX idx_paper_embedding_profile_paper
    ON paper_embedding (profile_key, paper_id);

CREATE FUNCTION reject_embedding_profile_mutation()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION
        'Embedding profiles are immutable; create a new profile instead';
END
$$;

CREATE TRIGGER trg_embedding_profile_immutable
BEFORE UPDATE OR DELETE ON embedding_profile
FOR EACH ROW
EXECUTE FUNCTION reject_embedding_profile_mutation();

CREATE FUNCTION invalidate_title_abstract_embeddings()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    EXECUTE format(
        'DELETE FROM %I.paper_embedding WHERE paper_id = $1',
        TG_TABLE_SCHEMA
    ) USING NEW.id;

    RETURN NEW;
END
$$;

CREATE TRIGGER trg_paper_embedding_source_invalidation
AFTER UPDATE OF title, abstract_text ON paper
FOR EACH ROW
WHEN (
    OLD.title IS DISTINCT FROM NEW.title
    OR OLD.abstract_text IS DISTINCT FROM NEW.abstract_text
)
EXECUTE FUNCTION invalidate_title_abstract_embeddings();
