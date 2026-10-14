package com.unispark.ecommerce.order;

import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

/** Who changed an order, as its event log records it: {@code customer:<id>}, {@code staff:<id>} or {@code system}. */
public record Actor(Kind kind, UUID id) {

    public enum Kind {
        CUSTOMER,
        STAFF,
        SYSTEM
    }

    public Actor {
        Objects.requireNonNull(kind, "kind");
        if ((kind == Kind.SYSTEM) != (id == null)) {
            throw new IllegalArgumentException("Customers and staff have an id, the system has none");
        }
    }

    public static Actor customer(UUID id) {
        return new Actor(Kind.CUSTOMER, id);
    }

    public static Actor staff(UUID id) {
        return new Actor(Kind.STAFF, id);
    }

    public static Actor system() {
        return new Actor(Kind.SYSTEM, null);
    }

    @Override
    public String toString() {
        return id == null ? "system" : kind.name().toLowerCase(Locale.ROOT) + ":" + id;
    }
}
