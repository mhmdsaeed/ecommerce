package com.unispark.ecommerce.identity;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Who works at the current store, for showing names (order history, driver lists) and checking roles. Read-only and
 * open to every staff role and to jobs, unlike {@link StaffManagement}; it holds no contact details.
 */
public interface StaffDirectory {

    /** Everyone, by name. */
    List<StaffCard> all();

    Optional<StaffCard> find(UUID staffId);

    /** Active drivers, by name: who an order can be given to. */
    default List<StaffCard> activeDrivers() {
        return all().stream().filter(card -> card.active() && card.role() == StaffRole.DRIVER).toList();
    }

    record StaffCard(UUID id, String name, StaffRole role, boolean active) {
    }
}
