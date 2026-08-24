ALTER TABLE search_snapshot
    ADD COLUMN requested_mode VARCHAR(16) NOT NULL DEFAULT 'AUTO',
    ADD COLUMN result_origin VARCHAR(24) NOT NULL DEFAULT 'PROVIDER',
    ADD CONSTRAINT chk_search_snapshot_requested_mode
        CHECK (requested_mode IN ('AUTO', 'ONLINE', 'LOCAL')),
    ADD CONSTRAINT chk_search_snapshot_result_origin
        CHECK (result_origin IN ('PROVIDER', 'LOCAL_CATALOG'));

DROP INDEX idx_search_snapshot_owner_fingerprint_freshness;

CREATE INDEX idx_search_snapshot_owner_fingerprint_origin_freshness
    ON search_snapshot (
        owner_id,
        fingerprint,
        result_origin,
        fresh_until DESC,
        searched_at DESC
    )
    WHERE status = 'COMPLETED';
