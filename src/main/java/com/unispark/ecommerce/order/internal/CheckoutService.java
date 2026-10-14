package com.unispark.ecommerce.order.internal;

import java.math.BigDecimal;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.unispark.ecommerce.cart.Carts;
import com.unispark.ecommerce.catalog.StorefrontCatalog;
import com.unispark.ecommerce.catalog.StorefrontCatalog.Offer;
import com.unispark.ecommerce.customer.Customers;
import com.unispark.ecommerce.customer.Customers.AddressDraft;
import com.unispark.ecommerce.customer.Customers.Registered;
import com.unispark.ecommerce.customer.PhoneVerification;
import com.unispark.ecommerce.delivery.DeliveryQuotes;
import com.unispark.ecommerce.delivery.DeliveryQuotes.BelowMinimum;
import com.unispark.ecommerce.delivery.DeliveryQuotes.Deliverable;
import com.unispark.ecommerce.delivery.DeliveryQuotes.NotDelivered;
import com.unispark.ecommerce.delivery.Destination;
import com.unispark.ecommerce.inventory.Inventory;
import com.unispark.ecommerce.inventory.Inventory.Insufficient;
import com.unispark.ecommerce.order.Actor;
import com.unispark.ecommerce.order.Checkout;
import com.unispark.ecommerce.order.OrderStatus;
import com.unispark.ecommerce.order.Orders.Address;
import com.unispark.ecommerce.order.Orders.Option;
import com.unispark.ecommerce.order.internal.persistence.OrderItem;
import com.unispark.ecommerce.order.internal.persistence.OrderItem.ProductSnapshot;
import com.unispark.ecommerce.order.internal.persistence.ShopOrder;
import com.unispark.ecommerce.order.internal.persistence.ShopOrderRepository;
import com.unispark.ecommerce.payment.PaymentMethods;
import com.unispark.ecommerce.payment.Payments;
import com.unispark.ecommerce.pricing.Prices;
import com.unispark.ecommerce.shared.Amounts;
import com.unispark.ecommerce.shared.FieldProblem;
import com.unispark.ecommerce.shared.Money;
import com.unispark.ecommerce.shared.PhoneNumbers;
import com.unispark.ecommerce.shared.SecretTokens;
import com.unispark.ecommerce.shared.TenantContext;
import com.unispark.ecommerce.tenant.StoreSettings;

/**
 * Places orders in one transaction, in this order: form checks; the idempotency key (a repeated submit returns its
 * order); phone verification; the cart, locked; prices in the display currency; the delivery quote; the expected
 * total; the payment method; the customer's standing; stock, reserved all or nothing; then the order, its first
 * event, and the cart is cleared. Any refusal leaves nothing behind: stock reservation undoes itself, and nothing
 * is written before it.
 *
 * <p>Two transaction-scoped advisory locks serialize what row locks cannot: submits with one idempotency key (so a
 * double click gives one order), and orders of one phone (so a new customer cannot pass the open-order limit with
 * two tabs).
 */
@Service
@Transactional
class CheckoutService implements Checkout {

    static final String TRACKING = "order-tracking";
    static final int FIRST_NUMBER = 1001;
    static final List<OrderStatus> OPEN = Arrays.stream(OrderStatus.values()).filter(OrderStatus::isOpen).toList();

    private final Carts carts;
    private final StorefrontCatalog catalog;
    private final Prices prices;
    private final DeliveryQuotes quotes;
    private final Customers customers;
    private final PhoneVerification verification;
    private final PaymentMethods methods;
    private final Payments payments;
    private final Inventory inventory;
    private final StoreSettings settings;
    private final ShopOrderRepository orders;
    private final OrderLog log;
    private final JdbcClient jdbc;
    private final Clock clock;

    CheckoutService(Carts carts, StorefrontCatalog catalog, Prices prices, DeliveryQuotes quotes, Customers customers,
            PhoneVerification verification, PaymentMethods methods, Payments payments, Inventory inventory,
            StoreSettings settings, ShopOrderRepository orders, OrderLog log, JdbcClient jdbc, Clock clock) {
        this.carts = carts;
        this.catalog = catalog;
        this.prices = prices;
        this.quotes = quotes;
        this.customers = customers;
        this.verification = verification;
        this.methods = methods;
        this.payments = payments;
        this.inventory = inventory;
        this.settings = settings;
        this.orders = orders;
        this.log = log;
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Override
    @Transactional(readOnly = true)
    public Preview preview(String cartToken, Destination destination) {
        var cart = carts.view(cartToken);
        var display = settings.current().displayCurrency();
        var subtotal = cart.totals().size() == 1 && cart.totals().getFirst().currency() == display
                ? cart.totals().getFirst() : null;
        var quote = destination == null || cart.isEmpty() ? null : quotes.quote(destination, subtotal);
        Money total = null;
        if (quote instanceof Deliverable deliverable && deliverable.fee().sameCurrency(subtotal)) {
            total = subtotal.plus(deliverable.fee());
        }
        return new Preview(cart, subtotal, quote, total);
    }

    @Override
    public PlaceResult place(Request request) {
        var problems = checkForm(request);
        if (!problems.isEmpty()) {
            return Rejected.fields(problems);
        }
        var phone = PhoneNumbers.normalize(request.phone()).orElseThrow();
        lock("order:" + request.idempotencyKey());
        var existing = orders.findByIdempotencyKey(request.idempotencyKey());
        if (existing.isPresent()) {
            return placed(existing.get(), true);
        }
        var store = settings.current();
        if (store.otpRequired() && !verification.isVerified(phone, request.phoneVerification())) {
            return Rejected.because(Reason.PHONE_NOT_VERIFIED);
        }

        var contents = carts.lockContents(request.cartToken());
        if (contents.isEmpty()) {
            return Rejected.because(Reason.CART_EMPTY);
        }
        var offers = catalog.offers(contents.keySet());
        if (!offers.keySet().containsAll(contents.keySet())) {
            return Rejected.because(Reason.CART_PROBLEMS);
        }
        var display = store.displayCurrency();
        BigDecimal rate = null;
        var subtotal = Money.zero(display);
        var items = new ArrayList<OrderItem>();
        for (var line : contents.entrySet()) {
            var offer = offers.get(line.getKey());
            var price = prices.display(offer.price());
            if (price.amount().currency() != display) {
                return Rejected.because(Reason.PRICES_UNAVAILABLE);
            }
            rate = price.converted() ? price.rate() : rate;
            var item = new OrderItem(offer.variantId(), snapshot(offer), price.amount().amount(), line.getValue());
            subtotal = subtotal.plus(Money.of(item.getLineTotal(), display));
            items.add(item);
        }

        var destination = Destination.of(request.governorate(), request.city(), request.area());
        Deliverable delivery;
        switch (quotes.quote(destination, subtotal)) {
            case NotDelivered notDelivered -> {
                return Rejected.because(Reason.NOT_DELIVERED);
            }
            case BelowMinimum below -> {
                return Rejected.because(Reason.BELOW_MINIMUM, below.minimum());
            }
            case Deliverable deliverable -> delivery = deliverable;
        }
        if (!delivery.fee().sameCurrency(subtotal)) {
            return Rejected.because(Reason.PRICES_UNAVAILABLE);
        }
        var total = subtotal.plus(delivery.fee());
        if (request.expectedTotal() != null && !request.expectedTotal().isBlank()
                && Amounts.parse(request.expectedTotal()).map(expected -> expected.compareTo(total.amount()) != 0)
                .orElse(true)) {
            return Rejected.because(Reason.TOTAL_CHANGED, total);
        }
        if (methods.enabled(request.paymentMethod()).isEmpty()) {
            return Rejected.because(Reason.METHOD_UNAVAILABLE);
        }

        lock("customer:" + phone);
        var known = customers.findByPhone(phone);
        if (known.isPresent() && known.get().blocked()) {
            return Rejected.because(Reason.BLOCKED);
        }
        if (known.isEmpty() || known.get().isNew()) {
            var cap = store.newCustomerMaxTotal();
            if (cap != null && total.amount().compareTo(cap) > 0) {
                return Rejected.because(Reason.NEW_CUSTOMER_TOTAL, Money.of(cap, display));
            }
            if (known.isPresent() && orders.countByCustomerIdAndStatusIn(known.get().id(), OPEN)
                    >= MAX_OPEN_ORDERS_FOR_NEW_CUSTOMERS) {
                return Rejected.because(Reason.NEW_CUSTOMER_OPEN_ORDERS);
            }
        }
        if (inventory.reserve(contents) instanceof Insufficient) {
            return Rejected.because(Reason.OUT_OF_STOCK);
        }

        var name = Destination.clean(request.name());
        var customer = ((Registered) customers.register(phone, name)).customer();
        var address = new Address(destination.governorate(), destination.city(), destination.area(),
                Destination.clean(request.landmark()), Destination.clean(request.details()));
        remember(customer.id(), address);
        var token = SecretTokens.derive(TRACKING, request.idempotencyKey().toString());
        var order = new ShopOrder(nextNumber(), SecretTokens.hash(token), request.idempotencyKey(), customer.id(),
                name, phone, request.paymentMethod(), payments.initialStatus(request.paymentMethod()), display, rate,
                subtotal.amount(), delivery.fee().amount(), address, notes(request.notes()), delivery.zone().id(),
                clock.instant());
        items.forEach(order::addItem);
        orders.save(order);
        log.statusChanged(order, OrderLog.PLACED, null, OrderStatus.PENDING_CONFIRMATION, Actor.customer(customer.id()),
                Map.of("total", total.amount().toPlainString(), "currency", display.name(),
                        "paymentMethod", request.paymentMethod().name()));
        carts.clear(request.cartToken());
        return new Placed(order.getId(), order.getNumber(), token, false);
    }

    private static List<FieldProblem> checkForm(Request request) {
        var problems = new ArrayList<FieldProblem>();
        if (request.idempotencyKey() == null) {
            problems.add(new FieldProblem("idempotencyKey", "required"));
        }
        var name = Destination.clean(request.name());
        if (name == null) {
            problems.add(new FieldProblem("name", "required"));
        } else if (name.length() > MAX_NAME_LENGTH) {
            problems.add(new FieldProblem("name", "too-long"));
        }
        if (Destination.clean(request.phone()) == null) {
            problems.add(new FieldProblem("phone", "required"));
        } else if (PhoneNumbers.normalize(request.phone()).isEmpty()) {
            problems.add(new FieldProblem("phone", "invalid"));
        }
        if (request.governorate() == null) {
            problems.add(new FieldProblem("governorate", "required"));
        }
        var city = Destination.clean(request.city());
        if (city == null || Destination.key(city) == null) {
            problems.add(new FieldProblem("city", "required"));
        }
        tooLong(request.city(), "city", Customers.MAX_PLACE_LENGTH, problems);
        tooLong(request.area(), "area", Customers.MAX_PLACE_LENGTH, problems);
        tooLong(request.landmark(), "landmark", Customers.MAX_LANDMARK_LENGTH, problems);
        tooLong(request.details(), "details", Customers.MAX_DETAILS_LENGTH, problems);
        if (Destination.clean(request.landmark()) == null && Destination.clean(request.details()) == null) {
            problems.add(new FieldProblem("landmark", "required"));
        }
        if (request.paymentMethod() == null) {
            problems.add(new FieldProblem("paymentMethod", "required"));
        }
        if (request.notes() != null && request.notes().strip().length() > MAX_NOTES_LENGTH) {
            problems.add(new FieldProblem("notes", "too-long"));
        }
        return problems;
    }

    private static void tooLong(String input, String field, int maxLength, List<FieldProblem> problems) {
        var value = Destination.clean(input);
        if (value != null && value.length() > maxLength) {
            problems.add(new FieldProblem(field, "too-long"));
        }
    }

    private Placed placed(ShopOrder order, boolean repeated) {
        return new Placed(order.getId(), order.getNumber(),
                SecretTokens.derive(TRACKING, order.getIdempotencyKey().toString()), repeated);
    }

    /** Waits for other transactions holding the same key; released when this transaction ends. */
    private void lock(String key) {
        jdbc.sql("SELECT 1 FROM pg_advisory_xact_lock(hashtextextended(:key, 0))")
                .param("key", TenantContext.requireTenantId() + ":" + key)
                .query((row, index) -> 1)
                .list();
    }

    /** 1001, 1002, ... per store. The upsert's row lock orders concurrent orders of one store. */
    private int nextNumber() {
        return jdbc.sql("""
                        INSERT INTO order_counter (tenant_id, last_number) VALUES (:tenant, :first)
                        ON CONFLICT (tenant_id) DO UPDATE SET last_number = order_counter.last_number + 1
                        RETURNING last_number
                        """)
                .param("tenant", TenantContext.requireTenantId()).param("first", FIRST_NUMBER)
                .query(Integer.class)
                .single();
    }

    /** Keeps the address for the customer's next order, unless it is already saved or the book is full. */
    private void remember(UUID customerId, Address address) {
        var known = customers.addresses(customerId).stream()
                .anyMatch(saved -> saved.governorate() == address.governorate()
                && Objects.equals(saved.city(), address.city()) && Objects.equals(saved.area(), address.area())
                && Objects.equals(saved.landmark(), address.landmark())
                && Objects.equals(saved.details(), address.details()));
        if (!known) {
            customers.addAddress(customerId, new AddressDraft(address.governorate(), address.city(), address.area(),
                    address.landmark(), address.details(), null, null, null));
        }
    }

    private static ProductSnapshot snapshot(Offer offer) {
        return new ProductSnapshot(offer.productName(), offer.sku(),
                offer.choices().stream().map(choice -> new Option(choice.option(), choice.value())).toList());
    }

    private static String notes(String input) {
        return input == null || input.isBlank() ? null : input.strip();
    }
}
