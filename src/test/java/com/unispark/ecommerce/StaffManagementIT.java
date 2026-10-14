package com.unispark.ecommerce;

import static com.unispark.ecommerce.TestAccounts.MAIN;
import static com.unispark.ecommerce.TestAccounts.PASSWORD;
import static com.unispark.ecommerce.TestAccounts.fromNewAddress;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.unispark.ecommerce.identity.StaffRole;
import com.unispark.ecommerce.identity.internal.persistence.StaffUserRepository;
import com.unispark.ecommerce.shared.TenantContext;

/** Staff management (SPEC 9.3 "Drivers/staff"): role rules, validation and tenant isolation. */
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfig.class, IsolationProbeWeb.class})
@ActiveProfiles("test")
class StaffManagementIT {

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    StaffUserRepository staffUsers;

    @Autowired
    PasswordEncoder passwords;

    String slug;
    UUID store;
    UUID ownerId;
    UUID managerId;
    UUID driverId;

    @BeforeEach
    void createStoreWithStaff() {
        slug = TestStores.randomSlug();
        store = TestStores.create(jdbc, slug, "ACTIVE");
        ownerId = TestAccounts.staff(staffUsers, passwords, store, "Owner One", "owner@" + slug + ".test", null, StaffRole.OWNER);
        managerId = TestAccounts.staff(staffUsers, passwords, store, "Manager One", "manager@" + slug + ".test", null, StaffRole.MANAGER);
        driverId = TestAccounts.staff(staffUsers, passwords, store, "Driver One", null, "+963955000001", StaffRole.DRIVER);
    }

    @Test
    void listsOnlyTheStaffOfTheLoggedInStore() throws Exception {
        var otherSlug = TestStores.randomSlug();
        var other = TestStores.create(jdbc, otherSlug, "ACTIVE");
        TestAccounts.staff(staffUsers, passwords, other, "Someone Else", "else@" + otherSlug + ".test", null, StaffRole.STAFF);

        mvc.perform(get(MAIN + "/admin/staff").session(login("owner@" + slug + ".test")))
                .andExpect(status().isOk())
                .andExpect(content().string(allOf(
                        containsString("Owner One"), containsString("Manager One"), containsString("Driver One"),
                        containsString("+963955000001"), not(containsString("Someone Else")))));
    }

    @Test
    void ownerAddsADriverWhoCanThenLogIn() throws Exception {
        var owner = login("owner@" + slug + ".test");

        mvc.perform(addStaff(owner, "New Driver", "", "0955 000 222", "DRIVER"))
                .andExpect(redirectedUrl("/admin/staff?added"));

        var role = TenantContext.callAs(store, () -> staffUsers.findByPhone("+963955000222").orElseThrow().getRole());
        assertThat(role).isEqualTo(StaffRole.DRIVER);
        mvc.perform(post(MAIN + "/driver/login").with(csrf()).with(fromNewAddress())
                        .param("store", slug).param("username", "0955000222").param("password", "temp-pass-123"))
                .andExpect(redirectedUrl("/driver"));
    }

    @Test
    void managersCanAddStaffButNotManagers() throws Exception {
        var manager = login("manager@" + slug + ".test");

        mvc.perform(addStaff(manager, "Cashier", "cashier@" + slug + ".test", "", "STAFF"))
                .andExpect(redirectedUrl("/admin/staff?added"));
        mvc.perform(addStaff(manager, "Boss", "boss@" + slug + ".test", "", "MANAGER"))
                .andExpect(status().isForbidden());
        mvc.perform(addStaff(manager, "Owner Two", "owner2@" + slug + ".test", "", "OWNER"))
                .andExpect(status().isForbidden());
    }

    @Test
    void nobodyCanAddAnotherOwner() throws Exception {
        mvc.perform(addStaff(login("owner@" + slug + ".test"), "Owner Two", "owner2@" + slug + ".test", "", "OWNER"))
                .andExpect(status().isForbidden());
    }

    @Test
    void reportsDuplicateAndMissingContactDetails() throws Exception {
        var owner = login("owner@" + slug + ".test");

        mvc.perform(addStaff(owner, "Copy", "", "+963 955 000 001", "DRIVER"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("هذا الرقم مستخدم لموظف آخر في متجرك")));
        mvc.perform(addStaff(owner, "Nobody", "", "", "STAFF"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("أدخل رقم الهاتف أو البريد الإلكتروني")));
    }

    @Test
    void theSamePhoneMayWorkForTwoDifferentStores() throws Exception {
        var otherSlug = TestStores.randomSlug();
        var other = TestStores.create(jdbc, otherSlug, "ACTIVE");
        TestAccounts.staff(staffUsers, passwords, other, "Owner Two", "owner@" + otherSlug + ".test", null, StaffRole.OWNER);

        mvc.perform(post(MAIN + "/admin/staff").session(login(otherSlug, "owner@" + otherSlug + ".test")).with(csrf())
                        .param("name", "Shared Driver").param("phone", "+963955000001").param("email", "")
                        .param("role", "DRIVER").param("password", "temp-pass-123"))
                .andExpect(redirectedUrl("/admin/staff?added"));
    }

    @Test
    void disablingADriverEndsTheirSessionAndEnablingRestoresLogin() throws Exception {
        var driver = login("0955000001");
        var owner = login("owner@" + slug + ".test");

        mvc.perform(post(MAIN + "/admin/staff/{id}/active", driverId).session(owner).with(csrf()).param("active", "false"))
                .andExpect(redirectedUrl("/admin/staff?disabled"));
        mvc.perform(get(MAIN + "/driver").session(driver)).andExpect(redirectedUrl("/driver/login?expired"));

        mvc.perform(post(MAIN + "/admin/staff/{id}/active", driverId).session(owner).with(csrf()).param("active", "true"))
                .andExpect(redirectedUrl("/admin/staff?enabled"));
        login("0955000001");
    }

    @Test
    void staffCannotDisableThemselvesOrTheirSuperiors() throws Exception {
        var owner = login("owner@" + slug + ".test");
        var manager = login("manager@" + slug + ".test");

        mvc.perform(toggle(owner, ownerId)).andExpect(status().isForbidden());
        mvc.perform(toggle(manager, managerId)).andExpect(status().isForbidden());
        mvc.perform(toggle(manager, ownerId)).andExpect(status().isForbidden());
        mvc.perform(toggle(manager, driverId)).andExpect(redirectedUrl("/admin/staff?disabled"));
    }

    @Test
    void cannotTouchStaffOfAnotherStore() throws Exception {
        var otherSlug = TestStores.randomSlug();
        var other = TestStores.create(jdbc, otherSlug, "ACTIVE");
        var foreignDriver = TestAccounts.staff(staffUsers, passwords, other, "Foreign", null, "+963955000999", StaffRole.DRIVER);

        mvc.perform(toggle(login("owner@" + slug + ".test"), foreignDriver)).andExpect(status().isNotFound());

        var stillActive = TenantContext.callAs(other, () -> staffUsers.findById(foreignDriver).orElseThrow().isActive());
        assertThat(stillActive).isTrue();
    }

    private MockHttpSession login(String username) throws Exception {
        return login(slug, username);
    }

    private MockHttpSession login(String storeSlug, String username) throws Exception {
        var result = mvc.perform(post(MAIN + "/admin/login").with(csrf()).with(fromNewAddress())
                        .param("store", storeSlug).param("username", username).param("password", PASSWORD))
                .andReturn();
        assertThat(result.getResponse().getRedirectedUrl()).doesNotContain("login");
        return (MockHttpSession) result.getRequest().getSession(false);
    }

    private static MockHttpServletRequestBuilder addStaff(MockHttpSession session, String name, String email,
            String phone, String role) {
        return post(MAIN + "/admin/staff").session(session).with(csrf())
                .param("name", name).param("email", email).param("phone", phone)
                .param("role", role).param("password", "temp-pass-123");
    }

    private static MockHttpServletRequestBuilder toggle(MockHttpSession session, UUID staffId) {
        return post(MAIN + "/admin/staff/{id}/active", staffId).session(session).with(csrf()).param("active", "false");
    }
}
