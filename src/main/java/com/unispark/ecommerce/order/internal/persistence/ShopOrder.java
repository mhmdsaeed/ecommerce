package com.unispark.ecommerce.order.internal.persistence;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import com.unispark.ecommerce.order.OrderStatus;
import com.unispark.ecommerce.order.Orders.Address;
import com.unispark.ecommerce.payment.PaymentMethod;
import com.unispark.ecommerce.payment.PaymentStatus;
import com.unispark.ecommerce.shared.Currency;
import com.unispark.ecommerce.shared.TenantScopedEntity;

/**
 * An order and its snapshot. Status changes go through the order service, which checks the state machine and writes
 * the event; the setters here do no checking of their own. {@code driver_id} is mapped with delivery (M9, M10).
 */
@Entity
@Table(name = "shop_order")
public class ShopOrder extends TenantScopedEntity {

    @Column(nullable = false, updatable = false)
    private int number;

    @Column(name = "tracking_token_hash", nullable = false, updatable = false)
    private String trackingTokenHash;

    @Column(name = "idempotency_key", nullable = false, updatable = false)
    private UUID idempotencyKey;

    @Column(name = "customer_id", nullable = false, updatable = false)
    private UUID customerId;

    @Column(name = "customer_name", nullable = false)
    private String customerName;

    @Column(name = "customer_phone", nullable = false)
    private String customerPhone;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private OrderStatus status;

    @Enumerated(EnumType.STRING)
    @Column(name = "payment_status", nullable = false)
    private PaymentStatus paymentStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "payment_method", nullable = false, updatable = false)
    private PaymentMethod paymentMethod;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private Currency currency;

    @Enumerated(EnumType.STRING)
    @Column(name = "display_currency", nullable = false, updatable = false)
    private Currency displayCurrency;

    @Column(name = "rate_snapshot", precision = 18, scale = 6, updatable = false)
    private BigDecimal rateSnapshot;

    @Column(nullable = false, precision = 19, scale = 2, updatable = false)
    private BigDecimal subtotal;

    @Column(name = "delivery_fee", nullable = false, precision = 19, scale = 2, updatable = false)
    private BigDecimal deliveryFee;

    @Column(nullable = false, precision = 19, scale = 2, updatable = false)
    private BigDecimal total;

    @Column(name = "total_display", nullable = false, precision = 19, scale = 2, updatable = false)
    private BigDecimal totalDisplay;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, updatable = false)
    private Address address;

    private String notes;

    @Column(name = "zone_id")
    private UUID zoneId;

    @Column(name = "placed_at", nullable = false, updatable = false)
    private Instant placedAt;

    @Column(name = "confirmed_at")
    private Instant confirmedAt;

    @Column(name = "delivered_at")
    private Instant deliveredAt;

    @OneToMany(mappedBy = "order", cascade = CascadeType.ALL)
    @OrderBy("position")
    private List<OrderItem> items = new ArrayList<>();

    protected ShopOrder() {
    }

    /** A new order, waiting for the merchant's confirmation. Amounts are in {@code currency}. */
    public ShopOrder(int number, String trackingTokenHash, UUID idempotencyKey, UUID customerId, String customerName,
            String customerPhone, PaymentMethod paymentMethod, PaymentStatus paymentStatus, Currency currency,
            BigDecimal rateSnapshot, BigDecimal subtotal, BigDecimal deliveryFee, Address address, String notes,
            UUID zoneId, Instant placedAt) {
        this.number = number;
        this.trackingTokenHash = trackingTokenHash;
        this.idempotencyKey = idempotencyKey;
        this.customerId = customerId;
        this.customerName = customerName;
        this.customerPhone = customerPhone;
        this.status = OrderStatus.PENDING_CONFIRMATION;
        this.paymentMethod = paymentMethod;
        this.paymentStatus = paymentStatus;
        // Shoppers pay what they were shown, so the charge currency is the display currency.
        this.currency = currency;
        this.displayCurrency = currency;
        this.rateSnapshot = rateSnapshot;
        this.subtotal = subtotal;
        this.deliveryFee = deliveryFee;
        this.total = subtotal.add(deliveryFee);
        this.totalDisplay = this.total;
        this.address = address;
        this.notes = notes;
        this.zoneId = zoneId;
        this.placedAt = placedAt;
    }

    public void addItem(OrderItem item) {
        item.attachTo(this, items.size());
        items.add(item);
    }

    public void moveTo(OrderStatus target, Instant at) {
        this.status = target;
        if (target == OrderStatus.CONFIRMED) {
            confirmedAt = at;
        } else if (target == OrderStatus.DELIVERED) {
            deliveredAt = at;
        }
    }

    public void setPaymentStatus(PaymentStatus paymentStatus) {
        this.paymentStatus = paymentStatus;
    }

    public int getNumber() {
        return number;
    }

    public String getTrackingTokenHash() {
        return trackingTokenHash;
    }

    public UUID getIdempotencyKey() {
        return idempotencyKey;
    }

    public UUID getCustomerId() {
        return customerId;
    }

    public String getCustomerName() {
        return customerName;
    }

    public String getCustomerPhone() {
        return customerPhone;
    }

    public OrderStatus getStatus() {
        return status;
    }

    public PaymentStatus getPaymentStatus() {
        return paymentStatus;
    }

    public PaymentMethod getPaymentMethod() {
        return paymentMethod;
    }

    public Currency getCurrency() {
        return currency;
    }

    public BigDecimal getRateSnapshot() {
        return rateSnapshot;
    }

    public BigDecimal getSubtotal() {
        return subtotal;
    }

    public BigDecimal getDeliveryFee() {
        return deliveryFee;
    }

    public BigDecimal getTotal() {
        return total;
    }

    public Address getAddress() {
        return address;
    }

    public String getNotes() {
        return notes;
    }

    public Instant getPlacedAt() {
        return placedAt;
    }

    public List<OrderItem> getItems() {
        return items;
    }
}
