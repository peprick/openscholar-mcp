CREATE INDEX idx_paper_embedding_qwen06b_v1_cosine_hnsw
    ON paper_embedding USING hnsw (
        (embedding::public.vector(1024)) public.vector_cosine_ops
    )
    WITH (m = 16, ef_construction = 64)
    WHERE profile_key = 'paper-semantic-v1-ac6da0dfba84a81fdbfbaf330198c33cd77c4cdfc53e8bc50eb581914a15621d-ollama-0-31-1';
