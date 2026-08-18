ALTER TABLE paper_author
    ADD COLUMN credited_name TEXT;

UPDATE paper_author pa
SET credited_name = a.display_name
FROM author a
WHERE a.id = pa.author_id;

ALTER TABLE paper_author
    ALTER COLUMN credited_name SET NOT NULL,
    ADD CONSTRAINT chk_paper_author_credited_name
        CHECK (btrim(credited_name) <> '');

UPDATE paper
SET publication_year = EXTRACT(YEAR FROM publication_date)::INTEGER
WHERE publication_date IS NOT NULL;

ALTER TABLE paper
    ADD CONSTRAINT chk_paper_publication_date_year
        CHECK (
            publication_date IS NULL
            OR (
                publication_year IS NOT NULL
                AND publication_year = EXTRACT(YEAR FROM publication_date)::INTEGER
            )
        );
