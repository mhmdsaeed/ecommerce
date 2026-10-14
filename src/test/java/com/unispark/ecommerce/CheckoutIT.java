package com.unispark.ecommerce;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Supplier;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import com.unispark.ecommerce.cart.Carts;
import com.unispark.ecommerce.catalog.ProductStatus;
import com.unispark.ecommerce.catalog.Products;
import com.unispark.ecommerce.catalog.Products.ProductDraft;
import com.unispark.ecommerce.catalog.Products.Saved;
import com.unispark.ecommerce.customer.Customers;
import com.unispark.ecommerce.delivery.DeliveryZones;
import com.unispark.ecommerce.delivery.DeliveryZones.ZoneDraft;
import com.unispark.ecommerce.delivery.Governorate;
import com.unispark.ecommerce.inventory.Inventory;
import com.unispark.ecommerce.inventory.StockLevels.Level;
import com.unispark.ecommerce.order.Actor;
import com.unispark.ecommerce.order.Checkout;
import com.unispark.ecommerce.order.Checkout.Placed;
import com.unispark.ecommerce.order.Checkout.PlaceResult;
import com.unispark.ecommerce.order.Checkout.Reason;
import com.unispark.ecommerce.order.Checkout.Refusal;
import com.unispark.ecommerce.order.Checkout.Rejected;
import com.unispark.ecommerce.order.Checkout.Request;
import com.unispark.ecommerce.order.IllegalOrderTransitionException;
import com.unispark.ecommerce.order.OrderStatus;
import com.unispark.ecommerce.order.Orders;
import com.unispark.ecommerce.order.Orders.InvalidReference;
import com.unispark.ecommerce.order.Orders.OrderNotFound;
import com.unispark.ecommerce.order.Orders.ProofAccepted;
import com.unispark.ecommerce.order.Orders.ProofNotExpected;
import com.unispark.ecommerce.payment.PaymentMethod;
import com.unispark.ecommerce.payment.PaymentMethods;
import com.unispark.ecommerce.payment.PaymentMethods.MethodDraft;
import com.unispark.ecommerce.payment.PaymentStatus;
import com.unispark.ecommerce.pricing.ExchangeRates;
import com.unispark.ecommerce.shared.Currency;
import com.unispark.ecommerce.shared.FieldProblem;
import com.unispark.ecommerce.shared.Money;
import com.unispark.ecommerce.shared.TenantContext;
import com.unispark.ecommerce.tenant.StoreSettings;

/** M8: placing orders (SPEC 6.4), the order state machine with stock (SPEC 6.1), and payment proofs (SPEC 6.2). */
@SpringBootTest
@Import({TestcontainersConfig.class, IsolationProbeWeb.class})
@ActiveProfiles("test")
class CheckoutIT {

    @Autowired
    Checkout checkout;

    @Autowired
    Orders orders;

    @Autowired
    Carts carts;

    @Autowired
    Products products;

    @Autowired
    Inventory inventory;

    @Autowired
    DeliveryZones zones;

    @Autowired
    Customers customers;

    @Autowired
    PaymentMethods methods;

    @Autowired
    ExchangeRates rates;

    @Autowired
    StoreSettings settings;

    @Autowired
    JdbcTemplate jdbc;

    UUID store;
    UUID scarf;
    UUID mug;

    @BeforeEach
    void createStore() {
        store = TestStores.create(jdbc, TestStores.randomSlug(), "ACTIVE");
        as(() -> {
            scarf = published(new ProductDraft("وشاح", "Scarf", null, null, "scarf", null, Currency.SYP), "10000");
            mug = published(new ProductDraft("كوب", "Mug", null, null, "mug", null, Currency.USD), "4.50");
            inventory.setOnHand(scarf, 5);
            zones.create(new ZoneDraft(Governorate.DAMASCUS, "دمشق", null, "2000", Currency.SYP, null, "50000",
                    null, null));
            zones.create(new ZoneDraft(Governorate.RIF_DIMASHQ, null, null, "3500", Currency.SYP, "15000", null,
                    null, null));
            return null;
        });
    }

    @Test
    void placesACashOnDeliveryOrder() {
        var cart = cartWith(scarf, 2);

        var placed = (Placed) place(request(cart, "0933 111 222"));

        assertThat(placed.number()).isEqualTo(1001);
        assertThat(placed.repeated()).isFalse();
        var order = as(() -> orders.track(placed.number(), placed.trackingToken())).orElseThrow();
        assertThat(order.status()).isEqualTo(OrderStatus.PENDING_CONFIRMATION);
        assertThat(order.paymentMethod()).isEqualTo(PaymentMethod.COD);
        assertThat(order.paymentStatus()).isEqualTo(PaymentStatus.UNPAID);
        assertThat(order.subtotal()).isEqualTo(syp("20000"));
        assertThat(order.deliveryFee()).isEqualTo(syp("2000"));
        assertThat(order.total()).isEqualTo(syp("22000"));
        assertThat(order.rateSnapshot()).isNull();
        assertThat(order.customerName()).isEqualTo("سارة");
        assertThat(order.customerPhone()).isEqualTo("+963933111222");
        assertThat(order.address().city()).isEqualTo("دمشق");
        assertThat(order.address().landmark()).isEqualTo("قرب الجامع");
        assertThat(order.items()).singleElement().satisfies(item -> {
            assertThat(item.name().ar()).isEqualTo("وشاح");
            assertThat(item.quantity()).isEqualTo(2);
            assertThat(item.unitPrice()).isEqualTo(syp("10000"));
            assertThat(item.lineTotal()).isEqualTo(syp("20000"));
        });
        assertThat(order.events()).singleElement().satisfies(event -> {
            assertThat(event.type()).isEqualTo("PLACED");
            assertThat(event.to()).isEqualTo(OrderStatus.PENDING_CONFIRMATION);
            assertThat(event.actor()).isEqualTo("customer:" + order.customerId());
        });
        // Stock is reserved, the cart is gone, the customer and their address are kept.
        assertThat(level(scarf)).isEqualTo(new Level(scarf, 5, 2));
        assertThat(as(() -> carts.view(cart)).isEmpty()).isTrue();
        assertThat(as(() -> customers.addresses(order.customerId()))).singleElement()
                .satisfies(address -> assertThat(address.landmark()).isEqualTo("قرب الجامع"));
        // The next order gets the next number; the token only opens its own order.
        var second = (Placed) place(request(cartWith(scarf, 1), "0944 000 111"));
        assertThat(second.number()).isEqualTo(1002);
        assertThat(as(() -> orders.track(1001, second.trackingToken()))).isEmpty();
    }

    @Test
    void snapshotsConvertedPricesAndTheRate() {
        as(() -> rates.set("118.5", null));

        var placed = (Placed) place(request(cartWith(mug, 2), "0933 111 223"));

        var order = as(() -> orders.findByNumber(placed.number())).orElseThrow();
        // $4.50 at 118.5 rounds to 535 pounds: 1,070 + 2,000 delivery.
        assertThat(order.items().getFirst().unitPrice()).isEqualTo(syp("535"));
        assertThat(order.total()).isEqualTo(syp("3070"));
        assertThat(order.rateSnapshot()).isEqualByComparingTo("118.5");
        // A new rate later changes nothing in the order.
        as(() -> rates.set("150", null));
        assertThat(as(() -> orders.findByNumber(placed.number())).orElseThrow().total()).isEqualTo(syp("3070"));
    }

    @Test
    void aRepeatedSubmitReturnsTheFirstOrder() throws Exception {
        var cart = cartWith(scarf, 1);
        var key = UUID.randomUUID();

        var results = concurrently(6, () -> place(request(cart, "0933 111 224", key)));

        var placed = results.stream().map(Placed.class::cast).toList();
        assertThat(placed).extracting(Placed::number).containsOnly(placed.getFirst().number());
        assertThat(placed).extracting(Placed::trackingToken).containsOnly(placed.getFirst().trackingToken());
        assertThat(placed).filteredOn(result -> !result.repeated()).hasSize(1);
        assertThat(count("shop_order")).isEqualTo(1);
        assertThat(level(scarf).reserved()).isEqualTo(1);
    }

    @Test
    void twoShoppersRaceForTheLastUnits() throws Exception {
        as(() -> inventory.setOnHand(scarf, 1));
        var first = cartWith(scarf, 1);
        var second = cartWith(scarf, 1);

        var results = concurrently(List.of(
                () -> place(request(first, "0933 111 225")), () -> place(request(second, "0933 111 226"))));

        assertThat(results).filteredOn(Placed.class::isInstance).hasSize(1);
        assertThat(results).filteredOn(Rejected.class::isInstance).singleElement()
                .isEqualTo(Rejected.because(Reason.OUT_OF_STOCK));
        assertThat(level(scarf)).isEqualTo(new Level(scarf, 1, 1));
    }

    @Test
    void explainsInvalidForms() {
        var result = place(new Request(cartWith(scarf, 1), null, " ", "123", null, null, "  ", null, null, " ", null,
                "x".repeat(501), null));

        assertThat(result).isEqualTo(Rejected.fields(List.of(new FieldProblem("idempotencyKey", "required"),
                new FieldProblem("name", "required"), new FieldProblem("phone", "invalid"),
                new FieldProblem("governorate", "required"), new FieldProblem("city", "required"),
                new FieldProblem("landmark", "required"), new FieldProblem("paymentMethod", "required"),
                new FieldProblem("notes", "too-long"))));
    }

    @Test
    void refusesWhatCannotBeDelivered() {
        assertThat(place(request(null, "0933 111 227"))).isEqualTo(Rejected.because(Reason.CART_EMPTY));
        assertThat(place(request(cartWith(scarf, 1), "0933 111 227", Governorate.HOMS, "حمص")))
                .isEqualTo(Rejected.because(Reason.NOT_DELIVERED));
        assertThat(place(request(cartWith(scarf, 1), "0933 111 227", Governorate.RIF_DIMASHQ, "جرمانا")))
                .isEqualTo(Rejected.because(Reason.BELOW_MINIMUM, syp("15000")));
        assertThat(count("shop_order")).isZero();
        assertThat(level(scarf).reserved()).isZero();
    }

    @Test
    void refusesWhenTheCartOrStockChanged() {
        var cart = cartWith(scarf, 2);
        as(() -> inventory.setOnHand(scarf, 1));
        assertThat(place(request(cart, "0933 111 228"))).isEqualTo(Rejected.because(Reason.OUT_OF_STOCK));

        var archived = cartWith(mug, 1);
        as(() -> products.changeStatus(productOf(mug), ProductStatus.ARCHIVED));
        assertThat(place(request(archived, "0933 111 228"))).isEqualTo(Rejected.because(Reason.CART_PROBLEMS));
    }

    @Test
    void refusesPricesThatCannotBeConverted() {
        // A dollar product and no exchange rate: the shopper cannot be charged in pounds.
        assertThat(place(request(cartWith(mug, 1), "0933 111 229")))
                .isEqualTo(Rejected.because(Reason.PRICES_UNAVAILABLE));
    }

    @Test
    void refusesAChangedTotal() {
        var cart = cartWith(scarf, 1);
        var base = request(cart, "0933 111 230");

        var changed = place(new Request(base.cartToken(), base.idempotencyKey(), base.name(), base.phone(), null,
                base.governorate(), base.city(), base.area(), base.landmark(), base.details(), base.paymentMethod(),
                null, "11000"));
        assertThat(changed).isEqualTo(Rejected.because(Reason.TOTAL_CHANGED, syp("12000")));

        // Confirming the new total places the order, with the same key.
        var confirmed = place(new Request(base.cartToken(), base.idempotencyKey(), base.name(), base.phone(), null,
                base.governorate(), base.city(), base.area(), base.landmark(), base.details(), base.paymentMethod(),
                null, "12,000"));
        assertThat(confirmed).isInstanceOf(Placed.class);
    }

    @Test
    void refusesDisabledPaymentMethods() {
        var base = request(cartWith(scarf, 1), "0933 111 231");
        var bank = new Request(base.cartToken(), base.idempotencyKey(), base.name(), base.phone(), null,
                base.governorate(), base.city(), base.area(), base.landmark(), base.details(),
                PaymentMethod.BANK_TRANSFER, null, null);

        assertThat(place(bank)).isEqualTo(Rejected.because(Reason.METHOD_UNAVAILABLE));
    }

    @Test
    void refusesBlockedCustomersWithoutSayingWhy() {
        var first = (Placed) place(request(cartWith(scarf, 1), "0933 111 232"));
        var customer = as(() -> orders.findByNumber(first.number())).orElseThrow().customerId();
        as(() -> customers.block(customer, "Refused three deliveries"));

        var refused = (Rejected) place(request(cartWith(scarf, 1), "+963 933 111 232"));

        assertThat(refused.refusal()).isEqualTo(new Refusal(Reason.BLOCKED, null));
        assertThat(refused.problems()).isEmpty();
    }

    @Test
    void limitsNewCustomers() {
        as(() -> settings.updateCheckout(false, new BigDecimal("25000")));
        assertThat(place(request(cartWith(scarf, 3), "0933 111 233")))
                .isEqualTo(Rejected.because(Reason.NEW_CUSTOMER_TOTAL, syp("25000")));

        var first = (Placed) place(request(cartWith(scarf, 1), "0933 111 233"));
        place(request(cartWith(scarf, 1), "0933 111 233"));
        assertThat(place(request(cartWith(scarf, 1), "0933 111 233")))
                .isEqualTo(Rejected.because(Reason.NEW_CUSTOMER_OPEN_ORDERS));

        // Once an order is delivered, the customer is no longer new: no limits.
        deliver(first.orderId());
        assertThat(place(request(cartWith(scarf, 3), "0933 111 233"))).isInstanceOf(Placed.class);
    }

    @Test
    void cancellingReleasesStockAndDeliveringTakesIt() {
        var cancelled = (Placed) place(request(cartWith(scarf, 2), "0933 111 234"));
        var delivered = (Placed) place(request(cartWith(scarf, 1), "0933 111 235"));
        assertThat(level(scarf)).isEqualTo(new Level(scarf, 5, 3));

        as(() -> orders.changeStatus(cancelled.orderId(), OrderStatus.CANCELLED, Actor.system()));
        assertThat(level(scarf)).isEqualTo(new Level(scarf, 5, 1));

        deliver(delivered.orderId());
        assertThat(level(scarf)).isEqualTo(new Level(scarf, 4, 0));
        var order = as(() -> orders.findByNumber(delivered.number())).orElseThrow();
        assertThat(order.status()).isEqualTo(OrderStatus.DELIVERED);
        assertThat(order.events()).extracting(Orders.Event::to).containsExactly(OrderStatus.PENDING_CONFIRMATION,
                OrderStatus.CONFIRMED, OrderStatus.PREPARING, OrderStatus.OUT_FOR_DELIVERY, OrderStatus.DELIVERED);
        assertThat(as(() -> customers.find(order.customerId())).orElseThrow().orderCount()).isEqualTo(1);
    }

    @Test
    void refusedOrdersCountAndReturnTheirStock() {
        var placed = (Placed) place(request(cartWith(scarf, 2), "0933 111 236"));
        var staff = Actor.staff(UUID.randomUUID());
        as(() -> {
            orders.changeStatus(placed.orderId(), OrderStatus.CONFIRMED, staff);
            orders.changeStatus(placed.orderId(), OrderStatus.PREPARING, staff);
            orders.changeStatus(placed.orderId(), OrderStatus.OUT_FOR_DELIVERY, staff);
            orders.changeStatus(placed.orderId(), OrderStatus.REFUSED, staff);
            return orders.changeStatus(placed.orderId(), OrderStatus.RETURNED, staff);
        });

        var order = as(() -> orders.findByNumber(placed.number())).orElseThrow();
        assertThat(order.events()).last().satisfies(event -> assertThat(event.actor()).isEqualTo(staff.toString()));
        assertThat(as(() -> customers.find(order.customerId())).orElseThrow().refusedCount()).isEqualTo(1);
        assertThat(level(scarf)).isEqualTo(new Level(scarf, 5, 0));
    }

    @Test
    void refusesIllegalTransitionsAndChangesNothing() {
        var placed = (Placed) place(request(cartWith(scarf, 1), "0933 111 237"));

        assertThatThrownBy(() -> as(() -> orders.changeStatus(placed.orderId(), OrderStatus.DELIVERED, Actor.system())))
                .isInstanceOf(IllegalOrderTransitionException.class);

        var order = as(() -> orders.findByNumber(placed.number())).orElseThrow();
        assertThat(order.status()).isEqualTo(OrderStatus.PENDING_CONFIRMATION);
        assertThat(order.events()).hasSize(1);
        assertThat(level(scarf).reserved()).isEqualTo(1);
    }

    @Test
    void theEventLogIsAppendOnly() {
        place(request(cartWith(scarf, 1), "0933 111 238"));

        assertThatThrownBy(() -> TenantContext.runAs(store,
                () -> jdbc.update("UPDATE order_event SET actor = 'someone else'"))).hasMessageContaining("append-only");
        assertThatThrownBy(() -> TenantContext.runAs(store, () -> jdbc.update("DELETE FROM order_event")))
                .hasMessageContaining("append-only");
    }

    @Test
    void acceptsOneProofForATransfer() {
        as(() -> methods.save(PaymentMethod.SHAM_CASH, new MethodDraft(true, null, null, "0944 123 456", "Jasmine",
                null, null)));
        var base = request(cartWith(scarf, 1), "0933 111 239");
        var placed = (Placed) place(new Request(base.cartToken(), base.idempotencyKey(), base.name(), base.phone(),
                null, base.governorate(), base.city(), base.area(), base.landmark(), base.details(),
                PaymentMethod.SHAM_CASH, null, null));

        assertThat(as(() -> orders.submitProof(placed.number(), placed.trackingToken(), " "))).isInstanceOf(InvalidReference.class);
        assertThat(as(() -> orders.submitProof(placed.number(), "x".repeat(43), "TX-1"))).isInstanceOf(OrderNotFound.class);
        assertThat(as(() -> orders.submitProof(placed.number(), placed.trackingToken(), " TX-778899 ")))
                .isInstanceOf(ProofAccepted.class);
        assertThat(as(() -> orders.submitProof(placed.number(), placed.trackingToken(), "TX-2")))
                .isInstanceOf(ProofNotExpected.class);

        var order = as(() -> orders.findByNumber(placed.number())).orElseThrow();
        assertThat(order.paymentStatus()).isEqualTo(PaymentStatus.AWAITING_VERIFICATION);
        assertThat(order.proofs()).singleElement()
                .satisfies(proof -> assertThat(proof.transactionReference()).isEqualTo("TX-778899"));
        assertThat(order.events()).extracting(Orders.Event::type).containsExactly("PLACED", "PROOF_SUBMITTED");
    }

    @Test
    void cashOrdersTakeNoProof() {
        var placed = (Placed) place(request(cartWith(scarf, 1), "0933 111 240"));

        assertThat(as(() -> orders.submitProof(placed.number(), placed.trackingToken(), "TX-1")))
                .isInstanceOf(ProofNotExpected.class);
    }

    @Test
    void anotherStoreSeesNoOrders() {
        var placed = (Placed) place(request(cartWith(scarf, 1), "0933 111 241"));
        var other = TestStores.create(jdbc, TestStores.randomSlug(), "ACTIVE");

        TenantContext.runAs(other, () -> {
            assertThat(orders.findByNumber(placed.number())).isEmpty();
            assertThat(orders.track(placed.number(), placed.trackingToken())).isEmpty();
            assertThat(orders.list(0).rows()).isEmpty();
            assertThat(orders.changeStatus(placed.orderId(), OrderStatus.CANCELLED, Actor.system())).isEmpty();
        });
        assertThat(as(() -> orders.findByNumber(placed.number())).orElseThrow().status())
                .isEqualTo(OrderStatus.PENDING_CONFIRMATION);
    }

    private void deliver(UUID orderId) {
        as(() -> {
            for (var status : List.of(OrderStatus.CONFIRMED, OrderStatus.PREPARING, OrderStatus.OUT_FOR_DELIVERY,
                    OrderStatus.DELIVERED)) {
                orders.changeStatus(orderId, status, Actor.system());
            }
            return null;
        });
    }

    private PlaceResult place(Request request) {
        return as(() -> checkout.place(request));
    }

    private static Request request(String cart, String phone) {
        return request(cart, phone, UUID.randomUUID());
    }

    private static Request request(String cart, String phone, UUID key) {
        return new Request(cart, key, "سارة", phone, null, Governorate.DAMASCUS, "دمشق", "المزة", "قرب الجامع", null,
                PaymentMethod.COD, null, null);
    }

    private static Request request(String cart, String phone, Governorate governorate, String city) {
        return new Request(cart, UUID.randomUUID(), "سارة", phone, null, governorate, city, null, "قرب الجامع", null,
                PaymentMethod.COD, null, null);
    }

    private String cartWith(UUID variant, int quantity) {
        return as(() -> carts.add(null, variant, quantity).token());
    }

    private UUID published(ProductDraft draft, String price) {
        var id = ((Saved) products.create(draft, price)).id();
        products.changeStatus(id, ProductStatus.ACTIVE);
        return products.find(id).orElseThrow().variants().getFirst().id();
    }

    private UUID productOf(UUID variant) {
        return as(() -> jdbc.queryForObject("SELECT product_id FROM product_variant WHERE id = ?", UUID.class, variant));
    }

    private Level level(UUID variant) {
        return as(() -> inventory.levels(List.of(variant)).of(variant).orElseThrow());
    }

    private long count(String table) {
        return as(() -> jdbc.queryForObject("SELECT count(*) FROM " + table, Long.class));
    }

    private <T> T as(Supplier<T> action) {
        return TenantContext.callAs(store, action);
    }

    private <T> List<T> concurrently(int times, Callable<T> task) throws Exception {
        var tasks = new ArrayList<Callable<T>>();
        for (var i = 0; i < times; i++) {
            tasks.add(task);
        }
        return concurrently(tasks);
    }

    private <T> List<T> concurrently(List<Callable<T>> tasks) throws Exception {
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(tasks.size())) {
            var futures = new ArrayList<Future<T>>();
            for (var task : tasks) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return task.call();
                }));
            }
            start.countDown();
            var results = new ArrayList<T>();
            for (var future : futures) {
                results.add(future.get());
            }
            return results;
        }
    }

    private static Money syp(String amount) {
        return Money.of(new BigDecimal(amount), Currency.SYP);
    }
}
