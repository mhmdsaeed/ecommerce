-- Row-level security for tenant tables (isolation layer 2, SPEC 4.3).
--
-- Every migration that creates a tenant table calls this right after CREATE TABLE:
--
--     SELECT enable_tenant_rls('category');
--
-- RlsCoverageIT fails if any table with a tenant_id column lacks the forced policy.
--
-- The policy compares against NULLIF(current_setting('app.tenant_id', true), ''): once a session has set and
-- reset the variable, current_setting returns '' rather than NULL, and ''::uuid would raise an error. With
-- NULLIF, "no tenant" means "no rows" (and no writes), never an exception.
--
-- FORCE applies the policy to the table owner (app_owner) as well. Only app_platform (BYPASSRLS) sees across
-- tenants. The function is SECURITY INVOKER, so only the table owner can apply it; ALTER TABLE needs ownership.
CREATE FUNCTION enable_tenant_rls(target REGCLASS) RETURNS VOID
    LANGUAGE plpgsql
AS
$$
BEGIN
    EXECUTE format('ALTER TABLE %s ENABLE ROW LEVEL SECURITY', target);
    EXECUTE format('ALTER TABLE %s FORCE ROW LEVEL SECURITY', target);
    EXECUTE format(
            'CREATE POLICY tenant_isolation ON %s'
                || ' USING (tenant_id = NULLIF(current_setting(''app.tenant_id'', true), '''')::uuid)'
                || ' WITH CHECK (tenant_id = NULLIF(current_setting(''app.tenant_id'', true), '''')::uuid)',
            target);
END;
$$;

REVOKE EXECUTE ON FUNCTION enable_tenant_rls(REGCLASS) FROM PUBLIC;
