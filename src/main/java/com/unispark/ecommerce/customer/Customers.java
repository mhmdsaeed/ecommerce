package com.unispark.ecommerce.customer;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.unispark.ecommerce.delivery.Destination;
import com.unispark.ecommerce.delivery.Governorate;
import com.unispark.ecommerce.shared.FieldProblem;

/**
 * The current store's customers and their saved addresses (SPEC 5). A customer is a phone number within one store:
 * the same person shopping in two stores is two customers. Phones are accepted in any Syrian spelling and stored in
 * E.164. Checkout and OTP login (M8) create customers; addresses are matched against delivery zones there.
 */
public interface Customers {

    int MAX_ADDRESSES = 10;
    int MAX_NAME_LENGTH = 100;
    int MAX_PLACE_LENGTH = 80;
    int MAX_LANDMARK_LENGTH = 200;
    int MAX_DETAILS_LENGTH = 500;

    Optional<CustomerView> find(UUID id);

    Optional<CustomerView> findByPhone(String phone);

    /**
     * The customer with this phone, created on first use; safe when two requests register the same phone at once.
     * A non-blank name replaces the stored one.
     */
    RegisterResult register(String phone, String name);

    /**
     * Stops the customer from placing orders (SPEC 6.4). Checkout refuses them with a generic message; the reason is
     * for staff only. False for an unknown customer.
     */
    boolean block(UUID customerId, String reason);

    boolean unblock(UUID customerId);

    /** Counts a delivered order: customers with one are no longer new (SPEC 6.4). */
    void recordDelivered(UUID customerId);

    /** Counts an order refused at the door (SPEC 6.1). */
    void recordRefused(UUID customerId);

    /** Oldest first. Empty for an unknown customer. */
    List<AddressView> addresses(UUID customerId);

    AddressResult addAddress(UUID customerId, AddressDraft draft);

    AddressResult updateAddress(UUID customerId, UUID addressId, AddressDraft draft);

    AddressResult removeAddress(UUID customerId, UUID addressId);

    /** @param orderCount delivered orders */
    record CustomerView(UUID id, String phone, String name, boolean blocked, int orderCount, int refusedCount) {

        /** No delivered order yet: the store's new-customer limits apply. */
        public boolean isNew() {
            return orderCount == 0;
        }
    }

    /**
     * @param landmark e.g. "قرب صيدلية النور"; landmark or details is required
     * @param phone    someone else receiving the order, in any spelling; blank for the customer's own phone
     * @param lat      a location pin, with {@code lng}, or neither
     */
    record AddressDraft(Governorate governorate, String city, String area, String landmark, String details,
            String phone, BigDecimal lat, BigDecimal lng) {
    }

    record AddressView(UUID id, Governorate governorate, String city, String area, String landmark, String details,
            String phone, BigDecimal lat, BigDecimal lng) {

        public Destination destination() {
            return Destination.of(governorate, city, area);
        }
    }

    sealed interface RegisterResult {
    }

    record Registered(CustomerView customer) implements RegisterResult {
    }

    sealed interface AddressResult {
    }

    record AddressSaved(UUID id) implements AddressResult {
    }

    /** No such customer, or no such address of this customer. */
    record NotFound() implements AddressResult {
    }

    record Invalid(List<FieldProblem> problems) implements RegisterResult, AddressResult {
    }
}
