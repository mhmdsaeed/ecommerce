package com.unispark.ecommerce;

import static com.unispark.ecommerce.TestAccounts.MAIN;
import static com.unispark.ecommerce.TestAccounts.storefront;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import com.unispark.ecommerce.catalog.Categories;
import com.unispark.ecommerce.catalog.Categories.CategoryDraft;
import com.unispark.ecommerce.identity.StaffRole;
import com.unispark.ecommerce.identity.internal.persistence.StaffUserRepository;
import com.unispark.ecommerce.shared.TenantContext;

/**
 * Lists keep working when someone edits their URL: an unknown filter, sort or page value is ignored instead of
 * answered with an error page (found by the M9 runtime check: {@code payment=NOPE} gave 400).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfig.class, IsolationProbeWeb.class})
@ActiveProfiles("test")
class LenientListParamsIT {

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    StaffUserRepository staffUsers;

    @Autowired
    PasswordEncoder passwords;

    @Autowired
    Categories categories;

    @Test
    void adminListsIgnoreUnknownFiltersAndPages() throws Exception {
        var slug = TestStores.randomSlug();
        var store = TestStores.create(jdbc, slug, "ACTIVE");
        TestAccounts.staff(staffUsers, passwords, store, "Owner", "owner@" + slug + ".test", null, StaffRole.OWNER);
        var owner = TestAccounts.logIn(mvc, slug, "owner@" + slug + ".test");

        mvc.perform(get(MAIN + "/admin/orders").param("payment", "NOPE").param("page", "abc").param("view", "__x__")
                        .session(owner))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("لا توجد طلبات")));
        mvc.perform(get(MAIN + "/admin/products").param("status", "NOPE").param("page", "-1").session(owner))
                .andExpect(status().isOk());
        mvc.perform(get(MAIN + "/admin/inventory").param("page", "x").session(owner)).andExpect(status().isOk());
        mvc.perform(get(MAIN + "/admin/customers").param("page", "9999999999").session(owner))
                .andExpect(status().isOk());
    }

    @Test
    void storefrontListsIgnoreUnknownSortsAndPages() throws Exception {
        var slug = TestStores.randomSlug();
        var store = TestStores.create(jdbc, slug, "ACTIVE");
        TenantContext.runAs(store, () -> categories.create(new CategoryDraft("أحذية", "Shoes", "shoes", null, 0)));

        mvc.perform(get(storefront(slug) + "/c/shoes").param("sort", "PRICE_SIDEWAYS").param("page", "two"))
                .andExpect(status().isOk());
        mvc.perform(get(storefront(slug) + "/search").param("q", "x").param("page", "%%"))
                .andExpect(status().isOk());
    }
}
