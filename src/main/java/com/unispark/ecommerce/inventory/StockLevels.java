package com.unispark.ecommerce.inventory;

import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;

/** Stock of some variants. Variants missing here are not tracked. */
public record StockLevels(Map<UUID, Level> byVariant) {

    public static final StockLevels NONE = new StockLevels(Map.of());

    public StockLevels {
        byVariant = Map.copyOf(byVariant);
    }

    public Optional<Level> of(UUID variantId) {
        return Optional.ofNullable(byVariant.get(variantId));
    }

    /** Empty when the variant is not tracked. */
    public OptionalInt available(UUID variantId) {
        var level = byVariant.get(variantId);
        return level == null ? OptionalInt.empty() : OptionalInt.of(level.available());
    }

    public boolean inStock(UUID variantId) {
        var level = byVariant.get(variantId);
        return level == null || level.available() > 0;
    }

    public record Level(UUID variantId, int onHand, int reserved) {

        public int available() {
            return onHand - reserved;
        }
    }
}
