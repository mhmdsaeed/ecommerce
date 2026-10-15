package com.unispark.ecommerce;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * Every tenant table carries the second isolation layer (SPEC 4.3): forced row-level security with exactly the
 * {@code tenant_isolation} policy. A table is a tenant table when it has a {@code tenant_id} column; every other
 * table must be a known platform table, so a tenant table created without {@code tenant_id} fails here too.
 */
@SpringBootTest
@Import(TestcontainersConfig.class)
@ActiveProfiles("test")
class RlsCoverageIT {

    /** Tables without tenant_id (SPEC 5 "Platform tables"). Add new platform tables here deliberately. */
    static final Set<String> PLATFORM_TABLES = Set.of(
            "flyway_schema_history", "event_publication", "store", "platform_user",
            // JobRunr (V13): jobs carry their store in the payload.
            "jobrunr_migrations", "jobrunr_jobs", "jobrunr_recurring_jobs", "jobrunr_backgroundjobservers",
            "jobrunr_metadata");

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void checksTheKnownTenantTables() {
        assertThat(tenantTables()).contains("isolation_probe", "staff_user");
    }

    @Test
    void everyTableWithoutTenantIdIsAKnownPlatformTable() {
        var tablesWithoutTenantId = jdbc.queryForList("""
                SELECT t.table_name FROM information_schema.tables t
                WHERE t.table_schema = 'public' AND t.table_type = 'BASE TABLE'
                  AND NOT EXISTS (SELECT 1 FROM information_schema.columns c
                                  WHERE c.table_schema = 'public' AND c.table_name = t.table_name
                                    AND c.column_name = 'tenant_id')
                """, String.class);

        assertThat(tablesWithoutTenantId)
                .as("Tenant tables need tenant_id and enable_tenant_rls(); platform tables go in PLATFORM_TABLES")
                .allMatch(PLATFORM_TABLES::contains);
    }

    @Test
    void everyTenantTableEnablesAndForcesRowLevelSecurity() {
        for (var table : tenantTables()) {
            var flags = jdbc.queryForMap(
                    "SELECT relrowsecurity, relforcerowsecurity FROM pg_class WHERE oid = to_regclass(?)",
                    "public." + table);

            assertThat(flags.get("relrowsecurity")).as("%s: ENABLE ROW LEVEL SECURITY", table).isEqualTo(true);
            assertThat(flags.get("relforcerowsecurity")).as("%s: FORCE ROW LEVEL SECURITY", table).isEqualTo(true);
        }
    }

    @Test
    void everyTenantTableHasOnlyTheTenantIsolationPolicy() {
        for (var table : tenantTables()) {
            // Permissive policies are OR'ed, so any extra policy could widen access.
            var policies = jdbc.queryForList("""
                    SELECT policyname, permissive, cmd, roles::text AS roles, qual, with_check
                    FROM pg_policies WHERE schemaname = 'public' AND tablename = ?
                    """, table);

            assertThat(policies).as("%s: exactly one policy", table).hasSize(1);
            var policy = policies.getFirst();
            assertThat(policy.get("policyname")).as(table).isEqualTo("tenant_isolation");
            assertThat(policy.get("permissive")).as(table).isEqualTo("PERMISSIVE");
            assertThat(policy.get("cmd")).as("%s: applies to every command", table).isEqualTo("ALL");
            assertThat(policy.get("roles")).as("%s: applies to every role", table).isEqualTo("{public}");
            assertThat((String) policy.get("qual")).as("%s USING", table)
                    .contains("tenant_id = (NULLIF(current_setting('app.tenant_id'::text, true), ''::text))::uuid");
            assertThat((String) policy.get("with_check")).as("%s WITH CHECK", table)
                    .contains("tenant_id = (NULLIF(current_setting('app.tenant_id'::text, true), ''::text))::uuid");
        }
    }

    @Test
    void tenantIdIsAlwaysRequired() {
        for (var table : tenantTables()) {
            var nullable = jdbc.queryForObject("""
                    SELECT is_nullable FROM information_schema.columns
                    WHERE table_schema = 'public' AND table_name = ? AND column_name = 'tenant_id'
                    """, String.class, table);

            assertThat(nullable).as("%s.tenant_id NOT NULL", table).isEqualTo("NO");
        }
    }

    @Test
    void uniqueAndCompositeIndexesOnTenantTablesStartWithTenantId() {
        for (var table : tenantTables()) {
            // LEFT JOIN: an index led by an expression (indkey[0] = 0) has no first column and counts as offending.
            var offending = jdbc.queryForList("""
                    SELECT index_class.relname
                    FROM pg_index i
                    JOIN pg_class index_class ON index_class.oid = i.indexrelid
                    LEFT JOIN pg_attribute first_column ON first_column.attrelid = i.indrelid
                                                       AND first_column.attnum = i.indkey[0]
                    WHERE i.indrelid = to_regclass(?)
                      AND NOT i.indisprimary
                      AND (i.indisunique OR i.indnatts > 1)
                      AND (first_column.attname IS NULL OR first_column.attname <> 'tenant_id')
                    """, String.class, "public." + table);

            assertThat(offending).as("%s: indexes must lead with tenant_id (SPEC 5)", table).isEmpty();
        }
    }

    private List<String> tenantTables() {
        return jdbc.queryForList("""
                SELECT c.table_name FROM information_schema.columns c
                JOIN information_schema.tables t ON t.table_schema = c.table_schema AND t.table_name = c.table_name
                WHERE c.table_schema = 'public' AND c.column_name = 'tenant_id' AND t.table_type = 'BASE TABLE'
                ORDER BY c.table_name
                """, String.class);
    }
}
