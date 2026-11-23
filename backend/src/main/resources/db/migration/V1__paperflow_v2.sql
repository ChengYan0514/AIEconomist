CREATE TABLE v2_admin (
    username varchar(50) PRIMARY KEY CHECK (username ~ '^[a-z0-9_.-]{3,50}$'),
    password_hash varchar(100) NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE v2_paper (
    paper_id varchar(255) PRIMARY KEY,
    legacy boolean NOT NULL,
    source_id varchar(255),
    publication_year integer,
    title text,
    authors text,
    doi text,
    landing_url text,
    provider text,
    original_name text,
    original_path text,
    original_type varchar(10) CHECK (original_type IN ('PDF', 'XML', 'HTML')),
    original_size bigint CHECK (original_size >= 0),
    file_revision bigint NOT NULL CHECK (file_revision >= 0),
    record_version bigint NOT NULL DEFAULT 1 CHECK (record_version > 0),
    archived_at timestamptz,
    matched_work_id varchar(255),
    latest_parse_id uuid,
    published_parse_id uuid,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CHECK (legacy OR (
        paper_id ~ '^[0-9a-f]{64}$' AND file_revision > 0 AND source_id IS NOT NULL
        AND publication_year IS NOT NULL AND publication_year BETWEEN 1000 AND 9999
        AND title IS NOT NULL AND length(title) BETWEEN 1 AND 2000
        AND authors IS NOT NULL AND length(authors) BETWEEN 1 AND 2000
    ))
);

CREATE INDEX v2_paper_source_year ON v2_paper (source_id, publication_year, paper_id);
CREATE INDEX v2_paper_normalized_doi ON v2_paper (doi) WHERE doi IS NOT NULL;
CREATE UNIQUE INDEX v2_paper_matched_work ON v2_paper (matched_work_id) WHERE matched_work_id IS NOT NULL;

CREATE TABLE v2_parse_artifact (
    parse_id uuid PRIMARY KEY,
    paper_id varchar(255) NOT NULL REFERENCES v2_paper (paper_id),
    file_revision bigint NOT NULL CHECK (file_revision >= 0),
    legacy boolean NOT NULL,
    storage_path text NOT NULL,
    content_path text NOT NULL,
    markdown_path text,
    created_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (parse_id, paper_id, file_revision)
);

ALTER TABLE v2_paper ADD CONSTRAINT v2_paper_latest_parse
    FOREIGN KEY (latest_parse_id, paper_id, file_revision)
    REFERENCES v2_parse_artifact (parse_id, paper_id, file_revision);
ALTER TABLE v2_paper ADD CONSTRAINT v2_paper_published_parse
    FOREIGN KEY (published_parse_id, paper_id, file_revision)
    REFERENCES v2_parse_artifact (parse_id, paper_id, file_revision);

CREATE TABLE v2_content_block (
    parse_id uuid NOT NULL REFERENCES v2_parse_artifact (parse_id),
    sequence integer NOT NULL CHECK (sequence >= 0),
    block_type text NOT NULL,
    body text,
    page integer NOT NULL CHECK (page >= 0),
    image_path text,
    attributes jsonb NOT NULL DEFAULT '{}'::jsonb,
    PRIMARY KEY (parse_id, sequence)
);

CREATE TABLE v2_openalex_work (
    source_id varchar(255) NOT NULL,
    work_id varchar(255) NOT NULL,
    year integer,
    title text,
    doi text,
    authors text NOT NULL,
    imported_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (source_id, work_id)
);

CREATE INDEX v2_openalex_work_doi ON v2_openalex_work (source_id, doi) WHERE doi IS NOT NULL;
CREATE INDEX v2_openalex_work_year ON v2_openalex_work (source_id, year);

CREATE TABLE v2_job (
    job_id uuid PRIMARY KEY,
    kind varchar(20) NOT NULL CHECK (kind IN ('ORIGINAL_IMPORT', 'PARSE', 'PUBLISH', 'METADATA', 'MATCH')),
    scope jsonb NOT NULL DEFAULT '{}'::jsonb,
    username varchar(50) NOT NULL REFERENCES v2_admin (username),
    retry_of uuid REFERENCES v2_job (job_id),
    created_at timestamptz NOT NULL DEFAULT now(),
    confirmed_at timestamptz,
    UNIQUE (job_id, kind)
);

CREATE TABLE v2_job_item (
    job_id uuid NOT NULL,
    kind varchar(20) NOT NULL,
    sequence integer NOT NULL CHECK (sequence > 0),
    target text NOT NULL,
    paper_id varchar(255),
    file_revision bigint,
    record_version bigint,
    input jsonb NOT NULL DEFAULT '{}'::jsonb,
    state varchar(20) NOT NULL CHECK (state IN ('READY', 'QUEUED', 'RUNNING', 'SUCCEEDED', 'SKIPPED', 'FAILED')),
    message text,
    result jsonb,
    claim_until timestamptz,
    finished_at timestamptz,
    PRIMARY KEY (job_id, sequence),
    FOREIGN KEY (job_id, kind) REFERENCES v2_job (job_id, kind),
    CHECK ((state = 'RUNNING') = (claim_until IS NOT NULL))
);

CREATE UNIQUE INDEX v2_job_one_active_paper_stage ON v2_job_item (kind, paper_id)
    WHERE paper_id IS NOT NULL AND state IN ('QUEUED', 'RUNNING') AND kind <> 'ORIGINAL_IMPORT';
CREATE INDEX v2_job_items_queued ON v2_job_item (job_id, sequence) WHERE state = 'QUEUED';
CREATE INDEX v2_job_items_expiring ON v2_job_item (claim_until) WHERE state = 'RUNNING';
CREATE INDEX v2_job_recent ON v2_job (created_at DESC, job_id DESC);

CREATE INDEX v2_job_failed_paper ON v2_job_item (paper_id, file_revision, kind)
    WHERE state = 'FAILED' AND kind IN ('PARSE', 'PUBLISH');

CREATE VIEW v2_paper_read AS
SELECT v.paper_id, v.legacy, v.source_id, s.source_name, v.publication_year AS year,
       v.title, v.authors, v.doi, v.landing_url AS url, v.provider, v.original_name,
       v.original_path, v.original_type, v.original_size, v.file_revision AS revision,
       v.record_version AS version, (v.archived_at IS NOT NULL) AS archived,
       v.matched_work_id, v.latest_parse_id, v.published_parse_id,
       CASE WHEN v.published_parse_id IS NOT NULL OR (v.file_revision = 0 AND j.flag_block = 1) THEN 'PUBLISHED'
            WHEN v.latest_parse_id IS NOT NULL OR (v.file_revision = 0 AND j.flag_text = 2) THEN 'READY'
            WHEN EXISTS (SELECT 1 FROM v2_job_item x
                         WHERE x.paper_id = v.paper_id AND x.file_revision = v.file_revision
                           AND x.kind IN ('PARSE', 'PUBLISH') AND x.state = 'FAILED')
                 OR (v.file_revision = 0 AND (j.flag_text = -1 OR j.flag_block = -1)) THEN 'FAILED'
            WHEN v.original_type <> 'PDF' THEN 'UNSUPPORTED'
            ELSE 'UNPROCESSED' END AS status,
       v.updated_at
FROM v2_paper v
LEFT JOIN source s ON s.source_id = v.source_id
LEFT JOIN original_file_job j ON v.legacy AND j.file_id = v.paper_id
UNION ALL
SELECT f.file_id, true, f.source_id, s.source_name, f.year,
       f.paper_title, f.authors, f.doi, f.url, f.provider, f.original_file_name,
       f.original_file_path, upper(f.original_file_type), f.file_size, 0::bigint,
       0::bigint, f.deleted_at IS NOT NULL, j.matched_work_id, NULL::uuid, NULL::uuid,
       CASE WHEN j.flag_block = 1 THEN 'PUBLISHED'
            WHEN j.flag_text = 2 THEN 'READY'
            WHEN j.flag_text = -1 OR j.flag_block = -1 THEN 'FAILED'
            WHEN j.flag_text = -2 OR upper(f.original_file_type) <> 'PDF' THEN 'UNSUPPORTED'
            ELSE 'UNPROCESSED' END,
       COALESCE(f.updated_at, f.created_at)
FROM original_file f
LEFT JOIN source s ON s.source_id = f.source_id
LEFT JOIN original_file_job j ON j.file_id = f.file_id
WHERE NOT EXISTS (SELECT 1 FROM v2_paper v WHERE v.paper_id = f.file_id);
