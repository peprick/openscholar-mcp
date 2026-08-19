ALTER TABLE paper
    ADD COLUMN search_vector TSVECTOR
    GENERATED ALWAYS AS (
        setweight(to_tsvector('english'::regconfig, coalesce(title, '')), 'A')
        || setweight(to_tsvector('english'::regconfig, coalesce(abstract_text, '')), 'B')
        || setweight(to_tsvector('english'::regconfig, coalesce(venue_name, '')), 'C')
    ) STORED;

CREATE INDEX idx_paper_search_vector_fts
    ON paper USING GIN (search_vector);
