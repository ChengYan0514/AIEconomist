-- -----------------------------------------------------------------------------
-- V10: Runtime service settings (MinerU, Causal Engine, Chat & Embedding LLMs)
-- -----------------------------------------------------------------------------

CREATE TABLE v2_runtime_setting (
    setting_key varchar(64) PRIMARY KEY,
    value_json jsonb NOT NULL DEFAULT '{}'::jsonb,
    updated_at timestamptz NOT NULL DEFAULT now(),
    updated_by varchar(128)
);
