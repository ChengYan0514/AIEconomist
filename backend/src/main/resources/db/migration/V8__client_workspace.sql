DO $$
DECLARE
    role_id_constraint text;
    permissions_constraint text;
BEGIN
    SELECT con.conname INTO role_id_constraint
    FROM pg_constraint con
    JOIN pg_class rel ON rel.oid = con.conrelid
    JOIN pg_namespace nsp ON nsp.oid = rel.relnamespace
    WHERE nsp.nspname = current_schema()
      AND rel.relname = 'v2_role'
      AND con.contype = 'c'
      AND pg_get_constraintdef(con.oid) LIKE '%READ_ONLY%';
    IF role_id_constraint IS NOT NULL THEN
        EXECUTE format('ALTER TABLE v2_role DROP CONSTRAINT %I', role_id_constraint);
    END IF;

    SELECT con.conname INTO permissions_constraint
    FROM pg_constraint con
    JOIN pg_class rel ON rel.oid = con.conrelid
    JOIN pg_namespace nsp ON nsp.oid = rel.relnamespace
    WHERE nsp.nspname = current_schema()
      AND rel.relname = 'v2_role'
      AND con.contype = 'c'
      AND pg_get_constraintdef(con.oid) LIKE '%PAPER_READ%';
    IF permissions_constraint IS NOT NULL THEN
        EXECUTE format('ALTER TABLE v2_role DROP CONSTRAINT %I', permissions_constraint);
    END IF;
END $$;

ALTER TABLE v2_role ADD CONSTRAINT v2_role_id_check CHECK (
    role_id IN ('SYSTEM_ADMIN', 'DATA_MAINTAINER', 'READ_ONLY', 'RESEARCHER')
    OR role_id ~ '^CUSTOM_[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'
);

ALTER TABLE v2_role ADD CONSTRAINT v2_role_permissions_check CHECK (
    permissions <@ ARRAY[
        'PAPER_READ', 'CONTENT_READ', 'PAPER_CSV_EXPORT', 'PAPER_WRITE',
        'JOB_READ', 'PDF_PARSE', 'FULL_TEXT_IMPORT', 'OPENALEX_MANAGE',
        'CAUSAL_READ', 'CAUSAL_EXTRACT', 'CLIENT_ACCESS'
    ]
    AND (role_id LIKE 'CUSTOM_%' OR cardinality(permissions) = 0)
);

INSERT INTO v2_role (role_id, name, description) VALUES
    ('RESEARCHER', '研究者', '仅使用客户端科研工具，不进入数据管理端。')
ON CONFLICT (role_id) DO NOTHING;

CREATE TABLE v2_client_citation_task (
    task_id uuid PRIMARY KEY,
    username varchar(50) NOT NULL REFERENCES v2_admin (username),
    title varchar(160) NOT NULL CHECK (length(btrim(title)) > 0),
    status varchar(20) NOT NULL CHECK (
        status IN ('QUEUED', 'RUNNING', 'SUCCEEDED', 'FAILED', 'CANCELLED')
    ),
    stage varchar(32) NOT NULL DEFAULT 'QUEUED' CHECK (
        stage IN (
            'QUEUED', 'PARSE_TEXT', 'PLAN_RETRIEVAL', 'RETRIEVE_REFERENCES',
            'RANK_EVIDENCE', 'ENRICH_METADATA', 'FORMAT_OUTPUT',
            'COMPLETED', 'FAILED', 'CANCELLED'
        )
    ),
    progress double precision NOT NULL DEFAULT 0 CHECK (progress >= 0 AND progress <= 1),
    original_text text NOT NULL CHECK (length(btrim(original_text)) > 0),
    citation_style varchar(32) NOT NULL CHECK (
        citation_style IN ('apa', 'ieee', 'chicago-author-date', 'mla')
    ),
    retrieval_policy jsonb NOT NULL DEFAULT '{}'::jsonb,
    engine_provider varchar(32) NOT NULL,
    engine_run_id varchar(128),
    result_payload jsonb,
    annotated_text text,
    bibliography text,
    error_code varchar(64),
    error_message text,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    finished_at timestamptz
);

CREATE INDEX v2_client_citation_task_user_recent
    ON v2_client_citation_task (username, created_at DESC, task_id DESC);
CREATE INDEX v2_client_citation_task_active
    ON v2_client_citation_task (status)
    WHERE status IN ('QUEUED', 'RUNNING');

CREATE TABLE v2_client_citation_event (
    event_id bigserial PRIMARY KEY,
    task_id uuid NOT NULL REFERENCES v2_client_citation_task (task_id) ON DELETE CASCADE,
    stage varchar(32) NOT NULL,
    event_type varchar(32) NOT NULL CHECK (
        event_type IN ('STATUS_CHANGED', 'STAGE_STARTED', 'STAGE_COMPLETED', 'WARNING', 'COMPLETED', 'FAILED', 'CANCELLED')
    ),
    progress double precision NOT NULL DEFAULT 0 CHECK (progress >= 0 AND progress <= 1),
    message text NOT NULL,
    payload jsonb NOT NULL DEFAULT '{}'::jsonb,
    occurred_at timestamptz NOT NULL DEFAULT clock_timestamp()
);

CREATE INDEX v2_client_citation_event_task_seq
    ON v2_client_citation_event (task_id, event_id ASC);
