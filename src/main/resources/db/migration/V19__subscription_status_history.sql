-- Every change of a subscription's status, so the platform reports can count exactly how many stopped (suspended or
-- cancelled) or came back in a period (SPEC 5 platform tables: no tenant_id, no RLS).
--
-- A trigger writes it, so no code path that changes a status can forget to: a new subscription records its first
-- status (from_status NULL), and an update records a row only when the status actually changes. Rows are never
-- updated or deleted, like order_event. changed_at is the time of the changing transaction.

CREATE TABLE subscription_status_change
(
    id              UUID                     NOT NULL DEFAULT gen_random_uuid(),
    subscription_id UUID                     NOT NULL,
    store_id        UUID                     NOT NULL,
    from_status     TEXT,
    to_status       TEXT                     NOT NULL,
    changed_at      TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    -- Written by this migration for subscriptions that existed before it: their status then, not an observed change.
    backfilled      BOOLEAN                  NOT NULL DEFAULT FALSE,
    CONSTRAINT subscription_status_change_pk PRIMARY KEY (id),
    CONSTRAINT subscription_status_change_subscription_fk FOREIGN KEY (subscription_id)
        REFERENCES subscription (id) ON DELETE CASCADE,
    CONSTRAINT subscription_status_change_status_ck CHECK (
        (from_status IS NULL OR from_status IN ('TRIAL', 'ACTIVE', 'PAST_DUE', 'SUSPENDED', 'CANCELLED'))
        AND to_status IN ('TRIAL', 'ACTIVE', 'PAST_DUE', 'SUSPENDED', 'CANCELLED')
        AND from_status IS DISTINCT FROM to_status)
);
CREATE INDEX subscription_status_change_to_idx ON subscription_status_change (to_status, changed_at);
CREATE INDEX subscription_status_change_subscription_idx ON subscription_status_change (subscription_id, changed_at);

INSERT INTO subscription_status_change (subscription_id, store_id, from_status, to_status, changed_at, backfilled)
SELECT id, store_id, NULL, status, updated_at, TRUE
FROM subscription;

CREATE FUNCTION subscription_status_recorded() RETURNS trigger
    LANGUAGE plpgsql AS
$$
BEGIN
    IF TG_OP = 'INSERT' OR OLD.status IS DISTINCT FROM NEW.status THEN
        INSERT INTO subscription_status_change (subscription_id, store_id, from_status, to_status)
        VALUES (NEW.id, NEW.store_id, CASE WHEN TG_OP = 'UPDATE' THEN OLD.status END, NEW.status);
    END IF;
    RETURN NULL;
END;
$$;

CREATE TRIGGER subscription_status_recorded
    AFTER INSERT OR UPDATE OF status ON subscription
    FOR EACH ROW EXECUTE FUNCTION subscription_status_recorded();

CREATE FUNCTION subscription_status_change_append_only() RETURNS trigger
    LANGUAGE plpgsql AS
$$
BEGIN
    -- Deleting a subscription removes its history with it (ON DELETE CASCADE); nothing else may change it.
    IF TG_OP = 'DELETE' AND NOT EXISTS (SELECT 1 FROM subscription WHERE id = OLD.subscription_id) THEN
        RETURN OLD;
    END IF;
    RAISE EXCEPTION 'subscription_status_change is append-only';
END;
$$;

CREATE TRIGGER subscription_status_change_append_only
    BEFORE UPDATE OR DELETE ON subscription_status_change
    FOR EACH ROW EXECUTE FUNCTION subscription_status_change_append_only();
