package com.unispark.ecommerce;

import static com.unispark.ecommerce.TestAccounts.MAIN;
import static com.unispark.ecommerce.TestAccounts.PLATFORM;
import static com.unispark.ecommerce.TestAccounts.storefront;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.hamcrest.Matcher;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Each area loads app.js and its own script, and Alpine after them: Alpine starts as soon as it runs, so the
 * components it finds must be registered by then.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfig.class)
@ActiveProfiles("test")
class PageScriptsIT {

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void storefrontLoadsItsScriptBeforeAlpineAndNotTheAdminOne() throws Exception {
        var slug = TestStores.randomSlug();
        TestStores.create(jdbc, slug, "ACTIVE", "{\"ar\": \"متجر\", \"en\": \"Shop\"}");

        mvc.perform(get(storefront(slug) + "/"))
                .andExpect(status().isOk())
                .andExpect(content().string(allOf(
                        scripts("app", "storefront", "alpine\\.min"),
                        not(containsString("/js/admin-")))));
    }

    @Test
    void merchantAdminLoadsItsScriptBeforeAlpineAndNotTheStorefrontOne() throws Exception {
        mvc.perform(get(MAIN + "/signup"))
                .andExpect(status().isOk())
                .andExpect(content().string(allOf(
                        scripts("app", "admin", "alpine\\.min"),
                        not(containsString("/js/storefront-")))));
    }

    @Test
    void otherPagesLoadOnlyTheSharedScript() throws Exception {
        for (var page : new String[] {MAIN + "/welcome", PLATFORM + "/platform/login"}) {
            mvc.perform(get(page))
                    .andExpect(status().isOk())
                    .andExpect(content().string(allOf(
                            scripts("app", "alpine\\.min"),
                            not(containsString("/js/admin-")),
                            not(containsString("/js/storefront-")))));
        }
    }

    /** The page loads these scripts (content-hashed) in this order. */
    private static Matcher<String> scripts(String... names) {
        var pattern = new StringBuilder("(?s)");
        for (var name : names) {
            pattern.append(".*<script src=\"/js/").append(name).append("-[0-9a-f]{32}\\.js\" defer>");
        }
        return matchesPattern(pattern.append(".*").toString());
    }
}
