package com.unispark.ecommerce;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;

import com.unispark.ecommerce.shared.PlatformDatabase;
import com.unispark.ecommerce.shared.TenantContext;

/**
 * SPEC 4.4: tenants A and B each own data; nothing of B's is visible or writable while working as A, through
 * JPA (layer 1 and 2 together), through raw SQL on the application DataSource (layer 2 alone), or over HTTP.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfig.class, IsolationProbeWeb.class})
@ActiveProfiles("test")
class TenantIsolationIT {

    @Autowired
    IsolationProbeRepository probes;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    PlatformDatabase platform;

    @Autowired
    PostgreSQLContainer<?> postgres;

    @Autowired
    MockMvc mvc;

    String slugA;
    String slugB;
    UUID storeA;
    UUID storeB;
    UUID probeA;
    UUID probeB;

    @BeforeEach
    void createTwoTenantsWithData() {
        slugA = TestStores.randomSlug();
        slugB = TestStores.randomSlug();
        storeA = TestStores.create(jdbc, slugA, "ACTIVE");
        storeB = TestStores.create(jdbc, slugB, "ACTIVE");
        probeA = TenantContext.callAs(storeA, () -> probes.save(new IsolationProbe(labelOf(slugA))).getId());
        probeB = TenantContext.callAs(storeB, () -> probes.save(new IsolationProbe(labelOf(slugB))).getId());
    }

    // --- JPA: both layers ---

    @Test
    void savesNewEntitiesWithTheCurrentTenantId() {
        assertThat(tenantOfProbe(probeA)).isEqualTo(storeA);
        assertThat(tenantOfProbe(probeB)).isEqualTo(storeB);
    }

    @Test
    void findAllReturnsOnlyTheCurrentTenantsRows() {
        var visible = TenantContext.callAs(storeA, probes::findAll);

        assertThat(visible).extracting(IsolationProbe::getId).containsExactly(probeA);
        assertThat(TenantContext.<Long>callAs(storeA, () -> probes.count())).isEqualTo(1);
        assertThat(TenantContext.callAs(storeA, probes::findAllByJpql)).extracting(IsolationProbe::getId)
                .containsExactly(probeA);
    }

    @Test
    void doesNotFindAnotherTenantsRowById() {
        assertThat(TenantContext.callAs(storeA, () -> probes.findById(probeB))).isEmpty();
        assertThat(TenantContext.callAs(storeA, () -> probes.existsById(probeB))).isFalse();
        assertThat(TenantContext.callAs(storeA, () -> probes.findByLabel(labelOf(slugB)))).isEmpty();
    }

    @Test
    void seesNoTenantRowsWithoutATenant() {
        assertThat(TenantContext.currentTenantId()).isEmpty();

        assertThat(probes.findAll()).isEmpty();
        assertThat(probes.count()).isZero();
        assertThat(probes.findById(probeA)).isEmpty();
    }

    @Test
    void refusesToPersistTenantDataWithoutATenant() {
        var thrown = catchThrowable(() -> probes.save(new IsolationProbe("orphan")));

        assertThat(NestedExceptionUtils.getMostSpecificCause(thrown))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("outside a tenant context");
    }

    @Test
    void deletingByIdCannotReachAnotherTenantsRow() {
        TenantContext.runAs(storeA, () -> probes.deleteAllById(java.util.List.of(probeB)));

        assertThat(tenantOfProbe(probeB)).isEqualTo(storeB);
    }

    // --- Raw SQL on the application DataSource: RLS alone, no Hibernate filter ---

    @Test
    void rawSqlAsTenantASeesNoTenantBRows() {
        var foreignRows = TenantContext.callAs(storeA, () -> jdbc.queryForObject(
                "SELECT count(*) FROM isolation_probe WHERE tenant_id <> ?", Long.class, storeA));
        var ownRows = TenantContext.callAs(storeA, () -> jdbc.queryForObject(
                "SELECT count(*) FROM isolation_probe", Long.class));

        assertThat(foreignRows).isZero();
        assertThat(ownRows).isEqualTo(1);
    }

    @Test
    void rawSqlWithoutATenantSeesNothingAndDoesNotFail() {
        assertThat(jdbc.queryForObject("SELECT count(*) FROM isolation_probe", Long.class)).isZero();
    }

    @Test
    void rawSqlAsTenantACannotWriteTenantBRows() {
        TenantContext.runAs(storeA, () -> {
            assertThat(jdbc.update("UPDATE isolation_probe SET label = 'hijacked' WHERE id = ?", probeB)).isZero();
            assertThat(jdbc.update("DELETE FROM isolation_probe WHERE id = ?", probeB)).isZero();
            assertThatThrownBy(() -> jdbc.update("""
                    INSERT INTO isolation_probe (id, tenant_id, label, created_at, updated_at, version)
                    VALUES (gen_random_uuid(), ?, 'planted', now(), now(), 0)
                    """, storeB))
                    .isInstanceOf(DataAccessException.class)
                    .rootCause().hasMessageContaining("violates row-level security policy");
            assertThatThrownBy(() -> jdbc.update(
                    "UPDATE isolation_probe SET tenant_id = ? WHERE id = ?", storeB, probeA))
                    .isInstanceOf(DataAccessException.class)
                    .rootCause().hasMessageContaining("violates row-level security policy");
        });

        var labelB = platform.jdbc().sql("SELECT label FROM isolation_probe WHERE id = ?")
                .param(probeB).query(String.class).single();
        assertThat(labelB).isEqualTo(labelOf(slugB));
    }

    @Test
    void plainAppUserConnectionWithTenantASeesNoTenantBRows() throws SQLException {
        try (var connection = DriverManager.getConnection(
                TestcontainersConfig.jdbcUrl(postgres), TestcontainersConfig.APP_USER, TestcontainersConfig.APP_PASSWORD)) {
            try (var statement = connection.prepareStatement("SELECT set_config('app.tenant_id', ?, false)")) {
                statement.setString(1, storeA.toString());
                statement.execute();
            }
            try (var statement = connection.prepareStatement("SELECT count(*) FROM isolation_probe WHERE tenant_id = ?")) {
                statement.setObject(1, storeB);
                try (var result = statement.executeQuery()) {
                    result.next();
                    assertThat(result.getLong(1)).isZero();
                }
            }
        }
    }

    // --- Platform role: the one deliberate way across tenants ---

    @Test
    void platformDatabaseSeesEveryTenant() {
        var jdbc = platform.jdbc();

        assertThat(jdbc.sql("SELECT current_user").query(String.class).single()).isEqualTo("app_platform");
        assertThat(jdbc.sql("SELECT count(*) FROM isolation_probe WHERE id IN (?, ?)")
                .params(probeA, probeB).query(Long.class).single()).isEqualTo(2);
    }

    // --- HTTP ---

    @Test
    void storefrontOfTenantAShowsOnlyTenantAData() throws Exception {
        mvc.perform(get("http://" + slugA + ".lvh.me:8080/test/probes"))
                .andExpect(status().isOk())
                .andExpect(content().string(allOf(
                        containsString(labelOf(slugA)),
                        not(containsString(labelOf(slugB))))));

        assertThat(TenantContext.currentTenantId()).as("tenant cleared after the request").isEmpty();
    }

    @Test
    void platformHostShowsNoTenantData() throws Exception {
        mvc.perform(get("http://lvh.me:8080/test/probes"))
                .andExpect(status().isOk())
                .andExpect(content().json("[]"));
    }

    private UUID tenantOfProbe(UUID probeId) {
        return platform.jdbc().sql("SELECT tenant_id FROM isolation_probe WHERE id = ?")
                .param(probeId).query(UUID.class).single();
    }

    private static String labelOf(String slug) {
        return "probe-of-" + slug;
    }
}
