CREATE TABLE v2_role (
    role_id varchar(43) PRIMARY KEY CHECK (
        role_id IN ('SYSTEM_ADMIN', 'DATA_MAINTAINER', 'READ_ONLY')
        OR role_id ~ '^CUSTOM_[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'
    ),
    name varchar(100) NOT NULL CHECK (length(btrim(name)) > 0),
    description varchar(500) NOT NULL,
    permissions text[] NOT NULL DEFAULT '{}' CHECK (
        permissions <@ ARRAY['PAPER_READ', 'CONTENT_READ', 'PAPER_CSV_EXPORT', 'PAPER_WRITE',
            'JOB_READ', 'PDF_PARSE', 'FULL_TEXT_IMPORT', 'OPENALEX_MANAGE']
        AND (role_id LIKE 'CUSTOM_%' OR cardinality(permissions) = 0)
    )
);

CREATE UNIQUE INDEX v2_role_name ON v2_role (lower(name));

INSERT INTO v2_role (role_id, name, description) VALUES
    ('SYSTEM_ADMIN', '系统管理员', '管理平台用户、角色、系统状态与审计，并拥有全部业务权限。'),
    ('DATA_MAINTAINER', '数据维护员', '维护论文数据、执行处理任务并管理 OpenAlex 关联。'),
    ('READ_ONLY', '只读用户', '查阅论文书目、原件和已发布全文。');

ALTER TABLE v2_admin
    DROP CONSTRAINT v2_admin_role_check,
    ALTER COLUMN role TYPE varchar(43),
    ADD CONSTRAINT v2_admin_role_fk FOREIGN KEY (role) REFERENCES v2_role (role_id);

CREATE INDEX v2_admin_role_username ON v2_admin (role, username);
