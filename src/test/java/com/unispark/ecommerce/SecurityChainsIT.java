package com.unispark.ecommerce;

import static com.unispark.ecommerce.TestAccounts.MAIN;
import static com.unispark.ecommerce.TestAccounts.PASSWORD;
import static com.unispark.ecommerce.TestAccounts.PLATFORM;
import static com.unispark.ecommerce.TestAccounts.from;
import static com.unispark.ecommerce.TestAccounts.fromNewAddress;
import static com.unispark.ecommerce.TestAccounts.storefront;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrlPattern;
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
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.unispark.ecommerce.identity.StaffRole;
import com.unispark.ecommerce.identity.internal.persistence.PlatformUserRepository;
import com.unispark.ecommerce.identity.internal.persistence.StaffUserRepository;
import com.unispark.ecommerce.shared.TenantContext;

/**
 * The four security chains (SPEC 10): who can log in where, and that a session from one area is refused in every
 * other area and on the wrong host.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfig.class, IsolationProbeWeb.class})
@ActiveProfiles("test")
class SecurityChainsIT {

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    StaffUserRepository staffUsers;

    @Autowired
    PlatformUserRepository platformUsers;

    @Autowired
    PasswordEncoder passwords;

    String slug;
    UUID store;
    String ownerEmail;
    String driverPhone;
    String staffEmail;
    UUID driverId;

    @BeforeEach
    void createStoreWithStaff() {
        slug = TestStores.randomSlug();
        store = TestStores.create(jdbc, slug, "ACTIVE");
        ownerEmail = "owner@" + slug + ".test";
        staffEmail = "staff@" + slug + ".test";
        driverPhone = "+96393" + String.format("%07d", Math.abs(slug.hashCode()) % 10_000_000);
        TestAccounts.staff(staffUsers, passwords, store, "Owner", ownerEmail, "+963944111222", StaffRole.OWNER);
        TestAccounts.staff(staffUsers, passwords, store, "Staff", staffEmail, null, StaffRole.STAFF);
        driverId = TestAccounts.staff(staffUsers, passwords, store, "Driver", null, driverPhone, StaffRole.DRIVER);
    }

    // --- Anonymous ---

    @Test
    void sendsAnonymousVisitorsToTheLoginOfEachArea() throws Exception {
        mvc.perform(get(MAIN + "/admin")).andExpect(redirectedUrlPattern("**/admin/login"));
        mvc.perform(get(MAIN + "/admin/staff")).andExpect(redirectedUrlPattern("**/admin/login"));
        mvc.perform(get(MAIN + "/driver")).andExpect(redirectedUrlPattern("**/driver/login"));
        mvc.perform(get(PLATFORM + "/platform")).andExpect(redirectedUrlPattern("**/platform/login"));
    }

    @Test
    void showsTheLoginPagesInArabic() throws Exception {
        mvc.perform(get(MAIN + "/admin/login"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("الدخول إلى لوحة المتجر")))
                .andExpect(content().string(containsString("name=\"_csrf\"")));
        mvc.perform(get(MAIN + "/driver/login"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("دخول السائق")));
        mvc.perform(get(PLATFORM + "/platform/login"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("إدارة المنصة")));
    }

    // --- Staff login ---

    @Test
    void ownerLogsInWithStoreAndEmailAndReachesTheDashboard() throws Exception {
        var result = staffLogin("admin", slug, ownerEmail, PASSWORD)
                .andExpect(redirectedUrl("/admin"))
                .andExpect(cookie().value("store", slug))
                .andReturn();
        var session = (MockHttpSession) result.getRequest().getSession(false);

        mvc.perform(get(MAIN + "/admin").session(session))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("أهلاً Owner")))
                .andExpect(content().string(containsString(slug + ".lvh.me:8080")));
        assertThat(TenantContext.currentTenantId()).isEmpty();
    }

    @Test
    void acceptsThePhoneNumberInAnySpelling() throws Exception {
        staffLogin("admin", slug.toUpperCase(), "0944 111 222", PASSWORD).andExpect(redirectedUrl("/admin"));
    }

    @Test
    void sendsEachRoleToItsOwnAreaWhicheverLoginPageWasUsed() throws Exception {
        staffLogin("driver", slug, ownerEmail, PASSWORD).andExpect(redirectedUrl("/admin"));
        staffLogin("admin", slug, driverPhone, PASSWORD).andExpect(redirectedUrl("/driver"));
    }

    @Test
    void returnsToThePageThatAskedForLogin() throws Exception {
        var session = new MockHttpSession();
        mvc.perform(get(MAIN + "/admin/staff").session(session)).andExpect(redirectedUrlPattern("**/admin/login"));

        mvc.perform(post(MAIN + "/admin/login").session(session).with(csrf()).with(fromNewAddress())
                        .param("store", slug).param("username", ownerEmail).param("password", PASSWORD))
                .andExpect(redirectedUrl("/admin/staff"));
    }

    @Test
    void rejectsWrongPasswordsUnknownStoresAndUnknownUsersAlike() throws Exception {
        staffLogin("admin", slug, ownerEmail, "wrong-password").andExpect(redirectedUrl("/admin/login?error&store=" + slug));
        staffLogin("admin", "nosuchstore", ownerEmail, PASSWORD).andExpect(redirectedUrl("/admin/login?error&store=nosuchstore"));
        staffLogin("admin", slug, "nobody@" + slug + ".test", PASSWORD).andExpect(redirectedUrl("/admin/login?error&store=" + slug));
        staffLogin("admin", slug, "not a phone", PASSWORD).andExpect(redirectedUrlPattern("/admin/login?error*"));
    }

    @Test
    void staffOfOneStoreCannotLogInThroughAnotherStore() throws Exception {
        var otherSlug = TestStores.randomSlug();
        TestStores.create(jdbc, otherSlug, "ACTIVE");

        staffLogin("admin", otherSlug, ownerEmail, PASSWORD).andExpect(redirectedUrlPattern("/admin/login?error*"));
    }

    @Test
    void refusesStaffOfClosedStores() throws Exception {
        jdbc.update("UPDATE store SET status = 'CLOSED' WHERE id = ?", store);

        staffLogin("admin", slug, ownerEmail, PASSWORD).andExpect(redirectedUrlPattern("/admin/login?error*"));
    }

    @Test
    void refusesDisabledStaffAfterTheirPasswordIsChecked() throws Exception {
        TenantContext.runAs(store, () -> staffUsers.findById(driverId).ifPresent(driver -> {
            driver.setActive(false);
            staffUsers.save(driver);
        }));

        staffLogin("driver", slug, driverPhone, PASSWORD).andExpect(redirectedUrl("/driver/login?disabled&store=" + slug));
        staffLogin("driver", slug, driverPhone, "wrong-password").andExpect(redirectedUrlPattern("/driver/login?error*"));
    }

    @Test
    void requiresTheCsrfTokenToLogIn() throws Exception {
        mvc.perform(post(MAIN + "/admin/login").with(fromNewAddress())
                        .param("store", slug).param("username", ownerEmail).param("password", PASSWORD))
                .andExpect(status().isForbidden());
    }

    @Test
    void changesTheSessionIdOnLogin() throws Exception {
        var session = new MockHttpSession();
        var before = session.getId();

        var result = mvc.perform(post(MAIN + "/admin/login").session(session).with(csrf()).with(fromNewAddress())
                        .param("store", slug).param("username", ownerEmail).param("password", PASSWORD))
                .andReturn();

        assertThat(result.getRequest().getSession(false).getId()).isNotEqualTo(before);
    }

    // --- Rate limits ---

    @Test
    void locksAnAccountAfterFiveFailedAttemptsEvenForTheRightPassword() throws Exception {
        for (int attempt = 0; attempt < 5; attempt++) {
            staffLogin("admin", slug, ownerEmail, "wrong-" + attempt).andExpect(redirectedUrlPattern("/admin/login?error*"));
        }

        staffLogin("admin", slug, ownerEmail, PASSWORD).andExpect(redirectedUrlPattern("/admin/login?locked*"));
        // Other accounts of the same store are unaffected.
        staffLogin("admin", slug, staffEmail, PASSWORD).andExpect(redirectedUrl("/admin"));
    }

    @Test
    void limitsLoginAttemptsPerClientAddressAcrossAccounts() throws Exception {
        var address = from("192.0.2." + (1 + Math.abs(slug.hashCode()) % 250));
        for (int attempt = 0; attempt < 30; attempt++) {
            mvc.perform(post(MAIN + "/admin/login").with(csrf()).with(address)
                            .param("store", slug).param("username", "user" + attempt + "@x.test").param("password", "x"))
                    .andExpect(redirectedUrlPattern("/admin/login?error*"));
        }

        mvc.perform(post(MAIN + "/admin/login").with(csrf()).with(address)
                        .param("store", slug).param("username", ownerEmail).param("password", PASSWORD))
                .andExpect(redirectedUrlPattern("/admin/login?locked*"));
    }

    // --- Cross-chain access ---

    @Test
    void ownerSessionIsRefusedInTheDriverAndPlatformAreas() throws Exception {
        var owner = staffSession(ownerEmail);

        mvc.perform(get(MAIN + "/admin").session(owner)).andExpect(status().isOk());
        mvc.perform(get(MAIN + "/driver").session(owner)).andExpect(status().isForbidden());
        // The browser would not send this host-only cookie to admin.<base>; a replayed one still gets nothing.
        mvc.perform(get(PLATFORM + "/platform").session(owner)).andExpect(status().isForbidden());
    }

    @Test
    void driverSessionIsRefusedInTheMerchantAdmin() throws Exception {
        var driver = staffSession(driverPhone);

        mvc.perform(get(MAIN + "/driver").session(driver))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("مرحباً Driver")));
        mvc.perform(get(MAIN + "/admin").session(driver)).andExpect(status().isForbidden());
    }

    @Test
    void staffRoleCannotManageStaff() throws Exception {
        var staff = staffSession(staffEmail);

        mvc.perform(get(MAIN + "/admin").session(staff)).andExpect(status().isOk());
        mvc.perform(get(MAIN + "/admin/staff").session(staff)).andExpect(status().isForbidden());
    }

    @Test
    void platformAdminSessionIsRefusedInTheMerchantAndDriverAreas() throws Exception {
        var email = TestAccounts.platformAdmin(platformUsers, passwords);
        var result = mvc.perform(post(PLATFORM + "/platform/login").with(csrf()).with(fromNewAddress())
                        .param("username", email).param("password", PASSWORD))
                .andExpect(redirectedUrl("/platform"))
                .andReturn();
        var admin = (MockHttpSession) result.getRequest().getSession(false);

        mvc.perform(get(PLATFORM + "/platform").session(admin))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(slug)));
        mvc.perform(get(MAIN + "/admin").session(admin)).andExpect(status().isForbidden());
        mvc.perform(get(MAIN + "/driver").session(admin)).andExpect(status().isForbidden());
        mvc.perform(get(PLATFORM + "/admin").session(admin)).andExpect(status().isForbidden());
    }

    @Test
    void credentialsOnlyWorkOnTheirOwnLoginPage() throws Exception {
        var email = TestAccounts.platformAdmin(platformUsers, passwords);

        staffLogin("admin", slug, email, PASSWORD).andExpect(redirectedUrlPattern("/admin/login?error*"));
        mvc.perform(post(PLATFORM + "/platform/login").with(csrf()).with(fromNewAddress())
                        .param("username", ownerEmail).param("password", PASSWORD))
                .andExpect(redirectedUrl("/platform/login?error"));
    }

    // --- Areas on the wrong host ---

    @Test
    void storefrontHostsServeNoAdminDriverPlatformOrSignupPages() throws Exception {
        for (var path : new String[] {"/admin", "/admin/login", "/driver", "/platform", "/signup"}) {
            mvc.perform(get(storefront(slug) + path)).andExpect(status().isNotFound());
        }
    }

    @Test
    void mainSiteServesNoPlatformPages() throws Exception {
        mvc.perform(get(MAIN + "/platform")).andExpect(status().isNotFound());
        mvc.perform(get(MAIN + "/platform/login")).andExpect(status().isNotFound());
    }

    // --- Session lifecycle ---

    @Test
    void endsTheSessionOfStaffDisabledWhileLoggedIn() throws Exception {
        var driver = staffSession(driverPhone);
        TenantContext.runAs(store, () -> staffUsers.findById(driverId).ifPresent(user -> {
            user.setActive(false);
            staffUsers.save(user);
        }));

        mvc.perform(get(MAIN + "/driver").session(driver)).andExpect(redirectedUrl("/driver/login?expired"));
        mvc.perform(get(MAIN + "/driver").session(driver)).andExpect(redirectedUrlPattern("**/driver/login"));
    }

    @Test
    void endsStaffSessionsWhenTheStoreIsClosed() throws Exception {
        var owner = staffSession(ownerEmail);
        jdbc.update("UPDATE store SET status = 'CLOSED' WHERE id = ?", store);

        mvc.perform(get(MAIN + "/admin").session(owner)).andExpect(redirectedUrl("/admin/login?expired"));
    }

    @Test
    void logsOut() throws Exception {
        var owner = staffSession(ownerEmail);

        mvc.perform(post(MAIN + "/admin/logout").session(owner).with(csrf()))
                .andExpect(redirectedUrl("/admin/login?logout"));
        mvc.perform(get(MAIN + "/admin").session(owner)).andExpect(redirectedUrlPattern("**/admin/login"));
    }

    private ResultActions staffLogin(String area, String storeSlug,
            String username, String password) throws Exception {
        return staffLogin(area, storeSlug, username, password, fromNewAddress());
    }

    private ResultActions staffLogin(String area, String storeSlug,
            String username, String password, RequestPostProcessor address) throws Exception {
        return mvc.perform(post(MAIN + "/" + area + "/login").with(csrf()).with(address)
                .param("store", storeSlug).param("username", username).param("password", password));
    }

    private MockHttpSession staffSession(String username) throws Exception {
        var result = staffLogin("admin", slug, username, PASSWORD).andReturn();
        assertThat(result.getResponse().getRedirectedUrl()).doesNotContain("login");
        return (MockHttpSession) result.getRequest().getSession(false);
    }
}
