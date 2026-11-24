DO $$
DECLARE
    constraint_name text;
BEGIN
    SELECT con.conname INTO constraint_name
    FROM pg_constraint con
    JOIN pg_class rel ON rel.oid = con.conrelid
    JOIN pg_namespace nsp ON nsp.oid = rel.relnamespace
    WHERE nsp.nspname = current_schema()
      AND rel.relname = 'v2_role'
      AND con.contype = 'c'
      AND pg_get_constraintdef(con.oid) LIKE '%PAPER_READ%';
    IF constraint_name IS NOT NULL THEN
        EXECUTE format('ALTER TABLE v2_role DROP CONSTRAINT %I', constraint_name);
    END IF;
END $$;

ALTER TABLE v2_role ADD CONSTRAINT v2_role_permissions_check CHECK (
    permissions <@ ARRAY[
        'PAPER_READ', 'CONTENT_READ', 'PAPER_CSV_EXPORT', 'PAPER_WRITE',
        'JOB_READ', 'PDF_PARSE', 'FULL_TEXT_IMPORT', 'OPENALEX_MANAGE',
        'CAUSAL_READ', 'CAUSAL_EXTRACT'
    ]
    AND (role_id LIKE 'CUSTOM_%' OR cardinality(permissions) = 0)
);

CREATE TABLE v2_openalex_work_topic (
    work_id varchar(255) NOT NULL,
    topic_id varchar(255) NOT NULL,
    topic_name text,
    field_name text,
    subfield_name text,
    domain_name text,
    imported_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (work_id, topic_id)
);

CREATE INDEX v2_openalex_work_topic_work ON v2_openalex_work_topic (work_id);
CREATE INDEX v2_openalex_work_topic_subfield ON v2_openalex_work_topic (subfield_name)
    WHERE subfield_name IS NOT NULL AND subfield_name <> '';

CREATE TABLE v2_causal_batch (
    batch_id uuid PRIMARY KEY,
    ingestion_mode varchar(20) NOT NULL CHECK (ingestion_mode IN ('RAW', 'STANDARDIZED')),
    scope jsonb NOT NULL DEFAULT '{}'::jsonb,
    config_snapshot jsonb NOT NULL DEFAULT '{}'::jsonb,
    cancelled boolean NOT NULL DEFAULT false,
    created_at timestamptz NOT NULL DEFAULT now(),
    finished_at timestamptz
);

CREATE INDEX v2_causal_batch_recent ON v2_causal_batch (created_at DESC, batch_id DESC);

CREATE TABLE v2_causal_run (
    run_id uuid PRIMARY KEY,
    batch_id uuid NOT NULL REFERENCES v2_causal_batch (batch_id) ON DELETE CASCADE,
    paper_id varchar(255) NOT NULL,
    work_id varchar(255),
    source_id varchar(255),
    input_hash varchar(64),
    engine_run_id varchar(128),
    status varchar(20) NOT NULL CHECK (
        status IN ('QUEUED', 'RUNNING', 'PUBLISHING', 'SUCCEEDED', 'SKIPPED', 'FAILED', 'CANCELLED')
    ),
    stage varchar(20) NOT NULL DEFAULT 'PREPARE' CHECK (
        stage IN ('PREPARE', 'STAGE0', 'STAGE1', 'NORMALIZE', 'MAP', 'PUBLISH')
    ),
    metrics jsonb NOT NULL DEFAULT '{}'::jsonb,
    error_code varchar(64),
    error_message text,
    updated_at timestamptz NOT NULL DEFAULT now(),
    finished_at timestamptz,
    UNIQUE (batch_id, paper_id)
);

CREATE UNIQUE INDEX v2_causal_run_active_work ON v2_causal_run (work_id)
    WHERE work_id IS NOT NULL AND status IN ('QUEUED', 'RUNNING', 'PUBLISHING');
CREATE INDEX v2_causal_run_batch_status ON v2_causal_run (batch_id, status, updated_at DESC);
CREATE INDEX v2_causal_run_paper ON v2_causal_run (paper_id, updated_at DESC);

CREATE TABLE v2_causal_edge_index (
    claim_id bigint PRIMARY KEY,
    cause_standard varchar(255) NOT NULL,
    effect_standard varchar(255) NOT NULL,
    record_count bigint NOT NULL CHECK (record_count >= 0),
    paper_count bigint NOT NULL CHECK (paper_count >= 0),
    diversity bigint NOT NULL CHECK (diversity >= 0),
    positive_count bigint NOT NULL CHECK (positive_count >= 0),
    negative_count bigint NOT NULL CHECK (negative_count >= 0),
    null_count bigint NOT NULL CHECK (null_count >= 0),
    mixed_count bigint NOT NULL CHECK (mixed_count >= 0),
    subfields text[] NOT NULL DEFAULT '{}',
    revision bigint NOT NULL
);

CREATE INDEX v2_causal_edge_rank ON v2_causal_edge_index (record_count DESC, paper_count DESC, claim_id ASC);
CREATE INDEX v2_causal_edge_subfields ON v2_causal_edge_index USING GIN (subfields);
CREATE INDEX v2_causal_edge_cause_lower ON v2_causal_edge_index (lower(cause_standard));
CREATE INDEX v2_causal_edge_effect_lower ON v2_causal_edge_index (lower(effect_standard));

CREATE TABLE v2_causal_snapshot (
    snapshot_key varchar(32) PRIMARY KEY CHECK (snapshot_key IN ('SUMMARY', 'DEFAULT_GRAPH', 'FIELDS')),
    revision bigint NOT NULL,
    payload jsonb NOT NULL,
    refreshed_at timestamptz NOT NULL DEFAULT now()
);
