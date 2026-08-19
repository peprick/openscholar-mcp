CREATE TEMPORARY TABLE canonical_collection_paper_tag
ON COMMIT DROP
AS
WITH normalized AS (
    SELECT
        collection_paper_id,
        lower(btrim(regexp_replace(
            tag,
            U&'[[:space:]\00A0\1680\2000-\200A\2028\2029\202F\205F\3000\FEFF]+',
            ' ',
            'g'))) AS tag
    FROM collection_paper_tag
),
deduplicated AS (
    SELECT DISTINCT collection_paper_id, tag
    FROM normalized
    WHERE tag <> ''
),
ranked AS (
    SELECT
        collection_paper_id,
        tag,
        row_number() OVER (PARTITION BY collection_paper_id ORDER BY tag) AS tag_position
    FROM deduplicated
)
SELECT collection_paper_id, tag
FROM ranked
WHERE tag_position <= 10;

DELETE FROM collection_paper_tag;

INSERT INTO collection_paper_tag (collection_paper_id, tag)
SELECT collection_paper_id, tag
FROM canonical_collection_paper_tag
ORDER BY collection_paper_id, tag;

ALTER TABLE collection_paper_tag
    ADD CONSTRAINT chk_collection_paper_tag_canonical
    CHECK (tag = lower(btrim(regexp_replace(
        tag,
        U&'[[:space:]\00A0\1680\2000-\200A\2028\2029\202F\205F\3000\FEFF]+',
        ' ',
        'g'))));

CREATE FUNCTION enforce_collection_paper_tag_limit()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'INSERT' OR NEW.collection_paper_id IS DISTINCT FROM OLD.collection_paper_id THEN
        PERFORM 1
        FROM collection_paper
        WHERE id = NEW.collection_paper_id
        FOR UPDATE;

        IF NOT EXISTS (
            SELECT 1
            FROM collection_paper_tag
            WHERE collection_paper_id = NEW.collection_paper_id
              AND tag = NEW.tag
        ) AND (SELECT count(*)
               FROM collection_paper_tag
               WHERE collection_paper_id = NEW.collection_paper_id) >= 10 THEN
            RAISE EXCEPTION 'A saved paper can have at most 10 tags'
                USING ERRCODE = '23514';
        END IF;
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_collection_paper_tag_limit
    BEFORE INSERT OR UPDATE OF collection_paper_id ON collection_paper_tag
    FOR EACH ROW
    EXECUTE FUNCTION enforce_collection_paper_tag_limit();
