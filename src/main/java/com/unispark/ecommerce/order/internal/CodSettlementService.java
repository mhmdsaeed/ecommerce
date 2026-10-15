package com.unispark.ecommerce.order.internal;

import java.math.BigDecimal;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;

import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.unispark.ecommerce.identity.StaffDirectory;
import com.unispark.ecommerce.identity.StaffRole;
import com.unispark.ecommerce.order.CodSettlement;
import com.unispark.ecommerce.order.internal.persistence.CodCollectionRepository;
import com.unispark.ecommerce.order.internal.persistence.CodRemittance;
import com.unispark.ecommerce.order.internal.persistence.CodRemittanceRepository;
import com.unispark.ecommerce.order.internal.persistence.ShopOrderRepository;
import com.unispark.ecommerce.shared.Amounts;
import com.unispark.ecommerce.shared.Currency;
import com.unispark.ecommerce.shared.Money;
import com.unispark.ecommerce.shared.TenantContext;

/**
 * Balances are sums in SQL, per driver and currency. Recording a remittance takes a transaction-scoped advisory
 * lock per driver, so two staff members recording at once cannot together take more than the driver holds.
 */
@Service
@Transactional
class CodSettlementService implements CodSettlement {

    private final JdbcClient jdbc;
    private final CodCollectionRepository collections;
    private final CodRemittanceRepository remittances;
    private final ShopOrderRepository orders;
    private final StaffDirectory staff;
    private final Clock clock;

    CodSettlementService(JdbcClient jdbc, CodCollectionRepository collections, CodRemittanceRepository remittances,
            ShopOrderRepository orders, StaffDirectory staff, Clock clock) {
        this.jdbc = jdbc;
        this.collections = collections;
        this.remittances = remittances;
        this.orders = orders;
        this.staff = staff;
        this.clock = clock;
    }

    private record Key(UUID driverId, Currency currency) {
    }

    @Override
    @Transactional(readOnly = true)
    public List<DriverBalance> balances() {
        var collected = sums("cod_collection", null);
        var remitted = sums("cod_remittance", null);
        var drivers = new TreeSet<UUID>(Comparator.comparing(UUID::toString));
        collected.keySet().forEach(key -> drivers.add(key.driverId()));
        remitted.keySet().forEach(key -> drivers.add(key.driverId()));
        return drivers.stream()
                .map(driver -> new DriverBalance(driver, balances(driver, collected, remitted)))
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<Money> outstanding() {
        var collected = sums("cod_collection", null);
        var remitted = sums("cod_remittance", null);
        var totals = new TreeMap<Currency, BigDecimal>();
        collected.forEach((key, amount) -> totals.merge(key.currency(), amount, BigDecimal::add));
        remitted.forEach((key, amount) -> totals.merge(key.currency(), amount.negate(), BigDecimal::add));
        return totals.entrySet().stream().filter(total -> total.getValue().signum() > 0)
                .map(total -> Money.of(total.getValue(), total.getKey())).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public Statement statement(UUID driverId) {
        var balances = balances(driverId, sums("cod_collection", driverId), sums("cod_remittance", driverId));
        var page = PageRequest.of(0, STATEMENT_ROWS);
        var numbers = new HashMap<UUID, Integer>();
        var collectedRows = collections.findByDriverIdOrderByCollectedAtDesc(driverId, page);
        orders.findAllById(collectedRows.stream().map(row -> row.getOrderId()).toList())
                .forEach(order -> numbers.put(order.getId(), order.getNumber()));
        var collected = collectedRows.stream()
                .map(row -> new Collection(numbers.getOrDefault(row.getOrderId(), 0),
                        Money.of(row.getAmount(), row.getCurrency()), row.getCollectedAt()))
                .toList();
        var remitted = remittances.findByDriverIdOrderByRemittedAtDesc(driverId, page).stream()
                .map(row -> new Remittance(Money.of(row.getAmount(), row.getCurrency()), row.getRemittedAt(),
                        row.getReceivedBy(), row.getNote()))
                .toList();
        return new Statement(driverId, balances, collected, remitted);
    }

    @Override
    public RemitResult remit(UUID driverId, String amount, Currency currency, String note, UUID receivedBy) {
        var driver = staff.find(driverId).filter(card -> card.role() == StaffRole.DRIVER);
        if (driver.isEmpty()) {
            return new NotADriver();
        }
        var parsed = Amounts.parse(amount).filter(value -> value.signum() > 0);
        if (parsed.isEmpty() || currency == null) {
            return new InvalidAmount();
        }
        jdbc.sql("SELECT 1 FROM pg_advisory_xact_lock(hashtextextended(:key, 0))")
                .param("key", TenantContext.requireTenantId() + ":cod:" + driverId)
                .query((row, index) -> 1)
                .list();
        var held = balances(driverId, sums("cod_collection", driverId), sums("cod_remittance", driverId)).stream()
                .filter(balance -> balance.currency() == currency).findFirst()
                .map(Balance::outstanding).orElse(Money.zero(currency));
        if (held.amount().compareTo(parsed.get()) < 0) {
            return new MoreThanOutstanding(held);
        }
        var cleanNote = note == null || note.isBlank() ? null : note.strip();
        if (cleanNote != null && cleanNote.length() > MAX_NOTE_LENGTH) {
            cleanNote = cleanNote.substring(0, MAX_NOTE_LENGTH);
        }
        remittances.save(new CodRemittance(driverId, parsed.get(), currency, clock.instant(), receivedBy, cleanNote));
        return new Remitted();
    }

    /** Sum of amounts per driver and currency; one driver's only when {@code driverId} is given. */
    private Map<Key, BigDecimal> sums(String table, UUID driverId) {
        var sql = "SELECT driver_id, currency, sum(amount) AS total FROM " + table
                + " WHERE tenant_id = :tenant AND driver_id IS NOT NULL"
                + (driverId == null ? "" : " AND driver_id = :driver")
                + " GROUP BY driver_id, currency";
        var query = jdbc.sql(sql).param("tenant", TenantContext.requireTenantId());
        if (driverId != null) {
            query = query.param("driver", driverId);
        }
        var sums = new HashMap<Key, BigDecimal>();
        query.query((row, index) -> {
            sums.put(new Key(row.getObject("driver_id", UUID.class), Currency.valueOf(row.getString("currency"))),
                    row.getBigDecimal("total"));
            return null;
        }).list();
        return sums;
    }

    private static List<Balance> balances(UUID driverId, Map<Key, BigDecimal> collected,
            Map<Key, BigDecimal> remitted) {
        var result = new ArrayList<Balance>();
        for (var currency : Currency.values()) {
            var key = new Key(driverId, currency);
            var in = collected.getOrDefault(key, BigDecimal.ZERO);
            var out = remitted.getOrDefault(key, BigDecimal.ZERO);
            if (in.signum() == 0 && out.signum() == 0) {
                continue;
            }
            result.add(new Balance(currency, Money.of(in, currency), Money.of(out, currency),
                    Money.of(in.subtract(out), currency)));
        }
        return List.copyOf(result);
    }
}
