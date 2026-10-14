package com.unispark.ecommerce.delivery.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.UUID;
import java.util.function.UnaryOperator;

import org.junit.jupiter.api.Test;

import com.unispark.ecommerce.delivery.DeliveryQuotes.BelowMinimum;
import com.unispark.ecommerce.delivery.DeliveryQuotes.Deliverable;
import com.unispark.ecommerce.delivery.DeliveryZones.Zone;
import com.unispark.ecommerce.delivery.Governorate;
import com.unispark.ecommerce.pricing.CurrencyConversion;
import com.unispark.ecommerce.shared.Currency;
import com.unispark.ecommerce.shared.LocalizedText;
import com.unispark.ecommerce.shared.Money;
import com.unispark.ecommerce.shared.RoundingRule;

/** The checkout fee (M7 done-when): fee, minimum order and free delivery against the subtotal. */
class FeeRulesTest {

    static final UnaryOperator<Money> SAME = UnaryOperator.identity();
    /** A store showing pounds at 118.5 per dollar, rounded to the nearest 5. */
    static final UnaryOperator<Money> TO_POUNDS = money -> CurrencyConversion.convert(money, Currency.SYP,
            new BigDecimal("118.5"), RoundingRule.NEAREST_5);
    static final LocalizedText ETA = LocalizedText.of("خلال يوم", "Within a day");

    @Test
    void addsTheFeeToTheSubtotal() {
        var quote = (Deliverable) FeeRules.quote(zone("2000", null, null), syp("30000"), SAME);

        assertThat(quote.fee()).isEqualTo(syp("2000"));
        assertThat(quote.total()).isEqualTo(syp("32000"));
        assertThat(quote.free()).isFalse();
        assertThat(quote.untilFree()).isNull();
        assertThat(quote.eta()).isEqualTo(ETA);
    }

    @Test
    void freeZoneChargesNothing() {
        var quote = (Deliverable) FeeRules.quote(zone("0", null, null), syp("30000"), SAME);

        assertThat(quote.free()).isTrue();
        assertThat(quote.total()).isEqualTo(syp("30000"));
    }

    @Test
    void deliversFreeFromTheThresholdOn() {
        var zone = zone("2000", null, "50000");

        var below = (Deliverable) FeeRules.quote(zone, syp("49999"), SAME);
        assertThat(below.fee()).isEqualTo(syp("2000"));
        assertThat(below.untilFree()).isEqualTo(syp("1"));
        assertThat(below.total()).isEqualTo(syp("51999"));

        var at = (Deliverable) FeeRules.quote(zone, syp("50000"), SAME);
        assertThat(at.free()).isTrue();
        assertThat(at.untilFree()).isNull();
        assertThat(at.total()).isEqualTo(syp("50000"));
    }

    @Test
    void refusesOrdersBelowTheMinimum() {
        var zone = zone("3500", "10000", null);

        assertThat(FeeRules.quote(zone, syp("9000"), SAME))
                .isEqualTo(new BelowMinimum(zone, syp("10000"), syp("1000")));
        assertThat(FeeRules.quote(zone, syp("10000"), SAME)).isInstanceOf(Deliverable.class);
    }

    @Test
    void convertsDollarZonesIntoTheDisplayCurrency() {
        var aleppo = new Zone(UUID.randomUUID(), Governorate.ALEPPO, "حلب", null, usd("5"), usd("20"), usd("40"),
                null, true);

        // $5 at 118.5 = 592.5 -> 595; minimum $20 = 2,370; free from $40 = 4,740.
        var quote = (Deliverable) FeeRules.quote(aleppo, syp("3000"), TO_POUNDS);
        assertThat(quote.fee()).isEqualTo(syp("595"));
        assertThat(quote.total()).isEqualTo(syp("3595"));
        assertThat(quote.untilFree()).isEqualTo(syp("1740"));
        assertThat(FeeRules.quote(aleppo, syp("2000"), TO_POUNDS))
                .isEqualTo(new BelowMinimum(aleppo, syp("2370"), syp("370")));
    }

    @Test
    void withoutARateThresholdsAreNotAppliedAndNoTotalIsGiven() {
        // No rate: the dollar zone stays in dollars and cannot be compared with a pound subtotal.
        var zone = new Zone(UUID.randomUUID(), Governorate.ALEPPO, null, null, usd("5"), usd("20"), usd("40"),
                null, true);

        var quote = (Deliverable) FeeRules.quote(zone, syp("100"), SAME);
        assertThat(quote.fee()).isEqualTo(usd("5"));
        assertThat(quote.total()).isNull();
        assertThat(quote.untilFree()).isNull();
    }

    @Test
    void unknownSubtotalShowsTheFeeAlone() {
        var quote = (Deliverable) FeeRules.quote(zone("2000", "10000", "50000"), null, SAME);

        assertThat(quote.fee()).isEqualTo(syp("2000"));
        assertThat(quote.total()).isNull();
        assertThat(quote.untilFree()).isNull();
    }

    private static Zone zone(String fee, String minOrder, String freeOver) {
        return new Zone(UUID.randomUUID(), Governorate.DAMASCUS, "دمشق", null, syp(fee),
                minOrder == null ? null : syp(minOrder), freeOver == null ? null : syp(freeOver), ETA, true);
    }

    private static Money syp(String amount) {
        return Money.of(new BigDecimal(amount), Currency.SYP);
    }

    private static Money usd(String amount) {
        return Money.of(new BigDecimal(amount), Currency.USD);
    }
}
