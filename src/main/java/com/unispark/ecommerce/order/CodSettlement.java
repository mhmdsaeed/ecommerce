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

    /** One driver's balances and their latest collections and remittances, newest first. */
    Statement statement(UUID driverId);

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

    record Statement(UUID driverId, List<Balance> balances, List<Collection> collections,
            List<Remittance> remittances) {
    }

    record Collection(int orderNumber, Money amount, Instant collectedAt) {
    }

    record Remittance(Money amount, Instant remittedAt, UUID receivedBy, String note) {
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
