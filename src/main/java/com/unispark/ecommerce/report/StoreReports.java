package com.unispark.ecommerce.report;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.unispark.ecommerce.shared.Currency;
import com.unispark.ecommerce.shared.LocalizedText;
import com.unispark.ecommerce.shared.Money;

/**
 * The current store's reports (merchant admin, owners and managers). Read-only, over the period's orders.
 *
 * <p>Sales are orders placed in the period except cancelled, refused and returned ones, in the currency the shopper
 * paid ({@code total_display}). Amounts are kept per currency, never converted: a store that changed its display
 * currency has a line for each.
 */
public interface StoreReports {

    /** At most this many available units: a tracked variant is "running low". */
    int LOW_STOCK = 5;
    int LIST_ROWS = 20;
    int LONG_LIST_ROWS = 50;

    Sales sales(ReportPeriod period);

    Products products(ReportPeriod period);

    OrdersAndDelivery ordersAndDelivery(ReportPeriod period);

    CustomersAndPayments customersAndPayments(ReportPeriod period);

    // Sales

    /**
     * @param totals   per currency, this period
     * @param previous per currency, the period just before ({@link ReportPeriod#previous})
     * @param points   per group of the period's unit and currency, every group present (zero when no sales)
     */
    record Sales(List<Total> totals, List<Total> previous, List<Point> points) {
    }

    /** {@code average} is null without orders. */
    record Total(Currency currency, long orders, Money revenue, Money average) {
    }

    record Point(LocalDate group, Currency currency, long orders, Money revenue) {
    }

    // Products

    record Products(List<BestSeller> bestSellers, List<Idle> idle, long idleCount, List<LowStock> lowStock) {
    }

    /** @param productId null when the product was deleted since; its name is the one on the order */
    record BestSeller(UUID productId, LocalizedText name, long quantity, Money revenue) {
    }

    /** A product on sale that sold nothing in the period. */
    record Idle(UUID productId, LocalizedText name, Instant since) {
    }

    /** @param options the variant's option values, e.g. [M, Red]; empty for a product without options */
    record LowStock(UUID productId, LocalizedText name, List<String> options, int available) {
    }

    // Orders and delivery

    /**
     * @param byStatus orders placed in the period, by status name
     * @param lost     cancelled, refused and returned orders, newest first, with the reason given
     */
    record OrdersAndDelivery(long placed, Map<String, Long> byStatus, List<Lost> lost, DeliveryTime deliveryTime,
            List<ZoneLine> zones, List<DriverLine> drivers) {

        public long lostCount() {
            return byStatus.getOrDefault("CANCELLED", 0L) + byStatus.getOrDefault("REFUSED", 0L)
                    + byStatus.getOrDefault("RETURNED", 0L);
        }
    }

    /** @param reason as the merchant or driver wrote it; null when none was given */
    record Lost(int number, String status, Instant placedAt, String customerName, String reason) {
    }

    /** Placed to delivered, for orders delivered in the period; hours null without deliveries. */
    record DeliveryTime(long delivered, Double averageHours, Double medianHours) {
    }

    /**
     * Delivered orders by the zone that priced them. {@code governorate} is null for orders without a zone (the zone
     * was deleted since).
     */
    record ZoneLine(String governorate, String city, String area, long delivered, Money fees, Double averageHours) {
    }

    /** @param cash what the driver collected in the period, per currency */
    record DriverLine(UUID driverId, String name, long delivered, long refused, Double averageHours,
            List<Money> cash) {
    }

    // Customers and payments

    /**
     * @param newCustomers   who bought in the period for the first time
     * @param returning      who bought in the period and had bought before
     * @param awaitingProof  orders whose payment proof waits for review, now
     * @param cashWithDrivers what drivers hold now, per currency
     */
    record CustomersAndPayments(long newCustomers, long returning, List<TopCustomer> top, List<MethodLine> methods,
            long awaitingProof, List<Money> cashWithDrivers) {
    }

    record TopCustomer(UUID customerId, String name, String phone, long orders, Money spent) {
    }

    /** @param method a PaymentMethod name */
    record MethodLine(String method, long orders, Money amount) {
    }
}
