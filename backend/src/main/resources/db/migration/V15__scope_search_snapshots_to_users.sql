ALTER TABLE search_snapshot
    ADD COLUMN owner_id UUID;

UPDATE search_snapshot
SET owner_id = '00000000-0000-0000-0000-000000000001'
WHERE owner_id IS NULL;

ALTER TABLE search_snapshot
    ALTER COLUMN owner_id SET NOT NULL,
    ADD CONSTRAINT fk_search_snapshot_owner
        FOREIGN KEY (owner_id) REFERENCES app_user (id) ON DELETE CASCADE;

DROP INDEX idx_search_snapshot_fingerprint_freshness;

CREATE INDEX idx_search_snapshot_owner_fingerprint_freshness
    ON search_snapshot (owner_id, fingerprint, fresh_until DESC, searched_at DESC)
    WHERE status = 'COMPLETED';

CREATE INDEX idx_search_snapshot_owner_created
    ON search_snapshot (owner_id, created_at DESC, id);
