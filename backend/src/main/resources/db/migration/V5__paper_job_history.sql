CREATE INDEX v2_job_items_by_paper ON v2_job_item (paper_id) WHERE paper_id IS NOT NULL;
