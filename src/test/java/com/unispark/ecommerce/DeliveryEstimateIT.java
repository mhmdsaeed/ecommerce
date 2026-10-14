package com.unispark.ecommerce;

import static com.unispark.ecommerce.TestAccounts.storefront;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.LinkedHashMap;
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
import com.unispark.ecommerce.catalog.Products.ProductDraft;
import com.unispark.ecommerce.catalog.Products.Saved;
import com.unispark.ecommerce.delivery.DeliveryZones;
import com.unispark.ecommerce.delivery.DeliveryZones.ZoneDraft;
import com.unispark.ecommerce.delivery.Governorate;
import com.unispark.ecommerce.shared.Currency;
import com.unispark.ecommerce.shared.TenantContext;

/** M7 done-when, second half: the checkout fee is computed, and shown under the cart (SPEC 8). */
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfig.class, IsolationProbeWeb.class})
@ActiveProfiles("test")
class DeliveryEstimateIT {

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    Products products;

    @Autowired
    DeliveryZones zones;

    @Autowired
    Carts carts;

    UUID store;
    String shop;
    UUID scarf;

    @BeforeEach
    void createStore() {
        var slug = TestStores.randomSlug();
        store = TestStores.create(jdbc, slug, "ACTIVE");
        shop = storefront(slug);
        TenantContext.runAs(store, () -> {
            var id = ((Saved) products.create(new ProductDraft("وشاح", "Scarf", null, null, "scarf", null,
                    Currency.SYP), "10000")).id();
            products.changeStatus(id, ProductStatus.ACTIVE);
            scarf = products.find(id).orElseThrow().variants().getFirst().id();
            zones.create(new ZoneDraft(Governorate.DAMASCUS, "دمشق", null, "2000", Currency.SYP, null, "50000",
                    "خلال 24 ساعة", "Within 24 hours"));
            zones.create(new ZoneDraft(Governorate.DAMASCUS, "دمشق", "المزة", "1500", Currency.SYP, null, null, null, null));
            zones.create(new ZoneDraft(Governorate.RIF_DIMASHQ, null, null, "3500", Currency.SYP, "30000", null, null, null));
            zones.create(new ZoneDraft(Governorate.HOMS, "حمص", null, "0", Currency.SYP, null, null, null, null));
        });
    }

    @Test
    void shopperSeesTheDeliveryFeeAndTotal() throws Exception {
        var shopper = shopperWith(2);

        shopper.perform(get(shop + "/cart"))
                .andExpect(content().string(allOf(containsString("id=\"delivery\""),
                        containsString("اختر محافظتك لمعرفة رسوم التوصيل."), containsString("ريف دمشق"),
                        containsString("حمص"), not(containsString(">حلب<")),
                        not(containsString("تُحسب رسوم التوصيل عند إتمام الطلب.")))));

        // Governorate first: Damascus zones name cities, so the box asks for the city.
        shopper.perform(delivery("DAMASCUS", null, null))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(content().string(allOf(not(containsString("<html")), containsString("id=\"delivery-quote\""),
                        containsString("اكتب مدينتك لمعرفة رسوم التوصيل."),
                        containsString("<datalist id=\"delivery-cities\" hx-swap-oob=\"true\">"),
                        containsString("<option value=\"دمشق\">"))));

        // 2 x 10,000 to Damascus: 2,000 fee, 22,000 total, 30,000 more for free delivery.
        shopper.perform(delivery("DAMASCUS", "دمشق", "باب توما"))
                .andExpect(content().string(allOf(containsString("2,000 ل.س"), containsString("22,000 ل.س"),
                        containsString("خلال 24 ساعة"), containsString("أضف 30,000 ل.س واحصل على توصيل مجاني."),
                        containsString("<option value=\"المزة\">"))));
        shopper.perform(delivery("DAMASCUS", "دمشق", "المزه"))
                .andExpect(content().string(allOf(containsString("1,500 ل.س"), containsString("21,500 ل.س"))));
        shopper.perform(delivery("HOMS", "حمص", null))
                .andExpect(content().string(allOf(containsString("مجاني"), containsString("20,000 ل.س"))));
        shopper.perform(delivery("RIF_DIMASHQ", "جرمانا", null))
                .andExpect(content().string(containsString(
                        "الحد الأدنى للطلب للتوصيل إلى هنا 30,000 ل.س. أضف 10,000 ل.س لإتمام الطلب.")));
        shopper.perform(delivery("HOMS", "تدمر", null))
                .andExpect(content().string(containsString("لا نوصل إلى هذا العنوان حالياً.")));
    }

    @Test
    void cartKeepsTheChosenDestinationAcrossChanges() throws Exception {
        var shopper = shopperWith(1);
        shopper.perform(delivery("DAMASCUS", "دمشق", "المزة"));
        assertThat(shopper.cookies).containsKey("delivery_to");

        var item = TenantContext.callAs(store, () -> carts.view(shopper.cookies.get("cart")).lines().getFirst().itemId());
        shopper.perform(patch(shop + "/cart/items/" + item).param("quantity", "3"))
                .andExpect(content().string(allOf(containsString("selected=\"selected\">دمشق<"),
                        containsString("value=\"المزة\""), containsString("31,500 ل.س"))));

        // Reaching free delivery: 5 x 10,000 = 50,000 is at the threshold of the city zone (the area zone has none).
        shopper.perform(delivery("DAMASCUS", "دمشق", null));
        shopper.perform(patch(shop + "/cart/items/" + item).param("quantity", "5"))
                .andExpect(content().string(allOf(containsString("مجاني"), not(containsString("واحصل على توصيل مجاني")))));
    }

    @Test
    void worksWithoutJavaScriptAndShrugsOffBadInput() throws Exception {
        var shopper = shopperWith(1);

        mvc.perform(get(shop + "/cart/delivery").param("governorate", "HOMS").param("city", "حمص")
                        .cookie(new Cookie("cart", shopper.cookies.get("cart"))))
                .andExpect(redirectedUrl("/cart"));
        shopper.perform(delivery("NARNIA", "x", "y"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("اختر محافظتك لمعرفة رسوم التوصيل.")));
        shopper.perform(delivery("HOMS", "<script>alert(1)</script>", null))
                .andExpect(content().string(not(containsString("<script>alert"))));
        mvc.perform(get(shop + "/cart").cookie(new Cookie("cart", shopper.cookies.get("cart")),
                        new Cookie("delivery_to", "%%%garbage")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("اختر محافظتك")));
    }

    @Test
    void noDeliveryBoxWithoutZonesOrItems() throws Exception {
        new Shopper().perform(get(shop + "/cart"))
                .andExpect(content().string(allOf(containsString("سلتك فارغة."), not(containsString("id=\"delivery\"")))));

        var shopper = shopperWith(1);
        TenantContext.runAs(store, () -> zones.list().forEach(zone -> zones.setActive(zone.id(), false)));
        shopper.perform(get(shop + "/cart"))
                .andExpect(content().string(allOf(not(containsString("id=\"delivery\"")),
                        containsString("تُحسب رسوم التوصيل عند إتمام الطلب."))));
    }

    private Shopper shopperWith(int quantity) throws Exception {
        var shopper = new Shopper();
        shopper.visit("/p/scarf");
        shopper.perform(post(shop + "/cart/items").param("variantId", scarf.toString())
                .param("quantity", Integer.toString(quantity))).andExpect(status().isOk());
        return shopper;
    }

    private MockHttpServletRequestBuilder delivery(String governorate, String city, String area) {
        var request = get(shop + "/cart/delivery").param("governorate", governorate);
        if (city != null) {
            request.param("city", city);
        }
        if (area != null) {
            request.param("area", area);
        }
        return request;
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
