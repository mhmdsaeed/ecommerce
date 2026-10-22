package com.unispark.ecommerce.order;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.unispark.ecommerce.shared.Currency;
import com.unispark.ecommerce.shared.Money;

/**
 * Cash on delivery between drivers and the store (SPEC 8): what each driver collected, what they handed over, and
 * what they still hold. Amounts are kept per currency (orders are charged in the store's display currency, which can
 * change over time), never converted.
 */
public interface CodSettlement {

    int STATEMENT_ROWS = 50;
    int MAX_NOTE_LENGTH = 200;

    /** Every driver with cash collected or handed over, with their balances. */
    List<DriverBalance> balances();

    /** What drivers still hold in total, per currency, for the dashboard. */
    List<Money> outstanding();

    /**
     * One driver's balances and a page of their statement: collections and remittances together, newest first,
     * {@link #STATEMENT_ROWS} to a page (0 is the newest). Every line carries what the driver held after it, worked
     * out over their whole history, so it is right on every page.
     */
    Statement statement(UUID driverId, int page);

    /** The first page. */
    default Statement statement(UUID driverId) {
        return statement(driverId, 0);
    }

    /**
     * Records cash a driver handed over, received by {@code receivedBy}. It may not exceed what the driver holds in
     * that currency.
     *
     * @param amount as typed (Arabic digits and grouping accepted)
     */
    RemitResult remit(UUID driverId, String amount, Currency currency, String note, UUID receivedBy);

    /** @param outstanding collected minus remitted */
    record Balance(Currency currency, Money collected, Money remitted, Money outstanding) {
    }

    record DriverBalance(UUID driverId, List<Balance> balances) {

        public boolean settled() {
            return balances.stream().noneMatch(balance -> balance.outstanding().isPositive());
        }
    }

    /** @param hasNext older lines follow on the next page */
    record Statement(UUID driverId, List<Balance> balances, List<Line> lines, int page, boolean hasNext) {

        public boolean hasPrevious() {
            return page > 0;
        }
    }

    /**
     * A collection for an order ({@code orderNumber} set) or cash handed over to {@code receivedBy} (with an
     * optional note), and what the driver held in that currency after it.
     */
    record Line(Instant at, Integer orderNumber, Money amount, UUID receivedBy, String note, Money heldAfter) {

        public boolean collection() {
            return orderNumber != null;
        }
    }

    sealed interface RemitResult {
    }

    record Remitted() implements RemitResult {
    }

    record InvalidAmount() implements RemitResult {
    }

    /** The driver holds less than that in this currency. */
    record MoreThanOutstanding(Money outstanding) implements RemitResult {
    }

    record NotADriver() implements RemitResult {
    }
}
