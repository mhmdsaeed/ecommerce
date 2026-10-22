package com.unispark.ecommerce.merchantadmin.internal.web.orders;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.unispark.ecommerce.order.CodSettlement.Balance;
import com.unispark.ecommerce.order.CodSettlement.DriverBalance;
import com.unispark.ecommerce.shared.Currency;
import com.unispark.ecommerce.shared.Money;

class SettlementViewsTest {

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

    private static Balance balance(String collected, String remitted) {
        return new Balance(Currency.SYP, syp(collected), syp(remitted), syp(collected).minus(syp(remitted)));
    }

    private static Money syp(String amount) {
        return Money.of(new BigDecimal(amount), Currency.SYP);
    }
}
