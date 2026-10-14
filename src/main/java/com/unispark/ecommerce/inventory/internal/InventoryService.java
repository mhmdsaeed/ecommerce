package com.unispark.ecommerce.inventory.internal;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.unispark.ecommerce.inventory.Inventory;
import com.unispark.ecommerce.inventory.StockLevels;
import com.unispark.ecommerce.inventory.StockLevels.Level;
import com.unispark.ecommerce.inventory.internal.persistence.StockLevel;
import com.unispark.ecommerce.inventory.internal.persistence.StockLevelRepository;
import com.unispark.ecommerce.shared.Ids;
import com.unispark.ecommerce.shared.TenantContext;

/**
 * Stock changes as single conditional updates: the WHERE clause re-checks the rule on the row PostgreSQL has just
 * locked, so two orders for the last unit cannot both succeed, whatever the interleaving. The table's checks back
 * this up. Statements name {@code tenant_id} explicitly (SPEC 4.2) on top of row-level security.
 *
 * <p>Variants are always updated in id order, so two multi-variant reservations cannot deadlock.
 */
@Service
@Transactional
class InventoryService implements Inventory {

    private final StockLevelRepository levels;
    private final JdbcClient jdbc;

    InventoryService(StockLevelRepository levels, JdbcClient jdbc) {
        this.levels = levels;
        this.jdbc = jdbc;
    }

    @Override
    @Transactional(readOnly = true)
    public StockLevels levels(Collection<UUID> variantIds) {
        if (variantIds.isEmpty()) {
            return StockLevels.NONE;
        }
        return new StockLevels(levels.findByVariantIdIn(variantIds).stream()
                .collect(Collectors.toMap(StockLevel::getVariantId, StockLevel::toLevel)));
    }

    @Override
    public StockResult setOnHand(UUID variantId, Integer onHand) {
        var tenant = TenantContext.requireTenantId();
        if (onHand == null) {
            var deleted = jdbc.sql("DELETE FROM stock_level WHERE tenant_id = :tenant AND variant_id = :variant"
                            + " AND reserved = 0")
                    .param("tenant", tenant).param("variant", variantId).update();
            if (deleted == 0) {
                var current = current(variantId);
                if (current.isPresent()) {
                    return new Rejected("reserved", current.get().reserved());
                }
            }
            return new Updated(null);
        }
        if (onHand < 0 || onHand > MAX_ON_HAND) {
            return new Rejected("invalid", 0);
        }
        // The variant table belongs to the catalog; this only checks the id, the foreign key enforces it.
        var exists = jdbc.sql("SELECT EXISTS (SELECT 1 FROM product_variant WHERE tenant_id = :tenant AND id = :variant)")
                .param("tenant", tenant).param("variant", variantId).query(Boolean.class).single();
        if (!exists) {
            return new UnknownVariant();
        }
        var saved = jdbc.sql("""
                        INSERT INTO stock_level (id, tenant_id, variant_id, on_hand, reserved, created_at, updated_at, version)
                        VALUES (:id, :tenant, :variant, :onHand, 0, now(), now(), 0)
                        ON CONFLICT (tenant_id, variant_id) DO UPDATE
                            SET on_hand = excluded.on_hand, updated_at = now(), version = stock_level.version + 1
                            WHERE stock_level.reserved <= excluded.on_hand
                        RETURNING on_hand, reserved
                        """)
                .param("id", Ids.newId()).param("tenant", tenant).param("variant", variantId).param("onHand", onHand)
                .query((rs, row) -> new Level(variantId, rs.getInt("on_hand"), rs.getInt("reserved")))
                .optional();
        if (saved.isPresent()) {
            return new Updated(saved.get());
        }
        return new Rejected("below-reserved", current(variantId).map(Level::reserved).orElse(0));
    }

    @Override
    public Reservation reserve(Map<UUID, Integer> quantities) {
        var tenant = TenantContext.requireTenantId();
        var done = new LinkedHashMap<UUID, Integer>();
        var shortages = new ArrayList<Shortage>();
        for (var entry : inIdOrder(quantities)) {
            var updated = jdbc.sql("""
                            UPDATE stock_level SET reserved = reserved + :quantity, updated_at = now(), version = version + 1
                            WHERE tenant_id = :tenant AND variant_id = :variant AND on_hand - reserved >= :quantity
                            """)
                    .param("quantity", entry.getValue()).param("tenant", tenant).param("variant", entry.getKey())
                    .update();
            if (updated == 1) {
                done.put(entry.getKey(), entry.getValue());
                continue;
            }
            var level = current(entry.getKey());
            if (level.isPresent()) {
                shortages.add(new Shortage(entry.getKey(), entry.getValue(), level.get().available()));
            }
        }
        if (shortages.isEmpty()) {
            return new Reserved();
        }
        // Undo instead of marking the transaction rollback-only: a caller's own transaction stays usable.
        release(done);
        return new Insufficient(List.copyOf(shortages));
    }

    @Override
    public void release(Map<UUID, Integer> quantities) {
        change(quantities, "reserved = reserved - :quantity", "release");
    }

    @Override
    public void deduct(Map<UUID, Integer> quantities) {
        change(quantities, "on_hand = on_hand - :quantity, reserved = reserved - :quantity", "deduct");
    }

    /** Both take reserved units; more than is reserved means a bookkeeping bug, so fail loudly. */
    private void change(Map<UUID, Integer> quantities, String assignments, String action) {
        var tenant = TenantContext.requireTenantId();
        for (var entry : inIdOrder(quantities)) {
            var updated = jdbc.sql("UPDATE stock_level SET " + assignments
                            + ", updated_at = now(), version = version + 1"
                            + " WHERE tenant_id = :tenant AND variant_id = :variant AND reserved >= :quantity")
                    .param("quantity", entry.getValue()).param("tenant", tenant).param("variant", entry.getKey())
                    .update();
            if (updated == 0 && current(entry.getKey()).isPresent()) {
                throw new IllegalStateException("Cannot " + action + " " + entry.getValue()
                        + " units of variant " + entry.getKey() + ": fewer are reserved");
            }
        }
    }

    private Optional<Level> current(UUID variantId) {
        return jdbc.sql("SELECT on_hand, reserved FROM stock_level WHERE tenant_id = :tenant AND variant_id = :variant")
                .param("tenant", TenantContext.requireTenantId()).param("variant", variantId)
                .query((rs, row) -> new Level(variantId, rs.getInt("on_hand"), rs.getInt("reserved")))
                .optional();
    }

    private static List<Map.Entry<UUID, Integer>> inIdOrder(Map<UUID, Integer> quantities) {
        quantities.forEach((variant, quantity) -> {
            if (quantity == null || quantity <= 0) {
                throw new IllegalArgumentException("Quantity must be positive for variant " + variant);
            }
        });
        return quantities.entrySet().stream().sorted(Map.Entry.comparingByKey(Comparator.naturalOrder())).toList();
    }
}
