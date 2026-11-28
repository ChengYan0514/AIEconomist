-- Drop the narrower v2_causal_run_paper index created in V6, now superseded
-- by the covering index v2_causal_run_paper_status created in V11.
DROP INDEX IF EXISTS v2_causal_run_paper;
