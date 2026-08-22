ALTER TABLE app_user
    ADD COLUMN identity_issuer VARCHAR(512),
    ADD COLUMN identity_subject VARCHAR(255);

UPDATE app_user
SET identity_issuer = 'urn:openscholar:local',
    identity_subject = 'local-user'
WHERE id = '00000000-0000-0000-0000-000000000001';

ALTER TABLE app_user
    ADD CONSTRAINT ck_app_user_identity_pair CHECK (
        (identity_issuer IS NULL AND identity_subject IS NULL)
        OR
        (identity_issuer IS NOT NULL AND btrim(identity_issuer) <> ''
            AND identity_subject IS NOT NULL AND btrim(identity_subject) <> '')
    );

CREATE UNIQUE INDEX uk_app_user_external_identity
    ON app_user (identity_issuer, identity_subject)
    WHERE identity_issuer IS NOT NULL AND identity_subject IS NOT NULL;
