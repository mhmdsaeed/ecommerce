package com.unispark.ecommerce;

import static com.unispark.ecommerce.TestAccounts.MAIN;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;
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

import com.unispark.ecommerce.catalog.Products;
import com.unispark.ecommerce.catalog.Products.ProductDraft;
import com.unispark.ecommerce.catalog.Products.Saved;
import com.unispark.ecommerce.identity.StaffRole;
import com.unispark.ecommerce.identity.internal.persistence.StaffUserRepository;
import com.unispark.ecommerce.inventory.Inventory;
import com.unispark.ecommerce.inventory.StockLevels.Level;
import com.unispark.ecommerce.shared.Currency;
import com.unispark.ecommerce.shared.TenantContext;

/** The merchant admin's inventory page (SPEC 9.3). */
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfig.class, IsolationProbeWeb.class})
@ActiveProfiles("test")
class InventoryAdminIT {

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    StaffUserRepository staffUsers;

    @Autowired
    PasswordEncoder passwords;

    @Autowired
    Products products;

    @Autowired
    Inventory inventory;

    UUID store;
    UUID kettle;
    MockHttpSession staff;

    @BeforeEach
    void logInStaff() throws Exception {
        var slug = TestStores.randomSlug();
        store = TestStores.create(jdbc, slug, "ACTIVE");
        TestAccounts.staff(staffUsers, passwords, store, "Staff", "staff@" + slug + ".test", null, StaffRole.STAFF);
        staff = TestAccounts.logIn(mvc, slug, "staff@" + slug + ".test");
        kettle = TenantContext.callAs(store, () -> {
            var id = ((Saved) products.create(new ProductDraft("غلاية", "Kettle", null, null, "kettle", null,
                    Currency.SYP), "100")).id();
            products.create(new ProductDraft("فنجان", "Cup", null, null, "cup", null, Currency.SYP), "50");
            return products.find(id).orElseThrow().variants().getFirst().id();
        });
    }

    @Test
    void listsVariantsAndSearches() throws Exception {
        mvc.perform(get(MAIN + "/admin/inventory").session(staff))
                .andExpect(status().isOk())
                .andExpect(content().string(allOf(containsString("غلاية"), containsString("فنجان"),
                        containsString("placeholder=\"غير متتبَّع\""))));
        mvc.perform(get(MAIN + "/admin/inventory").param("q", "kettle").header("HX-Request", "true").session(staff))
                .andExpect(content().string(allOf(containsString("غلاية"), not(containsString("فنجان")),
                        not(containsString("<html")))));
    }

    @Test
    void savesStockTypedWithArabicDigits() throws Exception {
        mvc.perform(post(MAIN + "/admin/inventory/" + kettle).param("onHand", "١٢").header("HX-Request", "true")
                        .session(staff).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(content().string(allOf(containsString("value=\"12\""), containsString("تم الحفظ"),
                        containsString("محجوز 0 · متاح 12"))));
        assertThat(level()).isEqualTo(new Level(kettle, 12, 0));

        mvc.perform(post(MAIN + "/admin/inventory/" + kettle).param("onHand", "").header("HX-Request", "true")
                        .session(staff).with(csrf()))
                .andExpect(content().string(containsString("تم الحفظ")));
        assertThat(TenantContext.callAs(store, () -> inventory.levels(List.of(kettle)).byVariant())).isEmpty();
    }

    @Test
    void explainsRejectedQuantities() throws Exception {
        mvc.perform(post(MAIN + "/admin/inventory/" + kettle).param("onHand", "-3").header("HX-Request", "true")
                        .session(staff).with(csrf()))
                .andExpect(content().string(allOf(containsString("أدخل عدداً صحيحاً"), containsString("aria-invalid=\"true\""))));

        TenantContext.runAs(store, () -> {
            inventory.setOnHand(kettle, 5);
            inventory.reserve(Map.of(kettle, 4));
        });
        mvc.perform(post(MAIN + "/admin/inventory/" + kettle).param("onHand", "2").header("HX-Request", "true")
                        .session(staff).with(csrf()))
                .andExpect(content().string(containsString("لا يمكن أن تقل الكمية عن المحجوز للطلبات (4).")));
        assertThat(level()).isEqualTo(new Level(kettle, 5, 4));

        mvc.perform(post(MAIN + "/admin/inventory/" + UUID.randomUUID()).param("onHand", "1")
                        .header("HX-Request", "true").session(staff).with(csrf()))
                .andExpect(status().isNotFound());
    }

    private Level level() {
        return TenantContext.callAs(store, () -> inventory.levels(List.of(kettle)).of(kettle).orElseThrow());
    }
}
