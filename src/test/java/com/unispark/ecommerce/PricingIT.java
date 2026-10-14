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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
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
import com.unispark.ecommerce.pricing.ExchangeRates;
import com.unispark.ecommerce.pricing.Prices;
import com.unispark.ecommerce.shared.Currency;
import com.unispark.ecommerce.shared.Money;
import com.unispark.ecommerce.shared.NumberDigits;
import com.unispark.ecommerce.shared.RoundingRule;
import com.unispark.ecommerce.shared.TenantContext;
import com.unispark.ecommerce.tenant.StoreSettings;

/** Pricing settings, exchange rates and displayed prices (SPEC 7), through the admin and the Prices API. */
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfig.class, IsolationProbeWeb.class})
@ActiveProfiles("test")
class PricingIT {

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    StaffUserRepository staffUsers;

    @Autowired
    PasswordEncoder passwords;

    @Autowired
    ExchangeRates rates;

    @Autowired
    Prices prices;

    @Autowired
    StoreSettings settings;

    String slug;
    UUID store;
    MockHttpSession owner;

    @BeforeEach
    void createStore() throws Exception {
        slug = TestStores.randomSlug();
        store = TestStores.create(jdbc, slug, "ACTIVE");
        TestAccounts.staff(staffUsers, passwords, store, "Rana", "owner@" + slug + ".test", null, StaffRole.OWNER);
        owner = TestAccounts.logIn(mvc, slug, "owner@" + slug + ".test");
    }

    @Test
    void setsTheRateOverHtmxShowingThePreviewAndKeepingHistory() throws Exception {
        mvc.perform(setRate("118.5"))
                .andExpect(status().isOk())
                .andExpect(content().string(allOf(
                        not(containsString("<html")), containsString("تم تحديث سعر الصرف"),
                        containsString("1 دولار = 118.5 ل.س"),
                        // NEAREST_5 is the default rule: $4.50 x 118.5 = 533.25, shown as 535.
                        containsString("535 ل.س"), containsString("120 ل.س"),
                        // Dollars are rounded to cents: 15,000 / 118.5 = 126.58.
                        containsString("126.58 $"))));

        mvc.perform(setRate("١٢٠٫٢٥"))
                .andExpect(content().string(allOf(containsString("1 دولار = 120.25 ل.س"), containsString("السعر السابق"),
                        containsString("118.5"), containsString("Rana"))));

        assertThat(TenantContext.callAs(store, () -> rates.history(10)))
                .extracting(rate -> rate.sypPerUsd()).containsExactly(new BigDecimal("120.25"), new BigDecimal("118.5"));
    }

    @Test
    void rejectsRatesThatCannotBeRight() throws Exception {
        mvc.perform(setRate("abc")).andExpect(content().string(containsString("اكتب السعر بالأرقام")));
        mvc.perform(setRate("0")).andExpect(content().string(containsString("اكتب السعر بالأرقام")));
        mvc.perform(setRate("2,000,000")).andExpect(content().string(containsString("هذا السعر مرتفع جداً")));
        mvc.perform(setRate("12,5")).andExpect(content().string(containsString("اكتب السعر بالأرقام")));

        assertThat(TenantContext.callAs(store, () -> rates.current())).isEmpty();
    }

    @Test
    void convertsDisplayedPricesWithTheStoreRuleAndFallsBackWithoutARate() {
        var price = Money.of(new BigDecimal("4.50"), Currency.USD);

        var withoutRate = TenantContext.callAs(store, () -> prices.display(price));
        assertThat(withoutRate.converted()).isFalse();
        assertThat(withoutRate.amount()).isEqualTo(price);

        TenantContext.runAs(store, () -> rates.set("118.5", null));
        assertThat(TenantContext.callAs(store, () -> prices.display(price)).amount())
                .isEqualTo(Money.of(new BigDecimal("535"), Currency.SYP));

        TenantContext.runAs(store, () -> settings.updatePricing(Currency.SYP, Currency.SYP, RoundingRule.NEAREST_100,
                NumberDigits.WESTERN));
        var rounded = TenantContext.callAs(store, () -> prices.display(price));
        assertThat(rounded.amount()).isEqualTo(Money.of(new BigDecimal("500"), Currency.SYP));
        assertThat(rounded.rate()).isEqualByComparingTo("118.5");

        var ownCurrency = Money.of(new BigDecimal("1234.56"), Currency.SYP);
        assertThat(TenantContext.callAs(store, () -> prices.display(ownCurrency)).amount()).isEqualTo(ownCurrency);
    }

    @Test
    void appliesTheStoreDigitsToEveryNumberOnItsPages() throws Exception {
        mvc.perform(post(MAIN + "/admin/products").session(owner).with(csrf())
                .param("nameAr", "شاي").param("priceCurrency", "SYP").param("price", "150000"));
        mvc.perform(get(MAIN + "/admin/products").session(owner))
                .andExpect(content().string(containsString("150,000 ل.س")));

        mvc.perform(post(MAIN + "/admin/settings/pricing").session(owner).with(csrf())
                        .param("baseCurrency", "SYP").param("displayCurrency", "SYP")
                        .param("roundingRule", "NEAREST_5").param("numberDigits", "ARABIC_INDIC"))
                .andExpect(redirectedUrl("/admin/settings/pricing?saved"));

        mvc.perform(get(MAIN + "/admin/products").session(owner))
                .andExpect(content().string(allOf(containsString("١٥٠٬٠٠٠ ل.س"), not(containsString("150,000")))));
        // Inside the store's tenant: without it, row-level security hides the product from app_user.
        var productId = TenantContext.callAs(store, () -> jdbc.queryForObject("SELECT id FROM product", UUID.class));
        mvc.perform(get(MAIN + "/admin/products/{id}", productId).session(owner))
                .andExpect(content().string(allOf(
                        containsString("الصور (٠ من ١٠)"),
                        // Inputs keep plain digits, which the parser accepts either way.
                        containsString("value=\"150000\""))));
        mvc.perform(setRate("118.5"))
                .andExpect(content().string(containsString("١ دولار = ١١٨٫٥ ل.س")));
    }

    @Test
    void theOtherStoreKeepsItsOwnSettingsAndRates() throws Exception {
        var otherSlug = TestStores.randomSlug();
        var other = TestStores.create(jdbc, otherSlug, "ACTIVE");
        mvc.perform(setRate("118.5"));
        TenantContext.runAs(store, () -> settings.updatePricing(Currency.USD, Currency.USD, RoundingRule.NONE,
                NumberDigits.ARABIC_INDIC));

        assertThat(TenantContext.callAs(other, () -> rates.current())).isEmpty();
        assertThat(TenantContext.callAs(other, () -> settings.current()))
                .satisfies(otherSettings -> {
                    assertThat(otherSettings.displayCurrency()).isEqualTo(Currency.SYP);
                    assertThat(otherSettings.numberDigits()).isEqualTo(NumberDigits.WESTERN);
                });
    }

    @Test
    void remindsManagersToSetARateAndDefaultsNewProductsToTheBaseCurrency() throws Exception {
        mvc.perform(get(MAIN + "/admin").session(owner))
                .andExpect(content().string(containsString("لم تحدد سعر صرف الدولار بعد")));
        mvc.perform(setRate("118.5"));
        mvc.perform(post(MAIN + "/admin/settings/pricing").session(owner).with(csrf())
                .param("baseCurrency", "USD").param("displayCurrency", "SYP")
                .param("roundingRule", "NEAREST_10").param("numberDigits", "WESTERN"));

        mvc.perform(get(MAIN + "/admin").session(owner))
                .andExpect(content().string(not(containsString("لم تحدد سعر صرف الدولار بعد"))));
        mvc.perform(get(MAIN + "/admin/products/new").session(owner))
                .andExpect(content().string(containsString("<option value=\"USD\" selected=\"selected\"")));
    }

    @Test
    void onlyOwnersAndManagersChangePricing() throws Exception {
        TestAccounts.staff(staffUsers, passwords, store, "Clerk", "clerk@" + slug + ".test", null, StaffRole.STAFF);
        var clerk = TestAccounts.logIn(mvc, slug, "clerk@" + slug + ".test");

        mvc.perform(get(MAIN + "/admin/settings/pricing").session(clerk)).andExpect(status().isForbidden());
        mvc.perform(post(MAIN + "/admin/settings/pricing/rate").session(clerk).with(csrf()).param("rate", "1"))
                .andExpect(status().isForbidden());
        mvc.perform(get(MAIN + "/admin").session(clerk))
                .andExpect(content().string(not(containsString("لم تحدد سعر صرف الدولار بعد"))));
    }

    private MockHttpServletRequestBuilder setRate(String rate) {
        return post(MAIN + "/admin/settings/pricing/rate").session(owner).with(csrf()).header("HX-Request", "true")
                .param("rate", rate);
    }
}
