package com.unispark.ecommerce.merchantadmin.internal.web.orders;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.unispark.ecommerce.order.CodSettlement;
import com.unispark.ecommerce.order.CodSettlement.Balance;
import com.unispark.ecommerce.order.CodSettlement.DriverBalance;
import com.unispark.ecommerce.order.CodSettlement.Statement;
import com.unispark.ecommerce.shared.Currency;
import com.unispark.ecommerce.shared.Money;

/** What the settlement pages show, worked out from the driver balances and statement. */
final class SettlementViews {

    private SettlementViews() {
    }

    /** A driver on the list: drivers still holding cash come first, then by name. */
    record DriverRow(UUID driverId, String name, boolean settled, List<Track> tracks) {
    }

    /** One currency of a driver's cash: the share of what they collected that they have handed over, 0 to 100. */
    record Track(Balance balance, int handedOverPercent) {
    }

    /**
     * A line of a driver's statement, newest first: a collection for an order, or cash handed over to a member of
     * staff, and what the driver held in that currency after it.
     */
    record Line(Instant at, Integer orderNumber, String receivedBy, String note, Money amount, Money heldAfter) {

        public boolean collection() {
            return orderNumber != null;
        }
    }

    /** @param earlierHidden older lines exist but are not shown (the statement holds the latest ones only) */
    record Lines(List<Line> lines, boolean earlierHidden) {
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

    /**
     * Collections and handovers in one list, each with what the driver held after it: worked back from what they
     * hold now. Each kind is capped at {@link CodSettlement#STATEMENT_ROWS}; when one is full, lines older than its
     * oldest are left out, since the other kind's lines there could not be balanced.
     */
    static Lines lines(Statement statement, Map<UUID, String> names) {
        var lines = new ArrayList<Line>();
        statement.collections().forEach(collection -> lines.add(new Line(collection.collectedAt(),
                collection.orderNumber(), null, null, collection.amount(), null)));
        statement.remittances().forEach(remittance -> lines.add(new Line(remittance.remittedAt(), null,
                names.getOrDefault(remittance.receivedBy(), ""), remittance.note(), remittance.amount(), null)));
        lines.sort(Comparator.comparing(Line::at).reversed());

        Instant cutoff = null;
        if (statement.collections().size() >= CodSettlement.STATEMENT_ROWS) {
            cutoff = statement.collections().getLast().collectedAt();
        }
        if (statement.remittances().size() >= CodSettlement.STATEMENT_ROWS) {
            var oldest = statement.remittances().getLast().remittedAt();
            cutoff = cutoff == null || oldest.isAfter(cutoff) ? oldest : cutoff;
        }

        var held = new HashMap<Currency, Money>();
        statement.balances().forEach(balance -> held.put(balance.currency(), balance.outstanding()));
        var shown = new ArrayList<Line>();
        for (var line : lines) {
            if (cutoff != null && line.at().isBefore(cutoff)) {
                break;
            }
            var currency = line.amount().currency();
            var after = held.getOrDefault(currency, Money.zero(currency));
            shown.add(new Line(line.at(), line.orderNumber(), line.receivedBy(), line.note(), line.amount(), after));
            held.put(currency, line.collection() ? after.minus(line.amount()) : after.plus(line.amount()));
        }
        return new Lines(List.copyOf(shown), cutoff != null);
    }
}
