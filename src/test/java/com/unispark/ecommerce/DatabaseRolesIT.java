package com.unispark.ecommerce;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/** The three database roles from SPEC 4.3 have exactly the privileges the isolation model relies on. */
@SpringBootTest
@Import(TestcontainersConfig.class)
@ActiveProfiles("test")
class DatabaseRolesIT {

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void runtimeConnectsAsAppUser() {
        assertThat(jdbc.queryForObject("SELECT current_user", String.class)).isEqualTo("app_user");
    }

    @Test
    void appUserCannotBypassRowLevelSecurity() {
        assertThat(roleFlag("app_user", "rolbypassrls")).isFalse();
        assertThat(roleFlag("app_user", "rolsuper")).isFalse();
    }

    @Test
    void ownerRoleIsSubjectToForcedRowLevelSecurity() {
        assertThat(roleFlag("app_owner", "rolbypassrls")).isFalse();
        assertThat(roleFlag("app_owner", "rolsuper")).isFalse();
    }

    @Test
    void platformRoleBypassesRowLevelSecurityWithoutBeingSuperuser() {
        assertThat(roleFlag("app_platform", "rolbypassrls")).isTrue();
        assertThat(roleFlag("app_platform", "rolsuper")).isFalse();
    }

    @Test
    void flywayRunsAsSchemaOwner() {
        var installers = jdbc.queryForList("SELECT DISTINCT installed_by FROM flyway_schema_history", String.class);

        assertThat(installers).containsExactly("app_owner");
    }

    @Test
    void schemaOwnerOwnsEveryTable() {
        var owners = jdbc.queryForList(
                "SELECT DISTINCT tableowner FROM pg_tables WHERE schemaname = 'public'", String.class);

        assertThat(owners).containsExactly("app_owner");
    }

    @Test
    void runtimeRolesGetDataPrivilegesOnMigratedTables() {
        for (var role : new String[] {"app_user", "app_platform"}) {
            for (var privilege : new String[] {"SELECT", "INSERT", "UPDATE", "DELETE"}) {
                assertThat(jdbc.queryForObject(
                        "SELECT has_table_privilege(?, 'event_publication', ?)", Boolean.class, role, privilege))
                        .as("%s %s on event_publication", role, privilege)
                        .isTrue();
            }
        }
    }

    @Test
    void appUserCannotChangeTheSchema() {
        assertThatThrownBy(() -> jdbc.execute("CREATE TABLE not_allowed (id int)"))
                .isInstanceOf(DataAccessException.class)
                .rootCause().hasMessageContaining("permission denied for schema public");
        assertThatThrownBy(() -> jdbc.execute("ALTER TABLE event_publication ADD COLUMN not_allowed int"))
                .isInstanceOf(DataAccessException.class)
                .rootCause().hasMessageContaining("must be owner of table event_publication");
    }

    private boolean roleFlag(String role, String flag) {
        return jdbc.queryForObject("SELECT " + flag + " FROM pg_roles WHERE rolname = ?", Boolean.class, role);
    }
}
