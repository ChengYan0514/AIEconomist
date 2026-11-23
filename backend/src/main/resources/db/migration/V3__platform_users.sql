ALTER TABLE v2_admin
    ADD COLUMN display_name varchar(100),
    ADD COLUMN role varchar(32) NOT NULL DEFAULT 'SYSTEM_ADMIN'
        CHECK (role IN ('SYSTEM_ADMIN', 'DATA_MAINTAINER', 'READ_ONLY')),
    ADD COLUMN enabled boolean NOT NULL DEFAULT true,
    ADD COLUMN must_change_password boolean NOT NULL DEFAULT false,
    ADD COLUMN credential_version bigint NOT NULL DEFAULT 0;

UPDATE v2_admin SET display_name = username;
ALTER TABLE v2_admin ALTER COLUMN display_name SET NOT NULL;
