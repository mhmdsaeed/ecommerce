package com.unispark.ecommerce;

import static com.unispark.ecommerce.TestAccounts.MAIN;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;
import java.util.function.Supplier;

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
import com.unispark.ecommerce.order.Actor;
import com.unispark.ecommerce.order.Checkout;
import com.unispark.ecommerce.order.Checkout.Placed;
import com.unispark.ecommerce.order.Checkout.Request;
import com.unispark.ecommerce.order.OrderStatus;
import com.unispark.ecommerce.order.Orders;
import com.unispark.ecommerce.payment.PaymentMethod;
import com.unispark.ecommerce.report.ReportPeriod;
import com.unispark.ecommerce.report.StoreReports;
import com.unispark.ecommerce.shared.Currency;
import com.unispark.ecommerce.shared.Money;
import com.unispark.ecommerce.shared.TenantContext;

/**
 * Store reports over real orders: two customers, a cancelled order with its reason, one delivered by a driver who
 * collected the cash. The pages, the CSV files, who may see them, and that a store sees only its own orders.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfig.class)
@ActiveProfiles("test")
class ReportsIT {

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
    Carts carts;

    @Autowired
    Checkout checkout;

    @Autowired
    Orders orders;

    @Autowired
    StoreReports reports;

    String slug;
    UUID store;
    UUID scarf;
    UUID driver;
    MockHttpSession owner;
    ReportPeriod today;

    @BeforeEach
    void placeOrders() throws Exception {
        slug = TestStores.randomSlug();
        store = TestStores.create(jdbc, slug, "ACTIVE");
        driver = TestAccounts.staff(staffUsers, passwords, store, "أبو سمير", null, "+963933700001", StaffRole.DRIVER);
        TestAccounts.staff(staffUsers, passwords, store, "هالة", "owner@" + slug + ".test", null, StaffRole.OWNER);
        TestAccounts.staff(staffUsers, passwords, store, "رامي", "staff@" + slug + ".test", null, StaffRole.STAFF);
        owner = TestAccounts.logIn(mvc, slug, "owner@" + slug + ".test");
        TenantContext.runAs(store, () -> {
            scarf = published("وشاح", "scarf", "10000");
            var mug = published("كوب", "mug", "3000");
            inventory.setOnHand(mug, 0);
            zones.create(new ZoneDraft(Governorate.DAMASCUS, "دمشق", null, "2000", Currency.SYP, null, null, null,
                    null));
        });
        // Sara buys twice (2 scarves, then 1), Omar once; Omar's order is cancelled with a reason.
        var first = place("سارة", "0933 111 001", 2);
        place("سارة", "0933 111 001", 1);
        var omar = place("عمر", "0933 111 002", 1);
        as(() -> orders.changeStatus(omar.orderId(), OrderStatus.CANCELLED, Actor.system(), "غيّر رأيه"));
        // Sara's first order goes out with the driver, who delivers it and collects the cash.
        as(() -> {
            orders.changeStatus(first.orderId(), OrderStatus.CONFIRMED, Actor.system());
            orders.changeStatus(first.orderId(), OrderStatus.PREPARING, Actor.system());
            return orders.assignDriver(first.orderId(), driver, Actor.system());
        });
        var driverSession = TestAccounts.logIn(mvc, slug, "0933700001");
        mvc.perform(post(MAIN + "/driver/orders/" + first.number() + "/start").session(driverSession).with(csrf()));
        mvc.perform(post(MAIN + "/driver/orders/" + first.number() + "/deliver").session(driverSession).with(csrf())
                .param("collected", "22000"));
        today = ReportPeriod.of("today", null, null, LocalDate.now(ZoneId.of("Asia/Damascus")));
    }

    @Test
    void reportsSalesWithoutTheCancelledOrder() {
        var sales = as(() -> reports.sales(today));

        // 2 x 10,000 + 2,000 delivery, and 10,000 + 2,000; Omar's cancelled order does not count.
        assertThat(sales.totals()).singleElement().satisfies(total -> {
            assertThat(total.orders()).isEqualTo(2);
            assertThat(total.revenue()).isEqualTo(syp("34000"));
            assertThat(total.average()).isEqualTo(syp("17000"));
        });
        assertThat(sales.previous()).isEmpty();
        assertThat(sales.points()).singleElement().satisfies(point -> assertThat(point.orders()).isEqualTo(2));
    }

    @Test
    void reportsProductsOrdersAndCustomers() {
        var products = as(() -> reports.products(today));
        assertThat(products.bestSellers()).singleElement().satisfies(line -> {
            assertThat(line.name().ar()).isEqualTo("وشاح");
            assertThat(line.quantity()).isEqualTo(3);
        });
        assertThat(products.idle()).extracting(idle -> idle.name().ar()).containsExactly("كوب");
        assertThat(products.lowStock()).singleElement().satisfies(line -> assertThat(line.available()).isZero());

        var delivery = as(() -> reports.ordersAndDelivery(today));
        assertThat(delivery.placed()).isEqualTo(3);
        assertThat(delivery.byStatus()).containsEntry("CANCELLED", 1L).containsEntry("DELIVERED", 1L);
        assertThat(delivery.lost()).singleElement().satisfies(lost -> {
            assertThat(lost.customerName()).isEqualTo("عمر");
            assertThat(lost.reason()).isEqualTo("غيّر رأيه");
        });
        assertThat(delivery.deliveryTime().delivered()).isEqualTo(1);
        assertThat(delivery.zones()).singleElement().satisfies(zone -> {
            assertThat(zone.city()).isEqualTo("دمشق");
            assertThat(zone.fees()).isEqualTo(syp("2000"));
        });
        assertThat(delivery.drivers()).singleElement().satisfies(line -> {
            assertThat(line.name()).isEqualTo("أبو سمير");
            assertThat(line.delivered()).isEqualTo(1);
            assertThat(line.cash()).containsExactly(syp("22000"));
        });

        var customers = as(() -> reports.customersAndPayments(today));
        assertThat(customers.newCustomers()).isEqualTo(1);
        assertThat(customers.returning()).isZero();
        assertThat(customers.top()).singleElement().satisfies(top -> {
            assertThat(top.name()).isEqualTo("سارة");
            assertThat(top.orders()).isEqualTo(2);
        });
        assertThat(customers.methods()).singleElement().satisfies(method -> assertThat(method.method()).isEqualTo("COD"));
        assertThat(customers.cashWithDrivers()).containsExactly(syp("22000"));
    }

    @Test
    void ownersSeeTheReportPages() throws Exception {
        mvc.perform(get(MAIN + "/admin/reports").session(owner)).andExpect(redirectedUrl("/admin/reports/sales"));
        mvc.perform(get(MAIN + "/admin/reports/sales").session(owner).param("period", "today"))
                .andExpect(status().isOk())
                .andExpect(content().string(allOf(containsString("34,000"),
                        matchesPattern("(?s).*<a href=\"/admin/reports/sales\\?period=today\"[^>]*aria-current=\"page\".*"),
                        containsString("class=\"report-chart__column\""), containsString("لا مبيعات في اليوم السابق للمقارنة"))));
        mvc.perform(get(MAIN + "/admin/reports/products").session(owner).param("period", "today"))
                .andExpect(content().string(allOf(containsString("وشاح"), containsString("كوب"),
                        containsString("نفد"))));
        mvc.perform(get(MAIN + "/admin/reports/orders").session(owner).param("period", "today"))
                .andExpect(content().string(allOf(containsString("غيّر رأيه"), containsString("أبو سمير"),
                        containsString("report-bars__bar--lost"))));
        mvc.perform(get(MAIN + "/admin/reports/customers").session(owner).param("period", "today"))
                .andExpect(content().string(allOf(containsString("سارة"), containsString("الدفع عند الاستلام"))));
        // A custom period keeps itself in the links between reports.
        mvc.perform(get(MAIN + "/admin/reports/sales").session(owner).param("period", "custom")
                        .param("from", "2026-01-01").param("to", "2026-01-31"))
                .andExpect(content().string(allOf(containsString("لا مبيعات في هذه الفترة."),
                        containsString("href=\"/admin/reports/orders?period=custom&amp;from=2026-01-01&amp;to=2026-01-31\""))));
    }

    @Test
    void downloadsTablesAsCsv() throws Exception {
        var result = mvc.perform(get(MAIN + "/admin/reports/orders.csv").session(owner)
                        .param("table", "lost").param("period", "today"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", containsString("text/csv")))
                .andExpect(header().string("Content-Disposition", containsString("attachment")))
                .andReturn();
        var bytes = result.getResponse().getContentAsByteArray();
        assertThat(bytes).startsWith((byte) 0xEF, (byte) 0xBB, (byte) 0xBF);
        var text = new String(bytes, 3, bytes.length - 3, StandardCharsets.UTF_8);
        assertThat(text).startsWith("الطلب,الحالة,تاريخ الطلب,الزبون,السبب\r\n").contains("ملغى").contains("غيّر رأيه");

        mvc.perform(get(MAIN + "/admin/reports/orders.csv").session(owner).param("table", "nothing"))
                .andExpect(status().isNotFound());
    }

    @Test
    void staffAndOtherStoresSeeNoneOfIt() throws Exception {
        var staff = TestAccounts.logIn(mvc, slug, "staff@" + slug + ".test");
        mvc.perform(get(MAIN + "/admin/reports/sales").session(staff)).andExpect(status().isForbidden());
        mvc.perform(get(MAIN + "/admin/reports/sales.csv").session(staff)).andExpect(status().isForbidden());
        mvc.perform(get(MAIN + "/admin").session(staff))
                .andExpect(content().string(not(containsString("href=\"/admin/reports\""))));

        var otherSlug = TestStores.randomSlug();
        var other = TestStores.create(jdbc, otherSlug, "ACTIVE");
        assertThat(TenantContext.callAs(other, () -> reports.sales(today)).totals()).isEmpty();
        assertThat(TenantContext.callAs(other, () -> reports.ordersAndDelivery(today)).placed()).isZero();
    }

    private UUID published(String name, String slug, String price) {
        var id = ((Saved) products.create(new ProductDraft(name, null, null, null, slug, null, Currency.SYP), price))
                .id();
        products.changeStatus(id, ProductStatus.ACTIVE);
        return products.find(id).orElseThrow().variants().getFirst().id();
    }

    private Placed place(String name, String phone, int quantity) {
        return (Placed) as(() -> checkout.place(new Request(carts.add(null, scarf, quantity).token(), UUID.randomUUID(),
                name, phone, null, Governorate.DAMASCUS, "دمشق", null, "قرب الجامع", null, PaymentMethod.COD, null,
                null)));
    }

    private static Money syp(String amount) {
        return Money.of(new BigDecimal(amount), Currency.SYP);
    }

    private <T> T as(Supplier<T> action) {
        return TenantContext.callAs(store, action);
    }
}
