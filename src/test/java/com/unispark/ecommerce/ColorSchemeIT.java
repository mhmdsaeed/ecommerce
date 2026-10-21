package com.unispark.ecommerce;

import static com.unispark.ecommerce.TestAccounts.MAIN;
import static com.unispark.ecommerce.TestAccounts.PASSWORD;
import static com.unispark.ecommerce.TestAccounts.PLATFORM;
import static com.unispark.ecommerce.TestAccounts.fromNewAddress;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;

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

import com.unispark.ecommerce.identity.StaffRole;
import com.unispark.ecommerce.identity.internal.persistence.PlatformUserRepository;
import com.unispark.ecommerce.identity.internal.persistence.StaffUserRepository;

/**
 * Light or dark admin screens: each person's choice, remembered on their account, for the merchant admin, the
 * driver view and the platform admin. The storefront has no switch.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestcontainersConfig.class)
class ColorSchemeIT {

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

    @BeforeEach
    void createStore() {
        slug = TestStores.randomSlug();
        store = TestStores.create(jdbc, slug, "ACTIVE");
        TestAccounts.staff(staffUsers, passwords, store, "Owner", "owner@" + slug + ".test", null, StaffRole.OWNER);
        TestAccounts.staff(staffUsers, passwords, store, "Driver", null, "+963933800001", StaffRole.DRIVER);
    }

    @Test
    void merchantsChooseDarkAndKeepItAcrossSessions() throws Exception {
        var owner = TestAccounts.logIn(mvc, slug, "owner@" + slug + ".test");
        mvc.perform(get(MAIN + "/admin/orders").session(owner))
                .andExpect(content().string(allOf(containsString("data-scheme=\"device\""),
                        pressed("DEVICE"), containsString("prefers-color-scheme: dark"))));

        mvc.perform(post(MAIN + "/admin/scheme").session(owner).with(csrf()).param("scheme", "DARK")
                        .header("Referer", MAIN + "/admin/orders?view=all"))
                .andExpect(redirectedUrl("/admin/orders?view=all"));

        var again = TestAccounts.logIn(mvc, slug, "owner@" + slug + ".test");
        mvc.perform(get(MAIN + "/admin").session(again))
                .andExpect(content().string(allOf(containsString("data-scheme=\"dark\""), pressed("DARK"))));
    }

    @Test
    void goesBackOnlyToAPageOfTheSameArea() throws Exception {
        var owner = TestAccounts.logIn(mvc, slug, "owner@" + slug + ".test");
        mvc.perform(post(MAIN + "/admin/scheme").session(owner).with(csrf()).param("scheme", "LIGHT")
                        .header("Referer", "https://evil.example/admin/orders"))
                .andExpect(redirectedUrl("/admin"));
        mvc.perform(post(MAIN + "/admin/scheme").session(owner).with(csrf()).param("scheme", "LIGHT")
                        .header("Referer", MAIN + "/signup"))
                .andExpect(redirectedUrl("/admin"));
        // Something that is not a choice changes nothing.
        mvc.perform(post(MAIN + "/admin/scheme").session(owner).with(csrf()).param("scheme", "PURPLE"));
        mvc.perform(get(MAIN + "/admin").session(owner))
                .andExpect(content().string(containsString("data-scheme=\"light\"")));
    }

    @Test
    void driversAndPlatformAdminsHaveTheSwitchToo() throws Exception {
        var driver = TestAccounts.logIn(mvc, slug, "0933800001");
        mvc.perform(post(MAIN + "/driver/scheme").session(driver).with(csrf()).param("scheme", "DARK"))
                .andExpect(redirectedUrl("/driver"));
        mvc.perform(get(MAIN + "/driver").session(driver))
                .andExpect(content().string(allOf(containsString("data-scheme=\"dark\""), pressed("DARK"))));

        var platform = platformAdmin();
        mvc.perform(get(PLATFORM + "/platform").session(platform))
                .andExpect(content().string(containsString("data-scheme=\"device\"")));
        mvc.perform(post(PLATFORM + "/platform/scheme").session(platform).with(csrf()).param("scheme", "DARK")
                        .header("Referer", PLATFORM + "/platform/stores"))
                .andExpect(redirectedUrl("/platform/stores"));
        mvc.perform(get(PLATFORM + "/platform/stores").session(platform))
                .andExpect(content().string(containsString("data-scheme=\"dark\"")));
    }

    @Test
    void storefrontsAndLoginPagesHaveNoScheme() throws Exception {
        mvc.perform(get(TestAccounts.storefront(slug) + "/"))
                .andExpect(content().string(not(containsString("data-scheme"))));
        mvc.perform(get(MAIN + "/admin/login"))
                .andExpect(content().string(not(containsString("data-scheme"))));
    }

    private static org.hamcrest.Matcher<String> pressed(String scheme) {
        return matchesPattern("(?s).*value=\"" + scheme + "\" aria-pressed=\"true\".*");
    }

    private MockHttpSession platformAdmin() throws Exception {
        var email = TestAccounts.platformAdmin(platformUsers, passwords);
        var result = mvc.perform(post(PLATFORM + "/platform/login").with(csrf()).with(fromNewAddress())
                        .param("username", email).param("password", PASSWORD))
                .andExpect(redirectedUrl("/platform"))
                .andReturn();
        return (MockHttpSession) result.getRequest().getSession(false);
    }
}
