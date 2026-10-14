package com.unispark.ecommerce.shared;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class IdsTest {

    @Test
    void generatesVersion7RfcVariantUuids() {
        var id = Ids.newId();

        assertThat(id.version()).isEqualTo(7);
        assertThat(id.variant()).isEqualTo(2);
    }

    @Test
    void encodesTheMillisecondTimestampInTheFirst48Bits() {
        var millis = 1_760_000_000_123L;

        var id = Ids.newId(millis);

        assertThat(id.getMostSignificantBits() >>> 16).isEqualTo(millis);
    }

    @Test
    void sortsIdsFromLaterMillisecondsAfterEarlierOnes() {
        var earlier = Ids.newId(1_760_000_000_000L);
        var later = Ids.newId(1_760_000_000_001L);

        assertThat(earlier.toString()).isLessThan(later.toString());
    }

    @Test
    void generatesDistinctIds() {
        var ids = new HashSet<UUID>();
        for (int i = 0; i < 10_000; i++) {
            ids.add(Ids.newId());
        }

        assertThat(ids).hasSize(10_000);
    }
}
