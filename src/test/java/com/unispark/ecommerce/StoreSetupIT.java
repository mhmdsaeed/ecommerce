package com.unispark.ecommerce;

import static com.unispark.ecommerce.TestAccounts.MAIN;
import static com.unispark.ecommerce.TestAccounts.PLATFORM;
import static com.unispark.ecommerce.TestAccounts.fromNewAddress;
import static com.unispark.ecommerce.TestAccounts.storefront;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import jakarta.servlet.http.Cookie;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import com.unispark.ecommerce.catalog.Categories;
import com.unispark.ecommerce.catalog.Categories.CategoryDraft;
import com.unispark.ecommerce.catalog.ProductStatus;
import com.unispark.ecommerce.catalog.Products;
import com.unispark.ecommerce.catalog.Products.ProductDraft;
import com.unispark.ecommerce.catalog.Products.Saved;
import com.unispark.ecommerce.delivery.DeliveryZones;
import com.unispark.ecommerce.delivery.DeliveryZones.ZoneDraft;
import com.unispark.ecommerce.delivery.Governorate;
import com.unispark.ecommerce.shared.Currency;
import com.unispark.ecommerce.shared.LocalizedText;
import com.unispark.ecommerce.shared.TenantContext;
import com.unispark.ecommerce.tenant.StoreLookup;
import com.unispark.ecommerce.tenant.StoreSettings;

/**
 * M13 done-when, as far as a test can take it: a new merchant signs up, follows the setup wizard and goes live
 * (SPEC 9.3); search engines get descriptions, a sitemap and robots rules (SPEC 9.5).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfig.class)
@ActiveProfiles("test")
class StoreSetupIT {

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    StoreLookup stores;

    @Autowired
    StoreSettings settings;

    @Autowired
    Products products;

    @Autowired
    Categories categories;

    @Autowired
    DeliveryZones zones;

    String slug;
    UUID store;
    MockHttpSession owner;

    @BeforeEach
    void signUp() throws Exception {
        slug = TestStores.randomSlug();
        var result = mvc.perform(post(MAIN + "/signup").with(csrf()).with(fromNewAddress())
                        .param("storeName", "متجر الياسمين").param("slug", slug).param("ownerName", "سامر")
                        .param("phone", "0933 444 001").param("email", "owner@" + slug + ".test")
                        .param("password", "long-enough-pw"))
                .andExpect(redirectedUrl("/admin/setup"))
                .andReturn();
        owner = (MockHttpSession) result.getRequest().getSession(false);
        store = stores.findBySlug(slug).orElseThrow().id();
    }

    @Test
    void newMerchantFollowsTheWizardAndGoesLive() throws Exception {
        // Fresh from signup: the store is closed to shoppers, the wizard lists what is left.
        assertThat(stores.findBySlug(slug).orElseThrow().launched()).isFalse();
        mvc.perform(get(storefront(slug) + "/")).andExpect(status().isServiceUnavailable());
        mvc.perform(get(MAIN + "/admin/setup").session(owner))
                .andExpect(status().isOk())
                .andExpect(content().string(allOf(containsString("جهّز متجرك"), containsString("منطقة التوصيل"),
                        containsString("أول منتج"), containsString("disabled=\"disabled\""),
                        containsString("?preview="))));
        mvc.perform(get(MAIN + "/admin").session(owner))
                .andExpect(content().string(allOf(containsString("متجرك غير مفتوح بعد"),
                        containsString("تجهيز المتجر"))));
        mvc.perform(post(MAIN + "/admin/setup/launch").session(owner).with(csrf()))
                .andExpect(redirectedUrl("/admin/setup?not-ready"));
        assertThat(stores.findBySlug(slug).orElseThrow().launched()).isFalse();

        // The required steps: cash on delivery is on from the start; a zone and a product are added.
        addZoneAndProduct();
        mvc.perform(get(MAIN + "/admin/setup").session(owner))
                .andExpect(content().string(not(containsString("disabled=\"disabled\""))));
        mvc.perform(post(MAIN + "/admin/setup/launch").session(owner).with(csrf()))
                .andExpect(redirectedUrl("/admin/setup?launched"));

        assertThat(stores.findBySlug(slug).orElseThrow().launched()).isTrue();
        mvc.perform(get(storefront(slug) + "/"))
                .andExpect(status().isOk())
                .andExpect(content().string(allOf(containsString("وشاح"), not(containsString("معاينة")))));
        mvc.perform(get(MAIN + "/admin/setup").session(owner))
                .andExpect(content().string(allOf(containsString("متجرك مفتوح"), not(containsString("?preview=")))));
        mvc.perform(get(MAIN + "/admin").session(owner))
                .andExpect(content().string(allOf(not(containsString("متجرك غير مفتوح بعد")),
                        not(containsString("تجهيز المتجر")))));
    }

    @Test
    void staffPreviewTheStoreBeforeLaunchThroughTheirLink() throws Exception {
        addZoneAndProduct();
        var token = TenantContext.callAs(store, settings::previewToken);

        mvc.perform(get(storefront(slug) + "/").param("preview", "wrong")).andExpect(status().isServiceUnavailable());
        mvc.perform(get(storefront(slug) + "/").param("preview", token))
                .andExpect(status().isOk())
                .andExpect(cookie().value("store_preview", token))
                .andExpect(cookie().httpOnly("store_preview", true))
                .andExpect(header().string("X-Robots-Tag", "noindex"))
                .andExpect(content().string(allOf(containsString("معاينة: لا يرى الزبائن"), containsString("وشاح"),
                        containsString("<meta name=\"robots\" content=\"noindex\">"),
                        not(containsString("canonical")))));
        // The cookie carries the preview through the store, product pages included.
        mvc.perform(get(storefront(slug) + "/p/scarf").cookie(new Cookie("store_preview", token)))
                .andExpect(status().isOk());
        // Another store's token opens nothing.
        var other = TestStores.randomSlug();
        mvc.perform(post(MAIN + "/signup").with(csrf()).with(fromNewAddress())
                .param("storeName", "آخر").param("slug", other).param("ownerName", "رامي")
                .param("phone", "0933 444 002").param("password", "long-enough-pw"));
        mvc.perform(get(storefront(other) + "/").cookie(new Cookie("store_preview", token)))
                .andExpect(status().isServiceUnavailable());
    }

    @Test
    void storeDetailsShowInTheStorefront() throws Exception {
        mvc.perform(post(MAIN + "/admin/settings/store").session(owner).with(csrf())
                        .param("nameAr", " ").param("nameEn", "").param("contactPhone", "123").param("whatsapp", ""))
                .andExpect(status().isOk())
                .andExpect(content().string(allOf(containsString("أدخل اسم المتجر بلغة واحدة على الأقل"),
                        containsString("أدخل رقم هاتف صحيحاً"))));
        mvc.perform(post(MAIN + "/admin/settings/store").session(owner).with(csrf())
                        .param("nameAr", "متجر الورد").param("nameEn", "Rose Shop")
                        .param("contactPhone", "0933 444 001").param("whatsapp", "٠٩٤٤ ٥٥٥ ٠٠١"))
                .andExpect(redirectedUrl("/admin/settings/store?saved"));

        var saved = TenantContext.callAs(store, settings::current);
        assertThat(saved.name()).isEqualTo(LocalizedText.of("متجر الورد", "Rose Shop"));
        assertThat(saved.contactPhone()).isEqualTo("+963933444001");
        assertThat(saved.whatsapp()).isEqualTo("+963944555001");
        addZoneAndProduct();
        TenantContext.runAs(store, settings::launch);
        mvc.perform(get(storefront(slug) + "/"))
                .andExpect(content().string(allOf(containsString("متجر الورد"), containsString("tel:+963933444001"),
                        containsString("https://wa.me/963944555001"))));
    }

    @Test
    void indexablePagesDescribeThemselves() throws Exception {
        addZoneAndProduct();
        TenantContext.runAs(store, settings::launch);
        var base = storefront(slug);

        mvc.perform(get(base + "/"))
                .andExpect(content().string(allOf(
                        containsString("<meta name=\"description\" content=\"تسوّق من متجر الياسمين"),
                        containsString("<meta property=\"og:type\" content=\"website\">"),
                        containsString("<meta property=\"og:site_name\" content=\"متجر الياسمين\">"),
                        containsString("<link rel=\"canonical\" href=\"" + base + "/\">"))));
        mvc.perform(get(base + "/p/scarf").param("utm_source", "whatsapp"))
                .andExpect(content().string(allOf(
                        containsString("<meta property=\"og:type\" content=\"product\">"),
                        containsString("<meta property=\"og:title\" content=\"وشاح\">"),
                        containsString("<meta name=\"description\" content=\"وشاح صوف ناعم، دافئ ومناسب للشتاء."),
                        containsString("<link rel=\"canonical\" href=\"" + base + "/p/scarf\">"))));
        mvc.perform(get(base + "/c/winter").param("page", "1").param("sort", "PRICE_ASC"))
                .andExpect(content().string(allOf(
                        containsString("<meta name=\"description\" content=\"شتاء في متجر الياسمين"),
                        containsString("<link rel=\"canonical\" href=\"" + base + "/c/winter?page=1\">"))));
        mvc.perform(get(base + "/search").param("q", "وشاح"))
                .andExpect(content().string(allOf(containsString("noindex"), not(containsString("og:type")))));
    }

    @Test
    void searchEnginesGetRobotsRulesAndASitemap() throws Exception {
        addZoneAndProduct();
        TenantContext.runAs(store, settings::launch);
        var base = storefront(slug);

        // Publicly cacheable, so never a cookie: a shared cache would hand the same CSRF token to everyone.
        for (var file : new String[] {"/robots.txt", "/sitemap.xml"}) {
            mvc.perform(get(base + file))
                    .andExpect(header().string("Cache-Control", "public, max-age=3600"))
                    .andExpect(header().doesNotExist("Set-Cookie"));
        }
        mvc.perform(get(base + "/robots.txt"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("text/plain"))
                .andExpect(content().string(allOf(containsString("Disallow: /checkout"),
                        containsString("Sitemap: " + base + "/sitemap.xml"))));
        mvc.perform(get(base + "/sitemap.xml"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("application/xml"))
                .andExpect(content().string(allOf(containsString("<loc>" + base + "/</loc>"),
                        containsString("<loc>" + base + "/c/winter</loc>"),
                        containsString("<loc>" + base + "/p/scarf</loc><lastmod>"),
                        not(containsString("/p/draft")))));
        mvc.perform(get(MAIN + "/robots.txt"))
                .andExpect(content().string(allOf(containsString("Disallow: /admin/"),
                        not(containsString("Sitemap")))));
        mvc.perform(get(PLATFORM + "/robots.txt")).andExpect(content().string(containsString("Disallow: /\n")));
        mvc.perform(get(MAIN + "/sitemap.xml")).andExpect(status().isNotFound());
    }

    /** A zone, a published product in a category, and a draft that must stay hidden. */
    private void addZoneAndProduct() {
        TenantContext.runAs(store, () -> {
            zones.create(new ZoneDraft(Governorate.DAMASCUS, "دمشق", null, "2000", Currency.SYP, null, null, null,
                    null));
            var category = ((Categories.Saved) categories.create(new CategoryDraft("شتاء", "Winter", "winter", null,
                    null))).id();
            var id = ((Saved) products.create(new ProductDraft("وشاح", "Scarf", "وشاح صوف ناعم، دافئ ومناسب للشتاء.",
                    null, "scarf", category, Currency.SYP), "10000")).id();
            products.changeStatus(id, ProductStatus.ACTIVE);
            products.create(new ProductDraft("مسودة", "Draft", null, null, "draft", null, Currency.SYP), "5000");
        });
    }
}
