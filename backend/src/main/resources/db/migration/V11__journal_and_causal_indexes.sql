-- Covering index on v2_paper for fast single-pass journal directory aggregation
-- without fetching wide heap columns (title, authors, doi, url, original_path).
CREATE INDEX IF NOT EXISTS v2_paper_journal_agg
    ON v2_paper (source_id)
    INCLUDE (archived_at, publication_year, published_parse_id, latest_parse_id,
             file_revision, legacy_status, original_type, matched_work_id, updated_at, paper_id)
    WHERE source_id IS NOT NULL AND btrim(source_id) <> '';

-- Covering index on v2_causal_run to support fast latest-status lookup per paper.
-- Replaces the narrower v2_causal_run_paper index created in V6.
CREATE INDEX IF NOT EXISTS v2_causal_run_paper_status
    ON v2_causal_run (paper_id, updated_at DESC)
    INCLUDE (status);

DROP INDEX IF EXISTS v2_causal_run_paper;
