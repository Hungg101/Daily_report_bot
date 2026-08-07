ALTER TABLE identity_admin_audit_events
    ADD CONSTRAINT ck_identity_admin_audit_actor_not_blank
        CHECK (CHAR_LENGTH(TRIM(actor)) > 0);

ALTER TABLE identity_admin_audit_events
    ADD CONSTRAINT ck_identity_admin_audit_reason_not_blank
        CHECK (CHAR_LENGTH(TRIM(reason)) > 0);
