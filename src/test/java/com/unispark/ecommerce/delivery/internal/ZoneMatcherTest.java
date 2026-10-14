package com.unispark.ecommerce.delivery.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.unispark.ecommerce.delivery.DeliveryZones.Zone;
import com.unispark.ecommerce.delivery.Destination;
import com.unispark.ecommerce.delivery.Governorate;
import com.unispark.ecommerce.shared.Currency;
import com.unispark.ecommerce.shared.Money;

/** SPEC 8: the most specific active zone wins (M7 done-when: zone matching tests pass). */
class ZoneMatcherTest {

    static final Zone RIF = zone(Governorate.RIF_DIMASHQ, null, null, true);
    static final Zone DAMASCUS_GOVERNORATE = zone(Governorate.DAMASCUS, null, null, true);
    static final Zone DAMASCUS_CITY = zone(Governorate.DAMASCUS, "دمشق", null, true);
    static final Zone MAZZEH = zone(Governorate.DAMASCUS, "دمشق", "المزة", true);
    static final Zone ALEPPO_CITY = zone(Governorate.ALEPPO, "حلب", null, true);
    static final List<Zone> ALL = List.of(RIF, DAMASCUS_GOVERNORATE, DAMASCUS_CITY, MAZZEH, ALEPPO_CITY);

    @Test
    void areaZoneBeatsCityAndGovernorate() {
        assertThat(match(ALL, Governorate.DAMASCUS, "دمشق", "المزة")).contains(MAZZEH);
    }

    @Test
    void cityZoneCoversItsOtherAreas() {
        assertThat(match(ALL, Governorate.DAMASCUS, "دمشق", "باب توما")).contains(DAMASCUS_CITY);
        assertThat(match(ALL, Governorate.DAMASCUS, "دمشق", null)).contains(DAMASCUS_CITY);
    }

    @Test
    void governorateZoneCoversEveryCityInIt() {
        assertThat(match(ALL, Governorate.RIF_DIMASHQ, "جرمانا", "الروضة")).contains(RIF);
        assertThat(match(ALL, Governorate.RIF_DIMASHQ, null, null)).contains(RIF);
        assertThat(match(ALL, Governorate.DAMASCUS, "قدسيا", null)).contains(DAMASCUS_GOVERNORATE);
    }

    @Test
    void orderOfZonesDoesNotMatter() {
        assertThat(match(List.of(MAZZEH, DAMASCUS_GOVERNORATE, DAMASCUS_CITY), Governorate.DAMASCUS, "دمشق", "المزة"))
                .contains(MAZZEH);
        assertThat(match(List.of(DAMASCUS_CITY, MAZZEH, DAMASCUS_GOVERNORATE), Governorate.DAMASCUS, "دمشق", "المزة"))
                .contains(MAZZEH);
    }

    @Test
    void nothingMatchesOutsideTheZones() {
        assertThat(match(ALL, Governorate.HOMS, "حمص", null)).isEmpty();
        assertThat(match(ALL, Governorate.ALEPPO, "منبج", null)).isEmpty();
        assertThat(match(ALL, Governorate.ALEPPO, null, null)).isEmpty();
        // Same city name in another governorate is another place.
        assertThat(match(List.of(ALEPPO_CITY), Governorate.IDLIB, "حلب", null)).isEmpty();
    }

    @Test
    void areaZoneDoesNotCoverOtherAreasOrTheWholeCity() {
        var onlyMazzeh = List.of(MAZZEH);
        assertThat(match(onlyMazzeh, Governorate.DAMASCUS, "دمشق", "المالكي")).isEmpty();
        assertThat(match(onlyMazzeh, Governorate.DAMASCUS, "دمشق", null)).isEmpty();
        // An area of the same name in another city is another place.
        assertThat(match(onlyMazzeh, Governorate.DAMASCUS, "داريا", "المزة")).isEmpty();
    }

    @Test
    void pausedZonesAreSkippedInFavourOfBroaderOnes() {
        var zones = List.of(DAMASCUS_GOVERNORATE, zone(Governorate.DAMASCUS, "دمشق", "المزة", false));
        assertThat(match(zones, Governorate.DAMASCUS, "دمشق", "المزة")).contains(DAMASCUS_GOVERNORATE);
        assertThat(match(List.of(zone(Governorate.HOMS, null, null, false)), Governorate.HOMS, "حمص", null)).isEmpty();
    }

    @Test
    void namesMatchWhateverTheSpelling() {
        assertThat(match(ALL, Governorate.DAMASCUS, "  دمشق ", "المزه")).contains(MAZZEH);
        assertThat(match(ALL, Governorate.DAMASCUS, "دِمَشق", "الـمـزة")).contains(MAZZEH);
        var latin = List.of(zone(Governorate.LATAKIA, "Jableh", "Old  Town", true));
        assertThat(match(latin, Governorate.LATAKIA, "JABLEH", "old town")).contains(latin.getFirst());
        var hamza = List.of(zone(Governorate.IDLIB, "إدلب", null, true));
        assertThat(match(hamza, Governorate.IDLIB, "ادلب", null)).contains(hamza.getFirst());
    }

    @Test
    void areaWithoutCityIsIgnored() {
        assertThat(Destination.of(Governorate.DAMASCUS, " ", "المزة").area()).isNull();
        assertThat(match(ALL, Governorate.DAMASCUS, null, "المزة")).contains(DAMASCUS_GOVERNORATE);
    }

    private static java.util.Optional<Zone> match(List<Zone> zones, Governorate governorate, String city, String area) {
        return ZoneMatcher.match(zones, Destination.of(governorate, city, area));
    }

    static Zone zone(Governorate governorate, String city, String area, boolean active) {
        return new Zone(UUID.randomUUID(), governorate, city, area, Money.of(new BigDecimal("1000"), Currency.SYP),
                null, null, null, active);
    }
}
