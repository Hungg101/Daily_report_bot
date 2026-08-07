ALTER TABLE daily_reports
    ADD CONSTRAINT ck_daily_reports_content_not_blank
        CHECK (REGEXP_LIKE(content, '.*\S.*', 'n'));

ALTER TABLE users
    ADD CONSTRAINT ck_users_employee_code_not_blank
        CHECK (employee_code IS NULL OR REGEXP_LIKE(employee_code, '.*\S.*', 'n'));

ALTER TABLE identity_admin_audit_events
    ADD CONSTRAINT ck_identity_admin_audit_actor_has_non_whitespace
        CHECK (REGEXP_LIKE(actor, '.*\S.*', 'n'));

ALTER TABLE identity_admin_audit_events
    ADD CONSTRAINT ck_identity_admin_audit_reason_has_non_whitespace
        CHECK (REGEXP_LIKE(reason, '.*\S.*', 'n'));
