package com.unispark.ecommerce.merchantadmin.internal.web.orders;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.unispark.ecommerce.order.CodSettlement;
import com.unispark.ecommerce.order.CodSettlement.Balance;
import com.unispark.ecommerce.order.CodSettlement.Collection;
import com.unispark.ecommerce.order.CodSettlement.DriverBalance;
import com.unispark.ecommerce.order.CodSettlement.Remittance;
import com.unispark.ecommerce.order.CodSettlement.Statement;
import com.unispark.ecommerce.shared.Currency;
import com.unispark.ecommerce.shared.Money;

class SettlementViewsTest {

    private static final UUID DRIVER = UUID.randomUUID();
    private static final UUID HALA = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-10-20T18:00:00Z");

    @Test
    void listsDriversHoldingCashFirst() {
        var settled = UUID.randomUUID();
        var holding = UUID.randomUUID();
        var rows = SettlementViews.rows(List.of(
                new DriverBalance(settled, List.of(balance("10000", "10000"))),
                new DriverBalance(holding, List.of(balance("10000", "2500")))),
                Map.of(settled, "أحمد", holding, "سامر"));

        assertThat(rows).extracting(SettlementViews.DriverRow::name).containsExactly("سامر", "أحمد");
        assertThat(rows.getFirst().tracks().getFirst().handedOverPercent()).isEqualTo(25);
        assertThat(rows.getLast().tracks().getFirst().handedOverPercent()).isEqualTo(100);
    }

    @Test
    void showsWhatTheDriverHeldAfterEachLine() {
        // Collected 5,000 then 3,000, handed over 6,000: holds 2,000 now.
        var statement = new Statement(DRIVER, List.of(balance("8000", "6000")),
                List.of(new Collection(1002, syp("3000"), NOW.minusSeconds(3600)),
                        new Collection(1001, syp("5000"), NOW.minusSeconds(7200))),
                List.of(new Remittance(syp("6000"), NOW, HALA, "تسليم المساء")));

        var lines = SettlementViews.lines(statement, Map.of(HALA, "هالة"));

        assertThat(lines.earlierHidden()).isFalse();
        assertThat(lines.lines()).extracting(SettlementViews.Line::heldAfter)
                .containsExactly(syp("2000"), syp("8000"), syp("5000"));
        assertThat(lines.lines().getFirst().receivedBy()).isEqualTo("هالة");
        assertThat(lines.lines().getFirst().collection()).isFalse();
        assertThat(lines.lines().getLast().orderNumber()).isEqualTo(1001);
    }

    @Test
    void leavesOutLinesOlderThanAFullList() {
        var collections = new ArrayList<Collection>();
        for (int i = 0; i < CodSettlement.STATEMENT_ROWS; i++) {
            collections.add(new Collection(2000 - i, syp("1000"), NOW.minusSeconds(60L * i)));
        }
        // A handover older than every collection shown: what the driver held after it is unknown here.
        var statement = new Statement(DRIVER, List.of(balance("90000", "1000")), collections,
                List.of(new Remittance(syp("1000"), NOW.minusSeconds(86_400), HALA, null)));

        var lines = SettlementViews.lines(statement, Map.of(HALA, "هالة"));

        assertThat(lines.earlierHidden()).isTrue();
        assertThat(lines.lines()).hasSize(CodSettlement.STATEMENT_ROWS).allMatch(SettlementViews.Line::collection);
        assertThat(lines.lines().getFirst().heldAfter()).isEqualTo(syp("89000"));
    }

    private static Balance balance(String collected, String remitted) {
        return new Balance(Currency.SYP, syp(collected), syp(remitted), syp(collected).minus(syp(remitted)));
    }

    private static Money syp(String amount) {
        return Money.of(new BigDecimal(amount), Currency.SYP);
    }
}
