-- -----------------------------------------------------------------------------
-- V9: Causal extraction review workbench & paper causal status support
-- -----------------------------------------------------------------------------

-- 1. Extend v2_causal_batch with review policy
ALTER TABLE v2_causal_batch
    ADD COLUMN review_policy varchar(30) NOT NULL DEFAULT 'MANUAL_REVIEW'
    CHECK (review_policy IN ('MANUAL_REVIEW', 'AUTO_PUBLISH'));

-- 2. Extend v2_causal_run with review status, draft payload and active work index
ALTER TABLE v2_causal_run
    DROP CONSTRAINT IF EXISTS v2_causal_run_status_check;

ALTER TABLE v2_causal_run
    ADD CONSTRAINT v2_causal_run_status_check
    CHECK (status IN ('QUEUED', 'RUNNING', 'PENDING_REVIEW', 'PUBLISHING', 'SUCCEEDED', 'SKIPPED', 'FAILED', 'CANCELLED'));

ALTER TABLE v2_causal_run
    ADD COLUMN draft_payload jsonb;

-- Drop and recreate partial unique index to include PENDING_REVIEW as an active work state
DROP INDEX IF EXISTS v2_causal_run_active_work;
CREATE UNIQUE INDEX v2_causal_run_active_work ON v2_causal_run (work_id)
    WHERE work_id IS NOT NULL AND status IN ('QUEUED', 'RUNNING', 'PENDING_REVIEW', 'PUBLISHING');
