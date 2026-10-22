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

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.unispark.ecommerce.identity.StaffDirectory;
import com.unispark.ecommerce.identity.StaffRole;
import com.unispark.ecommerce.order.CodSettlement;
import com.unispark.ecommerce.order.internal.persistence.CodRemittance;
import com.unispark.ecommerce.order.internal.persistence.CodRemittanceRepository;
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

    /**
     * The driver's collections (+) and remittances (-) in one list. held_after is the running sum per currency over
     * their whole history in time order (a collection before a remittance at the same moment, then by id), so it is
     * exact on every page; the page is then cut newest first in the reverse of that order.
     */
    private static final String STATEMENT_LINES = """
            WITH lines AS (
                SELECT c.collected_at AS at, c.id, o.number AS order_number, c.amount, c.currency,
                       NULL::uuid AS received_by, NULL::text AS note, 1 AS sign
                FROM cod_collection c
                JOIN shop_order o ON o.tenant_id = c.tenant_id AND o.id = c.order_id
                WHERE c.driver_id = :driver
                UNION ALL
                SELECT r.remitted_at, r.id, NULL, r.amount, r.currency, r.received_by, r.note, -1
                FROM cod_remittance r
                WHERE r.driver_id = :driver),
            running AS (
                SELECT lines.*, sum(sign * amount) OVER (PARTITION BY currency ORDER BY at, sign DESC, id
                                                         ROWS UNBOUNDED PRECEDING) AS held_after
                FROM lines)
            SELECT * FROM running
            ORDER BY at DESC, sign, id DESC
            LIMIT :rows OFFSET :skip
            """;

    private final JdbcClient jdbc;
    private final CodRemittanceRepository remittances;
    private final StaffDirectory staff;
    private final Clock clock;

    CodSettlementService(JdbcClient jdbc, CodRemittanceRepository remittances, StaffDirectory staff, Clock clock) {
        this.jdbc = jdbc;
        this.remittances = remittances;
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
    public Statement statement(UUID driverId, int page) {
        var balances = balances(driverId, sums("cod_collection", driverId), sums("cod_remittance", driverId));
        var current = Math.max(page, 0);
        // One more than a page, to know whether an older page follows.
        var lines = jdbc.sql(STATEMENT_LINES)
                .param("driver", driverId)
                .param("rows", STATEMENT_ROWS + 1)
                .param("skip", (long) current * STATEMENT_ROWS)
                .query((row, index) -> {
                    var currency = Currency.valueOf(row.getString("currency"));
                    return new Line(row.getTimestamp("at").toInstant(), row.getObject("order_number", Integer.class),
                            Money.of(row.getBigDecimal("amount"), currency), row.getObject("received_by", UUID.class),
                            row.getString("note"), Money.of(row.getBigDecimal("held_after"), currency));
                })
                .list();
        var hasNext = lines.size() > STATEMENT_ROWS;
        return new Statement(driverId, balances, hasNext ? List.copyOf(lines.subList(0, STATEMENT_ROWS)) : lines,
                current, hasNext);
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
