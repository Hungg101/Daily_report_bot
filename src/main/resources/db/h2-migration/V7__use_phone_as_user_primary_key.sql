SELECT 1 / CASE WHEN COUNT(*) = 0 THEN 1 ELSE 0 END AS phone_primary_key_precondition
FROM users
WHERE phone_number IS NULL
   OR NOT REGEXP_LIKE(phone_number, '.*\S.*', 'n');

ALTER TABLE daily_reports DROP CONSTRAINT fk_daily_reports_user;
ALTER TABLE identity_admin_audit_events DROP CONSTRAINT fk_identity_admin_audit_target_user;
ALTER TABLE identity_admin_audit_events DROP CONSTRAINT fk_identity_admin_audit_related_user;
ALTER TABLE reminder_occurrences DROP CONSTRAINT fk_reminder_occurrences_user;

ALTER TABLE users DROP CONSTRAINT uk_users_phone_number;
ALTER TABLE users DROP CONSTRAINT pk_users;
ALTER TABLE users ADD CONSTRAINT uk_users_internal_id UNIQUE (id);
ALTER TABLE users ALTER COLUMN phone_number SET NOT NULL;
ALTER TABLE users ADD CONSTRAINT pk_users PRIMARY KEY (phone_number);
ALTER TABLE users ADD CONSTRAINT ck_users_phone_number_not_blank
    CHECK (REGEXP_LIKE(phone_number, '.*\S.*', 'n'));

ALTER TABLE daily_reports ADD CONSTRAINT fk_daily_reports_user
    FOREIGN KEY (user_id) REFERENCES users (id);
ALTER TABLE identity_admin_audit_events ADD CONSTRAINT fk_identity_admin_audit_target_user
    FOREIGN KEY (target_user_id) REFERENCES users (id);
ALTER TABLE identity_admin_audit_events ADD CONSTRAINT fk_identity_admin_audit_related_user
    FOREIGN KEY (related_user_id) REFERENCES users (id);
ALTER TABLE reminder_occurrences ADD CONSTRAINT fk_reminder_occurrences_user
    FOREIGN KEY (user_id) REFERENCES users (id);
