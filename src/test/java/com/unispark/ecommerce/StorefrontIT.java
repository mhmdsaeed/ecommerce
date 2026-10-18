package com.unispark.ecommerce;

import static com.unispark.ecommerce.TestAccounts.MAIN;
import static com.unispark.ecommerce.TestAccounts.storefront;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.UUID;

import jakarta.servlet.http.Cookie;

import org.junit.jupiter.api.BeforeEach;
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
import com.unispark.ecommerce.catalog.ProductStatus;
import com.unispark.ecommerce.catalog.Products;
import com.unispark.ecommerce.catalog.Products.OptionDraft;
import com.unispark.ecommerce.catalog.Products.ProductDraft;
import com.unispark.ecommerce.catalog.Products.VariantDraft;
import com.unispark.ecommerce.delivery.DeliveryZones;
import com.unispark.ecommerce.delivery.DeliveryZones.ZoneDraft;
import com.unispark.ecommerce.delivery.Governorate;
import com.unispark.ecommerce.identity.StaffRole;
import com.unispark.ecommerce.payment.PaymentMethod;
import com.unispark.ecommerce.payment.PaymentMethods;
import com.unispark.ecommerce.payment.PaymentMethods.MethodDraft;
import com.unispark.ecommerce.identity.internal.persistence.StaffUserRepository;
import com.unispark.ecommerce.pricing.ExchangeRates;
import com.unispark.ecommerce.shared.Currency;
import com.unispark.ecommerce.shared.LocalizedText;
import com.unispark.ecommerce.shared.TenantContext;
import com.unispark.ecommerce.tenant.StoreSettings;
import com.unispark.ecommerce.tenant.StoreTheme;

/** M5 done-when: a shopper browses a store in Arabic and English (SPEC 9.2, 17). */
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfig.class, IsolationProbeWeb.class})
@ActiveProfiles("test")
class StorefrontIT {

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    Categories categories;

    @Autowired
    Products products;

    @Autowired
    ExchangeRates rates;

    @Autowired
    StoreSettings settings;

    @Autowired
    DeliveryZones zones;

    @Autowired
    PaymentMethods methods;

    @Autowired
    StaffUserRepository staffUsers;

    @Autowired
    PasswordEncoder passwords;

    String slug;
    UUID store;
    String shop;

    @BeforeEach
    void createCatalog() {
        slug = TestStores.randomSlug();
        store = TestStores.create(jdbc, slug, "ACTIVE", "{\"ar\": \"متجر الورد\", \"en\": \"Rose Shop\"}");
        shop = storefront(slug);
        TenantContext.runAs(store, () -> {
            rates.set("118.5", null);
            var clothes = saved(categories.create(new CategoryDraft("ملابس", "Clothes", "clothes", null, 1)));
            var shirts = saved(categories.create(new CategoryDraft("قمصان", "Shirts", "shirts", clothes, 1)));
            var hidden = saved(categories.create(new CategoryDraft("موسمي", "Seasonal", "seasonal", null, 2)));
            categories.setActive(hidden, false);

            var shirt = published(new ProductDraft("قميص أحمر", "Red shirt", "قطن", "Cotton", "red-shirt", shirts,
                    Currency.SYP), "1000");
            products.defineOptions(shirt, List.of(new OptionDraft("المقاس", "Size", "S, M", "S, M")));
            var variants = products.find(shirt).orElseThrow().variants();
            products.updateVariants(shirt, List.of(
                    new VariantDraft(variants.get(0).id(), null, "1000", null, true),
                    new VariantDraft(variants.get(1).id(), null, "1200", "1500", true)));
            published(new ProductDraft("كوب", "Mug", null, null, "mug", clothes, Currency.USD), "4.50");
            published(new ProductDraft("جوارب", "Socks", null, null, "socks", clothes, Currency.SYP), "300");
            created(new ProductDraft("مسودة", "Draft item", null, null, "draft-item", clothes, Currency.SYP), "100");
            published(new ProductDraft("وشاح شتوي", "Winter scarf", null, null, "scarf", hidden, Currency.SYP), "900");
            // Published but with nothing available. The admin refuses to get there, so set it up directly.
            var gone = published(new ProductDraft("نفد", "Sold out", null, null, "sold-out", null, Currency.SYP), "50");
            jdbc.update("UPDATE product_variant SET active = false WHERE product_id = ?", gone);
        });
    }

    @Test
    void shopperBrowsesTheStoreInArabic() throws Exception {
        mvc.perform(get(shop + "/"))
                .andExpect(status().isOk())
                .andExpect(content().string(allOf(
                        containsString("lang=\"ar\""), containsString("dir=\"rtl\""),
                        containsString("ملابس"), not(containsString("موسمي")),
                        containsString("قميص أحمر"), containsString("كوب"), containsString("جوارب"),
                        not(containsString("مسودة")), not(containsString("وشاح شتوي")), not(containsString("نفد")),
                        // $4.50 at 118.5 with the default NEAREST_5 rule.
                        containsString("535 ل.س"),
                        // Variants with different prices show "from" the lowest.
                        containsString("من</span>"),
                        // The store's sign in the header carries the page's h1: the store's name.
                        matchesPattern("(?s).*<header>.*<h1[^>]*>متجر الورد</h1>.*</header>.*"))));

        mvc.perform(get(shop + "/c/clothes"))
                .andExpect(status().isOk())
                .andExpect(content().string(allOf(containsString("قميص أحمر"), containsString("قمصان"))));

        mvc.perform(get(shop + "/p/red-shirt"))
                .andExpect(status().isOk())
                .andExpect(content().string(allOf(
                        containsString("قميص أحمر"), containsString("المقاس"), containsString("قطن"),
                        containsString("x-data=\"variantPicker\""),
                        containsString("&quot;price&quot;:&quot;1,200 ل.س&quot;"),
                        containsString("&quot;compareAt&quot;:&quot;1,500 ل.س&quot;"),
                        // The price on its swing tag, the photo strip's place, the description under its heading.
                        containsString("class=\"swing-tag\""), containsString("عن المنتج"),
                        containsString("x-model.number=\"quantity\""),
                        // No delivery zones or payment methods yet: nothing to say about them.
                        not(containsString("التوصيل إلى")))));
    }

    @Test
    void productPageSaysWhereTheStoreDeliversAndHowToPay() throws Exception {
        TenantContext.runAs(store, () -> {
            zones.create(new ZoneDraft(Governorate.ALEPPO, null, null, "5000", Currency.SYP, null, null, null, null));
            zones.create(new ZoneDraft(Governorate.DAMASCUS, null, null, "2000", Currency.SYP, null, null, null, null));
            methods.save(PaymentMethod.SHAM_CASH, new MethodDraft(true, null, null, "0944 123 456", "متجر الورد",
                    null, null));
        });

        mvc.perform(get(shop + "/p/red-shirt"))
                .andExpect(content().string(allOf(
                        matchesPattern("(?s).*<dt[^>]*>التوصيل إلى</dt>.*<span>دمشق</span>،\\s*<span>حلب</span>.*"),
                        containsString("تظهر أجرة التوصيل في السلة"),
                        matchesPattern("(?s).*<dt[^>]*>الدفع</dt>.*شام كاش.*"))));
    }

    @Test
    void shopperBrowsesTheStoreInEnglish() throws Exception {
        var english = new Cookie("lang", "en");

        mvc.perform(get(shop + "/").cookie(english))
                .andExpect(content().string(allOf(
                        containsString("lang=\"en\""), containsString("dir=\"ltr\""), containsString(">Rose Shop</h1>"),
                        containsString("Clothes"), containsString("Red shirt"), containsString("535 SYP"))));
        mvc.perform(get(shop + "/p/red-shirt").cookie(english))
                .andExpect(content().string(allOf(containsString("Size"), containsString("Cotton"))));
    }

    @Test
    void usesTheStoreLanguageUntilTheShopperPicksOne() throws Exception {
        jdbc.update("UPDATE store SET default_locale = 'en' WHERE id = ?", store);
        TenantContext.runAs(store, () -> settings.updateTheme(StoreTheme.DEFAULT));

        mvc.perform(get(shop + "/")).andExpect(content().string(containsString("lang=\"en\"")));
        mvc.perform(get(shop + "/").cookie(new Cookie("lang", "ar")))
                .andExpect(content().string(containsString("lang=\"ar\"")));
    }

    @Test
    void switchesLanguageAndReturnsToTheSamePage() throws Exception {
        mvc.perform(get(shop + "/lang/en").header("Referer", shop + "/c/clothes?sort=PRICE_ASC"))
                .andExpect(redirectedUrl("/c/clothes?sort=PRICE_ASC"))
                .andExpect(cookie().value("lang", "en"));
        mvc.perform(get(shop + "/lang/ar").header("Referer", "https://evil.example/phish"))
                .andExpect(redirectedUrl("/"));
        mvc.perform(get(shop + "/lang/fr")).andExpect(status().isNotFound());
    }

    @Test
    void sortsByPriceAcrossCurrencies() throws Exception {
        // Socks 300 SYP < shirt from 1,000 SYP < mug $4.50 (533.25 SYP): SYP order must interleave the USD product.
        var ascending = mvc.perform(get(shop + "/c/clothes").param("sort", "PRICE_ASC")).andReturn()
                .getResponse().getContentAsString();
        assertThat(ascending.indexOf("جوارب")).isLessThan(ascending.indexOf("كوب"));
        assertThat(ascending.indexOf("كوب")).isLessThan(ascending.indexOf("قميص أحمر"));

        var descending = mvc.perform(get(shop + "/c/clothes").param("sort", "PRICE_DESC")).andReturn()
                .getResponse().getContentAsString();
        assertThat(descending.indexOf("قميص أحمر")).isLessThan(descending.indexOf("جوارب"));
    }

    @Test
    void searchesForgivingArabicSpelling() throws Exception {
        mvc.perform(get(shop + "/search").param("q", "احمر"))
                .andExpect(content().string(allOf(containsString("قميص أحمر"), not(containsString("جوارب")))));
        mvc.perform(get(shop + "/search").param("q", "MUG"))
                .andExpect(content().string(containsString("كوب")));
        mvc.perform(get(shop + "/search").param("q", "وشاح"))
                .andExpect(content().string(containsString("لم نجد منتجات تطابق بحثك")));
        mvc.perform(get(shop + "/search").param("q", "100%_"))
                .andExpect(status().isOk());
    }

    @Test
    void hidesWhatShoppersShouldNotSee() throws Exception {
        for (var path : new String[] {"/c/seasonal", "/c/nope", "/p/draft-item", "/p/scarf", "/p/sold-out", "/p/nope"}) {
            mvc.perform(get(shop + path)).andExpect(status().isNotFound());
        }
        mvc.perform(get(MAIN + "/c/clothes")).andExpect(status().isNotFound());
        mvc.perform(get(MAIN + "/p/red-shirt")).andExpect(status().isNotFound());
    }

    @Test
    void lightPagesAreCacheableAndCreateNoSession() throws Exception {
        for (var path : new String[] {"/", "/c/clothes", "/p/red-shirt", "/search?q=mug"}) {
            var result = mvc.perform(get(shop + path))
                    .andExpect(header().string("Cache-Control", "private, max-age=60"))
                    .andExpect(content().string(not(containsString("X-CSRF-TOKEN"))))
                    .andReturn();
            assertThat(result.getRequest().getSession(false)).as(path).isNull();
            assertThat(result.getResponse().getCookie("JSESSIONID")).as(path).isNull();
        }
    }

    @Test
    void appliesTheStoreTheme() throws Exception {
        TenantContext.runAs(store, () -> settings.updateTheme(new StoreTheme("#ffff00", null,
                LocalizedText.of("تخفيضات الصيف", "Summer sale"), null, StoreTheme.FontChoice.CLASSIC, false, true)));

        mvc.perform(get(shop + "/"))
                .andExpect(content().string(allOf(
                        containsString("--color-brand-500:#ffff00;"), containsString("Traditional Arabic"),
                        // The merchant's headline is the tagline under the store's name.
                        matchesPattern("(?s).*<h1[^>]*>متجر الورد</h1>.*<p[^>]*>تخفيضات الصيف</p>.*"),
                        // Categories section off: its heading is gone, the header navigation stays.
                        not(containsString("<h2 class=\"text-lg font-semibold\">الأقسام</h2>")))));
    }

    @Test
    void merchantsEditTheThemeInTheAdmin() throws Exception {
        TestAccounts.staff(staffUsers, passwords, store, "Owner", "owner@" + slug + ".test", null, StaffRole.OWNER);
        var owner = TestAccounts.logIn(mvc, slug, "owner@" + slug + ".test");

        mvc.perform(get(MAIN + "/admin/settings/theme").session(owner))
                .andExpect(content().string(containsString("value=\"#00855d\"")));
        mvc.perform(post(MAIN + "/admin/settings/theme").session(owner).with(csrf())
                        .param("primaryColor", "#8e24aa").param("heroTitleAr", "أهلاً بالجميع")
                        .param("font", "MODERN").param("showCategories", "true").param("showNewProducts", "true"))
                .andExpect(redirectedUrl("/admin/settings/theme?saved"));
        // The preview beside the form: the store's sign with its name and the saved title; the tab marks the page.
        mvc.perform(get(MAIN + "/admin/settings/theme").session(owner))
                .andExpect(content().string(allOf(containsString("x-data=\"themePreview\""),
                        matchesPattern("(?s).*theme-preview__sign.*متجر الورد.*أهلاً بالجميع.*"),
                        matchesPattern("(?s).*<a href=\"/admin/settings/theme\"[^>]*aria-current=\"page\".*"))));
        mvc.perform(post(MAIN + "/admin/settings/theme").session(owner).with(csrf())
                        .param("primaryColor", "red; background:url(x)").param("font", "MODERN"))
                .andExpect(status().isOk());

        mvc.perform(get(shop + "/"))
                .andExpect(content().string(allOf(containsString("--color-brand-500:#8e24aa;"),
                        containsString("أهلاً بالجميع"), not(containsString("url(x)")))));
    }

    private UUID published(ProductDraft draft, String price) {
        var id = created(draft, price);
        products.changeStatus(id, ProductStatus.ACTIVE);
        return id;
    }

    private UUID created(ProductDraft draft, String price) {
        return ((Products.Saved) products.create(draft, price)).id();
    }

    private static UUID saved(Categories.CategoryResult result) {
        return ((Categories.Saved) result).id();
    }
}
