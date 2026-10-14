package com.unispark.ecommerce.order.internal.persistence;

import java.util.Map;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import com.unispark.ecommerce.order.OrderStatus;
import com.unispark.ecommerce.shared.TenantScopedEntity;

/** One entry of an order's log (SPEC 5). Append-only: immutable here, and the database refuses changes too. */
@Entity
@Immutable
@Table(name = "order_event")
public class OrderEvent extends TenantScopedEntity {

    @Column(name = "order_id", nullable = false)
    private UUID orderId;

    @Column(nullable = false)
    private int position;

    @Column(nullable = false)
    private String type;

    @Enumerated(EnumType.STRING)
    @Column(name = "from_status")
    private OrderStatus fromStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "to_status")
    private OrderStatus toStatus;

    @Column(nullable = false)
    private String actor;

    @JdbcTypeCode(SqlTypes.JSON)
    private Map<String, Object> payload;

    protected OrderEvent() {
    }

    public OrderEvent(UUID orderId, int position, String type, OrderStatus fromStatus, OrderStatus toStatus,
            String actor, Map<String, Object> payload) {
        this.orderId = orderId;
        this.position = position;
        this.type = type;
        this.fromStatus = fromStatus;
        this.toStatus = toStatus;
        this.actor = actor;
        this.payload = payload;
    }

    public UUID getOrderId() {
        return orderId;
    }

    public String getType() {
        return type;
    }

    public OrderStatus getFromStatus() {
        return fromStatus;
    }

    public OrderStatus getToStatus() {
        return toStatus;
    }

    public String getActor() {
        return actor;
    }

    public Map<String, Object> getPayload() {
        return payload;
    }
}
