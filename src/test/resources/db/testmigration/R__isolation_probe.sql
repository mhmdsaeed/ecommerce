-- Test-only tenant table used by TenantIsolationIT and RlsCoverageIT to exercise both isolation layers.
-- Tests run on a fresh Testcontainers database, and repeatable migrations run after all versioned ones,
-- so this never conflicts with application migrations.
CREATE TABLE isolation_probe
(
    id         UUID                     NOT NULL,
    tenant_id  UUID                     NOT NULL,
    label      TEXT                     NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    version    BIGINT                   NOT NULL,
    CONSTRAINT isolation_probe_pk PRIMARY KEY (id),
    CONSTRAINT isolation_probe_tenant_fk FOREIGN KEY (tenant_id) REFERENCES store (id),
    CONSTRAINT isolation_probe_label_uk UNIQUE (tenant_id, label)
);

SELECT enable_tenant_rls('isolation_probe');
