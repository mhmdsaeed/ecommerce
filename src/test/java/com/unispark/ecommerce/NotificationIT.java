package com.unispark.ecommerce;

import static com.unispark.ecommerce.TestAccounts.MAIN;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;

import com.unispark.ecommerce.MailpitConfig.Mailbox;
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
import com.unispark.ecommerce.notification.NotificationLog;
import com.unispark.ecommerce.notification.NotificationLog.Channel;
import com.unispark.ecommerce.notification.NotificationLog.Entry;
import com.unispark.ecommerce.notification.NotificationLog.Status;
import com.unispark.ecommerce.order.Actor;
import com.unispark.ecommerce.order.Checkout;
import com.unispark.ecommerce.order.Checkout.Placed;
import com.unispark.ecommerce.order.Checkout.Request;
import com.unispark.ecommerce.order.OrderStatus;
import com.unispark.ecommerce.order.OrderStatusChanged;
import com.unispark.ecommerce.order.Orders;
import com.unispark.ecommerce.payment.PaymentMethod;
import com.unispark.ecommerce.payment.PaymentMethods;
import com.unispark.ecommerce.payment.PaymentMethods.MethodDraft;
import com.unispark.ecommerce.shared.Currency;
import com.unispark.ecommerce.shared.TenantContext;

/**
 * M11 done-when: order status changes produce notifications in Mailpit and {@code notification_log} (SPEC 12).
 * JobRunr's server runs here (it is off in other tests), so messages go out the way they do in production.
 */
@SpringBootTest(properties = {
        "jobrunr.background-job-server.enabled=true",
        "jobrunr.background-job-server.poll-interval-in-seconds=5"})
@AutoConfigureMockMvc
@Import({TestcontainersConfig.class, MailpitConfig.class})
@ActiveProfiles("test")
@ExtendWith(OutputCaptureExtension.class)
class NotificationIT {

    private static final Duration JOBS = Duration.ofSeconds(60);
    private static final String SHOPPER = "+963933111001";

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
    NotificationLog log;

    @Autowired
    Mailbox mailbox;

    @Autowired
    ApplicationEventPublisher events;

    @Autowired
    TransactionTemplate transaction;

    String slug;
    UUID store;
    UUID scarf;
    String owner;
    String manager;

    @BeforeEach
    void createStore() {
        slug = TestStores.randomSlug();
        store = TestStores.create(jdbc, slug, "ACTIVE");
        owner = "owner@" + slug + ".test";
        manager = "manager@" + slug + ".test";
        TestAccounts.staff(staffUsers, passwords, store, "هالة", owner, null, StaffRole.OWNER);
        TestAccounts.staff(staffUsers, passwords, store, "سامر", manager, null, StaffRole.MANAGER);
        TestAccounts.staff(staffUsers, passwords, store, "رامي", "staff@" + slug + ".test", null, StaffRole.STAFF);
        TestAccounts.staff(staffUsers, passwords, store, "قديم", "former@" + slug + ".test", null, StaffRole.MANAGER);
        TenantContext.runAs(store, () -> {
            jdbc.update("UPDATE staff_user SET active = false WHERE email = ?", "former@" + slug + ".test");
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

    @AfterEach
    void acceptMailAgain() {
        mailbox.refuseRecipients(false);
    }

    @Test
    void orderStatusChangesReachTheShopperAndTheMerchants(CapturedOutput output) throws Exception {
        var placed = place(PaymentMethod.COD);

        // Owners and managers get an email about the new order; the shopper gets a text (logged: no SMS provider).
        await().atMost(JOBS).untilAsserted(() -> {
            assertThat(mailbox.to(owner)).hasSize(1);
            assertThat(mailbox.to(manager)).hasSize(1);
        });
        var mail = mailbox.to(owner).getFirst();
        assertThat(mail.subject()).isEqualTo("متجر " + slug + ": طلب جديد #" + placed.number() + " بقيمة 22,000 ل.س");
        assertThat(mail.from()).isEqualTo("orders@lvh.me");
        assertThat(mail.html()).contains("وشاح", "× 2", "22,000 ل.س", "سارة", SHOPPER, "دمشق، دمشق، قرب الجامع",
                "الدفع عند الاستلام", "http://lvh.me:8080/admin/orders/" + placed.number(), "dir=\"rtl\"");
        assertThat(mail.text()).contains("http://lvh.me:8080/admin/orders/" + placed.number());
        assertThat(mailbox.to("staff@" + slug + ".test")).isEmpty();
        assertThat(mailbox.to("former@" + slug + ".test")).isEmpty();
        await().atMost(JOBS).untilAsserted(() -> assertThat(entries(placed)).hasSize(3));
        assertThat(entries(placed)).extracting(Entry::template, Entry::channel, Entry::recipient, Entry::status)
                .containsExactlyInAnyOrder(
                        tuple("order_placed", Channel.LOG, SHOPPER, Status.SENT),
                        tuple("new_order", Channel.EMAIL, owner, Status.SENT),
                        tuple("new_order", Channel.EMAIL, manager, Status.SENT));
        assertThat(output.getOut()).contains("Message to " + SHOPPER + ": متجر " + slug + ": استلمنا طلبك رقم #"
                + placed.number() + " بقيمة 22,000 ل.س.");

        // Each status the shopper cares about sends one more message; preparing sends none.
        change(placed, OrderStatus.CONFIRMED);
        change(placed, OrderStatus.PREPARING);
        change(placed, OrderStatus.OUT_FOR_DELIVERY);
        change(placed, OrderStatus.DELIVERED);
        await().atMost(JOBS).untilAsserted(() -> assertThat(entries(placed)).hasSize(6));
        assertThat(entries(placed)).filteredOn(entry -> entry.recipient().equals(SHOPPER))
                .extracting(Entry::template)
                .containsExactlyInAnyOrder("order_placed", "order_confirmed", "out_for_delivery", "delivered");
        assertThat(entries(placed)).allSatisfy(entry -> assertThat(entry.status()).isEqualTo(Status.SENT));
        assertThat(output.getOut()).contains("طلبك رقم #" + placed.number() + " في الطريق إليك. المبلغ المطلوب عند الاستلام: 22,000 ل.س.");
        assertThat(mailbox.to(owner)).hasSize(1);

        // The merchant sees what was sent on the order page.
        var session = TestAccounts.logIn(mvc, slug, owner);
        mvc.perform(get(MAIN + "/admin/orders/" + placed.number()).session(session))
                .andExpect(content().string(allOf(containsString("الرسائل"), containsString("تنبيه طلب جديد"),
                        containsString("في الطريق"), containsString("سجل التطبيق (لا يوجد مزوّد)"),
                        containsString(manager))));
    }

    @Test
    void rejectedPaymentProofTellsTheShopperWhy(CapturedOutput output) {
        var placed = place(PaymentMethod.SHAM_CASH);
        as(() -> orders.submitProof(placed.number(), placed.trackingToken(), "TX-1", null));
        as(() -> orders.reviewProof(placed.orderId(), false, "المبلغ غير مطابق", Actor.system()));

        await().atMost(JOBS).untilAsserted(() -> assertThat(entries(placed))
                .anySatisfy(entry -> assertThat(entry.template()).isEqualTo("payment_proof_rejected")));
        await().atMost(JOBS).untilAsserted(() -> assertThat(output.getOut()).contains(
                "لم نتمكن من تأكيد دفع طلبك رقم #" + placed.number() + " (المبلغ غير مطابق)"));
    }

    @Test
    void failedEmailsAreRetriedWithoutRepeatingWhatWasSent() {
        mailbox.refuseRecipients(true);
        var placed = place(PaymentMethod.COD);

        await().atMost(JOBS).untilAsserted(() -> assertThat(entries(placed))
                .filteredOn(entry -> entry.template().equals("new_order"))
                .hasSize(2)
                .allSatisfy(entry -> {
                    assertThat(entry.status()).isEqualTo(Status.FAILED);
                    assertThat(entry.error()).contains("451");
                }));
        assertThat(mailbox.to(owner)).isEmpty();

        mailbox.refuseRecipients(false);
        await().atMost(JOBS).untilAsserted(() -> {
            assertThat(entries(placed)).allSatisfy(entry -> assertThat(entry.status()).isEqualTo(Status.SENT));
            assertThat(mailbox.to(owner)).hasSize(1);
        });
        assertThat(entries(placed)).filteredOn(entry -> entry.template().equals("new_order"))
                .allSatisfy(entry -> assertThat(entry.attempts()).isGreaterThanOrEqualTo(2));
        // The shopper's text went out on the first run; the retry did not send it again.
        assertThat(entries(placed)).filteredOn(entry -> entry.recipient().equals(SHOPPER))
                .singleElement().satisfies(entry -> assertThat(entry.attempts()).isEqualTo(1));
    }

    @Test
    void anEventDeliveredTwiceSendsOnce() {
        var placed = place(PaymentMethod.COD);
        await().atMost(JOBS).untilAsserted(() -> assertThat(entries(placed)).hasSize(3)
                .allSatisfy(entry -> assertThat(entry.status()).isEqualTo(Status.SENT)));

        // As after a restart, when Spring Modulith republishes an event whose listener had not finished.
        transaction.executeWithoutResult(status -> events.publishEvent(new OrderStatusChanged(store, placed.orderId(),
                placed.number(), null, OrderStatus.PENDING_CONFIRMATION, "system", Instant.now())));
        change(placed, OrderStatus.CONFIRMED);

        await().atMost(JOBS).untilAsserted(() -> assertThat(entries(placed)).hasSize(4));
        assertThat(entries(placed)).allSatisfy(entry -> assertThat(entry.attempts()).isEqualTo(1));
        assertThat(mailbox.to(owner)).hasSize(1);
    }

    @Test
    void anotherStoreSeesNoneOfTheMessages() {
        var placed = place(PaymentMethod.COD);
        await().atMost(JOBS).untilAsserted(() -> assertThat(entries(placed)).hasSize(3));

        var other = TestStores.create(jdbc, TestStores.randomSlug(), "ACTIVE");
        assertThat(TenantContext.callAs(other, () -> log.forOrder(placed.orderId()))).isEmpty();
    }

    private Placed place(PaymentMethod method) {
        return (Placed) as(() -> checkout.place(new Request(carts.add(null, scarf, 2).token(), UUID.randomUUID(),
                "سارة", "0933 111 001", null, Governorate.DAMASCUS, "دمشق", null, "قرب الجامع", null, method, null,
                null)));
    }

    private void change(Placed placed, OrderStatus target) {
        as(() -> orders.changeStatus(placed.orderId(), target, Actor.system()));
    }

    private List<Entry> entries(Placed placed) {
        return as(() -> log.forOrder(placed.orderId()));
    }

    private <T> T as(Supplier<T> action) {
        return TenantContext.callAs(store, action);
    }
}
