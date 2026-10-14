package com.unispark.ecommerce.delivery.internal;

import java.util.Collection;
import java.util.Comparator;
import java.util.Objects;
import java.util.Optional;

import com.unispark.ecommerce.delivery.DeliveryZones.Zone;
import com.unispark.ecommerce.delivery.Destination;

/**
 * Picks the zone for a destination (SPEC 8): among active zones of its governorate, an area zone for its exact
 * city and area beats a city zone for its city, which beats a zone for the whole governorate. Names match after
 * folding spelling variants. Pure, so the rule is unit-tested on its own.
 */
final class ZoneMatcher {

    private ZoneMatcher() {
    }

    static Optional<Zone> match(Collection<Zone> zones, Destination destination) {
        return zones.stream()
                .filter(Zone::active)
                .filter(zone -> covers(zone, destination))
                .max(Comparator.comparingInt(ZoneMatcher::specificity));
    }

    static boolean covers(Zone zone, Destination destination) {
        if (zone.governorate() != destination.governorate()) {
            return false;
        }
        if (zone.city() == null) {
            return true;
        }
        if (!Objects.equals(Destination.key(zone.city()), destination.cityKey())) {
            return false;
        }
        return zone.area() == null || Objects.equals(Destination.key(zone.area()), destination.areaKey());
    }

    /** 0 for a governorate, 1 for a city, 2 for an area. */
    static int specificity(Zone zone) {
        return zone.city() == null ? 0 : zone.area() == null ? 1 : 2;
    }
}
