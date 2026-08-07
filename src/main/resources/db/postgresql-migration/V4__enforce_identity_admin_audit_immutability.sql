ALTER TABLE identity_admin_audit_events
    ADD CONSTRAINT ck_identity_admin_audit_actor_not_blank
        CHECK (btrim(actor) <> '');

ALTER TABLE identity_admin_audit_events
    ADD CONSTRAINT ck_identity_admin_audit_reason_not_blank
        CHECK (btrim(reason) <> '');

CREATE FUNCTION prevent_identity_admin_audit_mutation()
RETURNS TRIGGER AS $prevent_identity_admin_audit_mutation$
BEGIN
    RAISE EXCEPTION 'identity_admin_audit_events is append-only';
END;
$prevent_identity_admin_audit_mutation$ LANGUAGE plpgsql;

CREATE TRIGGER trg_identity_admin_audit_events_immutable
    BEFORE UPDATE OR DELETE ON identity_admin_audit_events
    FOR EACH ROW
    EXECUTE FUNCTION prevent_identity_admin_audit_mutation();
