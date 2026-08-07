ALTER TABLE identity_admin_audit_events
    DROP CONSTRAINT ck_identity_admin_audit_action;

ALTER TABLE identity_admin_audit_events
    ADD CONSTRAINT ck_identity_admin_audit_action
        CHECK (action IN (
            'CREATE',
            'UPDATE_PROFILE',
            'REMAP_TELEGRAM',
            'DEACTIVATE',
            'REACTIVATE',
            'UPDATE_REPORT',
            'DELETE_REPORT',
            'GRANT_MANAGER',
            'REVOKE_MANAGER',
            'GRANT_ADMIN',
            'REVOKE_ADMIN'
        ));
