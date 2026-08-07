ALTER TABLE daily_reports
    ADD CONSTRAINT ck_daily_reports_content_not_blank
        CHECK (regexp_like(content, '\S'));

ALTER TABLE users
    ADD CONSTRAINT ck_users_employee_code_not_blank
        CHECK (employee_code IS NULL OR regexp_like(employee_code, '\S'));

ALTER TABLE identity_admin_audit_events
    ADD CONSTRAINT ck_identity_admin_audit_actor_has_non_whitespace
        CHECK (regexp_like(actor, '\S'));

ALTER TABLE identity_admin_audit_events
    ADD CONSTRAINT ck_identity_admin_audit_reason_has_non_whitespace
        CHECK (regexp_like(reason, '\S'));
