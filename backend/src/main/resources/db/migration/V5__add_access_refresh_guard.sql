ALTER TABLE paper_access_resolution
    ADD COLUMN lookup_fingerprint VARCHAR(64);

UPDATE paper_access_resolution
SET lookup_fingerprint = repeat('0', 64)
WHERE lookup_fingerprint IS NULL;

ALTER TABLE paper_access_resolution
    ALTER COLUMN lookup_fingerprint SET NOT NULL,
    ADD CONSTRAINT chk_paper_access_lookup_fingerprint
        CHECK (lookup_fingerprint ~ '^[0-9a-f]{64}$');

CREATE TABLE paper_access_refresh_guard (
    paper_id UUID PRIMARY KEY REFERENCES paper (id) ON DELETE CASCADE,
    last_forced_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX idx_paper_access_refresh_guard_last_forced
    ON paper_access_refresh_guard (last_forced_at);
