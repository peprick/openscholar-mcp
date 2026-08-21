ALTER TABLE paper
    ADD COLUMN publisher TEXT,
    ADD COLUMN institution TEXT,
    ADD COLUMN volume TEXT,
    ADD COLUMN issue TEXT,
    ADD COLUMN pages TEXT,
    ADD COLUMN article_number TEXT,
    ADD COLUMN edition TEXT,
    ADD COLUMN isbn JSONB NOT NULL DEFAULT '[]'::jsonb,
    ADD COLUMN issn JSONB NOT NULL DEFAULT '[]'::jsonb,
    ADD COLUMN degree TEXT,
    ADD CONSTRAINT chk_paper_isbn_array CHECK (jsonb_typeof(isbn) = 'array'),
    ADD CONSTRAINT chk_paper_issn_array CHECK (jsonb_typeof(issn) = 'array');
