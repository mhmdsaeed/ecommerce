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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrlPattern;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.regex.Pattern;

import jakarta.servlet.http.Cookie;

import org.hamcrest.Matcher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import com.unispark.ecommerce.cart.Carts;
import com.unispark.ecommerce.catalog.ProductStatus;
import com.unispark.ecommerce.catalog.Products;
import com.unispark.ecommerce.catalog.Products.ProductDraft;
import com.unispark.ecommerce.catalog.Products.Saved;
import com.unispark.ecommerce.customer.Customers;
import com.unispark.ecommerce.delivery.DeliveryZones;
import com.unispark.ecommerce.delivery.DeliveryZones.ZoneDraft;
import com.unispark.ecommerce.delivery.Governorate;
import com.unispark.ecommerce.identity.StaffRole;
import com.unispark.ecommerce.identity.internal.persistence.StaffUserRepository;
import com.unispark.ecommerce.inventory.Inventory;
import com.unispark.ecommerce.inventory.StockLevels.Level;
import com.unispark.ecommerce.order.Actor;
import com.unispark.ecommerce.order.Checkout;
import com.unispark.ecommerce.order.Checkout.Placed;
import com.unispark.ecommerce.order.Checkout.Reason;
import com.unispark.ecommerce.order.Checkout.Rejected;
import com.unispark.ecommerce.order.Checkout.Request;
import com.unispark.ecommerce.order.OrderStatus;
import com.unispark.ecommerce.order.Orders;
import com.unispark.ecommerce.payment.PaymentMethod;
import com.unispark.ecommerce.payment.PaymentMethods;
import com.unispark.ecommerce.payment.PaymentMethods.MethodDraft;
import com.unispark.ecommerce.payment.PaymentStatus;
import com.unispark.ecommerce.shared.Currency;
import com.unispark.ecommerce.shared.TenantContext;

/** M9 done-when: a merchant processes an order end to end (SPEC 9.3), with proofs, drivers and the blocklist. */
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfig.class, IsolationProbeWeb.class})
@ActiveProfiles("test")
class OrderManagementIT {

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

    @Autowired
    DeliveryZones zones;

    @Autowired
    PaymentMethods methods;

    @Autowired
    Carts carts;

    @Autowired
    Checkout checkout;

    @Autowired
    Orders orders;

    @Autowired
    Customers customers;

    String slug;
    UUID store;
    UUID scarf;
    UUID driver;
    MockHttpSession staff;
    MockHttpSession owner;

    @BeforeEach
    void createStore() throws Exception {
        slug = TestStores.randomSlug();
        store = TestStores.create(jdbc, slug, "ACTIVE");
        TestAccounts.staff(staffUsers, passwords, store, "رامي", "staff@" + slug + ".test", null, StaffRole.STAFF);
        TestAccounts.staff(staffUsers, passwords, store, "هالة", "owner@" + slug + ".test", null, StaffRole.OWNER);
        driver = TestAccounts.staff(staffUsers, passwords, store, "أبو سمير", null, "+963933900001", StaffRole.DRIVER);
        staff = TestAccounts.logIn(mvc, slug, "staff@" + slug + ".test");
        owner = TestAccounts.logIn(mvc, slug, "owner@" + slug + ".test");
        TenantContext.runAs(store, () -> {
            var id = ((Saved) products.create(new ProductDraft("وشاح", "Scarf", null, null, "scarf", null,
                    Currency.SYP), "10000")).id();
            products.changeStatus(id, ProductStatus.ACTIVE);
            scarf = products.find(id).orElseThrow().variants().getFirst().id();
            inventory.setOnHand(scarf, 10);
            zones.create(new ZoneDraft(Governorate.DAMASCUS, "دمشق", null, "2000", Currency.SYP, null, null, null,
                    null));
            methods.save(PaymentMethod.SHAM_CASH, new MethodDraft(true, null, null, "0944 123 456", "Jasmine", null,
                    null));
        });
    }

    @Test
    void theNavigationCountsOrdersToConfirmAndMarksTheCurrentPage() throws Exception {
        place("0933 111 010", "سارة", PaymentMethod.COD, 1);
        place("0933 111 011", "سارة", PaymentMethod.COD, 1);

        // On every full admin page, for staff too: the badge, with its words for screen readers.
        mvc.perform(get(MAIN + "/admin/products").session(staff))
                .andExpect(content().string(allOf(containsString("2 بانتظار التأكيد"),
                        current("/admin/products"),
                        not(current("/admin/orders")),
                        // Staff don't see the managers' links.
                        not(containsString("href=\"/admin/settlement\"")))));
        // The dashboard's ledger: two orders waiting, marked as needing the merchant.
        mvc.perform(get(MAIN + "/admin").session(owner))
                .andExpect(content().string(allOf(current("/admin"),
                        containsString("href=\"/admin/settlement\""), containsString("اليوم"),
                        containsString("border-s-4 border-s-amber-400"))));
        // HTMX fragments don't count.
        mvc.perform(get(MAIN + "/admin/products").session(staff).header("HX-Request", "true"))
                .andExpect(content().string(not(containsString("بانتظار التأكيد"))));

        // The orders list's tabs: open is current; the "awaiting confirmation" tab counts both, in amber.
        mvc.perform(get(MAIN + "/admin/orders").session(staff))
                .andExpect(content().string(allOf(current("/admin/orders\\?view=open"),
                        matchesPattern("(?s).*view=PENDING_CONFIRMATION.*?بانتظار التأكيد</span>\\s*<span class=\"[^\"]*bg-amber-100[^\"]*\">2</span>.*"),
                        containsString("<input type=\"hidden\" name=\"view\" form=\"order-filters\" value=\"open\">"))));
        // A tab's results carry its view, so searching stays on it.
        mvc.perform(get(MAIN + "/admin/orders").param("view", "PENDING_CONFIRMATION").session(staff)
                        .header("HX-Request", "true"))
                .andExpect(content().string(allOf(not(containsString("<html")),
                        containsString("name=\"view\" form=\"order-filters\" value=\"PENDING_CONFIRMATION\""),
                        current("/admin/orders\\?view=PENDING_CONFIRMATION"))));
    }

    @Test
    void merchantProcessesAnOrderEndToEnd() throws Exception {
        var placed = place("0933 111 001", "سارة", PaymentMethod.COD, 2);
        var page = MAIN + "/admin/orders/" + placed.number();

        mvc.perform(get(MAIN + "/admin").session(staff))
                .andExpect(content().string(allOf(containsString("طلبات اليوم"), containsString("بانتظار التأكيد"))));
        mvc.perform(get(MAIN + "/admin/orders").session(staff))
                .andExpect(content().string(allOf(containsString("#" + placed.number()), containsString("سارة"))));
        mvc.perform(get(page).session(staff))
                .andExpect(content().string(allOf(containsString("تأكيد الطلب"), containsString("إلغاء الطلب"),
                        containsString("أبو سمير"),
                        // It can be confirmed, not delivered yet (the "delivered" step itself shows on the track).
                        containsString("name=\"target\" value=\"CONFIRMED\""),
                        not(containsString("name=\"target\" value=\"DELIVERED\"")),
                        // It waits on the merchant (the next-step panel's amber edge); the track marks its step.
                        containsString("border-s-amber-400"), containsString("aria-current=\"step\""))));

        step(page, "CONFIRMED").andExpect(redirectedUrl(page.substring(MAIN.length()) + "?done=CONFIRMED"));
        mvc.perform(get(page + "?done=CONFIRMED").session(staff))
                .andExpect(content().string(allOf(containsString("تم تأكيد الطلب."), containsString("بدء التجهيز"))));
        step(page, "PREPARING");
        mvc.perform(post(page + "/driver").session(staff).with(csrf()).param("driverId", driver.toString()))
                .andExpect(redirectedUrl("/admin/orders/" + placed.number() + "?done=driver"));
        step(page, "OUT_FOR_DELIVERY");
        step(page, "DELIVERED");
        assertThat(level()).isEqualTo(new Level(scarf, 8, 0));
        step(page, "COMPLETED");

        var order = as(() -> orders.findByNumber(placed.number())).orElseThrow();
        assertThat(order.status()).isEqualTo(OrderStatus.COMPLETED);
        assertThat(order.driverId()).isEqualTo(driver);
        assertThat(order.events()).extracting(Orders.Event::type).containsExactly("PLACED", "STATUS_CHANGED",
                "STATUS_CHANGED", "DRIVER_ASSIGNED", "STATUS_CHANGED", "STATUS_CHANGED", "STATUS_CHANGED");
        mvc.perform(get(page).session(staff))
                .andExpect(content().string(allOf(containsString("مكتمل"), containsString("رامي"),
                        containsString("الزبون"), containsString("تغيّر السائق"), containsString("وصّله: أبو سمير"),
                        not(containsString("name=\"target\"")), not(containsString("name=\"driverId\"")))));
        mvc.perform(get(MAIN + "/admin/orders").param("view", "all").session(staff))
                .andExpect(content().string(allOf(containsString("#" + placed.number()), containsString("أبو سمير"))));
        mvc.perform(get(MAIN + "/admin/orders").session(staff))
                .andExpect(content().string(containsString("لا توجد طلبات قيد التنفيذ.")));
    }

    @Test
    void aSecondClickOnAnOldButtonChangesNothing() throws Exception {
        var placed = place("0933 111 002", "سارة", PaymentMethod.COD, 1);
        var page = MAIN + "/admin/orders/" + placed.number();
        step(page, "CONFIRMED");

        // The same "confirm" form again, from a tab that did not reload.
        step(page, "CONFIRMED").andExpect(redirectedUrl("/admin/orders/" + placed.number() + "?error=changed"));
        mvc.perform(get(page + "?error=changed").session(staff))
                .andExpect(content().string(containsString("تغيّر الطلب للتو")));
        assertThat(as(() -> orders.findByNumber(placed.number())).orElseThrow().events()).hasSize(2);
    }

    @Test
    void cancellingKeepsTheReasonAndReturnsTheStock() throws Exception {
        var placed = place("0933 111 003", "سارة", PaymentMethod.COD, 3);
        assertThat(level().reserved()).isEqualTo(3);

        mvc.perform(post(MAIN + "/admin/orders/" + placed.number() + "/status").session(staff).with(csrf())
                        .param("target", "CANCELLED").param("note", "الزبون لا يرد على الهاتف"))
                .andExpect(redirectedUrl("/admin/orders/" + placed.number() + "?done=CANCELLED"));

        assertThat(level()).isEqualTo(new Level(scarf, 10, 0));
        mvc.perform(get(MAIN + "/admin/orders/" + placed.number()).session(staff))
                .andExpect(content().string(allOf(containsString("ملغى"), containsString("الزبون لا يرد على الهاتف"))));
        // The shopper sees it too.
        mvc.perform(get(storefront(slug) + "/orders/" + placed.number()).param("t", placed.trackingToken()))
                .andExpect(content().string(containsString("ملغى")));
    }

    @Test
    void merchantChecksAScreenshotAndApprovesThePayment() throws Exception {
        var placed = place("0933 111 004", "سارة", PaymentMethod.SHAM_CASH, 1);
        var shopper = shopperCookies();

        mvc.perform(multipart(storefront(slug) + "/orders/" + placed.number() + "/proof")
                        .file(new MockMultipartFile("screenshot", "receipt.jpg", "image/jpeg", TestImages.jpeg(900, 1600)))
                        .param("t", placed.trackingToken()).param("reference", "")
                        .param("_csrf", shopper.getValue()).cookie(shopper))
                .andExpect(redirectedUrlPattern("/orders/" + placed.number() + "?t=*&proof-sent"));

        mvc.perform(get(MAIN + "/admin").session(staff))
                .andExpect(content().string(containsString("دفعات بانتظار التحقق")));
        mvc.perform(get(MAIN + "/admin/orders").param("view", "all").param("payment", "AWAITING_VERIFICATION")
                        .session(staff))
                .andExpect(content().string(containsString("#" + placed.number())));
        var detail = mvc.perform(get(MAIN + "/admin/orders/" + placed.number()).session(staff))
                .andExpect(content().string(allOf(containsString("بانتظار قرارك"), containsString("وصلني المبلغ (12,000 ل.س)"))))
                .andReturn().getResponse().getContentAsString();

        // The screenshot is private: a signed link opens it, the plain object URL does not.
        var signed = Pattern.compile("<img src=\"([^\"]+X-Amz-Signature[^\"]+)\"").matcher(detail);
        assertThat(signed.find()).isTrue();
        var signedUrl = signed.group(1).replace("&amp;", "&");
        var http = HttpClient.newHttpClient();
        var opened = http.send(HttpRequest.newBuilder(URI.create(signedUrl)).build(), HttpResponse.BodyHandlers.ofByteArray());
        assertThat(opened.statusCode()).isEqualTo(200);
        assertThat(opened.headers().firstValue("Content-Type")).contains("image/webp");
        var unsigned = http.send(HttpRequest.newBuilder(URI.create(signedUrl.substring(0, signedUrl.indexOf('?'))))
                .build(), HttpResponse.BodyHandlers.discarding());
        assertThat(unsigned.statusCode()).isEqualTo(403);

        mvc.perform(post(MAIN + "/admin/orders/" + placed.number() + "/proof").session(staff).with(csrf())
                        .param("decision", "approve"))
                .andExpect(redirectedUrl("/admin/orders/" + placed.number() + "?done=approved"));
        var order = as(() -> orders.findByNumber(placed.number())).orElseThrow();
        assertThat(order.paymentStatus()).isEqualTo(PaymentStatus.PAID);
        assertThat(order.proofs()).singleElement().satisfies(proof -> assertThat(proof.status()).isEqualTo("APPROVED"));
        mvc.perform(post(MAIN + "/admin/orders/" + placed.number() + "/proof").session(staff).with(csrf())
                        .param("decision", "reject"))
                .andExpect(redirectedUrl("/admin/orders/" + placed.number() + "?error=changed"));
    }

    @Test
    void aRejectedProofCanBeSentAgain() throws Exception {
        var placed = place("0933 111 005", "سارة", PaymentMethod.SHAM_CASH, 1);
        as(() -> orders.submitProof(placed.number(), placed.trackingToken(), "TX-1", null));

        mvc.perform(post(MAIN + "/admin/orders/" + placed.number() + "/proof").session(staff).with(csrf())
                        .param("decision", "reject").param("note", "لم يصل أي مبلغ بهذا الرقم"))
                .andExpect(redirectedUrl("/admin/orders/" + placed.number() + "?done=rejected"));

        mvc.perform(get(storefront(slug) + "/orders/" + placed.number()).param("t", placed.trackingToken()))
                .andExpect(content().string(allOf(containsString("ملاحظة المتجر: لم يصل أي مبلغ بهذا الرقم"),
                        containsString("name=\"reference\""))));
        assertThat(as(() -> orders.submitProof(placed.number(), placed.trackingToken(), "TX-2", null)))
                .isInstanceOf(Orders.ProofAccepted.class);
        assertThat(as(() -> orders.findByNumber(placed.number())).orElseThrow().pendingProof()).hasValueSatisfying(
                proof -> assertThat(proof.transactionReference()).isEqualTo("TX-2"));
    }

    @Test
    void ownerBlocksACustomerFromTheOrderPage() throws Exception {
        var placed = place("0933 111 006", "سارة", PaymentMethod.COD, 1);
        var page = MAIN + "/admin/orders/" + placed.number();

        mvc.perform(post(page + "/block").session(staff).with(csrf()).param("blocked", "true"))
                .andExpect(status().isForbidden());
        mvc.perform(get(page).session(staff)).andExpect(content().string(not(containsString("حظر الزبون"))));

        mvc.perform(post(page + "/block").session(owner).with(csrf()).param("blocked", "true")
                        .param("reason", "رفض ثلاث طلبات"))
                .andExpect(redirectedUrl("/admin/orders/" + placed.number() + "?done=blocked"));
        mvc.perform(get(page).session(owner))
                .andExpect(content().string(allOf(containsString("محظور"), containsString("رفض ثلاث طلبات"),
                        containsString("رفع الحظر"))));
        assertThat(as(() -> checkout.place(request(cartWith(1), "0933 111 006", "سارة", PaymentMethod.COD))))
                .isEqualTo(Rejected.because(Reason.BLOCKED));

        mvc.perform(get(MAIN + "/admin/customers").session(staff))
                .andExpect(content().string(allOf(containsString("+963933111006"), containsString("محظور"),
                        not(containsString("رفع الحظر")))));
        mvc.perform(post(MAIN + "/admin/customers/" + order(placed).customerId() + "/block").session(owner).with(csrf())
                        .header("HX-Request", "true").param("blocked", "false"))
                .andExpect(content().string(allOf(not(containsString("<html")), containsString("حظر الزبون"))));
        assertThat(as(() -> checkout.place(request(cartWith(1), "0933 111 006", "سارة", PaymentMethod.COD))))
                .isInstanceOf(Placed.class);
    }

    @Test
    void filtersAndSearches() throws Exception {
        var first = place("0933 222 001", "سارة أحمد", PaymentMethod.COD, 1);
        var second = place("0944 333 002", "Omar", PaymentMethod.COD, 1);
        as(() -> orders.changeStatus(second.orderId(), OrderStatus.CONFIRMED, Actor.system()));

        mvc.perform(get(MAIN + "/admin/orders").param("view", "PENDING_CONFIRMATION").session(staff))
                .andExpect(content().string(allOf(containsString("#" + first.number()), not(containsString("#" + second.number())))));
        mvc.perform(get(MAIN + "/admin/orders").param("view", "all").param("q", "omar").session(staff))
                .andExpect(content().string(allOf(containsString("#" + second.number()), not(containsString("#" + first.number())))));
        mvc.perform(get(MAIN + "/admin/orders").param("view", "all").param("q", "٠٩٣٣ ٢٢٢ ٠٠١").session(staff))
                .andExpect(content().string(allOf(containsString("#" + first.number()), not(containsString("#" + second.number())))));
        mvc.perform(get(MAIN + "/admin/orders").param("view", "all").param("q", "#" + second.number())
                        .header("HX-Request", "true").session(staff))
                .andExpect(content().string(allOf(not(containsString("<html")), containsString("#" + second.number()),
                        not(containsString("#" + first.number())))));
        mvc.perform(get(MAIN + "/admin/orders").param("view", "all").param("q", "100%_").session(staff))
                .andExpect(content().string(containsString("لا توجد طلبات مطابقة.")));
        mvc.perform(get(MAIN + "/admin/customers").param("q", "سارة").session(staff))
                .andExpect(content().string(allOf(containsString("+963933222001"), not(containsString("+963944333002")))));
    }

    @Test
    void walletQrCodeShowsOnTheOrderPage() throws Exception {
        mvc.perform(multipart(MAIN + "/admin/settings/payments/SHAM_CASH/qr")
                        .file(new MockMultipartFile("qr", "qr.png", "image/png", TestImages.png(400, 400)))
                        .session(owner).with(csrf()))
                .andExpect(redirectedUrl("/admin/settings/payments?saved=SHAM_CASH"));
        mvc.perform(multipart(MAIN + "/admin/settings/payments/SHAM_CASH/qr")
                        .file(new MockMultipartFile("qr", "qr.txt", "image/png", "not an image".getBytes()))
                        .session(owner).with(csrf()))
                .andExpect(redirectedUrl("/admin/settings/payments?qr-error=SHAM_CASH"));
        var key = as(() -> methods.all()).stream().filter(config -> config.method() == PaymentMethod.SHAM_CASH)
                .findFirst().orElseThrow().qrImageKey();
        assertThat(key).startsWith("public/" + store + "/");

        var placed = place("0933 111 010", "سارة", PaymentMethod.SHAM_CASH, 1);
        mvc.perform(get(storefront(slug) + "/orders/" + placed.number()).param("t", placed.trackingToken()))
                .andExpect(content().string(allOf(containsString(key + "/640.webp"), containsString("امسح الرمز للدفع"))));
        // Staff cannot change payment settings.
        mvc.perform(multipart(MAIN + "/admin/settings/payments/SHAM_CASH/qr")
                        .file(new MockMultipartFile("qr", "qr.png", "image/png", TestImages.png(10, 10)))
                        .session(staff).with(csrf()))
                .andExpect(status().isForbidden());
    }

    @Test
    void outcomesInTheUrlAreNeverEvaluated() throws Exception {
        var placed = place("0933 111 007", "سارة", PaymentMethod.COD, 1);

        mvc.perform(get(MAIN + "/admin/orders/" + placed.number()).param("done", "__${T(java.lang.Runtime)}__")
                        .param("error", "${7*7}").session(staff))
                .andExpect(status().isOk())
                .andExpect(content().string(allOf(not(containsString("??")), not(containsString("role=\"status\"")))));
    }

    @Test
    void anotherStoreCannotTouchTheOrder() throws Exception {
        var placed = place("0933 111 008", "سارة", PaymentMethod.COD, 1);
        var otherSlug = TestStores.randomSlug();
        var other = TestStores.create(jdbc, otherSlug, "ACTIVE");
        TestAccounts.staff(staffUsers, passwords, other, "Owner", "owner@" + otherSlug + ".test", null, StaffRole.OWNER);
        var otherOwner = TestAccounts.logIn(mvc, otherSlug, "owner@" + otherSlug + ".test");

        for (var action : List.of("/status", "/driver", "/proof", "/block")) {
            mvc.perform(post(MAIN + "/admin/orders/" + placed.number() + action).session(otherOwner).with(csrf())
                            .param("target", "CANCELLED").param("decision", "approve").param("blocked", "true")
                            .param("driverId", driver.toString()))
                    .andExpect(status().isNotFound());
        }
        assertThat(order(placed).status()).isEqualTo(OrderStatus.PENDING_CONFIRMATION);
        // Nor assign one of its drivers to its own orders.
        var own = TenantContext.callAs(other, () -> {
            var id = ((Saved) products.create(new ProductDraft("كوب", "Mug", null, null, "mug", null, Currency.SYP),
                    "100")).id();
            products.changeStatus(id, ProductStatus.ACTIVE);
            zones.create(new ZoneDraft(Governorate.DAMASCUS, null, null, "1", Currency.SYP, null, null, null, null));
            var variant = products.find(id).orElseThrow().variants().getFirst().id();
            return (Placed) checkout.place(request(carts.add(null, variant, 1).token(), "0933 111 009", "x",
                    PaymentMethod.COD));
        });
        assertThat(TenantContext.callAs(other, () -> orders.assignDriver(own.orderId(), driver,
                Actor.system()))).isInstanceOf(Orders.NotADriver.class);
    }

    private ResultActions step(String page, String target) throws Exception {
        return mvc.perform(post(page + "/status").session(staff).with(csrf()).param("target", target));
    }

    private Placed place(String phone, String name, PaymentMethod method, int quantity) {
        return (Placed) as(() -> checkout.place(request(cartWith(quantity), phone, name, method)));
    }

    private String cartWith(int quantity) {
        return as(() -> carts.add(null, scarf, quantity).token());
    }

    private static Request request(String cart, String phone, String name, PaymentMethod method) {
        return new Request(cart, UUID.randomUUID(), name, phone, null, Governorate.DAMASCUS, "دمشق", null,
                "قرب الجامع", null, method, null, null);
    }

    private Orders.OrderView order(Placed placed) {
        return as(() -> orders.findByNumber(placed.number())).orElseThrow();
    }

    /** A storefront visit, for the XSRF-TOKEN cookie the proof form posts back as _csrf. */
    private Cookie shopperCookies() throws Exception {
        var visit = mvc.perform(get(storefront(slug) + "/")).andReturn();
        return visit.getResponse().getCookie("XSRF-TOKEN");
    }

    private Level level() {
        return as(() -> inventory.levels(List.of(scarf)).of(scarf).orElseThrow());
    }


    /** The navigation link to this path, marked as the current page. */
    private static Matcher<String> current(String path) {
        return matchesPattern("(?s).*<a href=\"" + path + "\"[^>]*aria-current=\"page\".*");
    }

    private <T> T as(Supplier<T> action) {
        return TenantContext.callAs(store, action);
    }
}
