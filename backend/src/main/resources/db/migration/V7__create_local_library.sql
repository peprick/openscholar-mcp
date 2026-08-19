CREATE TABLE app_user (
    id UUID PRIMARY KEY,
    display_name VARCHAR(120) NOT NULL CHECK (btrim(display_name) <> ''),
    created_at TIMESTAMPTZ NOT NULL
);

INSERT INTO app_user (id, display_name, created_at)
VALUES ('00000000-0000-0000-0000-000000000001', 'Local OpenScholar User', CURRENT_TIMESTAMP);

CREATE TABLE library_collection (
    id UUID PRIMARY KEY,
    owner_id UUID NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    name VARCHAR(120) NOT NULL CHECK (btrim(name) <> ''),
    description VARCHAR(1000),
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX idx_library_collection_owner_updated
    ON library_collection (owner_id, updated_at DESC, id);

CREATE TABLE collection_paper (
    id UUID PRIMARY KEY,
    collection_id UUID NOT NULL REFERENCES library_collection (id) ON DELETE CASCADE,
    paper_id UUID NOT NULL REFERENCES paper (id) ON DELETE RESTRICT,
    reading_status VARCHAR(16) NOT NULL
        CHECK (reading_status IN ('UNREAD', 'READING', 'COMPLETED')),
    version BIGINT NOT NULL DEFAULT 0,
    saved_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uk_collection_paper UNIQUE (collection_id, paper_id)
);

CREATE INDEX idx_collection_paper_collection_saved
    ON collection_paper (collection_id, saved_at DESC, id);
CREATE INDEX idx_collection_paper_paper ON collection_paper (paper_id);

CREATE TABLE collection_paper_tag (
    collection_paper_id UUID NOT NULL REFERENCES collection_paper (id) ON DELETE CASCADE,
    tag VARCHAR(40) NOT NULL
        CHECK (btrim(tag) <> '' AND tag = lower(tag)),
    PRIMARY KEY (collection_paper_id, tag)
);

CREATE INDEX idx_collection_paper_tag_tag
    ON collection_paper_tag (tag, collection_paper_id);
