-- Messages to shoppers and staff (SPEC 5, 12, M11). One row per message and recipient, written before sending and
-- updated with the outcome. dedupe_key names the message (e.g. the order, the template and the recipient), so a
-- retried job or a redelivered event never sends again what already went out. channel is LOG when no provider is
-- configured and the message only went to the application log.
CREATE TABLE notification_log
(
    id           UUID                     NOT NULL,
    tenant_id    UUID                     NOT NULL,
    dedupe_key   TEXT                     NOT NULL,
    template     TEXT                     NOT NULL,
    order_id     UUID,
    channel      TEXT                     NOT NULL,
    recipient    TEXT                     NOT NULL,
    status       TEXT                     NOT NULL,
    attempts     INTEGER                  NOT NULL,
    provider_ref TEXT,
    error        TEXT,
    sent_at      TIMESTAMP WITH TIME ZONE,
    created_at   TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at   TIMESTAMP WITH TIME ZONE NOT NULL,
    version      BIGINT                   NOT NULL,
    CONSTRAINT notification_log_pk PRIMARY KEY (id),
    CONSTRAINT notification_log_dedupe_uk UNIQUE (tenant_id, dedupe_key),
    CONSTRAINT notification_log_channel_ck CHECK (channel IN ('EMAIL', 'SMS', 'WHATSAPP', 'LOG')),
    CONSTRAINT notification_log_status_ck CHECK (status IN ('PENDING', 'SENT', 'FAILED')),
    CONSTRAINT notification_log_attempts_ck CHECK (attempts >= 0)
);
CREATE INDEX notification_log_order_idx ON notification_log (tenant_id, order_id, created_at);
SELECT enable_tenant_rls('notification_log');
