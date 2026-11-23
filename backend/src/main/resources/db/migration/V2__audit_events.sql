CREATE TABLE v2_audit_event (
    event_id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    occurred_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    actor varchar(50) NOT NULL,
    action varchar(64) NOT NULL,
    object_type varchar(64) NOT NULL,
    object_id varchar(255) NOT NULL,
    outcome varchar(16) NOT NULL CHECK (outcome IN ('SUCCEEDED', 'FAILED', 'DENIED')),
    summary jsonb NOT NULL CHECK (jsonb_typeof(summary) = 'object')
);

CREATE INDEX v2_audit_event_time_idx ON v2_audit_event (occurred_at DESC, event_id DESC);
