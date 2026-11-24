-- Consolidate paper master data into v2_paper, reuse work* tables directly,
-- remove source table in favor of the OpenAlex snapshot sources table,
-- and retain original_file / original_file_job as deprecated archives pending removal.

DROP TABLE IF EXISTS v2_openalex_work_topic;
DROP TABLE IF EXISTS v2_openalex_work;

DO $$
DECLARE
    r record;
BEGIN
    FOR r IN
        SELECT con.conname, rel.relname
        FROM pg_constraint con
        JOIN pg_class rel ON rel.oid = con.conrelid
        JOIN pg_class ref ON ref.oid = con.confrelid
        JOIN pg_namespace nsp ON nsp.oid = rel.relnamespace
        WHERE nsp.nspname = current_schema()
          AND con.contype = 'f'
          AND ref.relname = 'source'
          AND rel.relname IN ('work_source', 'original_file')
    LOOP
        EXECUTE format('ALTER TABLE %I DROP CONSTRAINT %I', r.relname, r.conname);
    END LOOP;
END $$;

ALTER TABLE work
    ALTER COLUMN title TYPE text,
    ALTER COLUMN doi TYPE text;
ALTER TABLE work_author
    ALTER COLUMN author_name TYPE text;
ALTER TABLE work_topic
    ALTER COLUMN topic_name TYPE text,
    ALTER COLUMN field_name TYPE text,
    ALTER COLUMN subfield_name TYPE text,
    ALTER COLUMN domain_name TYPE text;

CREATE INDEX IF NOT EXISTS idx_work_source_source_work ON work_source (source_id, work_id);
CREATE INDEX IF NOT EXISTS idx_work_topic_subfield ON work_topic (subfield_name)
    WHERE subfield_name IS NOT NULL AND subfield_name <> '';

ALTER TABLE v2_paper
    ADD COLUMN legacy_status varchar(20)
    CHECK (legacy_status IN ('PUBLISHED', 'READY', 'FAILED', 'UNSUPPORTED', 'UNPROCESSED'));

UPDATE v2_paper v
SET legacy_status = CASE
        WHEN j.flag_block = 1 THEN 'PUBLISHED'
        WHEN j.flag_text = 2 THEN 'READY'
        WHEN j.flag_text = -1 OR j.flag_block = -1 THEN 'FAILED'
        WHEN j.flag_text = -2 OR upper(v.original_type) <> 'PDF' THEN 'UNSUPPORTED'
        ELSE 'UNPROCESSED'
    END
FROM original_file_job j
WHERE v.legacy AND j.file_id = v.paper_id AND v.legacy_status IS NULL;

UPDATE v2_paper
SET legacy_status = CASE WHEN upper(original_type) <> 'PDF' THEN 'UNSUPPORTED' ELSE 'UNPROCESSED' END
WHERE legacy AND legacy_status IS NULL;

INSERT INTO v2_paper (
    paper_id, legacy, source_id, publication_year, title, authors, doi,
    landing_url, provider, original_name, original_path, original_type, original_size,
    file_revision, record_version, archived_at, matched_work_id, legacy_status,
    created_at, updated_at
)
SELECT f.file_id, true, f.source_id, f.year, f.paper_title, f.authors, f.doi,
       f.url, f.provider, f.original_file_name, f.original_file_path, upper(f.original_file_type), f.file_size,
       0, 1, f.deleted_at, j.matched_work_id,
       CASE WHEN j.flag_block = 1 THEN 'PUBLISHED'
            WHEN j.flag_text = 2 THEN 'READY'
            WHEN j.flag_text = -1 OR j.flag_block = -1 THEN 'FAILED'
            WHEN j.flag_text = -2 OR upper(f.original_file_type) <> 'PDF' THEN 'UNSUPPORTED'
            ELSE 'UNPROCESSED' END,
       COALESCE(f.created_at, now()), COALESCE(f.updated_at, f.created_at, now())
FROM original_file f
LEFT JOIN original_file_job j ON j.file_id = f.file_id
ON CONFLICT (paper_id) DO NOTHING;

DO $$
DECLARE
    r record;
BEGIN
    FOR r IN
        SELECT con.conname, rel.relname
        FROM pg_constraint con
        JOIN pg_class rel ON rel.oid = con.conrelid
        JOIN pg_class ref ON ref.oid = con.confrelid
        JOIN pg_namespace nsp ON nsp.oid = rel.relnamespace
        WHERE nsp.nspname = current_schema()
          AND con.contype = 'f'
          AND ref.relname = 'original_file'
          AND rel.relname IN ('block', 'text_file')
    LOOP
        EXECUTE format('ALTER TABLE %I DROP CONSTRAINT %I', r.relname, r.conname);
    END LOOP;
END $$;

ALTER TABLE block
    ADD CONSTRAINT fk_block_file_id_v2_paper
    FOREIGN KEY (file_id) REFERENCES v2_paper (paper_id) ON DELETE CASCADE NOT VALID;
ALTER TABLE block VALIDATE CONSTRAINT fk_block_file_id_v2_paper;

ALTER TABLE text_file
    ADD CONSTRAINT fk_text_file_file_id_v2_paper
    FOREIGN KEY (file_id) REFERENCES v2_paper (paper_id) ON DELETE CASCADE NOT VALID;
ALTER TABLE text_file VALIDATE CONSTRAINT fk_text_file_file_id_v2_paper;

DROP VIEW v2_paper_read;
CREATE VIEW v2_paper_read AS
SELECT v.paper_id, v.legacy, v.source_id, v.publication_year AS year,
       v.title, v.authors, v.doi, v.landing_url AS url, v.provider, v.original_name,
       v.original_path, v.original_type, v.original_size, v.file_revision AS revision,
       v.record_version AS version, (v.archived_at IS NOT NULL) AS archived,
       v.matched_work_id, v.latest_parse_id, v.published_parse_id,
       CASE WHEN v.published_parse_id IS NOT NULL OR (v.file_revision = 0 AND v.legacy_status = 'PUBLISHED') THEN 'PUBLISHED'
            WHEN v.latest_parse_id IS NOT NULL OR (v.file_revision = 0 AND v.legacy_status = 'READY') THEN 'READY'
            WHEN EXISTS (SELECT 1 FROM v2_job_item x
                         WHERE x.paper_id = v.paper_id AND x.file_revision = v.file_revision
                           AND x.kind IN ('PARSE', 'PUBLISH') AND x.state = 'FAILED')
                 OR (v.file_revision = 0 AND v.legacy_status = 'FAILED') THEN 'FAILED'
            WHEN (v.file_revision = 0 AND v.legacy_status = 'UNSUPPORTED') OR v.original_type <> 'PDF' THEN 'UNSUPPORTED'
            ELSE 'UNPROCESSED' END AS status,
       v.updated_at
FROM v2_paper v;

COMMENT ON TABLE original_file IS 'DEPRECATED: 数据已全量迁入 v2_paper，已停止读写，仅暂留参考待确认后删除';
COMMENT ON TABLE original_file_job IS 'DEPRECATED: 数据已全量迁入 v2_paper，已停止读写，仅暂留参考待确认后删除';

DROP TABLE source;
