package com.unispark.ecommerce.merchantadmin.internal.web.orders;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.unispark.ecommerce.order.CodSettlement.Balance;
import com.unispark.ecommerce.order.CodSettlement.DriverBalance;

/** What the settlement list shows, worked out from the driver balances. */
final class SettlementViews {

    private SettlementViews() {
    }

    /** A driver on the list: drivers still holding cash come first, then by name. */
    record DriverRow(UUID driverId, String name, boolean settled, List<Track> tracks) {
    }

    /** One currency of a driver's cash: the share of what they collected that they have handed over, 0 to 100. */
    record Track(Balance balance, int handedOverPercent) {
    }

    static List<DriverRow> rows(List<DriverBalance> balances, Map<UUID, String> names) {
        return balances.stream()
                .map(driver -> new DriverRow(driver.driverId(), names.getOrDefault(driver.driverId(), ""),
                        driver.settled(), driver.balances().stream().map(SettlementViews::track).toList()))
                .sorted(Comparator.comparing(DriverRow::settled).thenComparing(DriverRow::name))
                .toList();
    }

    static Track track(Balance balance) {
        var collected = balance.collected().amount();
        if (collected.signum() <= 0) {
            return new Track(balance, 100);
        }
        var percent = balance.remitted().amount().multiply(BigDecimal.valueOf(100))
                .divide(collected, 0, RoundingMode.DOWN).intValue();
        return new Track(balance, Math.clamp(percent, 0, 100));
    }
}
