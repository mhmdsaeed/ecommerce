package com.unispark.ecommerce;

import static com.unispark.ecommerce.TestAccounts.storefront;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import jakarta.servlet.http.Cookie;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.unispark.ecommerce.cart.Carts;
import com.unispark.ecommerce.catalog.ProductStatus;
import com.unispark.ecommerce.catalog.Products;
import com.unispark.ecommerce.catalog.Products.OptionDraft;
import com.unispark.ecommerce.catalog.Products.ProductDraft;
import com.unispark.ecommerce.catalog.Products.Saved;
import com.unispark.ecommerce.inventory.Inventory;
import com.unispark.ecommerce.pricing.ExchangeRates;
import com.unispark.ecommerce.shared.Currency;
import com.unispark.ecommerce.shared.TenantContext;

/** M6 done-when, first half: the cart works over HTMX (SPEC 9.2, 17), through the real CSRF cookie. */
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfig.class, IsolationProbeWeb.class})
@ActiveProfiles("test")
class CartIT {

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    Products products;

    @Autowired
    Inventory inventory;

    @Autowired
    ExchangeRates rates;

    @Autowired
    Carts carts;

    UUID store;
    String shop;
    UUID shirt;
    UUID small;
    UUID medium;
    UUID mug;

    @BeforeEach
    void createCatalog() {
        var slug = TestStores.randomSlug();
        store = TestStores.create(jdbc, slug, "ACTIVE");
        shop = storefront(slug);
        TenantContext.runAs(store, () -> {
            rates.set("118.5", null);
            shirt = published(new ProductDraft("قميص", "Shirt", null, null, "shirt", null, Currency.SYP), "1000");
            products.defineOptions(shirt, List.of(new OptionDraft("المقاس", "Size", "S, M", "S, M")));
            var variants = products.find(shirt).orElseThrow().variants();
            small = variants.get(0).id();
            medium = variants.get(1).id();
            mug = products.find(published(new ProductDraft("كوب", "Mug", null, null, "mug", null, Currency.USD), "4.50"))
                    .orElseThrow().variants().getFirst().id();
            // A photo of the medium shirt only: the cart shows a variant's own photo first. (Rows only; URLs need no S3.)
            jdbc.update("""
                    INSERT INTO product_image (id, tenant_id, product_id, variant_id, storage_key, width, height, sort_order,
                                               created_at, updated_at, version)
                    VALUES (?, ?, ?, NULL, 'shirt-front', 800, 800, 0, now(), now(), 0),
                           (?, ?, ?, ?, 'shirt-medium', 800, 800, 1, now(), now(), 0)
                    """, UUID.randomUUID(), store, shirt, UUID.randomUUID(), store, shirt, medium);
        });
    }

    @Test
    void shopperFillsTheCartOverHtmx() throws Exception {
        var shopper = new Shopper();
        shopper.visit("/p/shirt");

        shopper.perform(post(shop + "/cart/items").param("variantId", small.toString()).param("quantity", "2"))
                .andExpect(status().isOk())
                .andExpect(header().string("HX-Trigger", "cart-changed"))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(content().string(containsString("تمت الإضافة إلى سلتك.")));
        assertThat(shopper.cookies.get("cart_count")).isEqualTo("2");
        shopper.perform(post(shop + "/cart/items").param("variantId", mug.toString()))
                .andExpect(status().isOk());
        assertThat(shopper.cookies.get("cart_count")).isEqualTo("3");

        // 2 x 1,000 + $4.50 at 118.5 rounded to 535: 2,535 pounds.
        shopper.perform(get(shop + "/cart"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(content().string(allOf(
                        containsString("قميص"), containsString("المقاس: S"), containsString("كوب"),
                        containsString("1,000 ل.س للقطعة"), containsString("2,000 ل.س"),
                        containsString("2,535 ل.س"))));
    }

    @Test
    void changesAndRemovesLines() throws Exception {
        var shopper = new Shopper();
        shopper.visit("/p/shirt");
        shopper.perform(post(shop + "/cart/items").param("variantId", small.toString()));
        shopper.perform(post(shop + "/cart/items").param("variantId", medium.toString()));
        var item = itemOf(small);

        shopper.perform(patch(shop + "/cart/items/" + item).param("quantity", "3"))
                .andExpect(status().isOk())
                .andExpect(content().string(allOf(containsString("id=\"cart\""), containsString("3,000 ل.س"),
                        containsString("shirt-front/320.webp"), containsString("shirt-medium/320.webp"))));
        mvc.perform(get(shop + "/cart").cookie(new Cookie("cart", shopper.cookies.get("cart"))))
                .andExpect(status().isOk())
                .andExpect(content().string(allOf(containsString("<h1"), containsString("shirt-medium/320.webp"))));
        assertThat(shopper.cookies.get("cart_count")).isEqualTo("4");

        shopper.perform(patch(shop + "/cart/items/" + item).param("quantity", "0"))
                .andExpect(content().string(not(containsString("المقاس: S"))));
        shopper.perform(delete(shop + "/cart/items/" + itemOf(medium)))
                .andExpect(content().string(containsString("سلتك فارغة.")));
        assertThat(shopper.cookies.get("cart_count")).isEqualTo("0");
    }

    @Test
    void keepsQuantitiesWithinStock() throws Exception {
        TenantContext.runAs(store, () -> {
            inventory.setOnHand(small, 2);
            inventory.setOnHand(medium, 0);
        });
        var shopper = new Shopper();
        shopper.visit("/p/shirt");

        shopper.perform(post(shop + "/cart/items").param("variantId", medium.toString()))
                .andExpect(content().string(containsString("نفدت الكمية.")));
        assertThat(shopper.cookies).doesNotContainKey("cart");

        shopper.perform(post(shop + "/cart/items").param("variantId", small.toString()).param("quantity", "5"))
                .andExpect(content().string(containsString("لم تُضف الكمية كاملة")));
        assertThat(shopper.cookies.get("cart_count")).isEqualTo("2");
        shopper.perform(patch(shop + "/cart/items/" + itemOf(small)).param("quantity", "3"))
                .andExpect(content().string(containsString("لم تُضف الكمية كاملة")));
        assertThat(shopper.cookies.get("cart_count")).isEqualTo("2");

        // Stock drops below what is in the cart: the cart says so.
        TenantContext.runAs(store, () -> inventory.setOnHand(small, 1));
        shopper.perform(get(shop + "/cart"))
                .andExpect(content().string(containsString("المتوفر 1 فقط.")));
    }

    @Test
    void showsWhatIsNoLongerForSale() throws Exception {
        var shopper = new Shopper();
        shopper.visit("/p/shirt");
        shopper.perform(post(shop + "/cart/items").param("variantId", small.toString()));
        shopper.perform(post(shop + "/cart/items").param("variantId", mug.toString()));

        TenantContext.runAs(store, () -> products.changeStatus(shirt, ProductStatus.ARCHIVED));

        shopper.perform(get(shop + "/cart"))
                .andExpect(content().string(allOf(
                        containsString("هذا المنتج لم يعد متوفراً."), not(containsString("1,000 ل.س")),
                        containsString("535 ل.س"))));
        shopper.perform(post(shop + "/cart/items").param("variantId", small.toString()))
                .andExpect(content().string(containsString("هذا المنتج لم يعد متوفراً.")));
        shopper.perform(post(shop + "/cart/items").param("variantId", UUID.randomUUID().toString()))
                .andExpect(content().string(containsString("هذا المنتج لم يعد متوفراً.")));
    }

    @Test
    void refusesChangesWithoutTheCsrfToken() throws Exception {
        mvc.perform(post(shop + "/cart/items").param("variantId", small.toString()).header("HX-Request", "true"))
                .andExpect(status().isForbidden());

        var shopper = new Shopper();
        shopper.visit("/");
        mvc.perform(post(shop + "/cart/items").param("variantId", small.toString()).header("HX-Request", "true")
                        .cookie(new Cookie("XSRF-TOKEN", shopper.cookies.get("XSRF-TOKEN")))
                        .header("X-XSRF-TOKEN", "forged"))
                .andExpect(status().isForbidden());
    }

    @Test
    void browsingIssuesTheCsrfCookieOnceAndNoSession() throws Exception {
        var first = mvc.perform(get(shop + "/p/shirt")).andReturn();
        var token = first.getResponse().getCookie("XSRF-TOKEN");
        assertThat(token).isNotNull();
        assertThat(token.isHttpOnly()).isFalse();
        assertThat(first.getResponse().getContentAsString()).doesNotContain(token.getValue());
        assertThat(first.getRequest().getSession(false)).isNull();

        var again = mvc.perform(get(shop + "/").cookie(token)).andReturn();
        assertThat(again.getResponse().getCookie("XSRF-TOKEN")).isNull();
        assertThat(again.getResponse().getHeader("Cache-Control")).isEqualTo("private, max-age=60");
    }

    @Test
    void cartCookiesAreHttpOnlyExceptTheCount() throws Exception {
        var shopper = new Shopper();
        shopper.visit("/");
        var result = shopper.perform(post(shop + "/cart/items").param("variantId", small.toString())).andReturn();

        var cart = result.getResponse().getCookie("cart");
        assertThat(cart.getValue()).matches("[A-Za-z0-9_-]{43}");
        assertThat(cart.isHttpOnly()).isTrue();
        assertThat(cart.getMaxAge()).isEqualTo(30 * 24 * 3600);
        assertThat(cart.getAttribute("SameSite")).isEqualTo("Lax");
        var count = result.getResponse().getCookie("cart_count");
        assertThat(count.getValue()).isEqualTo("1");
        assertThat(count.isHttpOnly()).isFalse();
        // Only the token's hash is stored.
        var stored = TenantContext.callAs(store, () -> jdbc.queryForObject("SELECT session_key FROM cart", String.class));
        assertThat(stored).hasSize(64).isNotEqualTo(cart.getValue());
    }

    @Test
    void aCartBelongsToOneStore() throws Exception {
        var shopper = new Shopper();
        shopper.visit("/");
        shopper.perform(post(shop + "/cart/items").param("variantId", small.toString()));

        var other = TestStores.randomSlug();
        TestStores.create(jdbc, other, "ACTIVE");
        mvc.perform(get(storefront(other) + "/cart").cookie(new Cookie("cart", shopper.cookies.get("cart"))))
                .andExpect(content().string(containsString("سلتك فارغة.")));
    }

    @Test
    void deletesExpiredCarts() throws Exception {
        var shopper = new Shopper();
        shopper.visit("/");
        shopper.perform(post(shop + "/cart/items").param("variantId", small.toString()));
        TenantContext.runAs(store, () -> jdbc.update("UPDATE cart SET expires_at = now() - interval '1 minute'"));

        shopper.perform(get(shop + "/cart")).andExpect(content().string(containsString("سلتك فارغة.")));
        assertThat(shopper.cookies.get("cart_count")).isEqualTo("0");
        assertThat(TenantContext.callAs(store, carts::deleteExpired)).isEqualTo(1);
        assertThat(TenantContext.callAs(store, () -> jdbc.queryForObject("SELECT count(*) FROM cart_item", Long.class)))
                .isZero();
    }

    @Test
    void productPageMarksSoldOutVariants() throws Exception {
        TenantContext.runAs(store, () -> inventory.setOnHand(small, 0));

        mvc.perform(get(shop + "/p/shirt"))
                .andExpect(content().string(allOf(
                        containsString("&quot;id&quot;:&quot;" + small + "&quot;"),
                        containsString("&quot;inStock&quot;:false"), containsString("&quot;inStock&quot;:true"),
                        containsString("hx-post=\"/cart/items\""))));
    }

    private UUID itemOf(UUID variant) {
        return TenantContext.callAs(store, () -> jdbc.queryForObject(
                "SELECT id FROM cart_item WHERE variant_id = ?", UUID.class, variant));
    }

    private UUID published(ProductDraft draft, String price) {
        var id = ((Saved) products.create(draft, price)).id();
        products.changeStatus(id, ProductStatus.ACTIVE);
        return id;
    }

    /** A browser on the store: keeps cookies and sends HTMX requests with the CSRF cookie copied to the header. */
    private final class Shopper {

        final Map<String, String> cookies = new LinkedHashMap<>();

        void visit(String path) throws Exception {
            remember(mvc.perform(withCookies(get(shop + path))).andExpect(status().isOk()).andReturn());
        }

        ResultActions perform(MockHttpServletRequestBuilder request) throws Exception {
            request.header("HX-Request", "true");
            if (cookies.containsKey("XSRF-TOKEN")) {
                request.header("X-XSRF-TOKEN", cookies.get("XSRF-TOKEN"));
            }
            var actions = mvc.perform(withCookies(request));
            remember(actions.andReturn());
            return actions;
        }

        private MockHttpServletRequestBuilder withCookies(MockHttpServletRequestBuilder request) {
            if (!cookies.isEmpty()) {
                request.cookie(cookies.entrySet().stream().map(c -> new Cookie(c.getKey(), c.getValue()))
                        .toArray(Cookie[]::new));
            }
            return request;
        }

        private void remember(MvcResult result) {
            for (var cookie : result.getResponse().getCookies()) {
                cookies.put(cookie.getName(), cookie.getValue());
            }
        }
    }
}
