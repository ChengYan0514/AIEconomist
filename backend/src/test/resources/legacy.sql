CREATE TABLE source (
    source_id varchar(255) PRIMARY KEY, source_name text
);
CREATE TABLE work (
    work_id varchar(255) PRIMARY KEY, publication_year integer, title text, doi text
);
CREATE TABLE work_source (
    work_id varchar(255), source_id varchar(255), PRIMARY KEY (work_id, source_id)
);
CREATE TABLE work_author (
    work_id varchar(255), author_id varchar(255), author_name text, PRIMARY KEY (work_id, author_id)
);
CREATE TABLE original_file (
    file_id varchar(255) PRIMARY KEY, source_id varchar(255) REFERENCES source(source_id),
    year integer, paper_title text, authors text, doi text, url text, provider text,
    original_file_name text, original_file_path text, original_file_type text, file_size bigint,
    deleted_at timestamptz, created_at timestamptz, updated_at timestamptz
);
CREATE TABLE original_file_job (
    file_id varchar(255) PRIMARY KEY REFERENCES original_file(file_id) ON DELETE CASCADE,
    flag_text smallint, flag_block smallint, matched_work_id varchar(255) UNIQUE REFERENCES work(work_id)
);
CREATE TABLE text_file (
    file_id varchar(255) REFERENCES original_file(file_id) ON DELETE CASCADE, file_type text, file_path text,
    PRIMARY KEY (file_id, file_type)
);
CREATE TABLE block (
    block_id varchar(32) PRIMARY KEY, file_id varchar(255) REFERENCES original_file(file_id) ON DELETE CASCADE,
    block_seq integer, block_type text, block_text text, pdf_page integer, pdf_bbox jsonb, title_level smallint
);
CREATE TABLE block_image (block_id varchar(32) PRIMARY KEY, image_path text, image_caption text, image_footnote text);
CREATE TABLE block_table (block_id varchar(32) PRIMARY KEY, image_path text, table_caption text, table_footnote text);
CREATE TABLE block_equation (block_id varchar(32) PRIMARY KEY, image_path text, format text);
CREATE TABLE block_footnote (block_id varchar(32) PRIMARY KEY, footnote_label text, footnote_text text);
CREATE TABLE block_reference (block_id varchar(32), reference_seq integer, reference_text text);
CREATE TABLE claim_table (claim_id bigint PRIMARY KEY);
CREATE TABLE paper_claim_table (
    paper_id varchar(255) REFERENCES original_file_job(file_id), claim_id bigint REFERENCES claim_table(claim_id)
);
CREATE TABLE openalex_source_search (source_id varchar(255) PRIMARY KEY);
CREATE TABLE causal_publication (publication_id bigint PRIMARY KEY);

INSERT INTO source VALUES ('S123', 'Review of Applied Economics');
INSERT INTO work VALUES ('W009', 2024, 'Legacy paper', NULL);
INSERT INTO original_file VALUES
    ('legacy-001', 'S123', 2024, 'Legacy paper', 'Alice;Bob', NULL, NULL, NULL,
     'legacy.pdf', 'original/legacy.pdf', 'PDF', 23, NULL, now(), NULL);
INSERT INTO original_file_job VALUES ('legacy-001', 2, 1, 'W009');
INSERT INTO text_file VALUES ('legacy-001', 'JSON', 'parsed/content_list.json');
INSERT INTO block VALUES ('legacy-block', 'legacy-001', 0, 'text', 'Existing published text', 0, NULL, NULL);
INSERT INTO claim_table VALUES (17);
INSERT INTO paper_claim_table VALUES ('legacy-001', 17);
INSERT INTO openalex_source_search VALUES ('S123');
INSERT INTO causal_publication VALUES (1);
