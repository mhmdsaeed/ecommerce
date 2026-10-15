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
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.regex.Pattern;

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
import org.springframework.test.web.servlet.ResultActions;

import com.unispark.ecommerce.cart.Carts;
import com.unispark.ecommerce.catalog.ProductStatus;
import com.unispark.ecommerce.catalog.Products;
import com.unispark.ecommerce.catalog.Products.ProductDraft;
import com.unispark.ecommerce.catalog.Products.Saved;
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
import com.unispark.ecommerce.order.Checkout.Request;
import com.unispark.ecommerce.order.CodSettlement;
import com.unispark.ecommerce.order.OrderStatus;
import com.unispark.ecommerce.order.Orders;
import com.unispark.ecommerce.payment.PaymentMethod;
import com.unispark.ecommerce.payment.PaymentMethods;
import com.unispark.ecommerce.payment.PaymentMethods.MethodDraft;
import com.unispark.ecommerce.payment.PaymentStatus;
import com.unispark.ecommerce.shared.Currency;
import com.unispark.ecommerce.shared.Money;
import com.unispark.ecommerce.shared.TenantContext;

/**
 * M10 done-when: a driver delivers and collects, and the settlement page shows what they still hold (SPEC 8).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfig.class, IsolationProbeWeb.class})
@ActiveProfiles("test")
class DriverDeliveryIT {

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
    CodSettlement settlement;

    String slug;
    UUID store;
    UUID scarf;
    UUID driver;
    UUID otherDriver;
    MockHttpSession driverSession;
    MockHttpSession otherDriverSession;
    MockHttpSession owner;
    MockHttpSession staff;

    @BeforeEach
    void createStore() throws Exception {
        slug = TestStores.randomSlug();
        store = TestStores.create(jdbc, slug, "ACTIVE");
        driver = TestAccounts.staff(staffUsers, passwords, store, "أبو سمير", null, "+963933900001", StaffRole.DRIVER);
        otherDriver = TestAccounts.staff(staffUsers, passwords, store, "وسيم", null, "+963933900002", StaffRole.DRIVER);
        TestAccounts.staff(staffUsers, passwords, store, "هالة", "owner@" + slug + ".test", null, StaffRole.OWNER);
        TestAccounts.staff(staffUsers, passwords, store, "رامي", "staff@" + slug + ".test", null, StaffRole.STAFF);
        driverSession = TestAccounts.logIn(mvc, slug, "0933900001");
        otherDriverSession = TestAccounts.logIn(mvc, slug, "0933900002");
        owner = TestAccounts.logIn(mvc, slug, "owner@" + slug + ".test");
        staff = TestAccounts.logIn(mvc, slug, "staff@" + slug + ".test");
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
    void driverDeliversCollectsAndTheMerchantSettles() throws Exception {
        var placed = readyFor(driver, PaymentMethod.COD, 2);

        mvc.perform(get(MAIN + "/driver").session(driverSession))
                .andExpect(content().string(allOf(containsString("#" + placed.number()), containsString("سارة"),
                        containsString("حصّل 22,000 ل.س نقداً"), not(containsString("المبلغ النقدي الذي معك")))));
        mvc.perform(get(MAIN + "/driver/orders/" + placed.number()).session(driverSession))
                .andExpect(content().string(allOf(containsString("tel:+963933111001"),
                        containsString("https://wa.me/963933111001"), containsString("google.com/maps/search"),
                        containsString("استلمت الطلب"))));

        driverPost(placed, "start").andExpect(redirectedUrl("/driver/orders/" + placed.number() + "?done=started"));
        mvc.perform(get(MAIN + "/driver/orders/" + placed.number()).session(driverSession))
                .andExpect(content().string(allOf(containsString("value=\"22000\""), containsString("رفض الزبون"))));
        driverPost(placed, "deliver", "collected", "٢٢٬٠٠٠").andExpect(redirectedUrl("/driver?done=delivered"));

        var order = order(placed);
        assertThat(order.status()).isEqualTo(OrderStatus.DELIVERED);
        assertThat(order.paymentStatus()).isEqualTo(PaymentStatus.PAID);
        assertThat(order.codCollected()).isEqualTo(syp("22000"));
        assertThat(order.events()).last().satisfies(event -> assertThat(event.actor()).isEqualTo("staff:" + driver));
        assertThat(level()).isEqualTo(new Level(scarf, 8, 0));
        mvc.perform(get(MAIN + "/driver?done=delivered").session(driverSession))
                .andExpect(content().string(allOf(containsString("تم تسجيل التسليم."), containsString("المبلغ النقدي الذي معك"),
                        containsString("22,000 ل.س"), not(containsString("#" + placed.number())))));

        // The merchant sees what the driver holds, and records what they hand over.
        mvc.perform(get(MAIN + "/admin").session(owner))
                .andExpect(content().string(allOf(containsString("نقود مع السائقين"), containsString("22,000 ل.س"))));
        mvc.perform(get(MAIN + "/admin/settlement").session(owner))
                .andExpect(content().string(allOf(containsString("أبو سمير"), containsString("22,000 ل.س"))));
        mvc.perform(get(MAIN + "/admin/orders/" + placed.number()).session(owner))
                .andExpect(content().string(containsString("حصّل أبو سمير مبلغ 22,000 ل.س نقداً.")));
        remit("15,000", "SYP").andExpect(redirectedUrl("/admin/settlement/" + driver + "?done=remitted"));
        mvc.perform(get(MAIN + "/admin/settlement/" + driver + "?done=remitted").session(owner))
                .andExpect(content().string(allOf(containsString("تم تسجيل المبلغ."), containsString("7,000 ل.س"),
                        containsString("#" + placed.number()), containsString("هالة"))));
        assertThat(as(() -> settlement.outstanding())).containsExactly(syp("7000"));

        remit("8000", "SYP").andExpect(redirectedUrl("/admin/settlement/" + driver + "?error=too-much"));
        remit("0", "SYP").andExpect(redirectedUrl("/admin/settlement/" + driver + "?error=amount"));
        remit("100", "USD").andExpect(redirectedUrl("/admin/settlement/" + driver + "?error=too-much"));
        remit("7000", "SYP").andExpect(redirectedUrl("/admin/settlement/" + driver + "?done=remitted"));
        assertThat(as(() -> settlement.outstanding())).isEmpty();
        assertThat(as(() -> settlement.balances())).singleElement().satisfies(balance -> assertThat(balance.settled()).isTrue());
    }

    @Test
    void driverRecordsARefusal() throws Exception {
        var placed = readyFor(driver, PaymentMethod.COD, 1);
        driverPost(placed, "start");

        driverPost(placed, "refuse", "reason", "not home").andExpect(redirectedUrl("/driver?done=refused"));

        var order = order(placed);
        assertThat(order.status()).isEqualTo(OrderStatus.REFUSED);
        assertThat(order.paymentStatus()).isEqualTo(PaymentStatus.UNPAID);
        assertThat(order.codCollected()).isNull();
        assertThat(order.events()).last().satisfies(event -> assertThat(event.note()).isEqualTo("not home"));
        assertThat(as(() -> settlement.outstanding())).isEmpty();
    }

    @Test
    void transfersAreDeliveredWithoutCash() throws Exception {
        var placed = readyFor(driver, PaymentMethod.SHAM_CASH, 1);
        driverPost(placed, "start");
        mvc.perform(get(MAIN + "/driver/orders/" + placed.number()).session(driverSession))
                .andExpect(content().string(allOf(containsString("مدفوع بالتحويل"), not(containsString("name=\"collected\"")))));

        driverPost(placed, "deliver").andExpect(redirectedUrl("/driver?done=delivered"));

        assertThat(order(placed).codCollected()).isNull();
        assertThat(as(() -> settlement.outstanding())).isEmpty();
    }

    @Test
    void stepsHappenOnceAndInOrder() throws Exception {
        var placed = readyFor(driver, PaymentMethod.COD, 1);

        driverPost(placed, "deliver", "collected", "12000")
                .andExpect(redirectedUrl("/driver/orders/" + placed.number() + "?error=step"));
        driverPost(placed, "start");
        driverPost(placed, "start").andExpect(redirectedUrl("/driver/orders/" + placed.number() + "?error=step"));
        driverPost(placed, "deliver", "collected", "abc")
                .andExpect(redirectedUrl("/driver/orders/" + placed.number() + "?error=amount"));
        driverPost(placed, "deliver", "collected", "11500");
        driverPost(placed, "deliver", "collected", "11500")
                .andExpect(redirectedUrl("/driver/orders/" + placed.number() + "?error=step"));

        // The driver typed what they really took: the merchant sees the difference.
        assertThat(order(placed).codCollected()).isEqualTo(syp("11500"));
        mvc.perform(get(MAIN + "/admin/orders/" + placed.number()).session(owner))
                .andExpect(content().string(containsString("حصّل أبو سمير مبلغ 11,500 ل.س نقداً.")));
    }

    @Test
    void driversOnlySeeTheirOwnOrders() throws Exception {
        var mine = readyFor(driver, PaymentMethod.COD, 1);
        var unassigned = place(PaymentMethod.COD, 1);

        mvc.perform(get(MAIN + "/driver").session(otherDriverSession))
                .andExpect(content().string(allOf(not(containsString("#" + mine.number())),
                        containsString("لا توجد توصيلات"))));
        mvc.perform(get(MAIN + "/driver/orders/" + mine.number()).session(otherDriverSession))
                .andExpect(status().isNotFound());
        mvc.perform(post(MAIN + "/driver/orders/" + mine.number() + "/start").session(otherDriverSession).with(csrf()))
                .andExpect(status().isNotFound());
        mvc.perform(get(MAIN + "/driver/orders/" + unassigned.number()).session(driverSession))
                .andExpect(status().isNotFound());
        assertThat(order(mine).status()).isEqualTo(OrderStatus.PREPARING);
        // Drivers do not reach the admin, and staff do not reach the settlement.
        mvc.perform(get(MAIN + "/admin/settlement").session(driverSession)).andExpect(status().isForbidden());
        mvc.perform(get(MAIN + "/admin/settlement").session(staff)).andExpect(status().isForbidden());
        mvc.perform(post(MAIN + "/admin/settlement/" + driver + "/remit").session(staff).with(csrf())
                .param("amount", "1").param("currency", "SYP")).andExpect(status().isForbidden());
    }

    @Test
    void merchantMarkingDeliveredRecordsTheCashToo() throws Exception {
        var placed = readyFor(driver, PaymentMethod.COD, 1);
        as(() -> {
            orders.changeStatus(placed.orderId(), OrderStatus.OUT_FOR_DELIVERY, Actor.system());
            return orders.changeStatus(placed.orderId(), OrderStatus.DELIVERED, Actor.system());
        });

        var order = order(placed);
        assertThat(order.paymentStatus()).isEqualTo(PaymentStatus.PAID);
        assertThat(order.codCollected()).isEqualTo(syp("12000"));
        assertThat(as(() -> settlement.statement(driver)).balances()).singleElement()
                .satisfies(balance -> assertThat(balance.outstanding()).isEqualTo(syp("12000")));
    }

    @Test
    void remitFormStartsOnTheCurrencyStillOutstanding() throws Exception {
        for (var collected : List.of("12000", "50")) {
            var placed = readyFor(driver, PaymentMethod.COD, 1);
            driverPost(placed, "start");
            driverPost(placed, "deliver", "collected", collected);
        }
        // The second collection was in dollars (the store's prices are in pounds, so set it directly).
        TenantContext.runAs(store, () -> jdbc.update("UPDATE cod_collection SET currency = 'USD' WHERE amount = 50"));
        remit("12000", "SYP").andExpect(redirectedUrl("/admin/settlement/" + driver + "?done=remitted"));

        var page = statementPage();
        assertThat(prefilledAmount(page)).isEqualTo("50");
        assertThat(page).containsPattern("<option value=\"USD\"\\s+selected=\"selected\"");
        remit("50", "USD").andExpect(redirectedUrl("/admin/settlement/" + driver + "?done=remitted"));
        page = statementPage();
        assertThat(prefilledAmount(page)).isEmpty();
        assertThat(page).doesNotContain("selected=\"selected\"");
    }

    private String statementPage() throws Exception {
        return mvc.perform(get(MAIN + "/admin/settlement/" + driver).session(owner)).andReturn().getResponse()
                .getContentAsString();
    }

    private static String prefilledAmount(String page) {
        var matcher = Pattern.compile("id=\"amount\"[^>]*?value=\"([^\"]*)\"").matcher(page);
        assertThat(matcher.find()).isTrue();
        return matcher.group(1);
    }

    @Test
    void anotherStoreSeesNoCash() throws Exception {
        var placed = readyFor(driver, PaymentMethod.COD, 1);
        driverPost(placed, "start");
        driverPost(placed, "deliver", "collected", "12000");
        var otherSlug = TestStores.randomSlug();
        var other = TestStores.create(jdbc, otherSlug, "ACTIVE");
        TestAccounts.staff(staffUsers, passwords, other, "Owner", "owner@" + otherSlug + ".test", null, StaffRole.OWNER);
        var otherOwner = TestAccounts.logIn(mvc, otherSlug, "owner@" + otherSlug + ".test");

        mvc.perform(get(MAIN + "/admin/settlement").session(otherOwner))
                .andExpect(content().string(containsString("لم يحصّل السائقون أي مبلغ بعد.")));
        mvc.perform(get(MAIN + "/admin/settlement/" + driver).session(otherOwner)).andExpect(status().isNotFound());
        mvc.perform(post(MAIN + "/admin/settlement/" + driver + "/remit").session(otherOwner).with(csrf())
                .param("amount", "1").param("currency", "SYP")).andExpect(status().isNotFound());
        assertThat(as(() -> settlement.outstanding())).containsExactly(syp("12000"));
    }

    /** A placed order, confirmed, being prepared and given to the driver. */
    private Placed readyFor(UUID driverId, PaymentMethod method, int quantity) {
        var placed = place(method, quantity);
        as(() -> {
            orders.changeStatus(placed.orderId(), OrderStatus.CONFIRMED, Actor.system());
            orders.changeStatus(placed.orderId(), OrderStatus.PREPARING, Actor.system());
            return orders.assignDriver(placed.orderId(), driverId, Actor.system());
        });
        return placed;
    }

    private Placed place(PaymentMethod method, int quantity) {
        return (Placed) as(() -> checkout.place(new Request(carts.add(null, scarf, quantity).token(), UUID.randomUUID(),
                "سارة", "0933 111 001", null, Governorate.DAMASCUS, "دمشق", null, "قرب الجامع", null, method, null,
                null)));
    }

    private ResultActions driverPost(Placed placed, String action, String... params) throws Exception {
        var request = post(MAIN + "/driver/orders/" + placed.number() + "/" + action).session(driverSession).with(csrf());
        for (var i = 0; i + 1 < params.length; i += 2) {
            request.param(params[i], params[i + 1]);
        }
        return mvc.perform(request);
    }

    private ResultActions remit(String amount, String currency) throws Exception {
        return mvc.perform(post(MAIN + "/admin/settlement/" + driver + "/remit").session(owner).with(csrf())
                .param("amount", amount).param("currency", currency));
    }

    private Orders.OrderView order(Placed placed) {
        return as(() -> orders.findByNumber(placed.number())).orElseThrow();
    }

    private Level level() {
        return as(() -> inventory.levels(List.of(scarf)).of(scarf).orElseThrow());
    }

    private <T> T as(Supplier<T> action) {
        return TenantContext.callAs(store, action);
    }

    private static Money syp(String amount) {
        return Money.of(new BigDecimal(amount), Currency.SYP);
    }
}
