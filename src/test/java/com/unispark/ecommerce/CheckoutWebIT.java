package com.unispark.ecommerce;

import static com.unispark.ecommerce.TestAccounts.MAIN;
import static com.unispark.ecommerce.TestAccounts.storefront;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrlPattern;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

import jakarta.servlet.http.Cookie;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.unispark.ecommerce.catalog.ProductStatus;
import com.unispark.ecommerce.catalog.Products;
import com.unispark.ecommerce.catalog.Products.ProductDraft;
import com.unispark.ecommerce.catalog.Products.Saved;
import com.unispark.ecommerce.delivery.DeliveryZones;
import com.unispark.ecommerce.delivery.DeliveryZones.ZoneDraft;
import com.unispark.ecommerce.delivery.Governorate;
import com.unispark.ecommerce.identity.StaffRole;
import com.unispark.ecommerce.identity.internal.persistence.StaffUserRepository;
import com.unispark.ecommerce.payment.PaymentMethod;
import com.unispark.ecommerce.payment.PaymentMethods;
import com.unispark.ecommerce.payment.PaymentMethods.MethodDraft;
import com.unispark.ecommerce.shared.Currency;
import com.unispark.ecommerce.shared.TenantContext;
import com.unispark.ecommerce.tenant.StoreSettings;

/** M8 web: checkout without JavaScript, the order page, phone codes over HTMX, and the merchant's side. */
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfig.class, IsolationProbeWeb.class})
@ActiveProfiles("test")
@ExtendWith(OutputCaptureExtension.class)
class CheckoutWebIT {

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    Products products;

    @Autowired
    DeliveryZones zones;

    @Autowired
    PaymentMethods methods;

    @Autowired
    StoreSettings settings;

    @Autowired
    StaffUserRepository staffUsers;

    @Autowired
    PasswordEncoder passwords;

    String slug;
    UUID store;
    String shop;
    UUID scarf;

    @BeforeEach
    void createStore() {
        slug = TestStores.randomSlug();
        store = TestStores.create(jdbc, slug, "ACTIVE");
        shop = storefront(slug);
        TenantContext.runAs(store, () -> {
            var id = ((Saved) products.create(new ProductDraft("وشاح", "Scarf", null, null, "scarf", null,
                    Currency.SYP), "10000")).id();
            products.changeStatus(id, ProductStatus.ACTIVE);
            scarf = products.find(id).orElseThrow().variants().getFirst().id();
            zones.create(new ZoneDraft(Governorate.DAMASCUS, "دمشق", null, "2000", Currency.SYP, null, null,
                    "خلال يوم", null));
            methods.save(PaymentMethod.SHAM_CASH, new MethodDraft(true, null, null, "0944 123 456", "متجر الياسمين",
                    "أرسل رقم العملية بعد التحويل.", null));
        });
    }

    @Test
    void shopperChecksOutWithoutJavaScript() throws Exception {
        var shopper = shopperWith(2);

        var page = shopper.perform(get(shop + "/checkout"), false)
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(content().string(allOf(containsString("name=\"_csrf\""), containsString("name=\"idempotencyKey\""),
                        containsString("الدفع عند الاستلام"), containsString("شام كاش"), containsString("20,000 ل.س"),
                        containsString("اختر عنوان التوصيل"))))
                .andReturn().getResponse().getContentAsString();
        var key = hidden(page, "idempotencyKey");

        var placed = shopper.perform(form(key).param("paymentMethod", "COD").param("expectedTotal", "22000"), false)
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrlPattern("/orders/1001?t=*"))
                .andReturn();
        var orderUrl = placed.getResponse().getRedirectedUrl();
        assertThat(shopper.cookies.get("cart_count")).isEqualTo("0");

        shopper.perform(get(shop + orderUrl), false)
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().string("Referrer-Policy", "no-referrer"))
                .andExpect(content().string(allOf(containsString("طلب رقم 1001"), containsString("بانتظار التأكيد"),
                        containsString("ادفع 22,000 ل.س نقداً عند استلام طلبك."), containsString("2 × وشاح"),
                        containsString("قرب الجامع"), not(containsString("name=\"reference\"")))));
        // A repeated submit (back button, double click) lands on the same order.
        shopper.perform(form(key).param("paymentMethod", "COD"), false)
                .andExpect(redirectedUrl(orderUrl));
        // Without the token the order does not exist.
        mvc.perform(get(shop + "/orders/1001")).andExpect(status().isNotFound());
        mvc.perform(get(shop + "/orders/1001").param("t", "x".repeat(43))).andExpect(status().isNotFound());
    }

    @Test
    void showsFieldErrorsAndRefusals() throws Exception {
        var shopper = shopperWith(1);
        var key = hidden(shopper.perform(get(shop + "/checkout"), false).andReturn().getResponse()
                .getContentAsString(), "idempotencyKey");

        shopper.perform(post(shop + "/checkout").param("idempotencyKey", key).param("phone", "12")
                        .param("governorate", "DAMASCUS"), false)
                .andExpect(status().isOk())
                .andExpect(content().string(allOf(containsString("اكتب اسمك."), containsString("رقم الموبايل غير صحيح"),
                        containsString("اكتب المدينة."), containsString("اختر طريقة الدفع."),
                        containsString("value=\"" + key + "\""))));

        shopper.perform(form(key, "0933 111 222", "HOMS", "حمص").param("paymentMethod", "COD"), false)
                .andExpect(content().string(containsString("لا نوصل إلى هذا العنوان حالياً.")));
        shopper.perform(form(key).param("paymentMethod", "COD").param("expectedTotal", "100"), false)
                .andExpect(content().string(containsString("تغيّر المجموع إلى 12,000 ل.س")));
        // A crafted governorate or method is a plain field error, not a server error.
        shopper.perform(form(key).param("paymentMethod", "BITCOIN"), false)
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("القيمة غير صالحة.")));
    }

    @Test
    void checkoutNeedsTheCsrfTokenAndACart() throws Exception {
        var shopper = shopperWith(1);
        mvc.perform(post(shop + "/checkout").cookie(new Cookie("cart", shopper.cookies.get("cart")))
                        .param("paymentMethod", "COD"))
                .andExpect(status().isForbidden());

        var empty = new Shopper();
        empty.visit("/");
        empty.perform(get(shop + "/checkout"), false).andExpect(redirectedUrl("/cart"));
        mvc.perform(get(MAIN + "/checkout")).andExpect(status().isNotFound());
    }

    @Test
    void summaryFollowsTheAddressOverHtmx() throws Exception {
        var shopper = shopperWith(1);

        shopper.perform(get(shop + "/checkout/summary").param("governorate", "DAMASCUS").param("city", "دمشق"), true)
                .andExpect(content().string(allOf(not(containsString("<html")), containsString("id=\"checkout-summary\""),
                        containsString("2,000 ل.س"), containsString("12,000 ل.س"), containsString("خلال يوم"),
                        containsString("name=\"expectedTotal\" value=\"12000.00\""),
                        containsString("<datalist id=\"checkout-cities\" hx-swap-oob=\"true\">"))));
        assertThat(shopper.cookies).containsKey("delivery_to");
        shopper.perform(get(shop + "/checkout/summary").param("governorate", "NARNIA"), true)
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("اختر عنوان التوصيل")));
    }

    @Test
    void phoneCodeGuardsCheckoutWhenTheStoreAsks(CapturedOutput output) throws Exception {
        TenantContext.runAs(store, () -> settings.updateCheckout(true, null));
        var shopper = shopperWith(1);
        var page = shopper.perform(get(shop + "/checkout"), false)
                .andExpect(content().string(containsString("أرسل الرمز"))).andReturn().getResponse().getContentAsString();
        var key = hidden(page, "idempotencyKey");

        shopper.perform(form(key).param("paymentMethod", "COD"), false)
                .andExpect(content().string(containsString("أكّد رقم موبايلك بالرمز أولاً.")));

        shopper.perform(post(shop + "/checkout/code").param("phone", "0933 600 001"), true)
                .andExpect(content().string(allOf(containsString("أرسلنا الرمز"), containsString("name=\"code\""))));
        // The code ends the message; the store name before it may contain digits too.
        var matcher = Pattern.compile("Message to \\+963933600001: .*: (\\d{6})\\s*$", Pattern.MULTILINE)
                .matcher(output.getOut());
        assertThat(matcher.find()).isTrue();
        shopper.perform(post(shop + "/checkout/verify").param("phone", "0933600001").param("code", "000000"), true)
                .andExpect(content().string(matcher.group(1).equals("000000") ? containsString("تم تأكيد")
                        : containsString("الرمز غير صحيح")));
        shopper.perform(post(shop + "/checkout/verify").param("phone", "0933600001").param("code", matcher.group(1)), true)
                .andExpect(content().string(containsString("تم تأكيد رقم موبايلك.")));
        assertThat(shopper.cookies).containsKey("phone_verified");

        // The verified phone places the order; another phone in the form would not.
        shopper.perform(form(key, "0933 600 002", "DAMASCUS", "دمشق").param("paymentMethod", "COD"), false)
                .andExpect(content().string(containsString("أكّد رقم موبايلك بالرمز أولاً.")));
        shopper.perform(form(key, "0933 600 001", "DAMASCUS", "دمشق").param("paymentMethod", "COD"), false)
                .andExpect(redirectedUrlPattern("/orders/*?t=*"));
    }

    @Test
    void shopperSendsTheTransferReference() throws Exception {
        var shopper = shopperWith(1);
        var key = hidden(shopper.perform(get(shop + "/checkout"), false).andReturn().getResponse()
                .getContentAsString(), "idempotencyKey");
        var orderUrl = shopper.perform(form(key).param("paymentMethod", "SHAM_CASH"), false)
                .andReturn().getResponse().getRedirectedUrl();
        var token = orderUrl.substring(orderUrl.indexOf("t=") + 2);

        shopper.perform(get(shop + orderUrl), false)
                .andExpect(content().string(allOf(containsString("شام كاش"), containsString("حوّل 12,000 ل.س"),
                        containsString("0944 123 456"), containsString("متجر الياسمين"),
                        containsString("أرسل رقم العملية بعد التحويل."), containsString("name=\"reference\""),
                        containsString("name=\"_csrf\""))));
        shopper.perform(post(shop + "/orders/1001/proof").param("t", token).param("reference", " "), false)
                .andExpect(redirectedUrlPattern("/orders/1001?t=*&proof-invalid"));
        shopper.perform(post(shop + "/orders/1001/proof").param("t", token).param("reference", "TX-5566"), false)
                .andExpect(redirectedUrlPattern("/orders/1001?t=*&proof-sent"));
        shopper.perform(get(shop + orderUrl), false)
                .andExpect(content().string(allOf(containsString("بانتظار التحقق من الدفع"),
                        containsString("نتحقق من دفعتك."), not(containsString("name=\"reference\"")))));
        shopper.perform(post(shop + "/orders/1001/proof").param("t", "x".repeat(43)).param("reference", "TX"), false)
                .andExpect(status().isNotFound());
    }

    @Test
    void merchantSeesTheOrderAndItsHistory() throws Exception {
        placeCodOrder();
        TestAccounts.staff(staffUsers, passwords, store, "Staff", "staff@" + slug + ".test", null, StaffRole.STAFF);
        var staff = TestAccounts.logIn(mvc, slug, "staff@" + slug + ".test");

        mvc.perform(get(MAIN + "/admin/orders").session(staff))
                .andExpect(status().isOk())
                .andExpect(content().string(allOf(containsString("#1001"), containsString("سارة"),
                        containsString("+963933700001"), containsString("12,000 ل.س"), containsString("بانتظار التأكيد"),
                        containsString("الدفع عند الاستلام"))));
        mvc.perform(get(MAIN + "/admin/orders/1001").session(staff))
                .andExpect(status().isOk())
                .andExpect(content().string(allOf(containsString("طلب رقم 1001"), containsString("1 × وشاح"),
                        containsString("قرب الجامع"), containsString("سجل الطلب"), containsString("tel:+963933700001"))));
        mvc.perform(get(MAIN + "/admin/orders/9999").session(staff)).andExpect(status().isNotFound());

        // Another store's staff cannot open it.
        var otherSlug = TestStores.randomSlug();
        var other = TestStores.create(jdbc, otherSlug, "ACTIVE");
        TestAccounts.staff(staffUsers, passwords, other, "Owner", "owner@" + otherSlug + ".test", null, StaffRole.OWNER);
        var otherOwner = TestAccounts.logIn(mvc, otherSlug, "owner@" + otherSlug + ".test");
        mvc.perform(get(MAIN + "/admin/orders/1001").session(otherOwner)).andExpect(status().isNotFound());
        mvc.perform(get(MAIN + "/admin/orders").session(otherOwner))
                .andExpect(content().string(containsString("لا توجد طلبات قيد التنفيذ.")));
    }

    @Test
    void ownerSetsCheckoutAndPaymentMethods() throws Exception {
        TestAccounts.staff(staffUsers, passwords, store, "Owner", "owner@" + slug + ".test", null, StaffRole.OWNER);
        var owner = TestAccounts.logIn(mvc, slug, "owner@" + slug + ".test");

        mvc.perform(post(MAIN + "/admin/settings/checkout").session(owner).with(csrf())
                        .param("otpRequired", "true").param("maxTotal", "٥٠٬٠٠٠"))
                .andExpect(redirectedUrl("/admin/settings/checkout?saved"));
        var saved = TenantContext.callAs(store, settings::current);
        assertThat(saved.otpRequired()).isTrue();
        assertThat(saved.newCustomerMaxTotal()).isEqualByComparingTo("50000");
        mvc.perform(post(MAIN + "/admin/settings/checkout").session(owner).with(csrf()).param("maxTotal", "-1"))
                .andExpect(content().string(containsString("أدخل مبلغاً أكبر من صفر")));

        mvc.perform(get(MAIN + "/admin/settings/payments").session(owner))
                .andExpect(content().string(allOf(containsString("سيريتل كاش"), containsString("تحويل مصرفي"),
                        containsString("0944 123 456"))));
        mvc.perform(post(MAIN + "/admin/settings/payments/BANK_TRANSFER").session(owner).with(csrf())
                        .param("enabled", "true").param("accountName", "Jasmine"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("اكتب رقم الحساب لتفعيل هذه الطريقة.")));
        mvc.perform(post(MAIN + "/admin/settings/payments/BANK_TRANSFER").session(owner).with(csrf())
                        .param("enabled", "true").param("accountId", "SY00 1234").param("accountName", "Jasmine"))
                .andExpect(redirectedUrl("/admin/settings/payments?saved=BANK_TRANSFER"));
        // Turning everything off is refused at the last method.
        for (var method : new String[] {"BANK_TRANSFER", "SHAM_CASH", "COD"}) {
            mvc.perform(post(MAIN + "/admin/settings/payments/" + method).session(owner).with(csrf()));
        }
        assertThat(TenantContext.callAs(store, () -> methods.enabled())).extracting(PaymentMethods.MethodConfig::method)
                .containsExactly(PaymentMethod.COD);
    }

    @Test
    void staffCannotChangeCheckoutSettings() throws Exception {
        TestAccounts.staff(staffUsers, passwords, store, "Staff", "staff@" + slug + ".test", null, StaffRole.STAFF);
        var staff = TestAccounts.logIn(mvc, slug, "staff@" + slug + ".test");

        mvc.perform(get(MAIN + "/admin/settings/checkout").session(staff)).andExpect(status().isForbidden());
        mvc.perform(get(MAIN + "/admin/settings/payments").session(staff)).andExpect(status().isForbidden());
        mvc.perform(post(MAIN + "/admin/settings/payments/COD").session(staff).with(csrf()))
                .andExpect(status().isForbidden());
    }

    private void placeCodOrder() throws Exception {
        var shopper = shopperWith(1);
        var key = hidden(shopper.perform(get(shop + "/checkout"), false).andReturn().getResponse()
                .getContentAsString(), "idempotencyKey");
        shopper.perform(form(key, "0933 700 001", "DAMASCUS", "دمشق").param("paymentMethod", "COD"), false)
                .andExpect(header().string("Location", startsWith("/orders/1001")));
    }

    private MockHttpServletRequestBuilder form(String key) {
        return form(key, "0933 111 222", "DAMASCUS", "دمشق");
    }

    private MockHttpServletRequestBuilder form(String key, String phone, String governorate, String city) {
        return post(shop + "/checkout").param("idempotencyKey", key).param("name", "سارة").param("phone", phone)
                .param("governorate", governorate).param("city", city).param("area", "المزة")
                .param("landmark", "قرب الجامع");
    }

    private Shopper shopperWith(int quantity) throws Exception {
        var shopper = new Shopper();
        shopper.visit("/p/scarf");
        shopper.perform(post(shop + "/cart/items").param("variantId", scarf.toString())
                .param("quantity", Integer.toString(quantity)), true).andExpect(status().isOk());
        return shopper;
    }

    private static String hidden(String page, String name) {
        var matcher = Pattern.compile("name=\"" + name + "\" value=\"([^\"]+)\"").matcher(page);
        assertThat(matcher.find()).as("hidden field %s", name).isTrue();
        return matcher.group(1);
    }

    /**
     * A browser on the store: keeps cookies; HTMX requests copy the CSRF cookie to the header (app.js), plain form
     * posts send it as the _csrf field the page carries.
     */
    private final class Shopper {

        final Map<String, String> cookies = new LinkedHashMap<>();

        void visit(String path) throws Exception {
            remember(mvc.perform(withCookies(get(shop + path))).andExpect(status().isOk()).andReturn());
        }

        ResultActions perform(MockHttpServletRequestBuilder request, boolean htmx) throws Exception {
            var token = cookies.get("XSRF-TOKEN");
            if (htmx) {
                request.header("HX-Request", "true");
                if (token != null) {
                    request.header("X-XSRF-TOKEN", token);
                }
            } else if (token != null) {
                request.param("_csrf", token);
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
